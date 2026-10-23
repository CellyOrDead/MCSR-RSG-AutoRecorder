# Architecture and implementation status

## Integration decisions

- **Minecraft state:** `HermesMonitor` discovers live Hermes instances through the global PID metadata folder, then reads `hermes/state.json` and the plain `hermes/world_logs` transition log. The active world and `entering`/`leave` events define attempt boundaries. The monitor never reads the per-world ciphered `hermes/play.log`, and it never adds a game mod.
- **Dimension:** The app reads the active world's `speedrunigt/record.json` under the configured game directory's `saves` folder and treats the `enter_nether` and `enter_end` timeline entries as persistent milestones. This is an allowed record output from the already approved SpeedRunIGT mod; no extra mod or game change is required. If record generation is disabled, the file is missing/partial, or the transition was not recorded, the milestone remains unknown and the attempt is preserved for review. Paths are normalized and constrained beneath `saves`.
- **Force Port:** This allowed mod fixes the Open to LAN port (default `25565`) and increases its player limit. It has no documented event or dimension API. Connecting to the Minecraft server would not be a valid substitute for event telemetry, so the recorder does not probe or join that port.
- **OBS:** The app connects with OBS WebSocket 5. The standard OBS recording output is not two independent source outputs. SeedQueue wall and gameplay files require an independently configured source-recording output/plugin or another separate output. The app does not stop OBS's main output.

MCSR rules permit use of Hermes `state.json` or equivalent instance-state output as a performant replacement for checks available in unmodified Minecraft, and describe continuous non-advantage uses such as OBS adjustments as permitted subject to verifier discretion. The monitor uses state only for recording boundaries. See [current rules A.8.14](https://mc.sr/rules/html/) and [Hermes documentation](https://github.com/DuncanRuns/Hermes).

## Attempt and retention model

`AttemptMachine` stores attempt state separately from recording lifecycle. Nether is a monotonic milestone. A clean exit without a known dimension is not proof that the attempt failed. Attempts with unknown results, active recordings, unverified files, or manual exclusions are preserved. Retention keep filters use OR semantics: Nether reached, End reached, or minimum elapsed duration. No selected filters means preserve all.

SQLite schema version 2 adds the End milestone and `app_settings`; startup migrates earlier databases by adding `end_reached`. Attempts and filter preferences persist. The current UI does not create recording metadata because OBS file ownership and output verification are not implemented.

## Implemented

1. JavaFX desktop app, SQLite attempt history, retention settings, OBS WebSocket connection and QR import.
2. Loopback line-delimited JSON event receiver for manual/synthetic integration testing.
3. Read-only Hermes instance discovery and world transition monitoring plus SpeedRunIGT record timeline milestone recovery. Rapid resets are read from Hermes entry/exit events; on app restart, old log entries are skipped and the current world is reconciled from `state.json`.
4. Same-computer OBS WebSocket host discovery by checking the configured port on local addresses only; remote OBS endpoints remain configured with the QR import.
5. State-machine tests, SQLite settings persistence tests, Hermes/SpeedRunIGT record parser and transition tests, and local OBS host discovery tests.

## Remaining before automatic recording/deletion

1. Add OBS source-output ownership, start/stop/status checks, and verified resulting file paths without touching the wall output.
2. Add stable-file checks, managed-directory validation, quarantine, recovery window, dry-run preview, disk limits, and deletion audit.
3. Add persistent wall session metadata, a full dashboard, automatic history refresh, and end-to-end tests with a mocked OBS output and temporary filesystem.
