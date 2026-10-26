using System.Buffers.Binary;
using System.Net;
using System.Net.Sockets;
using System.Text;

namespace AirKeys;

/// <summary>
/// TCP LAN server, v1.1: same frames, same AES-GCM, same PAIR handshake as
/// the Bluetooth RFCOMM server, over an ordinary TCP socket on the local
/// network. Works on whatever LAN is already there: the phone hotspot you
/// already share for internet, home/office Wi-Fi, or USB tethering.
/// Also broadcasts "AirKeys|47820|&lt;PC name&gt;" on UDP 47821 every second
/// so the phone can discover it without typing an IP. LAN-only traffic;
/// nothing here ever contacts the internet.
/// </summary>
public sealed class TcpServer : IDisposable
{
    public const int TcpPort = 47820;
    public const int UdpPort = 47821;
    private const byte DirServer = 0x01;
    private const byte DirClient = 0x00;

    private readonly InputInjector _injector;
    private readonly AppSettings _settings;
    private readonly Action<string> _onStatus;
    private readonly Action<string> _onDevice;

    private TcpListener? _listener;
    private TcpClient? _client;
    private Stream? _stream;
    private CancellationTokenSource? _serverCts;
    private CancellationTokenSource? _clientCts;
    private uint _serverSeq;
    private byte[]? _sessionKey;
    private string _deviceName = "";
    private bool _paired;
    private DateTime _lastFrameUtc = DateTime.UtcNow;
    private readonly SeqValidator _clientSeq = new();
    private readonly Dictionary<string, (int Attempts, DateTime LockedUntil)> _pairAttempts = new();

    public string? CurrentCode { get; private set; }
    public DateTime CodeExpiresUtc { get; private set; }
    public bool IsConnected => _stream != null;

    public TcpServer(InputInjector injector, AppSettings settings,
        Action<string> onStatus, Action<string> onDevice)
    {
        _injector = injector; _settings = settings; _onStatus = onStatus; _onDevice = onDevice;
    }

    public string GeneratePairingCode()
    {
        var code = Random.Shared.Next(100_000, 1_000_000).ToString("D6");
        SetPairingCode(code);
        return code;
    }

    public void SetPairingCode(string code)
    {
        CurrentCode = code;
        CodeExpiresUtc = DateTime.UtcNow.AddSeconds(120);
    }
    public void ClearPairingCode() => CurrentCode = null;

    /// <summary>Local IPv4 addresses (not loopback) for the tray menu.</summary>
    public static string LocalAddresses()
    {
        try
        {
            var addrs = Dns.GetHostAddresses(Dns.GetHostName())
                .Where(a => a.AddressFamily == AddressFamily.InterNetwork && !IPAddress.IsLoopback(a))
                .Select(a => a.ToString());
            return string.Join(", ", addrs);
        }
        catch { return ""; }
    }

    public Task StartAsync()
    {
        _serverCts = new CancellationTokenSource();
        _listener = new TcpListener(IPAddress.Any, TcpPort);
        _listener.Start();
        _ = AcceptLoopAsync(_serverCts.Token);
        _ = BroadcastLoopAsync(_serverCts.Token);
        _onStatus("Waiting for phone (LAN)…");
        return Task.CompletedTask;
    }

    private async Task BroadcastLoopAsync(CancellationToken token)
    {
        try
        {
            using var udp = new UdpClient();
            udp.EnableBroadcast = true;
            var payload = Encoding.UTF8.GetBytes($"AirKeys|{TcpPort}|{Environment.MachineName}");
            while (!token.IsCancellationRequested)
            {
                try { await udp.SendAsync(payload, new IPEndPoint(IPAddress.Broadcast, UdpPort)); }
                catch { /* broadcast may fail on some adapters; TCP still works */ }
                await Task.Delay(1000, token);
            }
        }
        catch (OperationCanceledException) { }
        catch { }
    }

