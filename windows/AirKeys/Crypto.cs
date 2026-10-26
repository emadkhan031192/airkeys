using System.Buffers.Binary;
using System.Security.Cryptography;
using System.Text;

namespace AirKeys;

/// <summary>
/// AES-256-GCM, nonce derived from direction + seq (never transmitted),
/// AAD = type || seq. Mirrors android/.../Crypto.kt exactly, including
/// PBKDF2 parameters, so the two sides derive identical keys.
/// .NET's AesGcm writes ciphertext and tag separately; the wire layout
/// (ciphertext followed by tag) matches the Android layout.
/// </summary>
public static class Crypto
{
    private static readonly byte[] Salt = Encoding.UTF8.GetBytes("AirKeys-v1");

    public static byte[] DeriveKeyFromCode(string code)
    {
        if (code.Length != 6 || code.Any(c => !char.IsDigit(c)))
            throw new ArgumentException("Code must be 6 digits", nameof(code));
        return Rfc2898DeriveBytes.Pbkdf2(
            Encoding.UTF8.GetBytes(code), Salt, 100_000, HashAlgorithmName.SHA256, 32);
    }

    public static byte[] Nonce(byte direction, uint seq)
    {
        var n = new byte[12];
        n[0] = direction;
        BinaryPrimitives.WriteUInt32LittleEndian(n.AsSpan(8, 4), seq);
        return n;
    }

    private static byte[] Aad(byte type, uint seq)
    {
        var a = new byte[5];
        a[0] = type;
        BinaryPrimitives.WriteUInt32LittleEndian(a.AsSpan(1, 4), seq);
        return a;
    }

    /// <summary>Encrypt one frame; returns the full wire frame with length prefix.</summary>
    public static byte[] EncryptFrame(byte[] key, byte direction, byte type, uint seq, byte[] payload)
    {
        var cipher = new byte[payload.Length];
        var tag = new byte[16];
        using (var aes = new AesGcm(key, 16))
            aes.Encrypt(Nonce(direction, seq), payload, cipher, tag, Aad(type, seq));
        var bodyLen = 1 + 4 + cipher.Length + tag.Length;
        var out_ = new byte[2 + bodyLen];
        BinaryPrimitives.WriteUInt16LittleEndian(out_.AsSpan(0, 2), (ushort)bodyLen);
        out_[2] = type;
        BinaryPrimitives.WriteUInt32LittleEndian(out_.AsSpan(3, 4), seq);
        Array.Copy(cipher, 0, out_, 7, cipher.Length);
        Array.Copy(tag, 0, out_, 7 + cipher.Length, tag.Length);
        return out_;
    }

    /// <summary>Decrypt one frame body (without the length prefix).
    /// Throws CryptographicException (AuthenticationTagMismatch) on tamper / wrong key.</summary>
    public static Frame DecryptFrame(byte[] key, byte direction, byte[] body)
    {
        if (body.Length < 1 + 4 + 16) throw new ArgumentException("Frame too short");
        byte type = body[0];
        uint seq = BinaryPrimitives.ReadUInt32LittleEndian(body.AsSpan(1, 4));
        var cipher = body.AsSpan(5, body.Length - 5 - 16).ToArray();
        var tag = body.AsSpan(body.Length - 16, 16).ToArray();
        var plain = new byte[cipher.Length];
        using (var aes = new AesGcm(key, 16))
            aes.Decrypt(Nonce(direction, seq), cipher, tag, plain, Aad(type, seq));
        return new Frame(type, seq, plain);
    }
}
