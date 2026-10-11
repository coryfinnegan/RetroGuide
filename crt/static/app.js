/* Retro Guide for a CRT.
 *
 * Same behaviour as the Android and Roku builds - discovery, saved server,
 * surfing with a banner, a timeline guide, themes, resume - plus
 * the two things a PC adds: a keypad, so channels can be typed, and an
 * adjustable overscan, because every tube crops a different amount.
 */
const ETV_PORT = 8409;
const SETTLE_MS = 400;     // let surfing settle before opening a stream
const BANNER_MS = 5000;
const GUIDE_REFRESH_MS = 30 * 60 * 1000;
/* A guide row is never shorter than this, in pixels of the 480 high layout:
   two lines of title plus the plate's edges. As many whole rows as fit. */
const MIN_ROW_PX = 46;
/* The guide is read only once the picture has been playing this long, so its
   download and parse never compete with the stream starting. */
const GUIDE_AFTER_PLAY_MS = 6000;
/* The guide shows ninety minutes from the current half hour, and can be paged
   forward a half hour at a time up to this many times. */
const WINDOW_MS = 90 * 60e3;
const MAX_PAGES = 16;
/* A block narrower than this cannot carry a readable title, so a run of them
   is drawn as one block - see blocks(). Pixels of the 640 wide
   layout; about six letters. */
const MIN_LABEL_PX = 60;
/* Long enough that a discontinuity between programmes - where the picture can
   legitimately pause for a few seconds - is never mistaken for a dead stream. */
const STALL_MS = 12000;

const $ = (id) => document.getElementById(id);
const store = {
  get host() { return localStorage.getItem("host") || ""; },
  set host(v) { localStorage.setItem("host", v); },
  get last() { return localStorage.getItem("last_channel") || ""; },
  set last(v) { localStorage.setItem("last_channel", v); },
  get sig() { return localStorage.getItem("lineup_sig") || ""; },
  set sig(v) { localStorage.setItem("lineup_sig", v); },
  get overscan() { return localStorage.getItem("overscan") || "5"; },
  set overscan(v) { localStorage.setItem("overscan", v); },
  get videoFit() { return localStorage.getItem("video_fit") || "cover"; },
  set videoFit(v) { localStorage.setItem("video_fit", v); },
  get screenFit() { return localStorage.getItem("screen_fit") || "letterbox"; },
  set screenFit(v) { localStorage.setItem("screen_fit", v); },
  get theme() { return localStorage.getItem("theme") || "classic"; },
  set theme(v) { localStorage.setItem("theme", v); },
};

let channels = [], guide = {}, current = 0, firstRow = 0;
/* The guide cursor: a row, and a moment on it. The block under that moment is
   the one highlighted, so moving up and down keeps the cursor in the same part
   of the schedule, as a cable box does. */
let selRow = 0, selTime = 0, paintedSlot = 0;
let mode = "setup";                 // setup | watch | guide | overscan | settings
let page = 0;                       // half hours the guide is paged forward
let settingsCursor = 0, settingsFrom = "watch";
let hls = null, tuneTimer = null, bannerTimer = null, typed = "", typeTimer = null;
let setupHosts = [], setupCursor = 0;
let lastTime = -1, lastAdvance = 0;

/* ------------------------------------------------------------ scaling */

/* The whole UI is laid out at 640x480 and scaled as one piece.
 *
 * LETTERBOX keeps it square by scaling both axes the same and centring what is
 * left over. Right for a monitor; wrong for the converter, because the bars it
 * leaves are real black pixels in the signal and the tube shows them.
 *
 * STRETCH scales the axes independently so every pixel of the framebuffer is
 * used, whatever shape it is. That is what a converter wants: it maps the whole
 * input raster onto the whole 4:3 picture, so filling a 16:9 raster with a 4:3
 * layout comes out 4:3 on the tube - the horizontal squash undoes the stretch.
 * Feed the converter its own preferred timing this way and nothing anywhere in
 * the chain has an aspect mismatch left to letterbox.
 */
function fit() {
  const stage = $("stage");
  if (store.screenFit === "stretch") {
    stage.style.transform = "scale(" + innerWidth / 640 + "," + innerHeight / 480 + ")";
    return;
  }
  const s = Math.min(innerWidth / 640, innerHeight / 480);
  const x = (innerWidth - 640 * s) / 2;
  const y = (innerHeight - 480 * s) / 2;
  stage.style.transform = "translate(" + x + "px," + y + "px) scale(" + s + ")";
}
addEventListener("resize", fit);

