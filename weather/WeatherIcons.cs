namespace WeatherChannel;

/// <summary>
/// The little sun-and-cloud glyphs, as inline SVG.
///
/// Drawn rather than sourced: the originals are 8-bit sprites, and anything
/// photographic looks wrong beside a chunky bitmap font. These keep the same
/// vocabulary — a spiked sun, a lumpy cloud built from overlapping circles,
/// a hard black outline and a hard drop shadow, no gradients on the shapes
/// themselves.
/// </summary>
public static class WeatherIcons
{
    private const string Sun = "#ffd83d";
    private const string SunEdge = "#c98f00";
    private const string Cloud = "#e2e2ea";
    private const string CloudDark = "#b6b6c6";
    private const string CloudEdge = "#5a5a72";
    private const string Rain = "#7ec8f5";
    private const string Bolt = "#fff27a";
    private const string Snow = "#ffffff";

    /// <summary>
    /// Pick a glyph from an NWS shortForecast string. The API has no icon code
    /// worth trusting — the legacy `icon` URLs were deprecated — so this reads
    /// the words, most specific first: "chance of thunderstorms" has to match
    /// the storm before it matches the cloud.
    /// </summary>
    public static string For(string shortForecast, bool daytime)
    {
        var s = (shortForecast ?? "").ToLowerInvariant();

        if (s.Contains("thunder") || s.Contains("t-storm")) return Thunderstorm();
        if (s.Contains("snow") || s.Contains("flurr") || s.Contains("wintry") ||
            s.Contains("sleet") || s.Contains("ice")) return Snowy();
        if (s.Contains("freezing")) return Snowy();
        if (s.Contains("rain") || s.Contains("shower") || s.Contains("drizzle")) return Rainy();
        if (s.Contains("fog") || s.Contains("haze") || s.Contains("smoke")) return Foggy();
        if (s.Contains("wind") || s.Contains("breezy")) return Windy();
        if (s.Contains("mostly cloudy") || s.Contains("overcast")) return Cloudy();
        if (s.Contains("partly") || s.Contains("mostly sunny") || s.Contains("mostly clear") ||
            s.Contains("partly cloudy") || s.Contains("few clouds")) return PartlyCloudy(daytime);
        if (s.Contains("cloud")) return Cloudy();
        return daytime ? Sunny() : ClearNight();
    }

    /// <summary>Wraps a body in the shared 100x100 viewBox and drop shadow.</summary>
    private static string Svg(string body) =>
        "<svg class=icon viewBox='0 0 100 100' xmlns='http://www.w3.org/2000/svg'>" +
        "<g class=ishadow>" + body + "</g></svg>";

    private static string SunDisc(double cx, double cy, double r)
    {
        var spikes = "";
        for (var i = 0; i < 12; i++)
        {
            var a = i * Math.PI / 6;
            var (x1, y1) = (cx + Math.Cos(a) * r * 0.95, cy + Math.Sin(a) * r * 0.95);
            var (x2, y2) = (cx + Math.Cos(a) * r * 1.55, cy + Math.Sin(a) * r * 1.55);
            var (x3, y3) = (cx + Math.Cos(a + 0.26) * r * 0.95, cy + Math.Sin(a + 0.26) * r * 0.95);
            spikes += Fmt(
                "<polygon points='{0:0.#},{1:0.#} {2:0.#},{3:0.#} {4:0.#},{5:0.#}' " +
                "fill='" + Sun + "' stroke='" + SunEdge + "' stroke-width='2'/>",
                x1, y1, x2, y2, x3, y3);
        }

        return spikes + Fmt(
            "<circle cx='{0}' cy='{1}' r='{2}' fill='" + Sun + "' stroke='" + SunEdge +
            "' stroke-width='3'/>", cx, cy, r);
    }

