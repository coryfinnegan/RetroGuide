using System.Globalization;
using Microsoft.Extensions.Logging;

namespace WeatherChannel;

/// <summary>
/// Appends to the same weather.log the scheduled task used to write, so the
/// history of the channel reads as one file across the change of host.
/// A WinExe has no console, so this log is the only record that the channel is
/// still being refreshed.
/// </summary>
public sealed class FileLogger : ILogger
{
    public static readonly string Path = System.IO.Path.Combine(
        SettingsStore.DataDirectory, "logs", "weather.log");

    private const long MaxBytes = 1_000_000;

    private static readonly Lock Gate = new();

    public IDisposable? BeginScope<TState>(TState state) where TState : notnull => null;

    public bool IsEnabled(LogLevel level) => level >= LogLevel.Information;

    public void Log<TState>(LogLevel level, EventId id, TState state, Exception? error,
                            Func<TState, Exception?, string> formatter)
    {
        if (!IsEnabled(level))
        {
            return;
        }

        var prefix = level >= LogLevel.Error ? "FAILED: "
            : level == LogLevel.Warning ? "warning: "
            : "";

        var line = string.Create(CultureInfo.InvariantCulture,
            $"{DateTime.Now:yyyy-MM-dd HH:mm:ss}  {prefix}{formatter(state, error)}");

        lock (Gate)
        {
            try
            {
                Directory.CreateDirectory(System.IO.Path.GetDirectoryName(Path)!);

                // Truncating rather than rotating: at one line per render this
                // takes years to reach, and nobody wants weather.log.1.
                if (File.Exists(Path) && new FileInfo(Path).Length > MaxBytes)
                {
                    var keep = File.ReadLines(Path).TakeLast(2000).ToArray();
                    File.WriteAllLines(Path, keep);
                }

                File.AppendAllText(Path, line + Environment.NewLine);
            }
            catch (Exception)
            {
                // Logging is best effort; never fail a render over it.
            }
        }
    }
}

public sealed class FileLoggerProvider : ILoggerProvider
{
    public ILogger CreateLogger(string categoryName) => new FileLogger();

    public void Dispose()
    {
    }
}
