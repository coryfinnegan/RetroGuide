using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;

namespace WeatherChannel;

public enum RenderState
{
    Idle,
    Rendering,
    Paused,
    Failed,
}

public sealed record RenderStatus(
    RenderState State, DateTime? LastSuccess, DateTime? NextRun, string Message);

/// <summary>
/// Re-renders the channel video on a timer for as long as the app is running.
///
/// This replaced a Windows scheduled task. The task worked, but the render can
/// legitimately sit and wait several minutes for ErsatzTV to release the file,
/// which the Task Scheduler shows only as a long-running instance, and pausing
/// it meant opening taskschd.msc. A resident service can say what it is doing
/// and can be switched off from the tray.
/// </summary>
public sealed class RenderService(
    SettingsStore settings,
    NwsClient nws,
    PageBuilder pages,
    ChromeRenderer chrome,
    VideoEncoder encoder,
    ILogger<RenderService> log) : BackgroundService
{
    private readonly SemaphoreSlim _wake = new(0);
    private readonly SemaphoreSlim _oneAtATime = new(1, 1);

    private RenderStatus _status = new(RenderState.Idle, null, null, "starting");

    public RenderStatus Status => _status;

    /// <summary>Raised whenever <see cref="Status"/> changes, for the tray icon.</summary>
    public event Action<RenderStatus>? StatusChanged;

    /// <summary>Cut the wait short - used by "Render now" and by settings changes.</summary>
    public void Wake()
    {
        if (_wake.CurrentCount == 0)
        {
            _wake.Release();
        }
    }

    protected override async Task ExecuteAsync(CancellationToken ct)
    {
        settings.Changed += Wake;

        while (!ct.IsCancellationRequested)
        {
            if (settings.Current.Enabled)
            {
                await RenderOnceAsync(ct);
            }
            else
            {
                Set(new RenderStatus(RenderState.Paused, _status.LastSuccess, null, "paused"));
            }

            var interval = TimeSpan.FromMinutes(Math.Max(1, settings.Current.IntervalMinutes));
            if (settings.Current.Enabled)
            {
                Set(_status with { NextRun = DateTime.Now + interval });
            }

            try
            {
                // Wake early on "Render now" or a settings change; otherwise
                // sleep the interval. Paused still ticks, so re-enabling from
                // the tray takes effect at once through Wake().
                await _wake.WaitAsync(interval, ct);
            }
            catch (OperationCanceledException)
            {
                break;
            }
        }

        settings.Changed -= Wake;
    }

    public async Task RenderOnceAsync(CancellationToken ct)
    {
        if (!await _oneAtATime.WaitAsync(0, ct))
        {
            return;   // a render is already in flight; let it finish
        }

        var work = Path.Combine(Path.GetTempPath(), "wx-" + Guid.NewGuid().ToString("N")[..8]);
        try
        {
            Set(_status with { State = RenderState.Rendering, Message = "rendering" });
            Directory.CreateDirectory(work);

            var s = settings.Current;
            var data = await nws.FetchAsync(s.Latitude, s.Longitude, ct);
            var html = pages.Build(data);
            if (html.Count == 0)
            {
                throw new InvalidOperationException(
                    "no forecast data; leaving the existing video alone");
            }

            var pngs = await chrome.RenderAsync(html, work, ct);
            var seconds = await encoder.EncodeAsync(pngs, work, ct);

            log.LogInformation("wrote {Path} ({Pages} pages, {Seconds}s)",
                s.OutputPath, pngs.Count, seconds);
            Set(new RenderStatus(RenderState.Idle, DateTime.Now, _status.NextRun,
                $"{pngs.Count} pages, {seconds}s"));
        }
        catch (OperationCanceledException)
        {
            throw;
        }
        catch (Exception e)
        {
            log.LogError("render failed: {Message}", e.Message);
            Set(new RenderStatus(RenderState.Failed, _status.LastSuccess, _status.NextRun,
                e.Message));
        }
        finally
        {
            try
            {
                Directory.Delete(work, recursive: true);
            }
            catch (Exception)
            {
                // The temp directory is disposable; a locked PNG is not worth a log line.
            }

            _oneAtATime.Release();
        }
    }

    private void Set(RenderStatus status)
    {
        _status = status;
        StatusChanged?.Invoke(status);
    }
}
