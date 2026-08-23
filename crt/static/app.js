/* Retro Guide for a CRT.
 *
 * Same behaviour as the Android and Roku builds - discovery, saved server,
 * surfing with a banner, a guide of three half hour columns, resume - plus
 * the two things a PC adds: a keypad, so channels can be typed, and an
 * adjustable overscan, because every tube crops a different amount.
 */
const ETV_PORT = 8409;
const SETTLE_MS = 400;     // let surfing settle before opening a stream
const BANNER_MS = 5000;
const GUIDE_REFRESH_MS = 30 * 60 * 1000;
const VISIBLE_ROWS = 6;

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
};

let channels = [], guide = {}, current = 0, cursor = 0, firstRow = 0;
let mode = "setup";                 // setup | watch | guide | overscan
let hls = null, tuneTimer = null, bannerTimer = null, typed = "", typeTimer = null;
let setupHosts = [], setupCursor = 0;

/* ------------------------------------------------------------ scaling */

/* The whole UI is laid out at 640x480 and scaled as one piece, so it keeps
   4:3 on a tube and letterboxes politely on anything else. */
function fit() {
  const s = Math.min(innerWidth / 640, innerHeight / 480);
  const x = (innerWidth - 640 * s) / 2;
  const y = (innerHeight - 480 * s) / 2;
  $("stage").style.transform = "translate(" + x + "px," + y + "px) scale(" + s + ")";
}
addEventListener("resize", fit);

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
  list.sort((a, b) => (parseInt(a.number, 10) || 1e9) - (parseInt(b.number, 10) || 1e9));
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

async function loadGuide() {
  const text = await (await fetch(api("/iptv/xmltv.xml"))).text();
  const doc = new DOMParser().parseFromString(text, "application/xml");
  const now = Date.now(), from = now - 3 * 3600e3, to = now + 12 * 3600e3;
  const out = {};
  for (const p of doc.getElementsByTagName("programme")) {
    const start = xmltvTime(p.getAttribute("start"));
    const stop = xmltvTime(p.getAttribute("stop"));
    if (!(stop > from && start < to)) continue;
    const ch = p.getAttribute("channel");
    const titleEl = p.getElementsByTagName("title")[0];
    const title = titleEl ? titleEl.textContent : "";
    if (!ch || !title) continue;
    (out[ch] = out[ch] || []).push({ start, stop, title });
  }
  for (const k in out) out[k].sort((a, b) => a.start - b.start);
  return out;
}

async function signature(list) {
  const text = list.map((c) => c.number + ":" + c.name).join("|");
  const buf = await crypto.subtle.digest("SHA-1", new TextEncoder().encode(text));
  return Array.from(new Uint8Array(buf)).map((b) => b.toString(16).padStart(2, "0")).join("");
}

/* ------------------------------------------------------------ playback */

function startStream() {
  const ch = channels[current];
  if (!ch) return;
  const video = $("video");
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

function showBanner() {
  const ch = channels[current];
  if (!ch) return;
  const p = onAir(ch);
  banner(ch.number, ch.name, p ? hhmm(p.start) + "-" + hhmm(p.stop) + "  " + p.title : "");
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

function slots() {
  const half = 30 * 60e3;
  const base = Math.floor(Date.now() / half) * half;
  return [base, base + half, base + 2 * half, base + 3 * half];
}

function inSlot(ch, a, b) {
  return (guide[ch.id] || []).find((p) => p.start < b && p.stop > a);
}

const escapeHtml = (s) => String(s).replace(/[&<>"]/g, (c) =>
  ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" })[c]);

function paintGuide() {
  const s = slots();
  $("slot0").textContent = hhmm(s[0]);
  $("slot1").textContent = hhmm(s[1]);
  $("slot2").textContent = hhmm(s[2]);

  if (cursor < firstRow) firstRow = cursor;
  if (cursor > firstRow + VISIBLE_ROWS - 1) firstRow = cursor - VISIBLE_ROWS + 1;
  firstRow = Math.max(0, Math.min(firstRow, Math.max(0, channels.length - VISIBLE_ROWS)));

  const rows = $("rows");
  rows.innerHTML = "";
  for (let i = 0; i < VISIBLE_ROWS && firstRow + i < channels.length; i++) {
    const idx = firstRow + i;
    const ch = channels[idx];
    const row = document.createElement("div");
    row.className = "row " + (idx % 2 ? "b" : "a") +
      (idx === current ? " on" : "") + (idx === cursor ? " cursor" : "");
    row.innerHTML = '<div class="chan"><span class="num">' + escapeHtml(ch.number) +
                    '</span><span class="name">' + escapeHtml(ch.name) + "</span></div>";

    // Merge columns holding the same programme, so a film reads as a film
    // instead of the same title three times over.
    const found = [0, 1, 2].map((c) => inSlot(ch, s[c], s[c + 1]));
    for (let c = 0; c < 3; ) {
      const p = found[c];
      let span = 1;
      while (c + span <= 2 && p && found[c + span] && found[c + span].start === p.start) span++;
      const cell = document.createElement("div");
      cell.className = "cell";
      cell.style.gridColumn = "span " + span;
      cell.textContent = p ? p.title : "—";
      row.appendChild(cell);
      c += span;
    }
    rows.appendChild(row);
  }
  const ch = channels[current];
  $("guideFoot").textContent = ch
    ? "NOW: " + ch.number + " " + ch.name + "    ▲▼ MOVE   ENTER WATCH   ESC BACK"
    : "";
}

function openGuide() {
  mode = "guide";
  cursor = current;
  paintGuide();
  $("guide").classList.remove("hidden");
  $("osd").classList.add("hidden");
}

function closeGuide() {
  mode = "watch";
  $("guide").classList.add("hidden");
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

async function start() {
  try {
    channels = await loadLineup();
    if (!channels.length) throw new Error("no channels");
    guide = await loadGuide().catch(() => ({}));
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
  setInterval(() => { if (mode === "guide") paintGuide(); }, 30000);
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
    if (k === "ArrowUp") { cursor = (cursor - 1 + channels.length) % channels.length; paintGuide(); }
    else if (k === "ArrowDown") { cursor = (cursor + 1) % channels.length; paintGuide(); }
    else if (k === "Enter") { tune(cursor); closeGuide(); }
    else if (k === "Escape" || k === "Backspace" || k.toLowerCase() === "g") closeGuide();
    return e.preventDefault();
  }

  if (k === "ArrowUp") tune(current - 1);
  else if (k === "ArrowDown") tune(current + 1);
  else if (k === "Enter" || k.toLowerCase() === "g") openGuide();
  else if (k.toLowerCase() === "i") showBanner();
  else if (k.toLowerCase() === "o") { mode = "overscan"; $("overscan").classList.remove("hidden"); }
  else if (k.toLowerCase() === "f") document.documentElement.requestFullscreen().catch(() => {});
  e.preventDefault();
});

/* ?host=192.168.1.200:8409 pins the server, which is handy in the shortcut
   that launches the kiosk so a fresh profile never stops at setup. */
const pinned = new URLSearchParams(location.search).get("host");
if (pinned) store.host = pinned;

fit();
applyOverscan();
if (store.host) start(); else runSetup();
