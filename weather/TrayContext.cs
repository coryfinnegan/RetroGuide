using System.Diagnostics;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Globalization;
using Microsoft.Extensions.Hosting;
using Microsoft.Win32;

namespace WeatherChannel;

/// <summary>
/// The tray icon. Owns the Windows message loop; the host runs underneath it.
///
/// Windows 11 hides icons it has not seen before in the overflow flyout, so on
/// a first run the icon is behind the chevron until it is dragged out. That is
/// the shell's behaviour, not a bug here.
/// </summary>
public sealed class TrayContext : ApplicationContext
{
    private const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";
    private const string RunValue = "ErsatzTV Weather Channel";

    private static readonly int[] Intervals = [5, 10, 15, 30, 60];

    private readonly SettingsStore _settings;
    private readonly RenderService _render;
    private readonly IHostApplicationLifetime _lifetime;

    private readonly NotifyIcon _icon;
    private readonly ToolStripMenuItem _status;
    private readonly ToolStripMenuItem _enabled;
    private readonly ToolStripMenuItem _renderNow;
    private readonly ToolStripMenuItem _every;
    private readonly ToolStripMenuItem _startup;

    private Icon? _active;
    private Icon? _paused;

    public TrayContext(SettingsStore settings, RenderService render,
                       IHostApplicationLifetime lifetime)
    {
        _settings = settings;
        _render = render;
        _lifetime = lifetime;

        _status = new ToolStripMenuItem("Starting…") { Enabled = false };
        _enabled = new ToolStripMenuItem("Enabled", null, ToggleEnabled)
        {
            CheckOnClick = true,
            Checked = settings.Current.Enabled,
        };
        _renderNow = new ToolStripMenuItem("Render now", null, RenderNow);
        _every = new ToolStripMenuItem("Refresh every");
        _startup = new ToolStripMenuItem("Start with Windows", null, ToggleStartup)
        {
            CheckOnClick = true,
            Checked = StartupEnabled(),
        };

        foreach (var minutes in Intervals)
        {
            var item = new ToolStripMenuItem(
                $"{minutes} minutes", null, (_, _) => SetInterval(minutes))
            {
                Checked = settings.Current.IntervalMinutes == minutes,
            };
            _every.DropDownItems.Add(item);
        }

        var menu = new ContextMenuStrip();
        menu.Items.AddRange(
        [
            new ToolStripMenuItem("Austin Weather — ErsatzTV channel 77") { Enabled = false },
            _status,
            new ToolStripSeparator(),
            _enabled,
            _renderNow,
            _every,
            new ToolStripSeparator(),
            new ToolStripMenuItem("Open log", null, OpenLog),
            new ToolStripMenuItem("Open output folder", null, OpenOutput),
            _startup,
            new ToolStripSeparator(),
            new ToolStripMenuItem("Exit", null, (_, _) => ExitThread()),
        ]);

        _icon = new NotifyIcon
        {
            Icon = IconFor(settings.Current.Enabled),
            Text = "Austin Weather",
            ContextMenuStrip = menu,
            Visible = true,
        };
        _icon.DoubleClick += RenderNow;

        _render.StatusChanged += OnStatusChanged;
        Show(_render.Status);
    }

    private void OnStatusChanged(RenderStatus status)
    {
        // Raised from the render loop's thread; marshal to the UI thread that
        // owns the icon before touching it.
        if (_icon.ContextMenuStrip is { IsHandleCreated: true } menu)
        {
            menu.BeginInvoke(() => Show(status));
        }
        else
        {
            Show(status);
        }
    }

    private void Show(RenderStatus status)
    {
        var line = status.State switch
        {
            RenderState.Rendering => "Rendering…",
            RenderState.Paused => "Paused",
            RenderState.Failed => "Last run failed: " + status.Message,
            _ when status.LastSuccess is { } t =>
                "Updated " + t.ToString("h:mm tt", CultureInfo.CurrentCulture),
            _ => "Waiting",
        };

        if (status.State is RenderState.Idle && status.NextRun is { } next)
        {
            line += ", next " + next.ToString("h:mm tt", CultureInfo.CurrentCulture);
        }

        _status.Text = line;
        _icon.Icon = IconFor(_settings.Current.Enabled && status.State != RenderState.Failed);

        // NotifyIcon.Text is capped at 63 characters and throws above it.
        var tip = "Austin Weather — " + line;
        _icon.Text = tip.Length > 63 ? tip[..60] + "…" : tip;
    }

