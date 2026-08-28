using System.Diagnostics;
using System.Globalization;
using System.Text;
using Microsoft.Extensions.Logging;

namespace WeatherChannel;

/// <summary>
/// Assembles the page PNGs into the video ErsatzTV loops.
///
/// Two things here are load-bearing, and breaking either breaks the channel
/// rather than just the picture:
///
/// 1. The output must always be exactly the same length. ErsatzTV stored the
///    duration when it scanned the file and builds the playout from that;
///    render a different length and every later item drifts, silently.
/// 2. The file is replaced atomically and never rescanned. ErsatzTV re-reads it
///    from disk each time it opens the item - verified - so overwriting in place
///    is enough. But a half-written file would be read exactly as it stands.
/// </summary>
public sealed class VideoEncoder(SettingsStore settings, ILogger<VideoEncoder> log)
{
    /// <summary>4:3, which ErsatzTV pads out to 1920x1080.</summary>
    private const int ScaleWidth = 1440;
    private const int ScaleHeight = 1080;

    public async Task<int> EncodeAsync(
        IReadOnlyList<string> pngs, string workDirectory, CancellationToken ct)
    {
        var s = settings.Current;
        var total = s.PageSeconds * pngs.Count;
        var output = Path.GetFullPath(s.OutputPath);

        var listing = Path.Combine(workDirectory, "list.txt");
        var text = new StringBuilder();
        foreach (var png in pngs)
        {
            text.Append(CultureInfo.InvariantCulture,
                $"file '{png.Replace('\\', '/')}'\nduration {s.PageSeconds}\n");
        }

        // The concat demuxer ignores the last duration unless the final file is
        // repeated, which would otherwise cost us one page's worth of runtime.
        text.Append(CultureInfo.InvariantCulture,
            $"file '{pngs[^1].Replace('\\', '/')}'\n");
        await File.WriteAllTextAsync(listing, text.ToString(), ct);

        // Stage the encode beside the destination, not in the system temp dir:
        // a move is only atomic within one filesystem, and on Windows it refuses
        // outright across drives - temp is on C:, the video lives on D:.
        var directory = Path.GetDirectoryName(output)!;
        Directory.CreateDirectory(directory);
        var staged = Path.Combine(directory, $".{Path.GetFileName(output)}.tmp.mp4");

        var psi = new ProcessStartInfo(s.FfmpegPath)
        {
            UseShellExecute = false,
            CreateNoWindow = true,
            RedirectStandardError = true,
            RedirectStandardOutput = true,
        };

        foreach (var a in new[] { "-y", "-loglevel", "error", "-f", "concat", "-safe", "0", "-i", listing })
        {
            psi.ArgumentList.Add(a);
        }

        var bed = MusicBed();
        if (bed is not null)
        {
            psi.ArgumentList.Add("-stream_loop");
            psi.ArgumentList.Add("-1");
            psi.ArgumentList.Add("-i");
            psi.ArgumentList.Add(bed);
        }
        else
        {
            psi.ArgumentList.Add("-f");
            psi.ArgumentList.Add("lavfi");
            psi.ArgumentList.Add("-i");
            psi.ArgumentList.Add("anullsrc=r=48000:cl=stereo");
        }

        string[] rest =
        [
            "-vf", string.Create(CultureInfo.InvariantCulture,
                $"scale={ScaleWidth}:{ScaleHeight}:flags=neighbor,fps=24"),
            "-c:v", "libx264", "-preset", "veryfast", "-pix_fmt", "yuv420p",
            "-c:a", "aac", "-b:a", "128k", "-ac", "2",
            "-map", "0:v:0", "-map", "1:a:0",
            // -t, not -shortest: the length must be exactly this every run, and
            // -shortest would let the audio decide it.
            "-t", total.ToString(CultureInfo.InvariantCulture),
            staged,
        ];

        foreach (var a in rest)
        {
            psi.ArgumentList.Add(a);
        }

        using (var proc = Process.Start(psi)!)
        {
            var stderr = proc.StandardError.ReadToEndAsync(ct);
            await proc.WaitForExitAsync(ct);
            if (proc.ExitCode != 0 || !File.Exists(staged))
            {
                var err = await stderr;
                throw new InvalidOperationException(
                    "ffmpeg failed: " + (err.Length > 500 ? err[^500..] : err));
            }
        }

        await ReplaceAsync(staged, output, ct);
        return total;
    }

    /// <summary>
    /// While the channel is being watched, ffmpeg holds the old file open
    /// without FILE_SHARE_DELETE, so the move fails with "access is denied" -
    /// the rename needs delete access to the destination. It is not a permission
    /// problem and retrying is the fix: every couple of minutes the playout cuts
    /// to a commercial, ffmpeg closes this file, and the rename lands.
    /// </summary>
    private async Task ReplaceAsync(string staged, string output, CancellationToken ct)
    {
        var timeout = TimeSpan.FromSeconds(settings.Current.ReplaceTimeoutSeconds);
        var deadline = DateTime.UtcNow + timeout;
        var waited = false;

        while (true)
        {
            try
            {
                File.Move(staged, output, overwrite: true);
                if (waited)
                {
                    log.LogInformation("swapped in during a commercial break");
                }

                return;
            }
            catch (Exception e) when (e is IOException or UnauthorizedAccessException)
            {
                if (DateTime.UtcNow >= deadline)
                {
                    File.Delete(staged);
                    throw new InvalidOperationException(
                        $"{output} is still held open after {timeout.TotalSeconds:0}s; " +
                        "kept the old video rather than half-writing a new one");
                }

                waited = true;
                await Task.Delay(TimeSpan.FromSeconds(3), ct);
            }
        }
    }

    private string? MusicBed()
    {
        var dir = settings.Current.MusicDirectory;
        if (string.IsNullOrWhiteSpace(dir) || !Directory.Exists(dir))
        {
            return null;
        }

        string[] audio = [".mp3", ".m4a", ".aac", ".wav", ".ogg", ".flac"];
        return Directory.EnumerateFiles(dir)
            .Where(f => audio.Contains(Path.GetExtension(f), StringComparer.OrdinalIgnoreCase))
            .Order(StringComparer.OrdinalIgnoreCase)
            .FirstOrDefault();
    }
}
