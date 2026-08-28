using System.Text.Json;
using System.Text.Json.Serialization;

namespace WeatherChannel;

/// <summary>
/// Everything the channel needs to know, persisted next to the ErsatzTV
/// database so it survives a rebuild of this app.
/// </summary>
public sealed class WeatherSettings
{
    public bool Enabled { get; set; } = true;
    public int IntervalMinutes { get; set; } = 10;

    public double Latitude { get; set; } = 30.3085;      // 78723, Austin TX
    public double Longitude { get; set; } = -97.6849;
    public string ChannelName { get; set; } = "AUSTIN WEATHER";

    public string OutputPath { get; set; } = @"D:\ETV\Weather\weather.mp4";

    /// <summary>Optional; the first audio file found here becomes the bed.</summary>
    public string MusicDirectory { get; set; } = @"D:\ETV\Weather\music";

    /// <summary>
    /// Seconds per page. The total render length is this times the page count,
    /// and it must not vary between runs - see <see cref="VideoEncoder"/>.
    /// </summary>
    public int PageSeconds { get; set; } = 24;

    public string FfmpegPath { get; set; } = @"C:\ErsatzTV\ffmpeg.exe";

    /// <summary>How long to keep trying to swap the file in while ErsatzTV holds it.</summary>
    public int ReplaceTimeoutSeconds { get; set; } = 420;
}

[JsonSerializable(typeof(WeatherSettings))]
[JsonSourceGenerationOptions(WriteIndented = true)]
internal partial class SettingsContext : JsonSerializerContext;

/// <summary>Loads, saves and broadcasts <see cref="WeatherSettings"/>.</summary>
public sealed class SettingsStore
{
    public static readonly string DataDirectory = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        "ErsatzTV");

    private static readonly string FilePath =
        Path.Combine(DataDirectory, "weather-channel.json");

    private readonly Lock _gate = new();

    public WeatherSettings Current { get; private set; } = new();

    /// <summary>Raised after any save. The scheduler uses it to re-time itself.</summary>
    public event Action? Changed;

    public SettingsStore()
    {
        try
        {
            if (File.Exists(FilePath))
            {
                var loaded = JsonSerializer.Deserialize(
                    File.ReadAllText(FilePath), SettingsContext.Default.WeatherSettings);
                if (loaded is not null)
                {
                    Current = loaded;
                }
            }
        }
        catch (Exception)
        {
            // A corrupt settings file must not stop the channel; the defaults
            // above are the ones that have been running in production anyway.
        }

        // Write the file out on first run so there is something to edit; every
        // knob the app has is then visible without reading the source.
        if (!File.Exists(FilePath))
        {
            Update(_ => { });
        }
    }

    public void Update(Action<WeatherSettings> edit)
    {
        lock (_gate)
        {
            edit(Current);
            try
            {
                Directory.CreateDirectory(DataDirectory);
                File.WriteAllText(FilePath, JsonSerializer.Serialize(
                    Current, SettingsContext.Default.WeatherSettings));
            }
            catch (Exception)
            {
                // Best effort. Losing the setting on restart beats crashing.
            }
        }

        Changed?.Invoke();
    }
}
