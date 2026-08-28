using System.Globalization;
using System.Net;
using System.Reflection;
using System.Text;

namespace WeatherChannel;

/// <summary>
/// Draws the pages, in the visual language of a WeatherSTAR 4000 — the box the
/// cable company kept in a closet that generated The Weather Channel's local
/// segments through the late 80s and 90s.
///
/// What makes it read as that machine and not as a generic dark UI:
/// - the Star4000 bitmap typeface, and a hard 3px black drop shadow on every
///   glyph, which is what the original's character generator produced;
/// - a purple-to-orange background wash with a gold header band that cuts away
///   on a diagonal at its right end;
/// - page titles in yellow, clock and date in white, both stacked on two lines;
/// - content sitting on a translucent periwinkle panel;
/// - the extended forecast as three bordered columns, each with a day name, an
///   icon, the conditions, and a Lo/Hi pair on a deeper blue block.
///
/// The canvas is 640x480, which is roughly what the original worked at, and is
/// the same canvas the CRT build is designed against.
/// </summary>
public sealed class PageBuilder(SettingsStore settings)
{
    public const int Width = 640;
    public const int Height = 480;

    private static string? _fontCss;

    public IReadOnlyList<string> Build(WeatherData d)
    {
        var now = DateTime.Now;
        var clock = now.ToString("h:mm:ss tt", CultureInfo.InvariantCulture).ToUpperInvariant();
        var date = now.ToString("ddd MMM d", CultureInfo.InvariantCulture).ToUpperInvariant();
        var where = $"{d.City}, {d.State}";

        var pages = new List<string>
        {
            Page("Current<br>Conditions", clock, date, Conditions(d), Footer(d), true),
        };

        if (d.Forecast.Count > 0)
        {
            pages.Add(Page("Local<br>Forecast", clock, date,
                $"<div class=narrative><span class=nlabel>{E(d.Forecast[0].Name.ToUpperInvariant())}...</span>" +
                $"{E(d.Forecast[0].DetailedForecast)}</div>", Footer(d), false));
        }

        if (d.Hourly.Count > 0)
        {
            pages.Add(Page("Hourly<br>Forecast", clock, date, Hourly(d), Footer(d), true));
        }

        var days = ExtendedDays(d.Forecast);
        if (days.Count > 0)
        {
            pages.Add(Page("Extended<br>Forecast", clock, date, Extended(days, where), Footer(d), false));
        }

        pages.Add(d.Alerts.Count > 0
            ? Page("Weather<br>Bulletin", clock, date, Alerts(d), Footer(d), true)
            : Page("Almanac", clock, date, Almanac(d, now), Footer(d), true));

        return pages;
    }

    // ------------------------------------------------------------------ pages

    private static string Conditions(WeatherData d)
    {
        var cur = d.Current;
        var temp = cur?.Temperature.Fahrenheit;
        var desc = cur?.Description ?? "";
        if (desc.Length == 0 && d.Forecast.Count > 0)
        {
            desc = d.Forecast[0].ShortForecast;
        }

        // A station can report a speed with no direction, which read as
        // "Wind: -- 6" until this stopped printing the placeholder.
        var wsp = cur?.WindSpeed.MilesPerHour;
        var dir = Compass(cur?.WindDirection.Value);
        var wind = wsp switch
        {
            null => "--",
            0 => "Calm",
            _ when dir.Length == 0 => $"{wsp} MPH",
            _ => $"{dir} {wsp}",
        };

        var daytime = d.Forecast.Count > 0 ? d.Forecast[0].IsDaytime : true;

        return "<div class=cc>" +
               "<div class=ccleft>" +
               $"<div class=bigtemp>{Or(temp)}&deg;</div>" +
               $"<div class=bigcond>{E(Shorten(desc))}</div>" +
               $"<div class=ccicon>{WeatherIcons.For(desc, daytime)}</div>" +
               $"<div class=ccwind>Wind: {E(wind)}</div>" +
               "</div>" +
               "<div class=ccright>" +
               $"<div class=place>{E(Title(d.City))}</div>" +
               Row("Humidity", cur?.RelativeHumidity.Value is { } h
                   ? Math.Round(h).ToString(CultureInfo.InvariantCulture) + "%" : "--") +
               Row("Dewpoint", cur?.Dewpoint.Fahrenheit is { } dp ? dp + "°" : "--") +
               Row("Pressure", cur?.Pressure.InchesOfMercury is { } p
                   ? p.ToString("0.00", CultureInfo.InvariantCulture) : "--") +
               Row("High", DayTemp(d.Forecast, wantDay: true)) +
               Row("Low", DayTemp(d.Forecast, wantDay: false)) +
               "</div></div>";

        static string Row(string k, string v) =>
            $"<div class=kv><span class=k>{k}:</span><span class=v>{E(v)}</span></div>";
    }

