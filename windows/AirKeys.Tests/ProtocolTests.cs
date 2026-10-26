using AirKeys;
using Xunit;

public class ProtocolTests
{
    [Fact]
    public void SeqValidatorRejectsDuplicatesAndReplays()
    {
        var v = new SeqValidator();
        Assert.True(v.Accept(1));
        Assert.True(v.Accept(2));
        Assert.False(v.Accept(2));
        Assert.False(v.Accept(1));
        Assert.True(v.Accept(3));
    }

    [Fact]
    public void MovePayloadParsesLittleEndian()
    {
        // Build a move payload the same way the phone does: int16 LE dx, dy.
        var p = new byte[4];
        System.Buffers.Binary.BinaryPrimitives.WriteInt16LittleEndian(p.AsSpan(0, 2), -10);
        System.Buffers.Binary.BinaryPrimitives.WriteInt16LittleEndian(p.AsSpan(2, 2), 300);
        var (dx, dy) = FrameCodec.ParseMove(p);
        Assert.Equal(-10, dx);
        Assert.Equal(300, dy);
    }

    [Fact]
    public void PointerCurveIsLinearAtZeroAccel()
    {
        var (dx, dy) = PointerCurve.Apply(10, 0, 1.5, 0.0);
        Assert.Equal(15, dx);
        Assert.Equal(0, dy);
        Assert.Equal((0, 0), PointerCurve.Apply(0, 0, 2.0, 1.0));
    }

    [Fact]
    public void PointerCurveAmplifiesFastMovesMoreThanSlowOnes()
    {
        var slow = PointerCurve.Apply(5, 0, 1.0, 1.0);
        var fast = PointerCurve.Apply(100, 0, 1.0, 1.0);
        // Slow: factor ~1.2; fast: factor ~1.83. Ratio fast/slow per unit must be higher.
        Assert.True((double)fast.Dx / 100 > (double)slow.Dx / 5);
    }

    [Fact]
    public void WheelDeltaScalesAndInverts()
    {
        Assert.Equal(120, PointerCurve.WheelDelta(1, 1.0, natural: false));
        Assert.Equal(-120, PointerCurve.WheelDelta(1, 1.0, natural: true));
        Assert.Equal(240, PointerCurve.WheelDelta(1, 2.0, natural: false));
    }
}
