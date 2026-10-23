package dev.autorecorder.retention;

/** Editable keep criteria. A recording is retained when any enabled criterion matches. */
public record RetentionConfig(boolean keepIfNether, boolean keepIfEnd,
                              boolean keepIfDuration, long minimumDurationSeconds,
                              boolean automaticDeletion, boolean dryRun) {
    public RetentionConfig {
        if (minimumDurationSeconds < 0) throw new IllegalArgumentException("minimum duration cannot be negative");
    }
    public static RetentionConfig safeDefaults() {
        return new RetentionConfig(true, false, false, 300, false, true);
    }
}