    private async Task AcceptLoopAsync(CancellationToken token)
    {
        while (!token.IsCancellationRequested)
        {
            TcpClient client;
            try { client = await _listener!.AcceptTcpClientAsync(token); }
            catch (OperationCanceledException) { return; }
            catch { continue; }
            if (_stream != null) { try { client.Close(); } catch { } continue; }
            try
            {
                _clientCts = new CancellationTokenSource();
                _client = client;
                client.NoDelay = true;
                _stream = client.GetStream();
                _serverSeq = 0;
                _clientSeq.Reset();
                _sessionKey = null;
                _paired = false;
                _lastFrameUtc = DateTime.UtcNow;
                _onStatus("Phone connected (LAN) — waiting for pairing…");
                _ = WatchdogAsync(_clientCts.Token);
                await ReadLoopAsync(_stream, _clientCts.Token);
            }
            catch { /* socket closed */ }
            finally { Disconnect(); }
        }
    }

    private async Task WatchdogAsync(CancellationToken token)
    {
        try
        {
            while (!token.IsCancellationRequested)
            {
                await Task.Delay(1000, token);
                if ((DateTime.UtcNow - _lastFrameUtc).TotalSeconds > 15) break;
            }
        }
        catch (OperationCanceledException) { return; }
        Disconnect();
    }

    private async Task ReadLoopAsync(Stream stream, CancellationToken token)
    {
        var lenBuf = new byte[2];
        while (!token.IsCancellationRequested)
        {
            if (!await ReadFullyAsync(stream, lenBuf, token)) break;
            int len = BinaryPrimitives.ReadUInt16LittleEndian(lenBuf);
            if (len <= 0 || len > FrameCodec.MaxFrame) break;
            var body = new byte[len];
            if (!await ReadFullyAsync(stream, body, token)) break;
            _lastFrameUtc = DateTime.UtcNow;
            if (!_paired)
            {
                if (!await TryHandlePairAsync(body, stream)) break;
                continue;
            }
            if (_sessionKey == null) break;
            Frame frame;
            try { frame = Crypto.DecryptFrame(_sessionKey, DirClient, body); }
            catch { continue; }
            if (!_clientSeq.Accept(frame.Seq)) continue;
            await DispatchAsync(frame, stream);
        }
    }

    private async Task<bool> TryHandlePairAsync(byte[] body, Stream output)
    {
        // Same logic as RfcommServer: stored key first, then current code.
        // A frame that decrypts with neither is ignored — and, crucially,
        // _paired stays false, so no input is ever injected unauthenticated.
        var candidates = new List<(byte[] Key, byte Step, string Device)>();
        if (!string.IsNullOrEmpty(_settings.PairedDeviceName))
        {
            // LAN keys live under "lan:<phone name>", separate from any
            // Bluetooth key for the same phone (different code, different key).
            var storedLan = PairingStore.Load("lan:" + _settings.PairedDeviceName);
            if (storedLan != null) candidates.Add((storedLan, 4, _settings.PairedDeviceName));
        }
        if (CurrentCode != null && DateTime.UtcNow <= CodeExpiresUtc)
            candidates.Add((Crypto.DeriveKeyFromCode(CurrentCode), 2, ""));

        foreach (var (key, step, device) in candidates)
        {
            Frame frame;
            try { frame = Crypto.DecryptFrame(key, DirClient, body); }
            catch { continue; }
            if (frame.Type != FrameType.Pair) continue;
            if (frame.Payload.Length < 1) continue;
            byte pairStep = frame.Payload[0];
            bool ok = (step == 4 && pairStep == 3) || (step == 2 && pairStep == 1);
            if (!ok) continue;
            string name = frame.Payload.Length > 1
                ? Encoding.UTF8.GetString(frame.Payload, 1, frame.Payload.Length - 1)
                : "Phone";
            _sessionKey = key;
            _deviceName = step == 2 ? name : _settings.PairedDeviceName;
            if (step == 2)
            {
                // LAN keys are stored under a lan: key so a Bluetooth key
                // for the same phone name can never be confused with it.
                PairingStore.Save("lan:" + name, key);
                _settings.PairedDeviceName = name;
                _settings.Save();
            }
            _paired = true;
            _clientSeq.Reset();
            _clientSeq.Accept(frame.Seq);
            _onDevice(_deviceName);
            _onStatus("Connected (LAN) — " + _deviceName);
            ClearPairingCode();
            await SendFrameAsync(output, FrameType.Pair,
                FrameCodec.PairPayload(step, Environment.MachineName));
            return true;
        }

        var now = DateTime.UtcNow;
        _pairAttempts.TryGetValue("global", out var entry);
        if (entry.LockedUntil > now) return true;
        entry.Attempts++;
        if (entry.Attempts >= 5)
        {
            entry = (0, now.AddSeconds(60));
            _onStatus("Too many wrong codes — locked for 60 s.");
        }
        _pairAttempts["global"] = entry;
        return true;
    }

