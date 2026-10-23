package dev.autorecorder.model;

import java.time.Instant;
import java.util.UUID;

public record Attempt(UUID id, UUID sessionId, String worldId, Instant startedAt,
                      Instant endedAt, boolean netherReached, boolean endReached, Instant netherAt,
                      String state, String recordingPath, String retentionDecision,
                      boolean important, boolean excluded, String error) {
    public Attempt withNether(Instant at) {
        return new Attempt(id, sessionId, worldId, startedAt, endedAt, true, endReached,
                netherAt == null ? at : netherAt, "NETHER_REACHED", recordingPath,
                "KEEP", important, excluded, error);
    }
    public Attempt withEndReached() {
        return new Attempt(id, sessionId, worldId, startedAt, endedAt, netherReached, true,
                netherAt, state, recordingPath, retentionDecision, important, excluded, error);
    }
    public Attempt withState(String next, Instant ended) {
        return new Attempt(id, sessionId, worldId, startedAt, ended, netherReached, endReached,
                netherAt, next, recordingPath, retentionDecision, important, excluded, error);
    }
    public Attempt withRecordingPath(String path) {
        return new Attempt(id, sessionId, worldId, startedAt, endedAt, netherReached, endReached,
                netherAt, state, path, retentionDecision, important, excluded, error);
    }
    public Attempt withError(String errorText) {
        return new Attempt(id, sessionId, worldId, startedAt, endedAt, netherReached, endReached,
                netherAt, state, recordingPath, retentionDecision, important, excluded, errorText);
    }
}
