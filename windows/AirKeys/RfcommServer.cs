using System.Buffers.Binary;
using Windows.Devices.Bluetooth.Rfcomm;
using Windows.Networking.Sockets;
using Windows.Storage.Streams;

namespace AirKeys;

/// <summary>
/// Bluetooth Classic RFCOMM server (Windows.Devices.Bluetooth.Rfcomm).
/// One client at a time. Reads frames, enforces replay protection and
/// authentication, dispatches input immediately (no queueing of moves),
/// replies to PING with PONG, and releases all input on any disconnect.
/// </summary>
public sealed class RfcommServer : IDisposable
{
    public static readonly Guid ServiceUuid = Guid.Parse("7a3e9b20-4f1c-4a2d-9c7e-1a2b3c4d5e6f");
    private const byte DirServer = 0x01; // PC -> phone
    private const byte DirClient = 0x00; // phone -> PC

    private readonly InputInjector _injector;
    private readonly AppSettings _settings;
    private readonly Action<string> _onStatus;
    private readonly Action<string> _onDevice;

    private RfcommServiceProvider? _provider;
    private StreamSocketListener? _listener;
    private Stream? _stream;
    private uint _serverSeq;
    private byte[]? _sessionKey;
    private string _deviceName = "";
    private bool _paired;
    private DateTime _lastFrameUtc = DateTime.UtcNow;
    private readonly SeqValidator _clientSeq = new();
    private readonly Dictionary<string, (int Attempts, DateTime LockedUntil)> _pairAttempts = new();
    private CancellationTokenSource? _cts;

    // Pairing code currently displayed (null when not pairing).
    public string? CurrentCode { get; private set; }
    public DateTime CodeExpiresUtc { get; private set; }
    public bool IsConnected => _stream != null;

    public RfcommServer(InputInjector injector, AppSettings settings,
        Action<string> onStatus, Action<string> onDevice)
    {
        _injector = injector; _settings = settings; _onStatus = onStatus; _onDevice = onDevice;
    }

    public string GeneratePairingCode()
    {
        var code = Random.Shared.Next(100_000, 1_000_000).ToString("D6");
        CurrentCode = code;
        CodeExpiresUtc = DateTime.UtcNow.AddSeconds(120);
        return code;
    }
    public void ClearPairingCode() => CurrentCode = null;

    public async Task StartAsync()
    {
        _provider = await RfcommServiceProvider.CreateAsync(
            RfcommServiceId.FromUuid(ServiceUuid));
        _provider.ServiceName = "AirKeys";
        _listener = new StreamSocketListener();
        _listener.ConnectionReceived += OnConnectionReceived;
        await _provider.StartAdvertisingAsync(_listener, true);
        _onStatus("Waiting for phone…");
    }

    private async void OnConnectionReceived(StreamSocketListener sender,
        StreamSocketListenerConnectionReceivedEventArgs args)
    {
        // One client only: drop a second connection attempt.
        if (_stream != null)
        {
            try { args.Socket.Dispose(); } catch { }
            return;
        }
        try
        {
            _cts = new CancellationTokenSource();
            _stream = args.Socket.InputStream.AsStreamForRead();
            var outStream = args.Socket.OutputStream.AsStreamForWrite();
            _serverSeq = 0;
            _clientSeq.Reset();
            _sessionKey = null;
            _paired = false;
            _lastFrameUtc = DateTime.UtcNow;
            _onStatus("Phone connected — waiting for pairing…");
            _ = WatchdogAsync(_cts.Token);
            await ReadLoopAsync(_stream, outStream, _cts.Token);
        }
        catch { /* socket closed */ }
        finally { Disconnect(); }
    }

    private async Task WatchdogAsync(CancellationToken token)
    {
        try
        {
            while (!token.IsCancellationRequested)
            {
                await Task.Delay(1000, token);
                if ((DateTime.UtcNow - _lastFrameUtc).TotalSeconds > 15)
                    break; // stale peer: drop, which triggers ReleaseAll via Disconnect
            }
        }
        catch (OperationCanceledException) { return; }
        Disconnect();
    }