    private static string Hourly(WeatherData d)
    {
        var rows = new StringBuilder(
            "<div class=hours><div class='hrow hhead'><span class=htime>Time</span>" +
            "<span class=htemp>Temp</span><span class=hcond>Conditions</span></div>");

        foreach (var h in d.Hourly.Take(7))
        {
            var t = h.StartTime.ToString("h tt", CultureInfo.InvariantCulture).ToUpperInvariant();
            rows.Append(CultureInfo.InvariantCulture,
                $"<div class=hrow><span class=htime>{t}</span>" +
                $"<span class=htemp>{h.Temperature}&deg;</span>" +
                $"<span class=hcond>{E(Shorten(h.ShortForecast))}</span></div>");
        }

        return rows.Append("</div>").ToString();
    }

    /// <summary>
    /// Three columns, the way the original did it — day name, icon, conditions,
    /// then Lo and Hi side by side on a darker block.
    /// </summary>
    private static string Extended(IReadOnlyList<DayCard> days, string where)
    {
        var cards = new StringBuilder();
        foreach (var day in days.Take(3))
        {
            cards.Append(
                "<div class=card>" +
                $"<div class=cday>{E(day.Label)}</div>" +
                $"<div class=cicon>{WeatherIcons.For(day.ShortForecast, true)}</div>" +
                $"<div class=ccond>{E(Shorten(day.ShortForecast))}</div>" +
                "<div class=ctemps><div class=clabels><span>Lo</span><span>Hi</span></div>" +
                $"<div class=cvals><span>{Or(day.Low)}</span><span>{Or(day.High)}</span></div>" +
                "</div></div>");
        }

        return $"<div class=cards>{cards}</div>";
    }

    private static string Alerts(WeatherData d)
    {
        var blocks = new StringBuilder();
        foreach (var a in d.Alerts.Take(2))
        {
            var headline = a.Headline.Length > 200 ? a.Headline[..200] + "..." : a.Headline;
            blocks.Append(CultureInfo.InvariantCulture,
                $"<div class=alert><div class=aevent>{E(a.Event.ToUpperInvariant())}</div>" +
                $"<div class=atext>{E(headline)}</div></div>");
        }

        return $"<div class=alerts>{blocks}</div>";
    }

    private static string Almanac(WeatherData d, DateTime now) =>
        "<div class=almanac>" +
        "<div class=aline><span class=k>Advisories:</span><span class=v>None</span></div>" +
        $"<div class=aline><span class=k>Sky:</span><span class=v>{E(Shorten(d.Current?.Description ?? "--"))}</span></div>" +
        $"<div class=aline><span class=k>Updated:</span><span class=v>{now.ToString("h:mm tt", CultureInfo.InvariantCulture).ToUpperInvariant()}</span></div>" +
        "<div class=asource>Forecasts from the<br>National Weather Service</div>" +
        "</div>";

    private static string Footer(WeatherData d)
    {
        var desc = d.Current?.Description ?? "";
        if (desc.Length == 0 && d.Forecast.Count > 0)
        {
            desc = d.Forecast[0].ShortForecast;
        }

        return E(desc.ToUpperInvariant());
    }

    // ------------------------------------------------------------ the chrome