function cycleScreenFit() {
  store.screenFit = store.screenFit === "stretch" ? "letterbox" : "stretch";
  fit();
  toast("SCREEN " + store.screenFit.toUpperCase());
}

/* How the 16:9 stream sits in the 4:3 stage. Default is cover: nearly every
   programme here is 4:3 pillarboxed into a 1080p frame, and cropping the frame
   back to 4:3 takes off the pillars rather than any picture. */
const FITS = [
  { key: "cover",   label: "FILL",    cls: "" },
  { key: "contain", label: "FIT",     cls: "fit-contain" },
  { key: "fill",    label: "STRETCH", cls: "fit-fill" },
];

function applyVideoFit() {
  const f = FITS.find((x) => x.key === store.videoFit) || FITS[0];
  const stage = $("stage");
  FITS.forEach((x) => x.cls && stage.classList.remove(x.cls));
  if (f.cls) stage.classList.add(f.cls);
  return f;
}

function cycleVideoFit() {
  const i = FITS.findIndex((x) => x.key === store.videoFit);
  store.videoFit = FITS[(i + 1 + FITS.length) % FITS.length].key;
  toast("PICTURE " + applyVideoFit().label);
}

/* Guide looks. The key is what is saved; the class goes on #stage and
   themes.css does the rest. */
const THEMES = [
  { key: "classic",   label: "CLASSIC",   logo: ["RETRO", "GUIDE"] },
  { key: "retro",     label: "RETRO 90S", logo: ["RETRO", "GUIDE"] },
  { key: "halloween", label: "HALLOWEEN", logo: ["RETRO", "guide"] },
];

function applyTheme() {
  const t = THEMES.find((x) => x.key === store.theme) || THEMES[0];
  const stage = $("stage");
  THEMES.forEach((x) => stage.classList.remove("theme-" + x.key));
  stage.classList.add("theme-" + t.key);
  $("logoA").textContent = t.logo[0];
  $("logoB").textContent = t.logo[1];
  if (mode === "guide") paintGuide();
  return t;
}

function cycleTheme(step) {
  const i = Math.max(0, THEMES.findIndex((x) => x.key === store.theme));
  store.theme = THEMES[(i + step + THEMES.length) % THEMES.length].key;
  return applyTheme();
}

function applyOverscan() {
  document.documentElement.style.setProperty("--overscan", store.overscan + "%");
  $("overscanValue").textContent = store.overscan + "%";
}

/* -------------------------------------------------------------- server */

const api = (path) => "http://" + store.host + path;

async function loadLineup() {
  const m3u = await (await fetch(api("/iptv/channels.m3u"))).text();
  const list = [];
  let pending = null;
  for (const raw of m3u.split(/\r?\n/)) {
    const line = raw.trim();
    if (line.startsWith("#EXTINF")) {
      const attr = (k) => (line.match(new RegExp(k + '="([^"]*)"')) || [])[1] || "";
      pending = {
        id: attr("tvg-id"),
        number: attr("tvg-chno"),
        name: (line.split(",").pop() || "Channel").trim(),
      };
    } else if (line && !line.startsWith("#") && pending) {
      // A browser will not play raw MPEG-TS. mode=segmenter is the one form
      // ErsatzTV serves as an ordinary live playlist of short segments.
      pending.url = line.replace(/\.ts$/, ".m3u8?mode=segmenter");
      list.push(pending);
      pending = null;
    }
  }
  // parseFloat, not parseInt: "23.1" is a sub-channel of 23 and belongs
  // directly after it, not merged with it.
  const num = (c) => { const n = parseFloat(c.number); return isNaN(n) ? 1e9 : n; };
  list.sort((a, b) => num(a) - num(b));
  return list;
}

/* XMLTV times look like "20260823194500 -0500". */
function xmltvTime(s) {
  const m = /^(\d{4})(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})(?:\s*([+-])(\d{2})(\d{2}))?/.exec(s || "");
  if (!m) return 0;
  const utc = Date.UTC(+m[1], +m[2] - 1, +m[3], +m[4], +m[5], +m[6]);
  if (!m[7]) return utc;
  const off = (+m[8] * 60 + +m[9]) * 60000 * (m[7] === "-" ? -1 : 1);
  return utc - off;
}

