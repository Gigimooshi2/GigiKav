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

## Live log

Kav Beta batches events to a private ntfy.sh topic made on the phone (Settings → Kav Beta; never
commit it). Read with `python3 tools/beta_log.py <topic> [--type live,fix,step,trip] [--since 12h]`.
ntfy keeps messages ~12 hours. Events: `app`, `flags`, `trip`, `step`, `fix` (every 10 s on a trip:
position, accuracy, provider), `live` (Moovit's record for each bus on the trip + the state Kav made
of it), `sharing`.

## To prod

When an experiment is approved: make its behaviour the default (drop the `Beta.flag` check), merge
`beta/dev` into `main`.
