# Kav Beta

`beta/dev` is the development line. Every push to a `beta/**` branch builds **Kav Beta**
(`uk.noammm.kav.beta`, installs beside Kav) and publishes it as a `beta-2.0.N` prerelease;
Kav Beta updates itself from those. `main` is prod: Kav only follows full releases.

## Switches

`beta/flags.json` on the beta branch holds the experiments' on/off switches. Kav Beta re-reads it
every 5 minutes (and from Settings → "Check switches"). Editing it doesn't trigger a build.
In code: `Beta.flag("name")`, always false in Kav.

| switch | what it does |
|---|---|
| `bus_status` | Tells a real bus (its own GPS) from Moovit's timetable placeholder parked at the first stop; shows Live / Live at first stop / Scheduled / Not seen yet / Cancelled on the wait step, the floating window and the map (placeholders drawn faded). |
| `dual_gps` | Runs Kav's own location (Android GPS + network) and Google's fused location (Play services, Kav Beta only) side by side: both on the trip map (Kav amber, Google blue; main solid, other a ring) with a legend that shows accuracy and distance apart and switches the main source; Settings has the same switch. Every fix from both is logged (`fix` with `src`). If the main source is silent 15 s the other stands in (`gps_standin`). |

## Log

Kav Beta logs everything it does to a 7-day file on the phone and streams it to a private ntfy.sh
topic made on the phone (Settings → Kav Beta; never commit it). Read with
`python3 tools/beta_log.py <topic> [--type live,fix,step,trip,api,logcat] [--since 12h]`.

- Stream: every 2 min on a trip, 10 min otherwise, slower if the day's 250-message ntfy budget runs
  low. Small batches inline (kept ~12 h), bigger ones as gzip attachments (kept ~3 h).
- Settings → "Upload last 3 h / 24 h" re-sends a stretch from the phone's file.
- Events: `app`, `app_open`/`app_close`, `screen`, `flags`, `plan`/`plan_refused`, `trip`, `step`,
  `here` (location, ≤ every 30 s), `fix` (trip GPS every 10 s: accuracy, provider), `live` (each bus
  on the trip: Moovit's raw record `raw` plus the state Kav made of it), `api` (every Moovit request:
  service, code, ms, bytes), `logcat` (Kav's whole Android log), `crash` (stack trace), `upload`.

## To prod

When an experiment is approved: make its behaviour the default (drop the `Beta.flag` check), merge
`beta/dev` into `main`.