    /// <summary>
    /// A cloud is four overlapping shapes, and stroking each one separately
    /// would draw the seams between them. Drawing the whole group twice — a
    /// fat dark pass underneath, the fill pass on top — outlines the silhouette
    /// and nothing else.
    /// </summary>
    private static string CloudBody(double x, double y, double scale, string fill)
    {
        const string shapes =
            "<circle cx='26' cy='26' r='17'/><circle cx='50' cy='18' r='22'/>" +
            "<circle cx='74' cy='28' r='18'/><rect x='9' y='26' width='82' height='22' rx='11'/>";

        return Fmt(
            "<g transform='translate({0},{1}) scale({2:0.###})'>" +
            "<g fill='" + CloudEdge + "' stroke='" + CloudEdge +
            "' stroke-width='7' stroke-linejoin='round'>" + shapes + "</g>" +
            "<g fill='" + "{3}" + "'>" + shapes + "</g></g>",
            x, y, scale, fill);
    }

    private static string Sunny() => Svg(SunDisc(50, 50, 24));

    private static string ClearNight() => Svg(
        "<path d='M66 20 A32 32 0 1 0 78 62 A26 26 0 0 1 66 20 Z' fill='" + Sun +
        "' stroke='" + SunEdge + "' stroke-width='3'/>");

    private static string PartlyCloudy(bool daytime) => Svg(
        (daytime
            ? SunDisc(36, 33, 17)
            : "<path d='M44 14 A22 22 0 1 0 52 43 A18 18 0 0 1 44 14 Z' fill='" + Sun +
              "' stroke='" + SunEdge + "' stroke-width='3'/>") +
        CloudBody(12, 45, 0.82, Cloud));

    private static string Cloudy() => Svg(
        CloudBody(6, 18, 0.72, CloudDark) + CloudBody(4, 40, 0.92, Cloud));

    private static string Rainy() => Svg(
        CloudBody(4, 22, 0.92, Cloud) +
        Drops(new[] { 26.0, 44, 62, 80 }, 76, 94, Rain));

    private static string Thunderstorm() => Svg(
        CloudBody(4, 18, 0.92, Cloud) +
        Drops(new[] { 24.0, 76 }, 72, 92, Rain) +
        "<polygon points='54,64 40,90 51,90 44,100 66,74 54,74 62,64' fill='" + Bolt +
        "' stroke='#a07a00' stroke-width='2.5'/>");

    private static string Snowy() => Svg(
        CloudBody(4, 22, 0.92, Cloud) +
        string.Concat(new[] { 26.0, 50, 74 }.Select(x => Fmt(
            "<g stroke='" + Snow + "' stroke-width='4' stroke-linecap='round'>" +
            "<line x1='{0}' y1='76' x2='{0}' y2='94'/>" +
            "<line x1='{1:0.#}' y1='80.5' x2='{2:0.#}' y2='89.5'/>" +
            "<line x1='{1:0.#}' y1='89.5' x2='{2:0.#}' y2='80.5'/></g>",
            x, x - 8, x + 8))));

    private static string Foggy() => Svg(string.Concat(
        new[] { 26.0, 44, 62, 80 }.Select(y => Fmt(
            "<path d='M12 {0} q 11 -8 22 0 t 22 0 t 22 0' fill='none' stroke='" + Cloud +
            "' stroke-width='7' stroke-linecap='round'/>", y))));

    private static string Windy() => Svg(
        CloudBody(4, 20, 0.85, Cloud) +
        "<path d='M16 74 h50 a10 10 0 1 0 -10 -10' fill='none' stroke='" + Cloud +
        "' stroke-width='7' stroke-linecap='round'/>" +
        "<path d='M22 92 h34 a9 9 0 1 1 -9 9' fill='none' stroke='" + Cloud +
        "' stroke-width='7' stroke-linecap='round'/>");

    private static string Drops(double[] xs, double y1, double y2, string colour) =>
        string.Concat(xs.Select(x => Fmt(
            "<line x1='{0}' y1='{1}' x2='{2:0.#}' y2='{3}' stroke='" + colour +
            "' stroke-width='6' stroke-linecap='round'/>", x, y1, x - 7, y2)));

    private static string Fmt(string format, params object[] args) =>
        string.Format(System.Globalization.CultureInfo.InvariantCulture, format, args);
}
