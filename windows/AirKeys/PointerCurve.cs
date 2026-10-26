namespace AirKeys;

/// <summary>
/// Pointer acceleration: a configurable curve, not a magic multiplier.
/// factor = sensitivity * (1 + accel * magnitude / (magnitude + 20))
/// At accel = 0 the cursor is purely linear (factor == sensitivity).
/// At accel > 0 slow moves stay near-linear (precise) and fast flicks
/// are amplified, saturating at sensitivity * (1 + accel).
/// Output is rounded and clamped so a single frame can never teleport.
/// </summary>
public static class PointerCurve
{
    public static (int Dx, int Dy) Apply(int dx, int dy, double sensitivity, double accel)
    {
        if (dx == 0 && dy == 0) return (0, 0);
        double mag = Math.Sqrt((double)dx * dx + (double)dy * dy);
        double factor = sensitivity * (1.0 + accel * mag / (mag + 20.0));
        int ox = (int)Math.Round(dx * factor);
        int oy = (int)Math.Round(dy * factor);
        ox = Math.Clamp(ox, -2000, 2000);
        oy = Math.Clamp(oy, -2000, 2000);
        return (ox, oy);
    }

    /// <summary>Scroll: clicks -> WHEEL_DELTA units, scaled by speed, optionally inverted.</summary>
    public static int WheelDelta(int clicks, double speed, bool natural)
    {
        int d = (int)Math.Round(clicks * 120.0 * speed);
        return natural ? -d : d;
    }
}
