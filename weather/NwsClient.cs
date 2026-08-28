using System.Globalization;
using System.Net.Http.Headers;
using System.Text.Json;
using Microsoft.Extensions.Logging;

namespace WeatherChannel;

/// <summary>One measurement as the National Weather Service reports it.</summary>
public readonly record struct Measurement(double? Value, string Unit)
{
    /// <summary>
    /// Every NWS measurement carries its own unitCode, and they are not what you
    /// would guess: these stations report windSpeed in km/h, not m/s. Assuming
    /// m/s turned a calm 14.8 km/h evening into "SSW 33 MPH" - wrong in a way
    /// that reads as a plausible number rather than as a bug. Convert from the
    /// unit the API states, and return null for anything unrecognised rather
    /// than printing a figure that means nothing.
    /// </summary>
    public int? Fahrenheit => Unit switch
    {
        "degC" or "degreeCelsius" => Value is { } v ? (int)Math.Round(v * 9.0 / 5.0 + 32) : null,
        "degF" or "degreeFahrenheit" => Value is { } v ? (int)Math.Round(v) : null,
        _ => null,
    };

    public int? MilesPerHour => Unit switch
    {
        "km_h-1" => Value is { } v ? (int)Math.Round(v * 0.621371) : null,
        "m_s-1" => Value is { } v ? (int)Math.Round(v * 2.23694) : null,
        "mi_h-1" => Value is { } v ? (int)Math.Round(v) : null,
        _ => null,
    };

    public double? InchesOfMercury => Unit switch
    {
        "Pa" => Value is { } v ? Math.Round(v / 3386.389, 2) : null,
        "inHg" => Value is { } v ? Math.Round(v, 2) : null,
        _ => null,
    };
}

public sealed record Period(string Name, int Temperature, string ShortForecast,
                            string DetailedForecast, DateTimeOffset StartTime);

public sealed record Observation(
    string Description, Measurement Temperature, Measurement Dewpoint,
    Measurement RelativeHumidity, Measurement WindSpeed, Measurement WindDirection,
    Measurement Pressure);

public sealed record Alert(string Event, string Headline);

public sealed record WeatherData(
    string City, string State,
    IReadOnlyList<Period> Forecast,
    IReadOnlyList<Period> Hourly,
    Observation? Current,
    IReadOnlyList<Alert> Alerts);

/// <summary>
/// Reads api.weather.gov. No key is needed; NWS asks only for a User-Agent that
/// identifies who is calling.
/// </summary>
public sealed class NwsClient(HttpClient http, ILogger<NwsClient> log)
{
    private const string Api = "https://api.weather.gov";

    public static void Configure(HttpClient c)
    {
        c.Timeout = TimeSpan.FromSeconds(20);
        c.DefaultRequestHeaders.UserAgent.ParseAdd(
            "RetroGuide-WeatherChannel/1.0 (personal ErsatzTV instance)");
        c.DefaultRequestHeaders.Accept.Add(
            new MediaTypeWithQualityHeaderValue("application/geo+json"));
    }

    private async Task<JsonElement> GetAsync(string url, CancellationToken ct)
    {
        Exception? last = null;
        for (var attempt = 0; attempt < 3; attempt++)
        {
            try
            {
                await using var stream = await http.GetStreamAsync(url, ct);
                using var doc = await JsonDocument.ParseAsync(stream, cancellationToken: ct);
                return doc.RootElement.Clone();
            }
            catch (Exception e) when (e is not OperationCanceledException)
            {
                last = e;
            }
        }

        throw new InvalidOperationException($"{url}: {last?.Message}", last);
    }

    /// <summary>
    /// Everything the pages need. Individual pieces degrade to empty rather than
    /// failing the run - a missing hourly forecast should cost one page, not the
    /// whole channel.
    /// </summary>
    public async Task<WeatherData> FetchAsync(double lat, double lon, CancellationToken ct)
    {
        var coords = string.Create(CultureInfo.InvariantCulture, $"{lat},{lon}");
        var point = (await GetAsync($"{Api}/points/{coords}", ct)).GetProperty("properties");

        var rel = point.GetProperty("relativeLocation").GetProperty("properties");
        var city = (rel.GetProperty("city").GetString() ?? "").ToUpperInvariant();
        var state = (rel.GetProperty("state").GetString() ?? "").ToUpperInvariant();

        var forecast = await PeriodsAsync(point, "forecast", ct);
        var hourly = await PeriodsAsync(point, "forecastHourly", ct);
        var current = await CurrentAsync(point, ct);
        var alerts = await AlertsAsync(coords, ct);

        return new WeatherData(city, state, forecast, hourly, current, alerts);
    }

