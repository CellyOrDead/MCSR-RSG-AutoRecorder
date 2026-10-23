package dev.autorecorder.persistence;

import java.time.Instant;

public record AuditEntry(Instant at, String attemptId, String event, String detail) { }