    private string Page(string title, string clock, string date,
                        string body, string footer, bool panel)
    {
        // The logo box carries this channel's own name rather than a copy of
        // the network's mark: the style is the point, and the real Weather
        // Channel idents already play in the breaks.
        var name = settings.Current.ChannelName.Split(' ', StringSplitOptions.RemoveEmptyEntries);
        var badge = string.Concat(name.Take(3).Select(w => $"<span>{E(Title(w))}</span>"));

        return "<meta charset='utf-8'><style>" + Css(settings.Current.SafeAreaPercent) + "</style>" +
               "<div class=safe>" +
               "<div class=header>" +
               $"<div class=logo>{badge}</div>" +
               $"<div class=title>{title}</div>" +
               $"<div class=when><span>{clock}</span><span>{date}</span></div>" +
               "</div>" +
               $"<div class='body{(panel ? " panel" : "")}'>{body}</div>" +
               $"<div class=footer>{footer}</div>" +
               "</div>";
    }

    // ------------------------------------------------------------------- data

    private sealed record DayCard(string Label, int? High, int? Low, string ShortForecast);

    /// <summary>
    /// NWS returns alternating day and night periods — "Tonight", "Saturday",
    /// "Saturday Night" — so a card is assembled from the pair that shares a
    /// date. Starting at index 1 skips the period that is already on the
    /// conditions and local forecast pages.
    /// </summary>
    private static IReadOnlyList<DayCard> ExtendedDays(IReadOnlyList<Period> forecast)
    {
        var cards = new List<DayCard>();
        var byDay = forecast.Skip(1)
            .GroupBy(p => p.StartTime.Date)
            .OrderBy(g => g.Key);

        foreach (var group in byDay)
        {
            var day = group.FirstOrDefault(p => p.IsDaytime);
            var night = group.FirstOrDefault(p => !p.IsDaytime);
            if (day is null && night is null)
            {
                continue;
            }

            var label = (day ?? night)!.StartTime
                .ToString("ddd", CultureInfo.InvariantCulture).ToUpperInvariant();

            cards.Add(new DayCard(
                label, day?.Temperature, night?.Temperature,
                (day ?? night)!.ShortForecast));

            if (cards.Count == 3)
            {
                break;
            }
        }

        return cards;
    }

    private static string DayTemp(IReadOnlyList<Period> forecast, bool wantDay)
    {
        var p = forecast.Take(3).FirstOrDefault(x => x.IsDaytime == wantDay);
        return p is null ? "--" : p.Temperature + "°";
    }

    // ------------------------------------------------------------------- CSS

