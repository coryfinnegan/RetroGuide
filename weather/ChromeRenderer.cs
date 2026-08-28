using System.Diagnostics;
using System.Globalization;
using System.Text;

namespace WeatherChannel;

/// <summary>Turns each HTML page into a PNG with headless Chrome (or Edge).</summary>
public sealed class ChromeRenderer
{
    private static readonly string[] Candidates =
    [
        @"C:\Program Files\Google\Chrome\Application\chrome.exe",
        @"C:\Program Files (x86)\Google\Chrome\Application\chrome.exe",
        @"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
        @"C:\Program Files\Microsoft\Edge\Application\msedge.exe",
    ];

    public static string FindBrowser() =>
        Candidates.FirstOrDefault(File.Exists)
        ?? throw new FileNotFoundException(
            "no Chrome or Edge found; one of them is needed to render the pages");

    public async Task<IReadOnlyList<string>> RenderAsync(
        IReadOnlyList<string> pages, string workDirectory, CancellationToken ct)
    {
        var exe = FindBrowser();
        var pngs = new List<string>(pages.Count);

        for (var i = 0; i < pages.Count; i++)
        {
            var html = Path.Combine(workDirectory,
                string.Create(CultureInfo.InvariantCulture, $"p{i:00}.html"));
            var png = Path.Combine(workDirectory,
                string.Create(CultureInfo.InvariantCulture, $"p{i:00}.png"));

            await File.WriteAllTextAsync(html, pages[i], new UTF8Encoding(false), ct);

            if (!await ShootAsync(exe, html, png, ct))
            {
                throw new InvalidOperationException($"failed to render page {i}");
            }

            pngs.Add(png);
        }

        return pngs;
    }

    /// <summary>
    /// Chrome returns 0 and exits *before* the screenshot has actually been
    /// written - about a second before, in practice - so checking for the file
    /// the moment the process ends always finds nothing, with no error anywhere
    /// to say why. Wait for the file rather than trusting the exit code.
    /// </summary>
    private static async Task<bool> ShootAsync(
        string exe, string html, string png, CancellationToken ct)
    {
        var psi = new ProcessStartInfo(exe)
        {
            UseShellExecute = false,
            CreateNoWindow = true,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
        };

        psi.ArgumentList.Add("--headless=new");
        psi.ArgumentList.Add("--disable-gpu");
        psi.ArgumentList.Add("--hide-scrollbars");
        psi.ArgumentList.Add("--force-device-scale-factor=1");
        psi.ArgumentList.Add("--screenshot=" + png);
        psi.ArgumentList.Add(string.Create(CultureInfo.InvariantCulture,
            $"--window-size={PageBuilder.Width},{PageBuilder.Height}"));
        psi.ArgumentList.Add("file:///" + html.Replace('\\', '/'));

        using (var proc = Process.Start(psi)!)
        {
            using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct);
            timeout.CancelAfter(TimeSpan.FromSeconds(90));
            try
            {
                await proc.WaitForExitAsync(timeout.Token);
            }
            catch (OperationCanceledException) when (!ct.IsCancellationRequested)
            {
                try
                {
                    proc.Kill(entireProcessTree: true);
                }
                catch (InvalidOperationException)
                {
                }
            }
        }

        for (var i = 0; i < 60; i++)
        {
            if (File.Exists(png) && new FileInfo(png).Length > 0)
            {
                return true;
            }

            await Task.Delay(250, ct);
        }

        return false;
    }
}
