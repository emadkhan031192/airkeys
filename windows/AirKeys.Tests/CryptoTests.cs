using System.Security.Cryptography;
using System.Text;
using AirKeys;
using Xunit;

public class CryptoTests
{
    [Fact]
    public void EncryptDecryptRoundTrip()
    {
        var key = Crypto.DeriveKeyFromCode("123456");
        var frame = Crypto.EncryptFrame(key, 0x00, FrameType.Text, 42,
            Encoding.UTF8.GetBytes("hello دنیا"));
        // Strip length prefix, decrypt body.
        var body = frame.AsSpan(2).ToArray();
        var out_ = Crypto.DecryptFrame(key, 0x00, body);
        Assert.Equal(FrameType.Text, out_.Type);
        Assert.Equal(42u, out_.Seq);
        Assert.Equal("hello دنیا", Encoding.UTF8.GetString(out_.Payload));
    }

    [Fact]
    public void TamperedFrameFails()
    {
        var key = Crypto.DeriveKeyFromCode("123456");
        var frame = Crypto.EncryptFrame(key, 0x00, FrameType.Text, 7, new byte[] { (byte)'x' });
        frame[^1] ^= 0xFF;
        Assert.Throws<CryptographicException>(() =>
            Crypto.DecryptFrame(key, 0x00, frame.AsSpan(2).ToArray()));
    }

    [Fact]
    public void WrongCodeCannotDecrypt()
    {
        var a = Crypto.DeriveKeyFromCode("123456");
        var b = Crypto.DeriveKeyFromCode("654321");
        var frame = Crypto.EncryptFrame(a, 0x00, FrameType.Text, 1, new byte[] { (byte)'x' });
        Assert.Throws<CryptographicException>(() =>
            Crypto.DecryptFrame(b, 0x00, frame.AsSpan(2).ToArray()));
    }

    [Fact]
    public void NonceIsDirectionAndSeqDependent()
    {
        Assert.NotEqual(Crypto.Nonce(0, 1), Crypto.Nonce(1, 1));
        Assert.NotEqual(Crypto.Nonce(0, 1), Crypto.Nonce(0, 2));
    }
}