    private async Task DispatchAsync(Frame frame, Stream output)
    {
        switch (frame.Type)
        {
            case FrameType.Text:
                _injector.SendText(Encoding.UTF8.GetString(frame.Payload));
                break;
            case FrameType.Backspace:
                int count = frame.Payload.Length >= 2
                    ? BinaryPrimitives.ReadUInt16LittleEndian(frame.Payload.AsSpan(0, 2)) : 1;
                _injector.Backspace(count);
                break;
            case FrameType.Key:
                if (frame.Payload.Length >= 2)
                {
                    if (frame.Payload[1] == 1) _injector.KeyDown(frame.Payload[0]);
                    else _injector.KeyUp(frame.Payload[0]);
                }
                break;
            case FrameType.Combo:
                if (frame.Payload.Length >= 3)
                    _injector.Combo(frame.Payload[0], frame.Payload[1], frame.Payload[2] == 1);
                break;
            case FrameType.MouseMove:
                {
                    var (dx, dy) = FrameCodec.ParseMove(frame.Payload);
                    var (ox, oy) = PointerCurve.Apply(dx, dy, _settings.Sensitivity, _settings.Acceleration);
                    _injector.MouseMove(ox, oy);
                    break;
                }
            case FrameType.MouseButton:
                if (frame.Payload.Length >= 2)
                    _injector.MouseButton(frame.Payload[0], frame.Payload[1] == 1);
                break;
            case FrameType.MouseScroll:
                {
                    var (dx, dy) = FrameCodec.ParseMove(frame.Payload);
                    int wx = PointerCurve.WheelDelta(dx, _settings.ScrollSpeed, _settings.NaturalScroll);
                    int wy = PointerCurve.WheelDelta(dy, _settings.ScrollSpeed, _settings.NaturalScroll);
                    _injector.Scroll(wx, wy);
                    break;
                }
            case FrameType.Ping:
                await SendFrameAsync(output, FrameType.Pong, frame.Payload);
                break;
        }
    }

    private async Task SendFrameAsync(Stream output, byte type, byte[] payload)
    {
        if (_sessionKey == null) return;
        var frame = Crypto.EncryptFrame(_sessionKey, DirServer, type, ++_serverSeq, payload);
        await output.WriteAsync(frame);
        await output.FlushAsync();
    }

    private static async Task<bool> ReadFullyAsync(Stream s, byte[] buf, CancellationToken token)
    {
        int off = 0;
        while (off < buf.Length)
        {
            int n = await s.ReadAsync(buf.AsMemory(off, buf.Length - off), token);
            if (n == 0) return false;
            off += n;
        }
        return true;
    }

    public void Disconnect()
    {
        try { _clientCts?.Cancel(); } catch { }
        _injector.ReleaseAll();
        try { _stream?.Dispose(); } catch { }
        try { _client?.Close(); } catch { }
        _stream = null; _client = null;
        _sessionKey = null; _paired = false;
        _onStatus("Waiting for phone (LAN)…");
    }

    public void Dispose()
    {
        try { _serverCts?.Cancel(); } catch { }
        Disconnect();
        try { _listener?.Stop(); } catch { }
    }
}