    private void ToggleEnabled(object? sender, EventArgs e)
    {
        _settings.Update(s => s.Enabled = _enabled.Checked);
        _render.Wake();
    }

    private void RenderNow(object? sender, EventArgs e) => _render.Wake();

    private void SetInterval(int minutes)
    {
        _settings.Update(s => s.IntervalMinutes = minutes);
        foreach (ToolStripMenuItem item in _every.DropDownItems)
        {
            item.Checked = item.Text!.StartsWith(
                minutes.ToString(CultureInfo.InvariantCulture) + " ", StringComparison.Ordinal);
        }

        _render.Wake();
    }

    private void OpenLog(object? sender, EventArgs e) => Open(FileLogger.Path);

    private void OpenOutput(object? sender, EventArgs e) =>
        Open(Path.GetDirectoryName(Path.GetFullPath(_settings.Current.OutputPath))!);

    private static void Open(string path)
    {
        try
        {
            Process.Start(new ProcessStartInfo(path) { UseShellExecute = true });
        }
        catch (Exception ex)
        {
            MessageBox.Show(ex.Message, "Weather Channel",
                MessageBoxButtons.OK, MessageBoxIcon.Warning);
        }
    }

    private static bool StartupEnabled()
    {
        using var key = Registry.CurrentUser.OpenSubKey(RunKey);
        return key?.GetValue(RunValue) is not null;
    }

    private void ToggleStartup(object? sender, EventArgs e)
    {
        try
        {
            using var key = Registry.CurrentUser.OpenSubKey(RunKey, writable: true)!;
            if (_startup.Checked)
            {
                key.SetValue(RunValue, $"\"{Environment.ProcessPath}\"");
            }
            else
            {
                key.DeleteValue(RunValue, throwOnMissingValue: false);
            }
        }
        catch (Exception ex)
        {
            _startup.Checked = StartupEnabled();
            MessageBox.Show(ex.Message, "Weather Channel",
                MessageBoxButtons.OK, MessageBoxIcon.Warning);
        }
    }

    /// <summary>
    /// A drawn icon rather than a resource: an amber sun when the channel is
    /// being refreshed, grey when it is not, so the tray shows the state without
    /// opening the menu.
    /// </summary>
    private Icon IconFor(bool active)
    {
        var cached = active ? _active : _paused;
        if (cached is not null)
        {
            return cached;
        }

        using var bmp = new Bitmap(32, 32);
        using (var g = Graphics.FromImage(bmp))
        {
            g.SmoothingMode = SmoothingMode.AntiAlias;
            g.Clear(Color.Transparent);

            var face = active ? Color.FromArgb(255, 176, 0) : Color.FromArgb(130, 130, 130);
            using var pen = new Pen(face, 3f);
            using var brush = new SolidBrush(face);

            for (var i = 0; i < 8; i++)
            {
                var a = i * Math.PI / 4;
                g.DrawLine(pen,
                    16 + (float)(Math.Cos(a) * 10), 16 + (float)(Math.Sin(a) * 10),
                    16 + (float)(Math.Cos(a) * 15), 16 + (float)(Math.Sin(a) * 15));
            }

            g.FillEllipse(brush, 6, 6, 20, 20);
        }

        cached = Icon.FromHandle(bmp.GetHicon());
        if (active)
        {
            _active = cached;
        }
        else
        {
            _paused = cached;
        }

        return cached;
    }

    protected override void Dispose(bool disposing)
    {
        if (disposing)
        {
            _render.StatusChanged -= OnStatusChanged;
            _icon.Visible = false;
            _icon.Dispose();
            _active?.Dispose();
            _paused?.Dispose();
        }

        base.Dispose(disposing);
    }

    protected override void ExitThreadCore()
    {
        _lifetime.StopApplication();
        base.ExitThreadCore();
    }
}
