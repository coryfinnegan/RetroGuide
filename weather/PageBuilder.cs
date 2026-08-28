using System.Globalization;
using System.Net;
using System.Text;

namespace WeatherChannel;

/// <summary>
/// Draws the pages as HTML, in the style of a 1990s cable weather channel.
/// The canvas is 640x480 - the same canvas the CRT build is designed at - and
/// gets upscaled with nearest-neighbour later so the pixels stay chunky.
/// </summary>
public sealed class PageBuilder(SettingsStore settings)
{
    public const int Width = 640;
    public const int Height = 480;

    private const string Css = """
        *{margin:0;padding:0;box-sizing:border-box}
        body{width:640px;height:480px;overflow:hidden;background:#0a0a32;
         font-family:"VCR OSD Mono","Consolas","DejaVu Sans Mono",monospace;
         color:#f4f4f4;font-size:19px;line-height:1.25}
        .bg{position:absolute;inset:0;background:linear-gradient(180deg,#1a1a6e 0%,#0a0a32 100%)}
        .wrap{position:absolute;inset:0;display:flex;flex-direction:column}
        .head{background:#101038;color:#ffb000;border-bottom:4px solid #ffb000;
         padding:8px 14px;letter-spacing:3px;font-size:22px;display:flex;justify-content:space-between}
        .body{flex:1;padding:14px}
        .foot{background:#101038;color:#ffb000;border-top:4px solid #ffb000;
         padding:6px 14px;font-size:15px;letter-spacing:2px;
         white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
        .big{font-size:104px;color:#ffb000;line-height:1}
        .cond{font-size:26px;margin:6px 0 14px}
        .grid{display:grid;grid-template-columns:1fr 1fr;gap:6px 18px;font-size:19px}
        .k{color:#56d8ff}
        .row{display:grid;grid-template-columns:132px 1fr;gap:10px;padding:5px 0;
         border-bottom:2px solid #16163f}
        .row .t{color:#ffb000}
        .p{font-size:19px;line-height:1.45}
        .alert{background:#8b0000;border:4px solid #ffb000;padding:12px;margin-bottom:10px}
        .alert h2{color:#ffb000;font-size:22px;letter-spacing:2px;margin-bottom:6px}
        """;

    public IReadOnlyList<string> Build(WeatherData d)
    {
        var now = DateTime.Now;
        var clock = Clock(now);
        var where = $"{d.City}, {d.State}";
        var foot = $"{settings.Current.ChannelName}   {where}   " +
                   now.ToString("ddd MMM dd", CultureInfo.InvariantCulture);

        var pages = new List<string>
        {
            Page("CURRENT CONDITIONS", clock, Conditions(d), foot),
        };

        if (d.Forecast.Count > 0)
        {
            var p0 = d.Forecast[0];
            pages.Add(Page("FORECAST", clock,
                $"<div class=cond style='color:#ffb000'>{E(p0.Name.ToUpperInvariant())}</div>" +
                $"<div class=p>{E(p0.DetailedForecast)}</div>", foot));
        }

        if (d.Hourly.Count > 0)
        {
            var rows = new StringBuilder();
            foreach (var h in d.Hourly.Take(7))
            {
                var t = h.StartTime.ToString("hh tt", CultureInfo.InvariantCulture).TrimStart('0');
                rows.Append(CultureInfo.InvariantCulture,
                    $"<div class=row><div class=t>{t}</div>" +
                    $"<div>{h.Temperature}&deg;  {E(h.ShortForecast)}</div></div>");
            }

            pages.Add(Page("HOUR BY HOUR", clock, rows.ToString(), foot));
        }

        if (d.Forecast.Count > 1)
        {
            var rows = new StringBuilder();
            foreach (var p in d.Forecast.Skip(1).Take(5))
            {
                var name = p.Name.ToUpperInvariant();
                if (name.Length > 14)
                {
                    name = name[..14];
                }

                rows.Append(CultureInfo.InvariantCulture,
                    $"<div class=row><div class=t>{E(name)}</div>" +
                    $"<div>{p.Temperature}&deg;  {E(p.ShortForecast)}</div></div>");
            }

            pages.Add(Page("EXTENDED FORECAST", clock, rows.ToString(), foot));
        }

        if (d.Alerts.Count > 0)
        {
            var blocks = new StringBuilder();
            foreach (var a in d.Alerts.Take(2))
            {
                var headline = a.Headline.Length > 220 ? a.Headline[..220] : a.Headline;
                blocks.Append(CultureInfo.InvariantCulture,
                    $"<div class=alert><h2>{E(a.Event.ToUpperInvariant())}</h2>" +
                    $"<div class=p>{E(headline)}</div></div>");
            }

            pages.Add(Page("WEATHER BULLETIN", clock, blocks.ToString(), foot));
        }
        else
        {
            pages.Add(Page("WEATHER BULLETIN", clock,
                "<div class=cond style='color:#ffb000'>NO ACTIVE ADVISORIES</div>" +
                "<div class=p>Forecasts from the National Weather Service.<br><br>" +
                $"{E(where)}<br>Updated {clock}</div>", foot));
        }

        return pages;
    }

    private static string Conditions(WeatherData d)
    {
        var cur = d.Current;
        var temp = cur?.Temperature.Fahrenheit;
        var dew = cur?.Dewpoint.Fahrenheit;
        var hum = cur?.RelativeHumidity.Value;
        var wsp = cur?.WindSpeed.MilesPerHour;
        var dir = Compass(cur?.WindDirection.Value);
        var pres = cur?.Pressure.InchesOfMercury;

        var desc = cur?.Description ?? "";
        if (desc.Length == 0 && d.Forecast.Count > 0)
        {
            desc = d.Forecast[0].ShortForecast;   // better than an empty box
        }

        var wind = wsp == 0
            ? "CALM"
            : $"{(dir.Length > 0 ? dir : "--")} {(wsp is { } w ? w.ToString(CultureInfo.InvariantCulture) : "--")} MPH";

        return $"<div class=big>{Or(temp)}&deg;</div>" +
               $"<div class=cond>{E(desc.Length > 0 ? desc : "--")}</div>" +
               "<div class=grid>" +
               $"<div><span class=k>WIND</span> {wind}</div>" +
               $"<div><span class=k>HUMIDITY</span> {(hum is { } h ? Math.Round(h).ToString(CultureInfo.InvariantCulture) : "--")}%</div>" +
               $"<div><span class=k>DEW POINT</span> {Or(dew)}&deg;</div>" +
               $"<div><span class=k>PRESSURE</span> {(pres is { } p ? p.ToString("0.00", CultureInfo.InvariantCulture) : "--")} in</div>" +
               "</div>";
    }

    private static string Page(string title, string clock, string body, string footer) =>
        $"<meta charset='utf-8'><style>{Css}</style><div class=bg></div>" +
        $"<div class=wrap><div class=head><span>{title}</span><span>{clock}</span></div>" +
        $"<div class=body>{body}</div><div class=foot>{footer}</div></div>";

    private static string Clock(DateTime now) =>
        now.ToString("hh:mm tt", CultureInfo.InvariantCulture).TrimStart('0');

    private static string Or(int? v) =>
        v is { } x ? x.ToString(CultureInfo.InvariantCulture) : "--";

    private static string E(string s) => WebUtility.HtmlEncode(s);

    private static string Compass(double? degrees)
    {
        if (degrees is not { } d)
        {
            return "";
        }

        string[] points =
        [
            "N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
            "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW",
        ];

        return points[(int)Math.Floor(d / 22.5 + 0.5) % 16];
    }
}
