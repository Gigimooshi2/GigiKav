// Kav's Google Places relay (Cloudflare Worker). Holds the Places key as a secret so it never ships
// in the app, caps use per install and per day, and counts requests (never search text). A summary
// of the last two weeks is saved to KV (key "stats") every few minutes, readable with the
// Cloudflare API.
//
// Secrets: PLACES_KEY (Google Places API key). Vars: PER_INSTALL_DAY, GLOBAL_DAY.
// Bindings: USAGE (Durable Object, the counters), STATS (KV namespace, the summary).

const APP_HEADER = "kav-places-1";
const GOOGLE = "https://places.googleapis.com/v1/";
// The key is restricted to the Android app; requests are sent on its behalf.
const ANDROID_PKG = "uk.noammm.kav";
const ANDROID_CERT = "1703F30BE750EBBFAD109DCBAB9C16ACF9F159EA";

const json = (status, body) =>
  new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });

function today() { return new Date().toISOString().slice(0, 10); }

export default {
  async fetch(req, env) {
    const url = new URL(req.url);
    if (url.pathname === "/health") return json(200, { ok: true, configured: !!env.PLACES_KEY });
    if (req.headers.get("x-kav-app") !== APP_HEADER) return json(403, { error: "not a Kav client" });
    const install = (req.headers.get("x-kav-install") || "").slice(0, 64);
    if (!/^[A-Za-z0-9-]{8,64}$/.test(install)) return json(400, { error: "missing install id" });

    let kind, upstream;
    if (url.pathname === "/v1/autocomplete" && req.method === "POST") {
      kind = "autocomplete";
      const b = await req.json().catch(() => null);
      if (!b || typeof b.input !== "string" || !b.input.trim() || b.input.length > 200) return json(400, { error: "bad input" });
      // Only the fields Kav sends, so the relay can't be used for anything else.
      const body = {
        input: b.input, sessionToken: b.sessionToken, languageCode: b.languageCode,
        includedRegionCodes: ["il"], locationBias: b.locationBias, origin: b.origin,
      };
      upstream = { url: GOOGLE + "places:autocomplete", init: { method: "POST", body: JSON.stringify(body), headers: { "content-type": "application/json" } } };
    } else if (url.pathname.startsWith("/v1/place/") && req.method === "GET") {
      kind = "details";
      const id = decodeURIComponent(url.pathname.slice("/v1/place/".length));
      if (!/^[A-Za-z0-9_-]{10,300}$/.test(id)) return json(400, { error: "bad place id" });
      const q = new URLSearchParams();
      if (url.searchParams.get("sessionToken")) q.set("sessionToken", url.searchParams.get("sessionToken"));
      q.set("languageCode", url.searchParams.get("languageCode") === "he" ? "he" : "en");
      upstream = { url: GOOGLE + "places/" + encodeURIComponent(id) + "?" + q, init: { method: "GET", headers: { "x-goog-fieldmask": "location,formattedAddress" } } };
    } else return json(404, { error: "not found" });

    if (!env.PLACES_KEY) return json(503, { error: "relay not configured" });

    const usage = env.USAGE.get(env.USAGE.idFromName("usage"));
    const call = (path, body) => usage.fetch("https://usage/" + path, { method: "POST", body: JSON.stringify(body) });
    const verdict = await (await call("hit", {
      day: today(), install, kind, perInstall: +env.PER_INSTALL_DAY || 60, global: +env.GLOBAL_DAY || 300,
    })).json();
    if (!verdict.allowed) return json(429, { error: verdict.reason });

    const headers = new Headers(upstream.init.headers);
    headers.set("x-goog-api-key", env.PLACES_KEY);
    headers.set("x-android-package", ANDROID_PKG);
    headers.set("x-android-cert", ANDROID_CERT);
    const res = await fetch(upstream.url, { ...upstream.init, headers });
    if (!res.ok) await call("result", { day: today(), install, kind, outcome: "google_" + res.status });
    return new Response(res.body, { status: res.status, headers: { "content-type": "application/json" } });
  },
};

// One counter object for everything: requests per day, install, kind and outcome.
export class Usage {
  constructor(state, env) {
    this.env = env;
    this.sql = state.storage.sql;
    this.sql.exec(`CREATE TABLE IF NOT EXISTS hits (day TEXT, install TEXT, kind TEXT, outcome TEXT, n INTEGER,
      PRIMARY KEY (day, install, kind, outcome))`);
    this.snapAt = 0;
  }
  add(day, install, kind, outcome) {
    this.sql.exec(`INSERT INTO hits VALUES (?, ?, ?, ?, 1)
      ON CONFLICT(day, install, kind, outcome) DO UPDATE SET n = n + 1`, day, install, kind, outcome);
  }
  async fetch(req) {
    const path = new URL(req.url).pathname;
    const b = await req.json();
    if (path === "/result") { this.add(b.day, b.install, b.kind, b.outcome); return Response.json({}); }
    // Counted against the caps: every request that went to Google.
    const sent = "outcome NOT IN ('daily limit', 'install limit')";
    const total = this.sql.exec(`SELECT COALESCE(SUM(n),0) AS n FROM hits WHERE day = ? AND ${sent}`, b.day).one().n;
    const mine = this.sql.exec(`SELECT COALESCE(SUM(n),0) AS n FROM hits WHERE day = ? AND install = ? AND ${sent}`, b.day, b.install).one().n;
    let verdict;
    if (total >= b.global) verdict = { allowed: false, reason: "daily limit" };
    else if (mine >= b.perInstall) verdict = { allowed: false, reason: "install limit" };
    else verdict = { allowed: true };
    this.add(b.day, b.install, b.kind, verdict.allowed ? "sent" : verdict.reason);
    this.sql.exec("DELETE FROM hits WHERE day < ?", new Date(Date.now() - 31 * 86400e3).toISOString().slice(0, 10));
    if (Date.now() - this.snapAt > 5 * 60e3) { this.snapAt = Date.now(); await this.snapshot(); }
    return Response.json(verdict);
  }
  // Last 14 days for the KV summary: totals by kind and outcome, installs, the busiest installs.
  async snapshot() {
    const since = new Date(Date.now() - 14 * 86400e3).toISOString().slice(0, 10);
    const days = {};
    for (const r of this.sql.exec("SELECT day, kind, outcome, SUM(n) AS n, COUNT(DISTINCT install) AS installs FROM hits WHERE day >= ? GROUP BY day, kind, outcome", since)) {
      const d = days[r.day] ??= { requests: {}, installs: 0, top: [] };
      d.requests[r.kind + ":" + r.outcome] = r.n;
    }
    for (const r of this.sql.exec("SELECT day, COUNT(DISTINCT install) AS installs FROM hits WHERE day >= ? GROUP BY day", since)) days[r.day].installs = r.installs;
    for (const r of this.sql.exec(`SELECT day, install, SUM(n) AS n FROM hits WHERE day >= ? AND outcome = 'sent'
        GROUP BY day, install ORDER BY n DESC`, since)) {
      if (days[r.day].top.length < 5) days[r.day].top.push([r.install.slice(0, 8), r.n]);
    }
    await this.env.STATS.put("stats", JSON.stringify({ updated: new Date().toISOString(), days }));
  }
}
