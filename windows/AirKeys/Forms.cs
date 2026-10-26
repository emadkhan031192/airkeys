namespace AirKeys;

/// <summary>Pairing window: shows the 6-digit code the user types on the phone.
/// The code is never logged and expires after 120 s (enforced server-side).</summary>
public sealed class PairingForm : Form
{
    public PairingForm(string code)
    {
        Text = "AirKeys — Pair your phone";
        Width = 380; Height = 220;
        StartPosition = FormStartPosition.CenterScreen;
        FormBorderStyle = FormBorderStyle.FixedDialog;
        MaximizeBox = false;
        var label = new Label
        {
            Text = "On your phone, tap Connect and enter this code:",
            Dock = DockStyle.Top, Height = 40, TextAlign = ContentAlignment.MiddleCenter,
        };
        var codeLabel = new Label
        {
            Text = code, Dock = DockStyle.Top, Height = 60,
            Font = new Font(FontFamily.GenericMonospace, 28, FontStyle.Bold),
            TextAlign = ContentAlignment.MiddleCenter,
        };
        var note = new Label
        {
            Text = "This code expires in 120 seconds. It is only needed once per phone.",
            Dock = DockStyle.Top, Height = 30, TextAlign = ContentAlignment.MiddleCenter,
        };
        var ok = new Button { Text = "Done", Dock = DockStyle.Bottom, Height = 36 };
        ok.Click += (_, _) => Close();
        Controls.Add(ok); Controls.Add(note); Controls.Add(codeLabel); Controls.Add(label);
    }
}

/// <summary>Settings: sensitivity, acceleration, scroll speed, natural scroll,
/// start-with-Windows toggle. Saved to %APPDATA%\AirKeys\settings.json.</summary>
public sealed class SettingsForm : Form
{
    private readonly AppSettings _settings;
    public SettingsForm(AppSettings settings)
    {
        _settings = settings;
        Text = "AirKeys — Settings";
        Width = 420; Height = 360;
        StartPosition = FormStartPosition.CenterScreen;
        FormBorderStyle = FormBorderStyle.FixedDialog;
        MaximizeBox = false;

        var layout = new TableLayoutPanel { Dock = DockStyle.Fill, ColumnCount = 2, RowCount = 6, Padding = new Padding(12) };
        layout.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 45));
        layout.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 55));

        var sens = new NumericUpDown { Minimum = 0.25m, Maximum = 3m, DecimalPlaces = 2, Increment = 0.05m, Value = (decimal)settings.Sensitivity, Dock = DockStyle.Fill };
        var accel = new NumericUpDown { Minimum = 0m, Maximum = 2m, DecimalPlaces = 2, Increment = 0.05m, Value = (decimal)settings.Acceleration, Dock = DockStyle.Fill };
        var scroll = new NumericUpDown { Minimum = 0.25m, Maximum = 3m, DecimalPlaces = 2, Increment = 0.05m, Value = (decimal)settings.ScrollSpeed, Dock = DockStyle.Fill };
        var natural = new CheckBox { Checked = settings.NaturalScroll, Text = "Natural scroll" };
        var startWin = new CheckBox { Checked = settings.StartWithWindows, Text = "Start with Windows" };

        layout.Controls.Add(new Label { Text = "Sensitivity", TextAlign = ContentAlignment.MiddleLeft }, 0, 0);
        layout.Controls.Add(sens, 1, 0);
        layout.Controls.Add(new Label { Text = "Acceleration (0 = linear)", TextAlign = ContentAlignment.MiddleLeft }, 0, 1);
        layout.Controls.Add(accel, 1, 1);
        layout.Controls.Add(new Label { Text = "Scroll speed", TextAlign = ContentAlignment.MiddleLeft }, 0, 2);
        layout.Controls.Add(scroll, 1, 2);
        layout.Controls.Add(natural, 0, 3); layout.SetColumnSpan(natural, 2);
        layout.Controls.Add(startWin, 0, 4); layout.SetColumnSpan(startWin, 2);

        var save = new Button { Text = "Save", Dock = DockStyle.Fill };
        save.Click += (_, _) =>
        {
            _settings.Sensitivity = (double)sens.Value;
            _settings.Acceleration = (double)accel.Value;
            _settings.ScrollSpeed = (double)scroll.Value;
            _settings.NaturalScroll = natural.Checked;
            _settings.StartWithWindows = startWin.Checked;
            _settings.Save();
            _settings.ApplyStartWithWindows();
            Close();
        };
        layout.Controls.Add(save, 0, 5); layout.SetColumnSpan(save, 2);

        var forget = new Button { Text = "Forget paired device (re-pair)", Dock = DockStyle.Bottom, Height = 32 };
        forget.Click += (_, _) =>
        {
            PairingStore.ClearAll();
            _settings.PairedDeviceName = "";
            _settings.Save();
            MessageBox.Show("Paired device forgotten. Use Tray > Re-pair for a new code.",
                "AirKeys", MessageBoxButtons.OK, MessageBoxIcon.Information);
        };
        Controls.Add(forget);
        Controls.Add(layout);
    }
}
