using System.Runtime.InteropServices;

namespace AirKeys;

internal static class Program
{
    [STAThread]
    static async Task Main()
    {
        ApplicationConfiguration.Initialize();
        var settings = AppSettings.Load();
        var injector = new InputInjector();
        using var context = new TrayAppContext(settings, injector);
        // Start the RFCOMM server before the message loop so the phone can
        // connect as soon as the tray icon appears.
        await context.StartServerAsync();
        Application.Run(context);
    }
}

/// <summary>
/// ApplicationContext that owns the tray icon, the panic hotkey, and the
/// server lifetime. There is no main window: the app lives in the tray.
/// </summary>
public sealed class TrayAppContext : ApplicationContext
{
    private readonly AppSettings _settings;
    private readonly InputInjector _injector;
    private readonly RfcommServer _server;
    private readonly TcpServer _tcpServer; // v1.2: not started, kept for future re-enable
    private readonly NotifyIcon _tray;
    private readonly ToolStripMenuItem _statusItem;
    private readonly ToolStripMenuItem _deviceItem;
    private IntPtr _hotkeyWindowHandle;

    // Win32 hotkey: Ctrl+Alt+Pause.
    private const int HOTKEY_ID = 0xA11C;
    private const uint MOD_CONTROL = 0x0002, MOD_ALT = 0x0001;
    private const int VK_PAUSE = 0x13;
    private const int WM_HOTKEY = 0x0312;
    [DllImport("user32.dll")] private static extern bool RegisterHotKey(IntPtr hWnd, int id, uint fsModifiers, uint vk);
    [DllImport("user32.dll")] private static extern bool UnregisterHotKey(IntPtr hWnd, int id);

    public TrayAppContext(AppSettings settings, InputInjector injector)
    {
        _settings = settings;
        _injector = injector;
        _server = new RfcommServer(injector, settings,
            status => UpdateStatus(status),
            device => UpdateDevice(device));
        _tcpServer = new TcpServer(injector, settings,
            status => UpdateStatus(status),
            device => UpdateDevice(device));

        _tray = new NotifyIcon
        {
            Icon = SystemIcons.Application,
            Visible = true,
            Text = "AirKeys — Waiting for phone…",
        };
        _statusItem = new ToolStripMenuItem("Status: Waiting for phone…") { Enabled = false };
        _deviceItem = new ToolStripMenuItem("Paired device: none") { Enabled = false };
        if (!string.IsNullOrEmpty(settings.PairedDeviceName))
            _deviceItem.Text = "Paired device: " + settings.PairedDeviceName;

        var menu = new ContextMenuStrip();
        menu.Items.Add(_statusItem);
        menu.Items.Add(_deviceItem);
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add("Settings…", null, (_, _) => new SettingsForm(_settings).ShowDialog());
        menu.Items.Add("Re-pair…", null, (_, _) => ShowPairing());
        menu.Items.Add("Disconnect", null, (_, _) => PanicDisconnect(showToast: false));
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add("Quit", null, (_, _) => ExitThread());
        _tray.ContextMenuStrip = menu;
        _tray.DoubleClick += (_, _) => new SettingsForm(_settings).ShowDialog();

        // Invisible message-only window for the hotkey.
        var host = new Form { ShowInTaskbar = false, WindowState = FormWindowState.Minimized, Opacity = 0 };
        host.Load += (_, _) =>
        {
            _hotkeyWindowHandle = host.Handle;
            RegisterHotKey(_hotkeyWindowHandle, HOTKEY_ID, MOD_CONTROL | MOD_ALT, VK_PAUSE);
        };
        host.FormClosing += (_, _) => UnregisterHotKey(_hotkeyWindowHandle, HOTKEY_ID);
        // Hook WndProc via a NativeWindow subclass would be cleaner; instead
        // we use the message filter below (see PanicMessageFilter).
        Application.AddMessageFilter(new PanicMessageFilter(() => PanicDisconnect(showToast: true)));
        host.Show();
        host.Hide();
    }

    public async Task StartServerAsync()
    {
        // v1.2: Bluetooth only, as requested. The TCP LAN server is
        // intentionally not started, so no LAN option appears in the tray.
        try { await _server.StartAsync(); } catch { }
    }

    private void UpdateStatus(string status)
    {
        if (_tray.ContextMenuStrip?.InvokeRequired == true)
        {
            _tray.ContextMenuStrip.Invoke(() => UpdateStatus(status));
            return;
        }
        _statusItem.Text = "Status: " + status;
        _tray.Text = ("AirKeys — " + status).Length > 63
            ? ("AirKeys — " + status)[..63] : "AirKeys — " + status;
        if (status.StartsWith("Connected", StringComparison.Ordinal))
            _tray.ShowBalloonTip(2000, "AirKeys", "Connected", ToolTipIcon.Info);
    }

    private void UpdateDevice(string device)
    {
        _deviceItem.Text = "Paired device: " + device;
    }

    private void ShowPairing()
    {
        // Bluetooth only in v1.2.
        var code = _server.GeneratePairingCode();
        var form = new PairingForm(code);
        form.Show();
        // If the user closes the window, the code stays valid for 120 s
        // (the servers enforce expiry); closing does not cancel pairing.
    }

    /// <summary>Panic: release all input, then disconnect. Triggered by
    /// Ctrl+Alt+Pause or by Tray > Disconnect.</summary>
    public void PanicDisconnect(bool showToast)
    {
        _injector.ReleaseAll();
        _server.Disconnect();
        if (showToast)
            _tray.ShowBalloonTip(2000, "AirKeys",
                "Panic: all keys and buttons released, disconnected.", ToolTipIcon.Warning);
    }

    protected override void Dispose(bool disposing)
    {
        if (disposing)
        {
            _server.Dispose();
            _tcpServer.Dispose();
            _tray.Visible = false;
            _tray.Dispose();
        }
        base.Dispose(disposing);
    }

    private sealed class PanicMessageFilter : IMessageFilter
    {
        private readonly Action _onPanic;
        public PanicMessageFilter(Action onPanic) => _onPanic = onPanic;
        public bool PreFilterMessage(ref Message m)
        {
            if (m.Msg == WM_HOTKEY && m.WParam.ToInt32() == HOTKEY_ID)
            {
                _onPanic();
                return true;
            }
            return false;
        }
    }
}
