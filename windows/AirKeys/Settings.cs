using System.Text.Json;

namespace AirKeys;

public sealed class AppSettings
{
    public double Sensitivity { get; set; } = 1.0;
    public double Acceleration { get; set; } = 0.5;
    public double ScrollSpeed { get; set; } = 1.0;
    public bool NaturalScroll { get; set; } = false;
    public bool StartWithWindows { get; set; } = false;
    public string PairedDeviceName { get; set; } = "";

    private static string Path =>
        System.IO.Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
            "AirKeys", "settings.json");

    public static AppSettings Load()
    {
        try
        {
            if (File.Exists(Path))
                return JsonSerializer.Deserialize<AppSettings>(File.ReadAllText(Path)) ?? new AppSettings();
        }
        catch { /* corrupt settings fall back to defaults, never crash startup */ }
        return new AppSettings();
    }

    public void Save()
    {
        try
        {
            Directory.CreateDirectory(System.IO.Path.GetDirectoryName(Path)!);
            File.WriteAllText(Path, JsonSerializer.Serialize(this, new JsonSerializerOptions { WriteIndented = true }));
        }
        catch { /* settings save is best-effort */ }
    }

    public void ApplyStartWithWindows()
    {
        try
        {
            using var key = Microsoft.Win32.Registry.CurrentUser.OpenSubKey(
                @"SOFTWARE\Microsoft\Windows\CurrentVersion\Run", writable: true);
            if (key == null) return;
            if (StartWithWindows)
                key.SetValue("AirKeys", Application.ExecutablePath);
            else
                key.DeleteValue("AirKeys", throwOnMissingValue: false);
        }
        catch { /* registry write needs no crash */ }
    }
}

/// <summary>DPAPI-protected pairing-key store, keyed by device name.
/// The raw key is never written to disk in plaintext.</summary>
public static class PairingStore
{
    private static string Dir =>
        System.IO.Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "AirKeys");
    private static string FilePath(string device) =>
        System.IO.Path.Combine(Dir, "pairing_" + Sanitize(device) + ".bin");

    private static string Sanitize(string s) =>
        new(s.Where(c => char.IsLetterOrDigit(c) || c is '-' or '_').ToArray());

    public static void Save(string device, byte[] key)
    {
        Directory.CreateDirectory(Dir);
        var protected_ = System.Security.Cryptography.ProtectedData.Protect(
            key, null, System.Security.Cryptography.DataProtectionScope.CurrentUser);
        File.WriteAllBytes(FilePath(device), protected_);
    }

    public static byte[]? Load(string device)
    {
        try
        {
            var p = FilePath(device);
            if (!File.Exists(p)) return null;
            return System.Security.Cryptography.ProtectedData.Unprotect(
                File.ReadAllBytes(p), null, System.Security.Cryptography.DataProtectionScope.CurrentUser);
        }
        catch { return null; }
    }

    public static bool HasAny()
    {
        try { return Directory.Exists(Dir) && Directory.GetFiles(Dir, "pairing_*.bin").Length > 0; }
        catch { return false; }
    }

    public static void ClearAll()
    {
        try
        {
            if (Directory.Exists(Dir))
                foreach (var f in Directory.GetFiles(Dir, "pairing_*.bin")) File.Delete(f);
        }
        catch { }
    }
}
