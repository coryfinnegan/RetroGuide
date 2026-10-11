# Guide themes

Every build has three looks for the guide, picked in its settings and
remembered:

The layout is taken from two reference photos the owner supplied - a Y2K-era
cable box guide (Y2Kable) and a DirecTV guide in its Halloween dress - not
from a description. Every theme shares it: an info panel (logo, clock, and the
highlighted programme's title, times, channel and what's next) beside the
picture across the top; a dense grid of bevelled, gradient-filled blocks below
with **one highlighted cell**, not a highlighted row; channel boxes with the
number over the name; times written "8:30p"; and a key-hint bar with coloured
dots along the bottom. Text is a condensed bold sans, as on those boxes.

The surfaces are period-accurate to the **Prevue Channel grid of 1993-99**,
checked against a broadcast capture (Wikipedia, `File:PrevueChannel.png`):
flat fills, never gradients, each box an embossed plate - a near-white line
along the top and left, a dark one along the bottom and right; no rounded
corners. Channel boxes carry the number in white over the call sign in a
second colour, titles wrap to two lines, and a triangle marks a programme
that runs off either edge of the window. Gradients and rounded boxes are
2000s (TV Guide Channel 2003 onward) and were rejected as too modern.

| Theme | Look |
|---|---|
| **Classic** | Indigo plates, white numbers over amber call signs, amber highlight |
| **Retro 90s** | The Prevue Channel itself, colours sampled from the capture: navy plates (#323278), white titles, white numbers over yellow call signs, yellow times on a lighter blue bar (#5A6EC8), yellow highlight; WeatherSTAR typeface on logo and clock |
| **Halloween** | Purple plates, orange numbers over green call signs, a slime-green highlight, jack-o'-lantern logo, moon and bats, pumpkins and a web on the hint bar |

All three builds have this layout: Android (`GuideGridView` in `Timeline.kt`),
Roku (`paintGrid` in `MainScene.brs`) and the CRT page (`paintGuide` in
`app.js`, plates in `style.css`). The palettes are the same three in each.

Where to change it:

- **CRT** — **M** (or Esc) opens SETTINGS; **T** cycles the theme directly;
  `?theme=retro` pins it in the URL
- **Android** — **hold OK** opens SETTINGS (the Google TV remote has no menu
  button; Menu / Red still work on remotes that do); THEME is the first row,
  Left/Right changes it
- **Roku** — **hold OK** opens SETTINGS (`*` opens the TV's own panel on a Roku
  TV); THEME is the first row, Left/Right changes it

## Artwork and fonts

None of the artwork is generated. It is sourced, under licences that allow it
to ship here, and only rasterised (SVG to PNG) for the TV builds.

| File | Source | Licence |
|---|---|---|
| `happy-halloween.svg` / `theme_happy_halloween.png` | [Happy Halloween](https://commons.wikimedia.org/wiki/File:Happy-Halloween--Arvin61r58.svg) by Arvin61r58, via openclipart and Wikimedia Commons | CC0 |
| `halloween-icon.svg` | [Halloween Icon](https://commons.wikimedia.org/wiki/File:Halloween_Icon.svg) by nicubunu, via Wikimedia Commons | CC0 |
| `pumpkin`, `bat`, `ghost`, `web`, `spider`, `moon`, `tv` | [Twemoji](https://github.com/jdecked/twemoji) 15.1.0 (U+1F383, 1F987, 1F47B, 1F578, 1F577, 1F315, 1F4FA) | [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/), © Twitter, Inc and other contributors |
| `Star4000*.woff` / `star4000.ttf` | [ws4kp](https://github.com/netbymatt/ws4kp), shared with `weather/` | MIT |
| `Creepster-Regular.ttf` (CRT only, no longer used by a theme) | [Creepster](https://fonts.google.com/specimen/Creepster) by Font Diner, via google/fonts | SIL OFL 1.1 — `crt/static/themes/fonts/OFL.txt` |

Where they live:

- CRT: `crt/static/themes/`
- Roku: `roku/images/themes/`, `roku/fonts/`
- Android: `android/app/src/main/res/drawable-nodpi/theme_*.png`, `res/font/`

The TV builds can't load WOFF, so `star4000.ttf` is the same font unwrapped
from its WOFF container — the tables are unchanged.

## Adding a theme

Each build keeps its themes in one place — `crt/static/themes.css`,
`GuideTheme.kt`, and `themeFor()` in `roku/components/MainScene.brs` — as a
palette plus a list of art to place. Add it to all three, and keep to the CRT
rules in `crt/static/style.css`: nothing thinner than 2px, no small type, flat
colour.
