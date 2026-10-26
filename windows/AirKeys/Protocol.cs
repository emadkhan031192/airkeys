using System.Buffers.Binary;

namespace AirKeys;

/// <summary>Wire protocol v1. Mirrored exactly by android/.../Protocol.kt.
/// Frame on wire: [uint16 length][uint8 type][uint32 seq][payload][16-byte tag]
/// Length excludes itself. See docs/PROTOCOL.md for the full spec.</summary>
public static class FrameType
{
    public const byte Text = 0x01;
    public const byte Backspace = 0x02;
    public const byte Key = 0x03;
    public const byte Combo = 0x04;
    public const byte MouseMove = 0x05;
    public const byte MouseButton = 0x06;
    public const byte MouseScroll = 0x07;
    public const byte Ping = 0x08;
    public const byte Pong = 0x09;
    public const byte Pair = 0x0A;
}

public static class Vk
{
    public const int Backspace = 8, Tab = 9, Enter = 13, Esc = 27, Space = 32;
    public const int PageUp = 33, PageDown = 34, End = 35, Home = 36;
    public const int Left = 37, Up = 38, Right = 39, Down = 40, Delete = 46;
    public const int Control = 17, Menu = 18, Shift = 16, LWin = 91;
    public static int F(int n) => 111 + n;
}

public static class Modifiers
{
    public const int Ctrl = 0x01, Alt = 0x02, Shift = 0x04, Win = 0x08;
}

public sealed record Frame(byte Type, uint Seq, byte[] Payload);

/// <summary>Replay protection in uint32 space. Accepts a seq only if it is
/// newer than the last accepted seq (handles the 2^32 wrap).</summary>
public sealed class SeqValidator
{
    private uint _last;
    private bool _seen;
    public bool Accept(uint seq)
    {
        if (!_seen) { _seen = true; _last = seq; return true; }
        if (seq == _last) return false;
        uint diff = unchecked(seq - _last);
        if (diff == 0 || diff >= (1u << 31)) return false;
        _last = seq; return true;
    }
    public void Reset() { _last = 0; _seen = false; }
}

public static class FrameCodec
{
    public const int MaxFrame = 8192;
    public static (short Dx, short Dy) ParseMove(byte[] p)
    {
        if (p.Length < 4) return (0, 0);
        return (BinaryPrimitives.ReadInt16LittleEndian(p.AsSpan(0, 2)),
                BinaryPrimitives.ReadInt16LittleEndian(p.AsSpan(2, 2)));
    }
    public static long ParsePing(byte[] p) =>
        p.Length >= 8 ? BinaryPrimitives.ReadInt64LittleEndian(p.AsSpan(0, 8)) : 0;
    public static byte[] PingPayload(long millis)
    {
        var b = new byte[8];
        BinaryPrimitives.WriteInt64LittleEndian(b, millis);
        return b;
    }
    public static byte[] PairPayload(byte step, string name)
    {
        var n = System.Text.Encoding.UTF8.GetBytes(name);
        var out_ = new byte[1 + n.Length];
        out_[0] = step; Array.Copy(n, 0, out_, 1, n.Length);
        return out_;
    }
}
