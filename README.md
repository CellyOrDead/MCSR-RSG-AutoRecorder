# MCSR AutoRecorder

Java 17 desktop manager for MCSR RSG attempt metadata, approved-mod game-state detection, and OBS source recording. It does not install or modify Minecraft mods.

## Build and launch

Requires JDK 17 and Gradle 8 or newer.

```powershell
gradle :desktop:test :desktop:build
gradle :desktop:run
```

OBS WebSocket v5 is enabled from **Tools → WebSocket Server Settings** in OBS. The app remembers the last address and port, protects the password with Windows DPAPI for the current Windows account, and reconnects automatically on the next launch. **Auto preencher IP** checks addresses on this computer; for OBS on another computer, use **Colar QR do OBS**.

## OBS recording setup

The app records gameplay attempts and the SeedQueue wall through the optional [OBS Source Record plugin](https://obsproject.com/forum/resources/source-record.1285/). Source Record registers a `source-record` WebSocket vendor with source-level `record_start` and `record_stop` requests; the app creates a uniquely named `source_record_filter` for each output. OBS's regular recording is never started or stopped by the app. The OBS WebSocket request protocol used for these commands is defined by the [OBS WebSocket project](https://github.com/obsproject/obs-websocket/blob/master/src/requesthandler/RequestHandler_General.cpp); the plugin's request implementation is in [Source Record's source](https://github.com/exeldro/obs-source-record/blob/master/source-record.c).

1. Install a Source Record plugin build compatible with your OBS version and restart OBS.
2. In OBS, prepare the source that captures the Minecraft window. SeedQueue's wall is rendered inside that game window, so use this same source for gameplay and the SeedQueue session segments.
3. Connect OBS in AutoRecorder, then open **Configurar gravações**.
4. Keep SeedQueue wall recording enabled and enter the OBS source that captures the whole Minecraft window (not a wall-only crop). This mode makes one continuous video for the entire wall cycle; separate per-attempt clips are disabled while it is selected.
5. Choose an output folder and save. AutoRecorder stores the wall-cycle videos in its managed wall folder with unique filenames.
6. Set the filename template in Configurar gravação. The default is resets #0; #0 or {attempt} is replaced with the attempt counter when a cycle starts.
7. Keep AutoRecorder open before launching Minecraft. A clip starts at each detected wall opening and records the entire Minecraft window through world generation and gameplay. It finalizes when you return to the Title screen, or when the next wall opens first. Verify that OBS Source Record confirms the output started.

AutoRecorder configures its Source Record filters to use **Different Audio** on OBS audio track 1. Make sure the Minecraft audio source is assigned to track 1 in OBS's Advanced Audio Properties. This uses the same OBS mixer as the regular recording output and avoids Source Record's documented source-audio crackling issue. If normal OBS recording has sound but AutoRecorder clips crackle or are silent, check the track 1 assignment first. See the [Source Record known issues and audio options](https://obsproject.com/forum/resources/source-record.1285/).

Each clip captures the actual Minecraft window from one wall opening through the lock and gameplay, ending when you return to Title or the next wall opens. It is not a wall-only recording and does not split out individual worlds. AutoRecorder does not create a separate wall scene or add an overlay. State Output provides the wall and Title transitions, so no manual batch-completion button is needed.

For reliable negative Nether results, set SpeedRunIGT's **Generate Record File** option (`GENERATE_RECORD_FILE`) to **EVERYTHING**. Its 1.16.1 implementation writes a world `record.json` only when that option allows it; at a clean world exit, a valid record with a timeline but no `enter_nether` confirms the negative result. Missing or invalid records remain unknown. This app only reads the allowed `record.json`; it does not inspect Hermes `play.log` or SpeedRunIGT raw event logs. See the [SpeedRunIGT 1.16.1 record writer](https://github.com/RedLime/SpeedRunIGT/blob/1.16.1/src/main/java/com/redlimerl/speedrunigt/timer/InGameTimer.java) and [record generation setting](https://github.com/RedLime/SpeedRunIGT/blob/1.16.1/src/main/java/com/redlimerl/speedrunigt/option/SpeedRunOptions.java).

Wall-interval recording is enabled by default after setup, and an existing install is migrated once to enable it. The first output starts on the first detected wall opening. Returning to Title finalizes the current output; the next wall opening starts another. If the next wall opens without an observed Title transition, it finalizes the previous output and starts the next. In this mode there are no separate per-attempt video files. If OBS or the plugin is unavailable, the app reports the error and retries after reconnecting.

The wall clipper watches the already-installed State Output wpstateout.txt marker. It starts recording on a transition into the wall and ends when the marker changes to `title`, or when another wall opens. The clip includes the full Minecraft window, the lock, and gameplay up to that boundary. Per-attempt clips are available only when wall-interval recording is turned off. Keep the app open before launching Minecraft so it sees the state changes.

AutoRecorder sends a Windows system-tray notification whenever OBS confirms a recording has started or finished. The latest notification also remains visible in the dashboard's **Gravações** card. Start notices are sent only after the output file is verified; finish notices are sent only after OBS confirms the stop and the file becomes stable.

Each attempt is linked to the active wall clip in SQLite. When Title is reached or a new wall opens, AutoRecorder checks the attempts linked to the clip that just ended: if any reached the Nether, that clip is kept. The clip is eligible for quarantine only when every linked attempt has a confirmed end and none reached the Nether. Unknown results and important/excluded attempts preserve the video. A finalized clip of 10 seconds or less with no linked attempt is treated as an empty Title-only clip and is eligible for quarantine. If OBS forced recording to restart during a clip, all finalized segments for that clip receive the same decision. Enable automatic quarantine and turn off dry-run in Retention rules to move eligible files automatically; the default remains preservation.

## Minecraft integration

No companion mod is installed. The app discovers an already installed approved Hermes instance through `%LOCALAPPDATA%\MCSRHermes\instances\*.json`, reads `hermes/state.json` and the plain `hermes/world_logs` entry/exit events, reads the approved State Output `wpstateout.txt` marker for SeedQueue's wall, and reads the per-world SpeedRunIGT `speedrunigt/record.json` timeline for `enter_nether` and `enter_end`. It does not read Hermes `play.log` or SpeedRunIGT raw event logs. If the required event is unavailable, the attempt remains unknown and is preserved.

Force Port Mod only forces the Open to LAN port; it does not expose dimension changes. The app does not connect to the game server or treat a port as proof of Nether entry.

The integration currently targets the user's MCSR 1.16.1 profile with its already installed legal mods. It does not add a mod or change game files. See [MCSR rules A.8.14](https://mc.sr/rules/html/), [Hermes documentation](https://github.com/DuncanRuns/Hermes), and [SpeedRunIGT source](https://github.com/RedLime/SpeedRunIGT) for the relevant allowed state outputs.

## Current functionality and limits

- OBS WebSocket v5 connection, QR import, and local host discovery.
- Persistent OBS host/port, Windows DPAPI-protected password, and automatic reconnect on app startup.
- Hermes instance discovery, attempt start/end detection, and SpeedRunIGT Nether/End milestone persistence.
- Configurable OBS gameplay and continuous wall capture using independent Source Record filters.
- Confirmation of recording start, stop, and file finalization, plus SQLite metadata and recovery of known active outputs.
- Retention filter settings for Nether, End, and minimum attempt duration.
- Optional dry-run or automatic quarantine for finalized eligible gameplay files and wall intervals, with attempt-level important/excluded overrides and interval-wide Nether preservation.
- Attempt duration/file size, managed/free disk space, retention preview, and audit log views.
- Loopback JSON event endpoint on `127.0.0.1:38765` for integration testing. It is not a Minecraft mod.

Automatic quarantine is off by default. When enabled with dry run turned off, eligible finalized gameplay videos and wall clips move into the managed `quarantine` subfolder. A wall clip is eligible when all linked attempts have known results and none reached the Nether, or when it is a finalized clip of 10 seconds or less with no linked attempt. Unknown, unverified, active, important, excluded, and unfinished recordings are preserved. This build does not permanently delete quarantined files; review and remove them yourself in Explorer.

The app depends on a compatible OBS Source Record plugin for independent streams. If the vendor is missing, the app will not fall back to OBS's global Start/Stop Recording controls because that could interrupt a manually started recording or the wall output.

## Data and recovery

SQLite database: `%USERPROFILE%\.mcsr-autorecorder\manager.sqlite` (schema version 5). Settings, attempts, wall-interval membership, recording outputs and sizes, and audit events are persisted locally. The OBS password is stored separately as a Windows DPAPI-protected blob in the same folder and can be unlocked only by the same Windows user on that machine. On restart, saved filters, paths, and OBS connection details are restored; active outputs are recovered conservatively.

Optional per-attempt clips are written under `<chosen folder>\gameplay`; full-window wall-interval videos are written under `<chosen folder>\wall`; eligible files may move to `<chosen folder>\quarantine`. Configure the directory before enabling automatic outputs. Keep enough free disk space for the selected video settings.

## Troubleshooting

- **OBS connected, but recording start fails:** Install/enable the Source Record plugin, restart OBS, and check the exact source name. A WebSocket connection alone does not provide independent source recording.
- **Gameplay or wall source not found:** Copy the source's exact name from the OBS Sources list into **Recording setup**.
- **SeedQueue output does not start or rotate:** Check that State Output is installed and its `wpstateout.txt` reports the wall state, then check the Minecraft source and output folder in **Configurar gravação**, OBS connection, and Source Record availability.
- **Output not finalized:** Keep OBS open, reconnect the OBS WebSocket, and check AutoRecorder's audit/history. The app preserves uncertain files and never treats a failed request as a confirmed stop.
- **Unknown Nether state:** Confirm SpeedRunIGT is already enabled and that its `record.json` contains the expected timeline event. The app does not add a mod or infer dimension from Force Port.

## Tests

The automated suite includes the attempt state machine, retention policy, persistence, quarantine path safeguards, managed storage summary, local IP/QR parsing, Hermes event reader, and simulated OBS Source Record start/stop flows including wall/gameplay separation. It does not replace a live test with the user's OBS setup and plugin.