    /// <summary>
    /// Built per render rather than cached, so editing SafeAreaPercent in the
    /// settings file takes effect on the next pass with no rebuild.
    /// </summary>
    private static string Css(double safeAreaPercent)
    {
        var safe = Math.Clamp(safeAreaPercent, 0, 20);
        var padX = Math.Round(Width * safe / 100.0);
        var padY = Math.Round(Height * safe / 100.0);

        return Fonts() + string.Create(CultureInfo.InvariantCulture, $$"""
            *{margin:0;padding:0;box-sizing:border-box}
            body{width:640px;height:480px;overflow:hidden;
             font-family:Star4000,Consolas,monospace;
             color:#fff;font-size:20px;line-height:1.15;
             /* The wash the 4000 sat its pages on: deep purple falling to a
                sunset orange, with a little vertical banding to stop the
                gradient looking like a modern flat fade. */
             background:
               linear-gradient(90deg,rgba(0,0,0,.22) 0%,rgba(0,0,0,0) 12%,
                 rgba(0,0,0,0) 88%,rgba(0,0,0,.22) 100%),
               linear-gradient(180deg,#2b1a5e 0%,#43307e 30%,#6a4f96 55%,
                 #b57f78 80%,#e0a05c 100%)}
            /* Every glyph gets the character generator's hard shadow. */
            body{text-shadow:3px 3px 0 rgba(0,0,0,.85)}
            /* Content is inset for CRT overscan; the background still bleeds to
               the edge, so what the tube eats is wash, not words. */
            .safe{position:absolute;left:{{padX}}px;top:{{padY}}px;
             width:{{Width - (padX * 2)}}px;height:{{Height - (padY * 2)}}px;
             display:flex;flex-direction:column}

            .header{display:flex;align-items:center;gap:10px;height:60px;flex:0 0 60px;
             position:relative;padding:0 6px}
            /* The gold band, cut away on a diagonal at its right end. */
            .header:before{content:'';position:absolute;inset:0;
             background:linear-gradient(180deg,#f0b969 0%,#d99542 55%,#b9752c 100%);
             clip-path:polygon(0 0,100% 0,calc(100% - 34px) 100%,0 100%);z-index:0}
            .header>*{position:relative;z-index:1}
            .logo{display:flex;flex-direction:column;justify-content:center;align-items:center;
             width:92px;flex:0 0 92px;height:48px;border-radius:9px;
             background:linear-gradient(180deg,#5b6fd8 0%,#3346ad 100%);
             border:3px solid #fff;box-shadow:2px 2px 0 rgba(0,0,0,.6);
             font-family:'Star4000 Extended',Star4000,Consolas,monospace;
             font-size:11px;line-height:1.12;letter-spacing:.5px;text-shadow:1px 1px 0 #000}
            .title{font-family:'Star4000 Extended',Star4000,Consolas,monospace;
             font-size:19px;line-height:1.42;color:#ffef4a;flex:1}
            .when{display:flex;flex-direction:column;align-items:flex-end;
             font-size:17px;line-height:1.38;padding-right:26px;white-space:nowrap}

            .body{flex:1;margin-top:8px;padding:10px 12px;overflow:hidden}
            .body.panel{background:rgba(150,163,214,.42);
             border:2px solid rgba(214,222,247,.5);border-radius:4px}

            .footer{flex:0 0 30px;height:30px;display:flex;align-items:center;
             padding:0 12px;margin-top:8px;font-size:16px;letter-spacing:1px;
             color:#12204a;text-shadow:none;
             background:linear-gradient(180deg,#cdd8f0 0%,#9fb0d8 100%);
             white-space:nowrap;overflow:hidden}

            /* ---- current conditions ---- */
            .cc{display:flex;height:100%;gap:10px}
            .ccleft{width:44%;display:flex;flex-direction:column;align-items:center}
            .ccright{flex:1;display:flex;flex-direction:column;justify-content:center;gap:9px}
            .bigtemp{font-family:'Star4000 Large',Star4000,Consolas,monospace;font-size:52px;
             line-height:1}
            .bigcond{font-size:19px;margin-top:2px;text-align:center}
            .ccicon{flex:1;display:flex;align-items:center;justify-content:center;min-height:0}
            .ccwind{font-size:18px}
            .place{color:#ffef4a;font-size:21px;margin-bottom:2px}
            .kv{display:flex;justify-content:space-between;gap:8px;font-size:19px}
            .k{color:#fff}
            .v{color:#fff}

            /* ---- hourly ---- */
            .hours{display:flex;flex-direction:column;height:100%}
            .hrow{display:flex;align-items:center;font-size:18px;padding:0 2px;
             flex:1 1 0;min-height:0;border-bottom:2px solid rgba(20,28,70,.28)}
            .hrow:last-child{border-bottom:none}
            .hhead{color:#ffef4a;border-bottom-width:3px}
            .htime{width:78px;flex:0 0 78px}
            .htemp{width:64px;flex:0 0 64px}
            .hcond{flex:1;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}

            /* ---- local forecast ---- */
            .narrative{font-size:19px;line-height:1.5}
            .nlabel{color:#ffef4a}

            /* ---- extended forecast ---- */
            .cards{display:flex;gap:10px;height:100%}
            .card{flex:1;display:flex;flex-direction:column;align-items:center;
             padding:6px 4px 0;border:3px solid #e8c98a;border-radius:3px;
             background:linear-gradient(180deg,#8d9ede 0%,#5a6bbe 55%,#3b4aa0 100%);
             box-shadow:2px 2px 0 rgba(0,0,0,.5)}
            .cday{color:#ffef4a;font-size:20px}
            .cicon{flex:1;display:flex;align-items:center;justify-content:center;
             min-height:0;width:100%;padding:2px 0}
            .ccond{font-size:15px;text-align:center;line-height:1.15;min-height:32px;
             padding:0 2px;display:flex;align-items:center;justify-content:center}
            .ctemps{width:100%;margin-top:2px}
            .clabels,.cvals{display:flex;justify-content:space-around}
            .clabels{color:#ffef4a;font-size:16px}
            .cvals{font-size:24px;background:rgba(16,26,86,.75);padding:1px 0}

            /* ---- bulletin / almanac ---- */
            .alerts{display:flex;flex-direction:column;justify-content:center;
             height:100%;gap:8px}
            .alert{border:3px solid #ffef4a;background:rgba(140,10,10,.72);
             padding:8px 10px}
            .aevent{color:#ffef4a;font-size:19px;margin-bottom:4px}
            .atext{font-size:17px;line-height:1.35}
            .almanac{display:flex;flex-direction:column;justify-content:center;
             height:100%;gap:11px}
            .aline{display:flex;justify-content:space-between;font-size:19px}
            .asource{color:#ffef4a;font-size:17px;line-height:1.4;margin-top:6px}

            .icon{width:100%;height:100%;max-height:130px;display:block}
            .ishadow{filter:drop-shadow(3px 3px 0 rgba(0,0,0,.6))}
            """);
    }

