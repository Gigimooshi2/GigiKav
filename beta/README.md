# Kav Beta

`beta/dev` is the development line. Every push to a `beta/**` branch builds a **beta build of Kav
itself** (same app id, same signing key, so places, widgets and settings carry over) and publishes it
as a `beta-2.0.N` prerelease. `main` builds the normal releases.

Settings → "Beta channel" (in every build) picks which Kav updates to. On: the newest beta build,
and in a beta build the experiments, switches and log below run. Off: they stop at once, and the
next normal release replaces the beta build (Android won't install an older build over a newer one,
so there's no instant way back). Keep `beta/dev` merged up with `main` so beta users aren't behind.
`Beta.on` = beta build && channel on; everything beta-only checks it, so the code can sit on `main`
inert.

## Switches

`beta/flags.json` on the beta branch holds the experiments' on/off switches. Kav Beta re-reads it
every 5 minutes (and from Settings → "Check switches"). Editing it doesn't trigger a build.
In code: `Beta.flag("name")`, always false in Kav.

| switch | what it does |
|---|---|
| `bus_status` (shipped: on for everyone; set false here to turn it off in the beta) | Tells a real bus (its own GPS) from Moovit's timetable placeholder parked at the first stop; shows Live / Live at first stop / Scheduled / Not seen yet / Cancelled on the wait step, the floating window and the map (placeholders drawn faded). |
| `dual_gps` (shipped: on for everyone, Google's library is in every build; set false here to turn it off in the beta) | Runs Kav's own location (Android GPS + network) and Google's fused location (Play services, Kav Beta only) side by side: both on the trip map (Kav amber, Google blue; main solid, other a ring) with a legend that shows accuracy and distance apart and switches the main source; Settings has the same switch. Every fix from both is logged (`fix` with `src`). If the main source is silent 15 s the other stands in (`gps_standin`). |
| `bus_estimate` (Kav Beta only) | Draws live buses on the trip map where they should be now: moved along their route by the age of their last sample (median ~40 s), at the pace Moovit's own prediction at your next stop implies, never past that stop, nothing for samples over 3 min. Every bus's next sample is checked against both the reported and the estimated position (`bus_est`: `rawErr` vs `estErr` in metres). |
| `route_variants` (Kav Beta only, nothing shown) | After each search, asks Moovit's planner again with each other value of its route preference field (0, 2, 3, 4; Kav normally sends 1, as Moovit's app does) and logs what comes back (`plan_variant`): routes the normal search didn't have and whether they arrive sooner (`newBestArrDelta`, seconds). |
| `get_off_nearest` (Kav Beta only) | On the ride card: "Get off closest to where you're going". Looks along the rest of the bus's stops (its trip pattern) for the one physically closest to the destination, times the bus there (Moovit's prediction, else scaled from your stop's), and the way on (Moovit's walking route, else a straight line +30%) walking (~4.5 km/h) and cycling (~15 km/h), next to the current plan. "Walk/Bike from there" replaces the rest of the trip with this bus to that stop and a walk or bike leg. Logs `nearest_find` and `nearest_switch`. |
| `bike_to_line` (Kav Beta only) | Search results also offer "cycle, then ride". Lines come from two places: Kav's own timetable (every line, trains included, stopping within 500 m of the destination, and its stop nearest you before that) and Moovit, for lines that timetable lacks such as the cities' weekend Na'im lines (the lines serving the 4 stops nearest the destination right now, each boarded at the regular stop on its route nearest you before them). Boarding 0.5–4 km away; the 5 nearest are planned with Moovit, leaving when you'd get there by bike (~15 km/h, straight line +30%, a minute to park); up to 3 added, none more than 15 min behind the best normal route. Logs `bike_line` (`src` timetable or moovit). |

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
