using System.Runtime.InteropServices;

namespace AirKeys;

/// <summary>
/// SendInput wrapper. Tracks every held key and button so ReleaseAll()
/// (called on disconnect and on the panic hotkey) can never leave a
/// stuck Ctrl, Shift, Win key or mouse button behind.
/// Text goes through KEYEVENTF_UNICODE so Urdu/Arabic/Pashto/emoji work
/// regardless of the PC keyboard layout. Right-to-left text is handled by
/// Windows itself once the characters arrive in order via UNICODE events;
/// we must NOT reorder, reverse, or reshape the string here.
/// </summary>
public sealed class InputInjector
{
    private readonly HashSet<int> _heldKeys = new();
    private readonly HashSet<int> _heldButtons = new(); // 0=L,1=R,2=M
    private readonly object _lock = new();

    // --- Win32 ---
    [StructLayout(LayoutKind.Sequential)]
    private struct INPUT
    {
        public uint Type; // 0=mouse, 1=keyboard
        public InputUnion U;
    }
    [StructLayout(LayoutKind.Explicit)]
    private struct InputUnion
    {
        [FieldOffset(0)] public MOUSEINPUT Mouse;
        [FieldOffset(0)] public KEYBDINPUT Keyboard;
    }
    [StructLayout(LayoutKind.Sequential)]
    private struct MOUSEINPUT
    {
        public int Dx; public int Dy; public uint MouseData; public uint Flags;
        public uint Time; public IntPtr ExtraInfo;
    }
    [StructLayout(LayoutKind.Sequential)]
    private struct KEYBDINPUT
    {
        public ushort Vk; public ushort Scan; public uint Flags; public uint Time; public IntPtr ExtraInfo;
    }
    [DllImport("user32.dll", SetLastError = true)]
    private static extern uint SendInput(uint nInputs, INPUT[] inputs, int cbSize);

    private const uint INPUT_MOUSE = 0, INPUT_KEYBOARD = 1;
    private const uint MOUSEEVENTF_MOVE = 0x0001;
    private const uint MOUSEEVENTF_LEFTDOWN = 0x0002, MOUSEEVENTF_LEFTUP = 0x0004;
    private const uint MOUSEEVENTF_RIGHTDOWN = 0x0008, MOUSEEVENTF_RIGHTUP = 0x0010;
    private const uint MOUSEEVENTF_MIDDLEDOWN = 0x0020, MOUSEEVENTF_MIDDLEUP = 0x0040;
    private const uint MOUSEEVENTF_WHEEL = 0x0800, MOUSEEVENTF_HWHEEL = 0x1000;
    private const uint KEYEVENTF_KEYUP = 0x0002, KEYEVENTF_UNICODE = 0x0004;

    private static void Send(INPUT input) => SendInput(1, new[] { input }, Marshal.SizeOf<INPUT>());

    private static INPUT MouseInput(int dx, int dy, uint data, uint flags) => new()
    {
        Type = INPUT_MOUSE,
        U = new InputUnion { Mouse = new MOUSEINPUT { Dx = dx, Dy = dy, MouseData = data, Flags = flags } }
    };
    private static INPUT KeyInput(ushort vk, ushort scan, uint flags) => new()
    {
        Type = INPUT_KEYBOARD,
        U = new InputUnion { Keyboard = new KEYBDINPUT { Vk = vk, Scan = scan, Flags = flags } }
    };

    public void SendText(string text)
    {
        // Send each UTF-16 code unit (surrogate pairs for emoji arrive as
        // two UNICODE events, which Windows recombines). Never reorder.
        foreach (char ch in text)
        {
            Send(KeyInput(0, (ushort)ch, KEYEVENTF_UNICODE));
            Send(KeyInput(0, (ushort)ch, KEYEVENTF_UNICODE | KEYEVENTF_KEYUP));
        }
    }

    public void Backspace(int count)
    {
        for (int i = 0; i < count; i++) { KeyDown(Vk.Backspace); KeyUp(Vk.Backspace); }
    }

    public void KeyDown(int vk)
    {
        lock (_lock) _heldKeys.Add(vk);
        Send(KeyInput((ushort)vk, 0, 0));
    }
    public void KeyUp(int vk)
    {
        lock (_lock) _heldKeys.Remove(vk);
        Send(KeyInput((ushort)vk, 0, KEYEVENTF_KEYUP));
    }

    public void Combo(int modifiers, int vk, bool down)
    {
        // Down: modifiers first, then key. Up: key first, then modifiers.
        int[] mods = ModList(modifiers);
        if (down)
        {
            foreach (int m in mods) KeyDown(m);
            KeyDown(vk);
        }
        else
        {
            KeyUp(vk);
            foreach (int m in mods) KeyUp(m);
        }
    }

    private static int[] ModList(int modifiers)
    {
        var list = new List<int>();
        if ((modifiers & Modifiers.Ctrl) != 0) list.Add(Vk.Control);
        if ((modifiers & Modifiers.Alt) != 0) list.Add(Vk.Menu);
        if ((modifiers & Modifiers.Shift) != 0) list.Add(Vk.Shift);
        if ((modifiers & Modifiers.Win) != 0) list.Add(Vk.LWin);
        return list.ToArray();
    }

    public void MouseMove(int dx, int dy) =>
        Send(MouseInput(dx, dy, 0, MOUSEEVENTF_MOVE));

    public void MouseButton(int button, bool down)
    {
        uint flag = button switch
        {
            0 => down ? MOUSEEVENTF_LEFTDOWN : MOUSEEVENTF_LEFTUP,
            1 => down ? MOUSEEVENTF_RIGHTDOWN : MOUSEEVENTF_RIGHTUP,
            _ => down ? MOUSEEVENTF_MIDDLEDOWN : MOUSEEVENTF_MIDDLEUP,
        };
        lock (_lock) { if (down) _heldButtons.Add(button); else _heldButtons.Remove(button); }
        Send(MouseInput(0, 0, 0, flag));
    }

    public void Scroll(int dx, int dy)
    {
        if (dy != 0) Send(MouseInput(0, 0, unchecked((uint)dy), MOUSEEVENTF_WHEEL));
        if (dx != 0) Send(MouseInput(0, 0, unchecked((uint)dx), MOUSEEVENTF_HWHEEL));
    }

    /// <summary>Release everything held, in a safe order. Idempotent.</summary>
    public void ReleaseAll()
    {
        int[] keys, buttons;
        lock (_lock)
        {
            keys = _heldKeys.ToArray(); buttons = _heldButtons.ToArray();
            _heldKeys.Clear(); _heldButtons.Clear();
        }
        foreach (int b in buttons) MouseButtonRaw(b, false);
        foreach (int vk in keys) Send(KeyInput((ushort)vk, 0, KEYEVENTF_KEYUP));
    }

    private void MouseButtonRaw(int button, bool down)
    {
        uint flag = button switch
        {
            0 => down ? MOUSEEVENTF_LEFTDOWN : MOUSEEVENTF_LEFTUP,
            1 => down ? MOUSEEVENTF_RIGHTDOWN : MOUSEEVENTF_RIGHTUP,
            _ => down ? MOUSEEVENTF_MIDDLEDOWN : MOUSEEVENTF_MIDDLEUP,
        };
        Send(MouseInput(0, 0, 0, flag));
    }
}