    /// <summary>
    /// The Star4000 faces, inlined as data URIs. They come from the MIT-licensed
    /// ws4kp project (netbymatt/ws4kp) and are recreations of the character
    /// generator's typeface; nothing else on this machine looks remotely right,
    /// and without them Chrome falls back to Consolas, which is what made the
    /// first version of these pages look like a terminal.
    /// </summary>
    private static string Fonts()
    {
        if (_fontCss is not null)
        {
            return _fontCss;
        }

        var css = new StringBuilder();
        foreach (var (family, resource) in new[]
                 {
                     ("Star4000", "Star4000.woff"),
                     ("Star4000 Large", "Star4000-Large.woff"),
                     ("Star4000 Extended", "Star4000-Extended.woff"),
                     ("Star4000 Small", "Star4000-Small.woff"),
                 })
        {
            var bytes = Resource(resource);
            if (bytes is null)
            {
                continue;
            }

            css.Append($"@font-face{{font-family:'{family}';src:url(data:font/woff;base64,")
               .Append(Convert.ToBase64String(bytes))
               .Append(") format('woff');font-display:block}");
        }

        return _fontCss = css.ToString();
    }

    private static byte[]? Resource(string fileName)
    {
        var asm = Assembly.GetExecutingAssembly();
        var name = asm.GetManifestResourceNames()
            .FirstOrDefault(n => n.EndsWith(fileName, StringComparison.OrdinalIgnoreCase));
        if (name is null)
        {
            return null;
        }

        using var stream = asm.GetManifestResourceStream(name)!;
        using var buffer = new MemoryStream();
        stream.CopyTo(buffer);
        return buffer.ToArray();
    }

    // --------------------------------------------------------------- helpers

    /// <summary>
    /// NWS writes "Chance Showers And Thunderstorms", which is three words too
    /// long for a forecast column. The original captions were terse.
    /// </summary>
    private static string Shorten(string s)
    {
        if (string.IsNullOrWhiteSpace(s))
        {
            return "--";
        }

        var t = s.Replace(" And ", " and ", StringComparison.OrdinalIgnoreCase)
                 .Replace("Slight Chance ", "", StringComparison.OrdinalIgnoreCase)
                 .Replace("Chance ", "", StringComparison.OrdinalIgnoreCase)
                 .Replace("Thunderstorms", "T'Storms", StringComparison.OrdinalIgnoreCase)
                 .Replace("Showers and T'Storms", "T'Storms", StringComparison.OrdinalIgnoreCase)
                 .Replace("Areas Of ", "", StringComparison.OrdinalIgnoreCase)
                 .Replace("Patchy ", "", StringComparison.OrdinalIgnoreCase)
                 .Trim();

        return t.Length > 0 ? char.ToUpperInvariant(t[0]) + t[1..] : "--";
    }

    private static string Title(string upper) =>
        CultureInfo.InvariantCulture.TextInfo.ToTitleCase(upper.ToLowerInvariant());

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