/* ErsatzTV writes every programme the same way -
     <programme start=".." stop=".." channel=".."><title ..>..</title>
   - so one regular expression pass reads all ~19,000 of a 7 MB guide in a few
   milliseconds, where DOMParser built the whole document first. */
const PROGRAMME = /<programme start="([^"]+)" stop="([^"]+)" channel="([^"]+)"[^>]*>\s*<title[^>]*>([^<]*)</g;
const unescapeXml = (t) => t.replace(/&#(\d+);/g, (_, n) => String.fromCharCode(+n))
  .replace(/&quot;/g, '"').replace(/&apos;/g, "'").replace(/&lt;/g, "<").replace(/&gt;/g, ">")
  .replace(/&amp;/g, "&");

async function loadGuide() {
  const text = await (await fetch(api("/iptv/xmltv.xml"))).text();
  const now = Date.now(), from = now - 3 * 3600e3, to = now + 12 * 3600e3;
  const out = {};
  for (const m of text.matchAll(PROGRAMME)) {
    const start = xmltvTime(m[1]);
    const stop = xmltvTime(m[2]);
    if (!(stop > from && start < to)) continue;
    const title = unescapeXml(m[4]);
    if (!title) continue;
    (out[m[3]] = out[m[3]] || []).push({ start, stop, title });
  }
  // The file lists each channel's programmes in time order already.
  return out;
}

async function signature(list) {
  const text = list.map((c) => c.number + ":" + c.name).join("|");
  const buf = await crypto.subtle.digest("SHA-1", new TextEncoder().encode(text));
  return Array.from(new Uint8Array(buf)).map((b) => b.toString(16).padStart(2, "0")).join("");
}

/* ------------------------------------------------------------ playback */

/* A dead stream does not always announce itself.
 *
 * When ErsatzTV retires a session, the media playlist does not 404 - it answers
 * 302 to /iptv/channel/N.m3u8, which serves raw MPEG-TS. So hls.js is handed
 * binary where it expects a manifest, and what comes out of that is never
 * anything `fatal`; the error handler below is not called, no retry is
 * scheduled, and the picture simply freezes on the last decoded frame and stays
 * there. Found exactly that way: a channel left running for twenty minutes,
 * stopped dead, with the app perfectly happy.
 *
 * So do not wait to be told. Watch whether the clock is moving, which catches
 * this and every other way a stream can quietly stop.
 */
function watchdog() {
  if (mode === "setup" || !channels.length) return;
  const video = $("video");
  if (video.paused || video.ended) return;
  if (video.currentTime !== lastTime) {
    lastTime = video.currentTime;
    lastAdvance = Date.now();
    return;
  }
  if (Date.now() - lastAdvance < STALL_MS) return;
  toast("STREAM STALLED - REOPENING");
  lastAdvance = Date.now();          // hold off until this attempt has had a go
  startStream();
}

function startStream() {
  const ch = channels[current];
  if (!ch) return;
  const video = $("video");
  lastTime = -1;
  lastAdvance = Date.now();
  if (hls) { hls.destroy(); hls = null; }
  if (window.Hls && Hls.isSupported()) {
    hls = new Hls({ liveDurationInfinity: true, enableWorker: true });
    hls.loadSource(ch.url);
    hls.attachMedia(video);
    // A live channel has no end; if it stops, reopen rather than sit black.
    hls.on(Hls.Events.ERROR, (_, data) => {
      if (!data.fatal) return;
      toast("NO SIGNAL - RETRYING");
      setTimeout(startStream, 1500);
    });
  } else {
    video.src = ch.url;                       // Safari plays HLS itself
  }
  video.play().catch(() => {});
}

function tune(index, announce) {
  if (!channels.length) return;
  current = ((index % channels.length) + channels.length) % channels.length;
  const ch = channels[current];
  store.last = ch.number;
  if (announce !== false) showBanner();
  clearTimeout(tuneTimer);
  tuneTimer = setTimeout(startStream, SETTLE_MS);
}

function tuneToNumber(number) {
  const i = channels.findIndex((c) => c.number === number);
  if (i >= 0) tune(i);
  else banner(number, "NO SUCH CHANNEL", "");
}

/* ----------------------------------------------------------------- OSD */

function onAir(ch) {
  const now = Date.now();
  return (guide[ch.id] || []).find((p) => now >= p.start && now < p.stop);
}

const hhmm = (t) => new Date(t).toLocaleTimeString([], { hour: "numeric", minute: "2-digit" });
/* Guide-style times: "7:30p". */
const shortTime = (t) => {
  const d = new Date(t);
  const h = d.getHours() % 12 || 12;
  return h + ":" + String(d.getMinutes()).padStart(2, "0") + (d.getHours() < 12 ? "a" : "p");
};

function showBanner() {
  const ch = channels[current];
  if (!ch) return;
  const p = onAir(ch);
  banner(ch.number, ch.name, p ? shortTime(p.start) + " – " + shortTime(p.stop) + "   " + p.title : "");
}

function banner(number, name, sub) {
  $("osdNumber").textContent = number;
  $("osdName").textContent = name;
  $("osdSub").textContent = sub;
  $("osdClock").textContent = hhmm(Date.now());
  $("osd").classList.remove("hidden");
  clearTimeout(bannerTimer);
  bannerTimer = setTimeout(() => $("osd").classList.add("hidden"), BANNER_MS);
}

function toast(text) {
  const el = $("toast");
  el.textContent = text;
  el.classList.remove("hidden");
  setTimeout(() => el.classList.add("hidden"), 2500);
}

/* --------------------------------------------------------------- guide */

/* The window the guide shows: ninety minutes from the current half hour,
   paged forward by whole half hours. The labels sit on half hours because
   that is how a person reads a clock; the programmes do not, and are drawn
   where they actually fall. */
function guideWindow() {
  const half = 30 * 60e3;
  const a = guideWindow.slot() + page * half;
  return { a, b: a + WINDOW_MS, marks: [a, a + half, a + 2 * half] };
}
/* The start of the current local half hour - a timezone a quarter hour off
   UTC still reads :00 and :30. */
guideWindow.slot = () => {
  const half = 30 * 60e3;
  const off = new Date().getTimezoneOffset() * 60e3;
  return Math.floor((Date.now() - off) / half) * half + off;
};

/* Lay one channel's programmes onto [a, b) by their real times.
 *
 * This replaced three half hour columns that each showed whichever programme
 * overlapped them first. Almost nothing on ErsatzTV starts on a half hour -
 * 0.07% of the listings when this was written - so a channel running four
 * cartoons in a half hour showed one of them, and every edge was wrong. Here
 * every block starts and stops where its programme does.
 *
 * Programmes too short to label (a Looney Tunes short, a two minute weather
 * loop) are merged with their short neighbours into one block that starts
 * where the first begins and stops where the last ends. The block is labelled with the title they share if
 * they share one, otherwise the first of them - the one on soonest. A run is
 * closed once it is twice the label width, so an hour of cartoon shorts reads
 * as a few labelled blocks rather than one block naming only the first.
 */
function blocks(progs, a, b, minMs) {
  const out = [];
  let run = null;
  const flush = () => {
    if (!run) return;
    out.push({
      start: run.start, stop: run.stop, cont: run.cont, after: run.after,
      title: commonTitle(run.items.map((p) => gridTitle(p.title))),
    });
    run = null;
  };
  for (const p of progs) {
    if (p.stop <= a || p.start >= b) continue;
    const start = Math.max(p.start, a), stop = Math.min(p.stop, b);
    const short = stop - start < minMs;
    // A long programme or a gap in the listings ends a run; a gap stays empty.
    if (run && (!short || start - run.stop > 60e3 || run.stop - run.start >= 2 * minMs)) flush();
    if (!short) {
      out.push({ start, stop, cont: p.start < a, after: p.stop > b, title: gridTitle(p.title) });
      continue;
    }
    if (!run) run = { start, stop, cont: p.start < a, items: [] };
    run.items.push(p);
    run.stop = stop;
    run.after = p.stop > b;
  }
  flush();
  return out;
}

/* Leading episode numbering - "1943 - 24 - Merrie Melodies - ...", "109a- ..." -
   is how these files sort on disk, and in a block a few letters wide it is
   all that would show. The banner keeps the full title. */
const gridTitle = (t) => t.replace(/^(\s*\d+[a-z]?\s*[-.]\s*)+(?=\S)/i, "") || t;

/* "The Simpsons Tracy Ullman Shorts 40 - ..." and "...Shorts 04 - ..." read as
   "The Simpsons Tracy Ullman Shorts"; titles with nothing worth sharing fall
   back to the first. */
function commonTitle(titles) {
  if (titles.length === 1) return titles[0];
  let prefix = titles[0];
  for (const t of titles) while (!t.startsWith(prefix)) prefix = prefix.slice(0, -1);
  prefix = prefix.replace(/[\s\-:#0-9]+$/, "");
  return prefix.length >= 6 ? prefix : titles[0];
}

const escapeHtml = (s) => String(s).replace(/[&<>"]/g, (c) =>
  ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" })[c]);

const pct = (t, w) => ((t - w.a) / (w.b - w.a)) * 100 + "%";

/* The blocks drawn for a row. A channel with nothing in the listings gets one
   block naming the channel, so the cursor still has somewhere to sit. */
function blocksFor(row, w) {
  const ch = channels[row];
  const minMs = (MIN_LABEL_PX / Math.max(1, $("times").clientWidth)) * WINDOW_MS;
  const bs = blocks(guide[ch.id] || [], w.a, w.b, minMs);
  return bs.length ? bs : [{ start: w.a, stop: w.b, title: ch.name, cont: false, after: false }];
}

/* The block under t on a row, or the nearest one. */
function blockAt(row, t, w) {
  const bs = blocksFor(row, w);
  return bs.find((b) => t >= b.start && t < b.stop) ||
    bs.reduce((best, b) => {
      const d = Math.min(Math.abs(b.start - t), Math.abs(b.stop - t));
      return !best || d < best.d ? { b, d } : best;
    }, null).b;
}

const programmeAt = (ch, t) => (guide[ch.id] || []).find((p) => t >= p.start && t < p.stop);

/* Width of the widest word in the cell face, to keep titles from breaking
   inside a word - a block too narrow for one gets a single cut line. */
const measure = document.createElement("canvas").getContext("2d");
function widestWord(title) {
  measure.font = "bold 16px " + getComputedStyle($("stage")).getPropertyValue("--t-grid-font");
  return Math.max(...title.split(" ").map((w) => measure.measureText(w).width));
}

function visibleRows() {
  return Math.max(1, Math.floor($("rows").clientHeight / MIN_ROW_PX));
}

function keepCursorOnScreen() {
  const n = visibleRows();
  if (selRow < firstRow) firstRow = selRow;
  if (selRow >= firstRow + n) firstRow = selRow - n + 1;
  firstRow = Math.max(0, Math.min(firstRow, Math.max(0, channels.length - n)));
}

function paintGuide() {
  const w = guideWindow();
  paintedSlot = guideWindow.slot();
  const times = $("times");
  times.innerHTML = "";
  w.marks.forEach((t, i) => {
    if (i > 0) {
      const tick = document.createElement("span");
      tick.className = "tick";
      tick.style.left = pct(t, w);
      times.appendChild(tick);
    }
    const s = document.createElement("span");
    s.style.left = pct(t, w);
    s.textContent = shortTime(t);
    times.appendChild(s);
  });

  const d = new Date(w.a);
  $("guideDate").textContent = d.toLocaleDateString([], { weekday: "short" }) +
    " " + (d.getMonth() + 1) + "/" + d.getDate();
  $("guideClock").textContent = hhmm(Date.now()).replace(/\s/g, " ");

  keepCursorOnScreen();
  const n = visibleRows();
  const rows = $("rows");
  rows.innerHTML = "";
  rows.style.gridTemplateRows = "repeat(" + n + ", 1fr)";
  const lineW = Math.max(1, times.clientWidth);
  for (let i = 0; i < n && firstRow + i < channels.length; i++) {
    const idx = firstRow + i;
    const ch = channels[idx];
    const row = document.createElement("div");
    row.className = "row" + (idx === current ? " on" : "");
    row.innerHTML = '<div class="chan plate"><span class="num">' + escapeHtml(ch.number) +
                    '</span><span class="name">' + escapeHtml(ch.name) + "</span></div>";
    const line = document.createElement("div");
    line.className = "line";
    const sel = idx === selRow ? blockAt(idx, selTime, w) : null;
    for (const blk of blocksFor(idx, w)) {
      const cell = document.createElement("div");
      cell.className = "cell plate" + (sel && blk.start === sel.start ? " sel" : "");
      cell.style.left = pct(blk.start, w);
      cell.style.width = "calc(" + pct(blk.stop, w) + " - " + pct(blk.start, w) + ")";
      const px = ((blk.stop - blk.start) / (w.b - w.a)) * lineW;
      const room = px - 14 - (blk.cont ? 10 : 0) - (blk.after ? 10 : 0);
      const roomy = px > 60;
      cell.innerHTML = (blk.cont && roomy ? '<i class="arr l"></i>' : "") +
        '<span class="t' + (widestWord(blk.title) > room ? " one" : "") + '">' +
        escapeHtml(blk.title) + "</span>" +
        (blk.after && roomy ? '<i class="arr r"></i>' : "");
      line.appendChild(cell);
    }
    row.appendChild(line);
    rows.appendChild(row);
  }
  paintInfo();
}

/* Fill the info panel from the highlighted programme. */
function paintInfo() {
  const ch = channels[selRow];
  if (!ch) return;
  const blk = blockAt(selRow, selTime, guideWindow());
  const p = programmeAt(ch, Math.max(selTime, blk ? blk.start : selTime));
  $("infoTitle").textContent = p ? p.title : blk ? blk.title : ch.name;
  $("infoChannel").textContent = ch.number + "   " + ch.name.toUpperCase();
  $("infoTime").textContent = "";
  $("infoNext").textContent = "";
  if (!p) return;
  const now = Date.now();
  const span = shortTime(p.start) + " – " + shortTime(p.stop);
  $("infoTime").textContent = now >= p.start && now < p.stop ? span + "   ON NOW"
    : new Date(p.start).toLocaleDateString([], { weekday: "short" }) + "  " + span;
  const next = (guide[ch.id] || []).find((q) => q.start >= p.stop);
  if (next) $("infoNext").textContent = "NEXT  " + shortTime(next.start) + "   " + next.title;
}

/* Up and down: another channel at the same moment. Wraps at both ends. */
function moveRow(delta) {
  const n = channels.length;
  if (!n) return;
  selRow = (((selRow + delta) % n) + n) % n;
  paintGuide();
}

/* Left and right: the previous or next programme on this row, paging the grid
   half an hour when the cursor runs off either end. */
function moveTime(delta) {
  const w = guideWindow();
  const bs = blocksFor(selRow, w);
  const cur = blockAt(selRow, selTime, w);
  const i = bs.findIndex((b) => cur && b.start === cur.start);
  if (delta < 0) {
    if (i > 0) selTime = bs[i - 1].start;
    else if (page > 0) { page--; selTime = w.a - 1; }
    else return;
  } else {
    if (i >= 0 && i < bs.length - 1) selTime = bs[i + 1].start;
    else if (page < MAX_PAGES) { page++; selTime = Math.max(cur ? cur.stop : w.b, guideWindow().a); }
    else return;
  }
  // Paging back lands on the last block; keep the cursor time inside it.
  const b = blockAt(selRow, selTime, guideWindow());
  if (b && (selTime < b.start || selTime >= b.stop)) selTime = b.start;
  paintGuide();
}

function paintFooter() {
  const hint = (color, keys, text) =>
    '<span><i class="dot" style="background:' + color + '"></i>' + keys + " " + text + "</span>";
  $("guideFoot").innerHTML =
    hint("#4cd964", "ENTER", "WATCH") + hint("#ffd60a", "◀ ▶", "TIME") +
    hint("#0a84ff", "▲ ▼", "CHANNELS") + hint("#ff453a", "M", "SETTINGS") +
    hint("#b0b0b0", "ESC", "CLOSE");
}

function openGuide() {
  mode = "guide";
  selRow = current;
  selTime = Date.now();
  page = 0;
  firstRow = selRow - 1;
  $("stage").classList.add("guiding");
  $("guide").classList.remove("hidden");
  $("osd").classList.add("hidden");
  paintFooter();
  paintGuide();
}

function closeGuide() {
  mode = "watch";
  $("guide").classList.add("hidden");
  $("stage").classList.remove("guiding");
}

/* ------------------------------------------------------------ settings */

/* One menu for everything that used to be a bare hotkey, plus the theme. The
   hotkeys still work; this is for whoever does not know them. */
const SETTINGS = [
  { label: "THEME", value: () => applyTheme().label, change: (d) => cycleTheme(d) },
  { label: "PICTURE", value: () => (FITS.find((x) => x.key === store.videoFit) || FITS[0]).label,
    change: () => cycleVideoFit() },
  { label: "SCREEN", value: () => store.screenFit.toUpperCase(), change: () => cycleScreenFit() },
  { label: "OVERSCAN", value: () => store.overscan + "%",
    choose: () => { closeSettings(); mode = "overscan"; $("overscan").classList.remove("hidden"); } },
  { label: "CHANGE SERVER", value: () => "", choose: () => { closeSettings(); runSetup(); } },
  { label: "BACK TO TV", value: () => "", choose: () => closeSettings() },
];

function openSettings() {
  settingsFrom = mode === "guide" ? "guide" : "watch";
  mode = "settings";
  settingsCursor = 0;
  $("osd").classList.add("hidden");
  // The guide's picture frame is drawn just outside the safe area, so it
  // would show round the edge of the settings panel.
  $("guide").classList.add("hidden");
  $("settings").classList.remove("hidden");
  paintSettings();
}

function closeSettings() {
  $("settings").classList.add("hidden");
  mode = settingsFrom;
  if (mode === "guide") { $("guide").classList.remove("hidden"); paintGuide(); }
}

function paintSettings() {
  const list = $("settingsList");
  list.innerHTML = "";
  SETTINGS.forEach((s, i) => {
    const li = document.createElement("li");
    if (i === settingsCursor) li.className = "cursor";
    const v = s.value();
    li.innerHTML = "<span>" + s.label + "</span>" + (v
      ? '<span class="val">' + (s.change ? "◀ " + escapeHtml(v) + " ▶" : escapeHtml(v)) + "</span>"
      : "");
    list.appendChild(li);
  });
}

/* --------------------------------------------------------------- setup */

function paintSetup() {
  const list = $("setupList");
  list.innerHTML = "";
  const items = setupHosts.map((h) => h.host + "   " + h.version).concat(["TYPE AN ADDRESS"]);
  items.forEach((label, i) => {
    const li = document.createElement("li");
    li.textContent = label;
    if (i === setupCursor) li.className = "cursor";
    list.appendChild(li);
  });
}

async function runSetup() {
  mode = "setup";
  $("setup").classList.remove("hidden");
  $("guide").classList.add("hidden");
  paintSetup();
  try {
    const res = await (await fetch("/api/discover")).json();
    setupHosts = res.servers || [];
    $("setupStatus").textContent = setupHosts.length
      ? "FOUND " + setupHosts.length + " SERVER(S)"
      : "NONE FOUND - TYPE AN ADDRESS";
  } catch (e) {
    $("setupStatus").textContent = "SEARCH FAILED - TYPE AN ADDRESS";
  }
  paintSetup();
}

function chooseSetup() {
  if (setupCursor >= setupHosts.length) {
    const entered = prompt("ErsatzTV address", "192.168.1.100:" + ETV_PORT);
    if (!entered) return;
    store.host = entered.indexOf(":") >= 0 ? entered.trim() : entered.trim() + ":" + ETV_PORT;
  } else {
    store.host = setupHosts[setupCursor].host;
  }
  $("setup").classList.add("hidden");
  start();
}

/* ---------------------------------------------------------------- boot */

/* Channels first, picture next, guide last: the guide is a 7 MB file, and
   reading it before tuning held every start on a black screen. */
let guideRequested = false;
function loadGuideSoon() {
  if (guideRequested) return;
  guideRequested = true;
  setTimeout(async () => {
    guide = await loadGuide().catch(() => guide);
    if (mode === "guide") paintGuide();
    else if (!$("osd").classList.contains("hidden")) showBanner();
  }, GUIDE_AFTER_PLAY_MS);
}
$("video").addEventListener("playing", loadGuideSoon);

async function start() {
  try {
    channels = await loadLineup();
    if (!channels.length) throw new Error("no channels");
  } catch (e) {
    $("setupStatus").textContent = "CANNOT REACH " + store.host;
    return runSetup();
  }
  $("setup").classList.add("hidden");     // configured; setup is the boot screen
  const sig = await signature(channels);
  const changed = store.sig && store.sig !== sig;
  store.sig = sig;

  mode = "watch";
  const resume = channels.findIndex((c) => c.number === store.last);
  tune(resume >= 0 ? resume : 0);
  if (changed) toast("CHANNEL LIST UPDATED - " + channels.length + " CHANNELS");

  // Listings age out; the channel list is left alone until a restart so it
  // cannot change underneath someone watching.
  setInterval(async () => { guide = await loadGuide().catch(() => guide); }, GUIDE_REFRESH_MS);
  // The clock ticks; the blocks only move when the half hour rolls over.
  setInterval(() => {
    if (mode !== "guide") return;
    $("guideClock").textContent = hhmm(Date.now());
    if (guideWindow.slot() !== paintedSlot) {
      selTime = Math.max(selTime, guideWindow().a);
      paintGuide();
    }
  }, 30000);
  setInterval(watchdog, 2000);
}

/* -------------------------------------------------------------- remote */

addEventListener("keydown", (e) => {
  const k = e.key;

  if (mode === "overscan") {
    if (k === "ArrowUp") store.overscan = String(Math.max(0, +store.overscan - 1));
    else if (k === "ArrowDown") store.overscan = String(Math.min(15, +store.overscan + 1));
    else if (k === "Enter" || k === "Escape" || k.toLowerCase() === "o") {
      $("overscan").classList.add("hidden");
      mode = channels.length ? "watch" : "setup";
    }
    applyOverscan();
    return e.preventDefault();
  }

  if (mode === "settings") {
    const s = SETTINGS[settingsCursor];
    const n = SETTINGS.length;
    if (k === "ArrowUp") settingsCursor = (settingsCursor - 1 + n) % n;
    else if (k === "ArrowDown") settingsCursor = (settingsCursor + 1) % n;
    else if (k === "ArrowLeft" && s.change) s.change(-1);
    else if ((k === "ArrowRight" || k === "Enter") && s.change) s.change(1);
    else if (k === "Enter" && s.choose) { s.choose(); return e.preventDefault(); }
    else if (k === "Escape" || k === "Backspace" || k.toLowerCase() === "m") {
      closeSettings();
      return e.preventDefault();
    }
    paintSettings();
    return e.preventDefault();
  }

  if (mode === "setup") {
    const n = setupHosts.length + 1;
    if (k === "ArrowUp") setupCursor = (setupCursor - 1 + n) % n;
    else if (k === "ArrowDown") setupCursor = (setupCursor + 1) % n;
    else if (k === "Enter") return chooseSetup();
    else if (k.toLowerCase() === "m") { setupCursor = setupHosts.length; return chooseSetup(); }
    paintSetup();
    return e.preventDefault();
  }

  if (/^[0-9]$/.test(k)) {                    // a PC has a keypad; use it
    typed += k;
    banner(typed, "…", "");
    clearTimeout(typeTimer);
    typeTimer = setTimeout(() => { tuneToNumber(typed); typed = ""; }, 1500);
    return e.preventDefault();
  }

  if (mode === "guide") {
    if (k === "ArrowUp") moveRow(-1);
    else if (k === "ArrowDown") moveRow(1);
    else if (k === "PageUp") moveRow(-visibleRows());
    else if (k === "PageDown") moveRow(visibleRows());
    else if (k === "ArrowRight") moveTime(1);
    else if (k === "ArrowLeft") moveTime(-1);
    else if (k === "Enter") { if (selRow !== current) tune(selRow); closeGuide(); }
    else if (k.toLowerCase() === "m") openSettings();
    else if (k === "Escape" || k === "Backspace" || k.toLowerCase() === "g") closeGuide();
    return e.preventDefault();
  }

  if (k === "ArrowUp") tune(current - 1);
  else if (k === "ArrowDown") tune(current + 1);
  else if (k === "Enter" || k.toLowerCase() === "g") openGuide();
  else if (k.toLowerCase() === "i") showBanner();
  else if (k.toLowerCase() === "a") cycleVideoFit();
  else if (k.toLowerCase() === "s") cycleScreenFit();
  else if (k.toLowerCase() === "o") { mode = "overscan"; $("overscan").classList.remove("hidden"); }
  else if (k.toLowerCase() === "m" || k === "Escape") openSettings();
  else if (k.toLowerCase() === "t") toast("THEME " + cycleTheme(1).label);
  else if (k.toLowerCase() === "f") document.documentElement.requestFullscreen().catch(() => {});
  e.preventDefault();
});

/* ?host=192.168.1.200:8409 pins the server, which is handy in the shortcut
   that launches the kiosk so a fresh profile never stops at setup. */
const params = new URLSearchParams(location.search);
const pinned = params.get("host");
if (pinned) store.host = pinned;
/* ?screen=stretch is pinned by kiosk.py, which knows it is driving a converter
   and that a fresh browser profile has nothing saved. */
const pinnedScreen = params.get("screen");
if (pinnedScreen) store.screenFit = pinnedScreen;
/* ?theme=halloween, likewise. */
const pinnedTheme = params.get("theme");
if (THEMES.some((t) => t.key === pinnedTheme)) store.theme = pinnedTheme;

fit();
applyOverscan();
applyVideoFit();
applyTheme();
if (store.host) start(); else runSetup();