    private async Task ReadLoopAsync(Stream input, Stream output, CancellationToken token)
    {
        var lenBuf = new byte[2];
        while (!token.IsCancellationRequested)
        {
            if (!await ReadFullyAsync(input, lenBuf, token)) break;
            int len = BinaryPrimitives.ReadUInt16LittleEndian(lenBuf);
            if (len <= 0 || len > FrameCodec.MaxFrame) break;
            var body = new byte[len];
            if (!await ReadFullyAsync(input, body, token)) break;
            _lastFrameUtc = DateTime.UtcNow;

            // Authentication gate: until PAIR succeeds, only PAIR frames are
            // attempted, and they must decrypt with a known key (see below).
            if (!_paired)
            {
                if (!await TryHandlePairAsync(body, output)) break;
                continue;
            }
            if (_sessionKey == null) break;
            Frame frame;
            try { frame = Crypto.DecryptFrame(_sessionKey, DirClient, body); }
            catch { continue; } // tampered frame: ignore, never inject
            if (!_clientSeq.Accept(frame.Seq)) continue; // duplicate / replay
            await DispatchAsync(frame, output);
        }
    }

    private async Task<bool> TryHandlePairAsync(byte[] body, Stream output)
    {
        // Try, in order: stored key (reconnect), then current pairing code.
        // Device identity for stored-key lookup is the pair name inside the
        // frame, which we cannot read before decrypting — so we try the
        // stored key for the last paired device first, then the code key.
        // If neither decrypts, the frame is ignored and the peer stays unpaired.
        var candidates = new List<(byte[] Key, byte Step, string Device)>();
        if (!string.IsNullOrEmpty(_settings.PairedDeviceName))
        {
            var stored = PairingStore.Load(_settings.PairedDeviceName);
            if (stored != null) candidates.Add((stored, 4, _settings.PairedDeviceName));
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
                ? System.Text.Encoding.UTF8.GetString(frame.Payload, 1, frame.Payload.Length - 1)
                : "Phone";
            _sessionKey = key;
            _deviceName = step == 2 ? name : _settings.PairedDeviceName;
            if (step == 2)
            {
                PairingStore.Save(name, key);
                _settings.PairedDeviceName = name;
                _settings.Save();
            }
            _paired = true;
            _clientSeq.Reset();
            // Accept this frame's seq as the start of the session.
            _clientSeq.Accept(frame.Seq);
            _onDevice(_deviceName);
            _onStatus("Connected — " + _deviceName);
            ClearPairingCode();
            await SendFrameAsync(output, FrameType.Pair,
                FrameCodec.PairPayload(step, Environment.MachineName));
            return true;
        }

        // Failed guess counting (per attempted device name is impossible
        // pre-decryption, so we count globally and lock out briefly).
        var now = DateTime.UtcNow;
        _pairAttempts.TryGetValue("global", out var entry);
        if (entry.LockedUntil > now) return true; // stay connected but ignore
        entry.Attempts++;
        if (entry.Attempts >= 5)
        {
            entry = (0, now.AddSeconds(60));
            _onStatus("Too many wrong codes — locked for 60 s.");
        }
        _pairAttempts["global"] = entry;
        return true; // keep socket open for a correct retry, but inject nothing
    }

    private async Task DispatchAsync(Frame frame, Stream output)
    {
        switch (frame.Type)
        {
            case FrameType.Text:
                _injector.SendText(System.Text.Encoding.UTF8.GetString(frame.Payload));
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
            case FrameType.Pair:
                // Re-pair acknowledgement path; session already open.
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

    /// <summary>Disconnect + release-all. Safe to call repeatedly.</summary>
    public void Disconnect()
    {
        try { _cts?.Cancel(); } catch { }
        _injector.ReleaseAll();
        try { _stream?.Dispose(); } catch { }
        _stream = null;
        _sessionKey = null;
        _paired = false;
        _onStatus("Waiting for phone…");
    }

    public void Dispose()
    {
        Disconnect();
        try { _listener?.Dispose(); } catch { }
        try { _provider?.StopAdvertising(); } catch { }
    }
}
