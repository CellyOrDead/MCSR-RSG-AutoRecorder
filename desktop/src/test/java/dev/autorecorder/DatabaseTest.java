package dev.autorecorder;

import dev.autorecorder.persistence.Database;
import dev.autorecorder.obs.ObsConnectionConfig;
import dev.autorecorder.recording.RecorderConfig;
import dev.autorecorder.recording.RecordingEntry;
import dev.autorecorder.retention.RetentionConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class DatabaseTest {
    @TempDir Path temp;
    @Test void obsConnectionAddressSurvivesDatabaseReopen() throws Exception {
        Path file = temp.resolve("obs-connection.sqlite");
        ObsConnectionConfig config = new ObsConnectionConfig("10.10.10.37", 4455);
        try (Database db = new Database(file)) {
            assertEquals(ObsConnectionConfig.defaults(), db.loadObsConnectionConfig());
            db.saveObsConnectionConfig(config);
        }
        try (Database db = new Database(file)) {
            assertEquals(config, db.loadObsConnectionConfig());
        }
    }

    @Test void retentionSettingsSurviveDatabaseReopen() throws Exception {
        Path file=temp.resolve("state.sqlite");
        try(Database db=new Database(file)){
            assertEquals(RetentionConfig.safeDefaults(),db.loadRetentionConfig());
            db.saveRetentionConfig(new RetentionConfig(false,true,true,420,false,true));
        }
        try(Database db=new Database(file)){
            assertEquals(new RetentionConfig(false,true,true,420,false,true),db.loadRetentionConfig());
        }
    }

    @Test void recorderSettingsAndRecordingMetadataSurviveDatabaseReopen() throws Exception {
        Path file = temp.resolve("recordings.sqlite");
        RecorderConfig config = new RecorderConfig(true, true, "Game Source", "Wall Source", temp.toString());
        RecordingEntry recording = new RecordingEntry(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "gameplay", "Game Source", "owned-filter", Instant.parse("2026-01-01T00:00:00Z"),
                null, temp.resolve("game.mkv").toString(), "RECORDING", null, 12345L);
        try (Database db = new Database(file)) {
            db.saveRecorderConfig(config);
            db.saveRecording(recording);
        }
        try (Database db = new Database(file)) {
            assertEquals(config, db.loadRecorderConfig());
            assertEquals(recording, db.recentRecordings().get(0));
        }
    }
}
