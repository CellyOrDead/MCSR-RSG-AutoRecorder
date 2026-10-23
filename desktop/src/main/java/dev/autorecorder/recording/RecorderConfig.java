package dev.autorecorder.recording;

import java.nio.file.Path;

/** User-controlled OBS source recording settings. Attempt clips default off; SeedQueue batches default on. */
public record RecorderConfig(boolean automaticAttempts, boolean recordWall,
                             String gameplaySource, String wallSource, String outputDirectory,
                             String filenameTemplate) {
    public RecorderConfig {
        // Wall mode is one continuous video per SeedQueue cycle. Attempt clips
        // would duplicate that footage and can overload OBS, so wall mode owns
        // the recording stream exclusively.
        if (recordWall) automaticAttempts = false;
        gameplaySource = gameplaySource == null ? "" : gameplaySource.trim();
        wallSource = wallSource == null ? "" : wallSource.trim();
        outputDirectory = outputDirectory == null ? "" : outputDirectory.trim();
        filenameTemplate = filenameTemplate == null || filenameTemplate.isBlank()
                ? "resets #0" : filenameTemplate.trim();
    }

    /** Compatibility constructor for settings saved before filename templates were added. */
    public RecorderConfig(boolean automaticAttempts, boolean recordWall, String gameplaySource,
                          String wallSource, String outputDirectory) {
        this(automaticAttempts, recordWall, gameplaySource, wallSource, outputDirectory, "resets #0");
    }

    public static RecorderConfig safeDefaults() {
        String videos = Path.of(System.getProperty("user.home"), "Videos", "MCSR AutoRecorder").toString();
        return new RecorderConfig(false, true, "", "", videos, "resets #0");
    }
}
