package dev.autorecorder.recording;

import java.time.Instant;
import java.util.UUID;

/** Persistent metadata for one gameplay or SeedQueue wall output. */
public record RecordingEntry(UUID id, UUID sessionId, UUID attemptId, String stream,
                             String sourceName, String filterName, Instant startedAt,
                             Instant endedAt, String path, String status, String error, long fileSize) {
    public RecordingEntry withState(String next, Instant ended, String outputPath, String errorText) {
        return new RecordingEntry(id, sessionId, attemptId, stream, sourceName, filterName,
                startedAt, ended, outputPath == null ? path : outputPath, next, errorText, fileSize);
    }
}