    private async Task<IReadOnlyList<Period>> PeriodsAsync(
        JsonElement point, string key, CancellationToken ct)
    {
        try
        {
            var url = point.GetProperty(key).GetString()!;
            var periods = (await GetAsync(url, ct))
                .GetProperty("properties").GetProperty("periods");

            var list = new List<Period>();
            foreach (var p in periods.EnumerateArray())
            {
                list.Add(new Period(
                    p.GetProperty("name").GetString() ?? "",
                    p.GetProperty("temperature").GetInt32(),
                    p.GetProperty("shortForecast").GetString() ?? "",
                    p.TryGetProperty("detailedForecast", out var d) ? d.GetString() ?? "" : "",
                    DateTimeOffset.Parse(p.GetProperty("startTime").GetString()!,
                                         CultureInfo.InvariantCulture)));
            }

            return list;
        }
        catch (Exception e) when (e is not OperationCanceledException)
        {
            log.LogWarning("could not read {Key}: {Message}", key, e.Message);
            return [];
        }
    }

    /// <summary>
    /// The nearest station is not always the most useful one: KATT, the closest
    /// to 78723, reports a temperature but no textDescription at all, which left
    /// the conditions page reading "--". Prefer the first station that has both,
    /// and settle for one that merely has a temperature.
    /// </summary>
    private async Task<Observation?> CurrentAsync(JsonElement point, CancellationToken ct)
    {
        try
        {
            var stations = (await GetAsync(
                point.GetProperty("observationStations").GetString()!, ct))
                .GetProperty("features");

            Observation? best = null;
            var tried = 0;
            foreach (var station in stations.EnumerateArray())
            {
                if (tried++ >= 4)
                {
                    break;
                }

                JsonElement props;
                try
                {
                    props = (await GetAsync(
                        station.GetProperty("id").GetString() + "/observations/latest", ct))
                        .GetProperty("properties");
                }
                catch (Exception e) when (e is not OperationCanceledException)
                {
                    continue;
                }

                var obs = new Observation(
                    props.TryGetProperty("textDescription", out var td)
                        ? (td.GetString() ?? "").Trim() : "",
                    Meas(props, "temperature"), Meas(props, "dewpoint"),
                    Meas(props, "relativeHumidity"), Meas(props, "windSpeed"),
                    Meas(props, "windDirection"), Meas(props, "barometricPressure"));

                if (obs.Temperature.Value is null)
                {
                    continue;
                }

                best ??= obs;
                if (obs.Description.Length > 0)
                {
                    return obs;
                }
            }

            return best;
        }
        catch (Exception e) when (e is not OperationCanceledException)
        {
            log.LogWarning("could not read current conditions: {Message}", e.Message);
            return null;
        }
    }

    private async Task<IReadOnlyList<Alert>> AlertsAsync(string coords, CancellationToken ct)
    {
        try
        {
            var features = (await GetAsync($"{Api}/alerts/active?point={coords}", ct))
                .GetProperty("features");

            var list = new List<Alert>();
            foreach (var f in features.EnumerateArray())
            {
                var p = f.GetProperty("properties");
                list.Add(new Alert(
                    p.TryGetProperty("event", out var e) ? e.GetString() ?? "ALERT" : "ALERT",
                    p.TryGetProperty("headline", out var h) ? h.GetString() ?? "" : ""));
            }

            return list;
        }
        catch (Exception e) when (e is not OperationCanceledException)
        {
            log.LogWarning("could not read alerts: {Message}", e.Message);
            return [];
        }
    }

    private static Measurement Meas(JsonElement props, string name)
    {
        if (!props.TryGetProperty(name, out var m) || m.ValueKind != JsonValueKind.Object)
        {
            return default;
        }

        double? value = m.TryGetProperty("value", out var v) && v.ValueKind == JsonValueKind.Number
            ? v.GetDouble()
            : null;

        var unit = m.TryGetProperty("unitCode", out var u) ? u.GetString() ?? "" : "";
        var colon = unit.LastIndexOf(':');
        return new Measurement(value, colon >= 0 ? unit[(colon + 1)..] : unit);
    }
}
