using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;

namespace WeatherChannel;

internal static class Program
{
    /// <summary>
    /// The generic host and the WinForms message loop both want to own the
    /// process. The host runs in the background, the message loop owns the main
    /// thread, and closing the tray icon stops the host.
    /// </summary>
    [STAThread]
    private static int Main()
    {
        using var single = new Mutex(true, @"Local\ErsatzTV.WeatherChannel", out var mine);
        if (!mine)
        {
            return 0;   // already running in the tray
        }

        ApplicationConfiguration.Initialize();

        var builder = Host.CreateApplicationBuilder();

        builder.Logging.ClearProviders();
        builder.Logging.AddProvider(new FileLoggerProvider());

        // The log is a record of the channel, not of the process: one line per
        // render. Without these the HttpClient and host-lifetime categories bury
        // it under a dozen lines an hour.
        builder.Logging.AddFilter("System.Net.Http", LogLevel.Warning);
        builder.Logging.AddFilter("Microsoft.Extensions.Http", LogLevel.Warning);
        builder.Logging.AddFilter("Microsoft.Hosting", LogLevel.Warning);
        builder.Logging.AddFilter("Microsoft.Extensions.Hosting", LogLevel.Warning);

        builder.Services.AddSingleton<SettingsStore>();
        builder.Services.AddSingleton<PageBuilder>();
        builder.Services.AddSingleton<ChromeRenderer>();
        builder.Services.AddSingleton<VideoEncoder>();
        builder.Services.AddSingleton<TrayContext>();
        builder.Services.AddHttpClient<NwsClient>(NwsClient.Configure);

        builder.Services.AddSingleton<RenderService>();
        builder.Services.AddHostedService(sp => sp.GetRequiredService<RenderService>());

        using var host = builder.Build();
        host.Start();

        try
        {
            Application.Run(host.Services.GetRequiredService<TrayContext>());
        }
        finally
        {
            // A render can be waiting several minutes for ErsatzTV to release
            // the file. Give it a moment to unwind, then go regardless: the
            // staged file is disposable and the old video is still in place.
            host.StopAsync(TimeSpan.FromSeconds(5)).GetAwaiter().GetResult();
        }

        return 0;
    }
}
