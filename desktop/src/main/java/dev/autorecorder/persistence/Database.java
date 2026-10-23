package dev.autorecorder.persistence;

import dev.autorecorder.model.Attempt;
import dev.autorecorder.obs.ObsConnectionConfig;
import dev.autorecorder.retention.RetentionConfig;
import dev.autorecorder.recording.RecorderConfig;
import dev.autorecorder.recording.RecordingEntry;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.*;

public final class Database implements AutoCloseable {
    private final Connection connection;
    private final ObjectMapper mapper = new ObjectMapper();
    public Database(Path file) throws SQLException {
        file.toAbsolutePath().getParent().toFile().mkdirs();
        connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
        try (Statement s = connection.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL");
            s.execute("CREATE TABLE IF NOT EXISTS attempts (id TEXT PRIMARY KEY, session_id TEXT, world_id TEXT, started_at TEXT, ended_at TEXT, nether INTEGER NOT NULL, nether_at TEXT, state TEXT NOT NULL, recording_path TEXT, decision TEXT, important INTEGER DEFAULT 0, excluded INTEGER DEFAULT 0, error TEXT)");
            s.execute("CREATE TABLE IF NOT EXISTS audit (at TEXT NOT NULL, attempt_id TEXT, event TEXT NOT NULL, detail TEXT)");
            s.execute("CREATE TABLE IF NOT EXISTS app_settings (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS recordings (id TEXT PRIMARY KEY, session_id TEXT NOT NULL, attempt_id TEXT, stream TEXT NOT NULL, source_name TEXT NOT NULL, filter_name TEXT NOT NULL, started_at TEXT NOT NULL, ended_at TEXT, path TEXT, status TEXT NOT NULL, error TEXT, file_size INTEGER NOT NULL DEFAULT 0)");
            s.execute("CREATE TABLE IF NOT EXISTS wall_batches (id TEXT PRIMARY KEY, started_at TEXT NOT NULL, completed_at TEXT, decision TEXT)");
            s.execute("CREATE TABLE IF NOT EXISTS wall_batch_attempts (batch_id TEXT NOT NULL, attempt_id TEXT NOT NULL UNIQUE, PRIMARY KEY(batch_id, attempt_id))");
        }
        if (!hasColumn("attempts", "end_reached")) try (Statement s = connection.createStatement()) {
            s.execute("ALTER TABLE attempts ADD COLUMN end_reached INTEGER NOT NULL DEFAULT 0");
        }
        if (!hasColumn("recordings", "file_size")) try (Statement s = connection.createStatement()) {
            s.execute("ALTER TABLE recordings ADD COLUMN file_size INTEGER NOT NULL DEFAULT 0");
        }
        try (Statement s = connection.createStatement()) { s.execute("PRAGMA user_version=5"); }
    }
    private boolean hasColumn(String table, String column) throws SQLException {
        try (Statement s=connection.createStatement(); ResultSet r=s.executeQuery("PRAGMA table_info("+table+")")) {
            while(r.next()) if(column.equalsIgnoreCase(r.getString("name"))) return true;
        }
        return false;
    }
    public synchronized RetentionConfig loadRetentionConfig() throws SQLException {
        try (PreparedStatement p=connection.prepareStatement("SELECT value FROM app_settings WHERE key='retention'")) {
            try (ResultSet r=p.executeQuery()) {
                if (!r.next()) return RetentionConfig.safeDefaults();
                try { return mapper.readValue(r.getString(1), RetentionConfig.class); }
                catch (Exception e) { throw new SQLException("Stored retention settings are invalid", e); }
            }
        }
    }
    public synchronized void saveRetentionConfig(RetentionConfig config) throws SQLException {
        try (PreparedStatement p=connection.prepareStatement("INSERT INTO app_settings(key,value) VALUES('retention',?) ON CONFLICT(key) DO UPDATE SET value=excluded.value")) {
            try { p.setString(1,mapper.writeValueAsString(config)); }
            catch (Exception e) { throw new SQLException("Could not encode retention settings", e); }
            p.executeUpdate();
        }
        audit(null,"SETTINGS","retention settings updated");
    }
    public synchronized RecorderConfig loadRecorderConfig() throws SQLException {
        RecorderConfig config;
        try (PreparedStatement p=connection.prepareStatement("SELECT value FROM app_settings WHERE key='recorder'")) {
            try (ResultSet r=p.executeQuery()) {
                if (!r.next()) config = RecorderConfig.safeDefaults();
                else try { config = mapper.readValue(r.getString(1), RecorderConfig.class); }
                catch (Exception e) { throw new SQLException("Stored recording settings are invalid", e); }
            }
        }
        // Existing installs used to default this off. Enable it once so the built-in
        // SeedQueue wall is not silently missed; later user changes are respected.
        if (!hasSetting("wall_recording_default_migrated")) {
            if (!config.recordWall()) {
                String source = config.wallSource().isBlank() ? config.gameplaySource() : config.wallSource();
                config = new RecorderConfig(config.automaticAttempts(), true, config.gameplaySource(), source,
                        config.outputDirectory());
                saveRecorderConfig(config);
            }
            saveSetting("wall_recording_default_migrated", "true");
        }
        return config;
    }
    public synchronized void saveRecorderConfig(RecorderConfig config) throws SQLException {
        try (PreparedStatement p=connection.prepareStatement("INSERT INTO app_settings(key,value) VALUES('recorder',?) ON CONFLICT(key) DO UPDATE SET value=excluded.value")) {
            try { p.setString(1,mapper.writeValueAsString(config)); }
            catch (Exception e) { throw new SQLException("Could not encode recording settings", e); }
            p.executeUpdate();
        }
        audit(null,"SETTINGS","recording settings updated");
    }
    public synchronized ObsConnectionConfig loadObsConnectionConfig() throws SQLException {
        try (PreparedStatement p=connection.prepareStatement("SELECT value FROM app_settings WHERE key='obs_connection'")) {
            try (ResultSet r=p.executeQuery()) {
                if (!r.next()) return ObsConnectionConfig.defaults();
                try { return mapper.readValue(r.getString(1), ObsConnectionConfig.class); }
                catch (Exception e) { throw new SQLException("Stored OBS connection settings are invalid", e); }
            }
        }
    }
    public synchronized void saveObsConnectionConfig(ObsConnectionConfig config) throws SQLException {
        try (PreparedStatement p=connection.prepareStatement("INSERT INTO app_settings(key,value) VALUES('obs_connection',?) ON CONFLICT(key) DO UPDATE SET value=excluded.value")) {
            try { p.setString(1,mapper.writeValueAsString(config)); }
            catch (Exception e) { throw new SQLException("Could not encode OBS connection settings", e); }
            p.executeUpdate();
        }
    }
    public synchronized boolean hasSavedObsConnectionConfig() throws SQLException {
        return hasSetting("obs_connection");
    }
    public synchronized long countAttempts() throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM attempts")) {
            return result.next() ? result.getLong(1) : 0L;
        }
    }
    private boolean hasSetting(String key) throws SQLException {
        try (PreparedStatement p=connection.prepareStatement("SELECT 1 FROM app_settings WHERE key=?")) {
            p.setString(1, key);
            try (ResultSet r=p.executeQuery()) { return r.next(); }
        }
    }
    private void saveSetting(String key, String value) throws SQLException {
        try (PreparedStatement p=connection.prepareStatement("INSERT INTO app_settings(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value")) {
            p.setString(1, key); p.setString(2, value); p.executeUpdate();
        }
    }
    public synchronized void updateAttemptRecordingPath(UUID attemptId, String path) throws SQLException {
        try (PreparedStatement p=connection.prepareStatement("UPDATE attempts SET recording_path=? WHERE id=?")) {
            p.setString(1,path); p.setString(2,attemptId.toString()); p.executeUpdate();
        }
    }
    public synchronized void updateAttemptRetentionDecision(UUID attemptId, String decision) throws SQLException {
        try (PreparedStatement p=connection.prepareStatement("UPDATE attempts SET decision=? WHERE id=?")) {
            p.setString(1,decision); p.setString(2,attemptId.toString()); p.executeUpdate();
        }
        audit(attemptId,"RETENTION",decision);
    }
    public synchronized void updateAttemptFlags(UUID attemptId, boolean important, boolean excluded) throws SQLException {
        try (PreparedStatement p=connection.prepareStatement("UPDATE attempts SET important=?,excluded=? WHERE id=?")) {
            p.setInt(1,important?1:0); p.setInt(2,excluded?1:0); p.setString(3,attemptId.toString()); p.executeUpdate();
        }
        audit(attemptId,"MANUAL_OVERRIDE","important="+important+", excluded="+excluded);
    }
    public synchronized void updateAttemptState(UUID attemptId, String state) throws SQLException {
        try (PreparedStatement p=connection.prepareStatement("UPDATE attempts SET state=? WHERE id=?")) {
            p.setString(1,state); p.setString(2,attemptId.toString()); p.executeUpdate();
        }
        audit(attemptId,"STATE",state);
    }
    public synchronized void saveRecording(RecordingEntry entry) throws SQLException {
        String sql="INSERT INTO recordings(id,session_id,attempt_id,stream,source_name,filter_name,started_at,ended_at,path,status,error,file_size) VALUES(?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET ended_at=excluded.ended_at,path=excluded.path,status=excluded.status,error=excluded.error,file_size=excluded.file_size";
        try (PreparedStatement p=connection.prepareStatement(sql)) {
            p.setString(1,entry.id().toString()); p.setString(2,entry.sessionId().toString());
            p.setString(3,entry.attemptId()==null?null:entry.attemptId().toString()); p.setString(4,entry.stream());
            p.setString(5,entry.sourceName()); p.setString(6,entry.filterName()); p.setString(7,entry.startedAt().toString());
            p.setString(8,str(entry.endedAt())); p.setString(9,entry.path()); p.setString(10,entry.status()); p.setString(11,entry.error()); p.setLong(12,entry.fileSize());
            p.executeUpdate();
        }
        audit(entry.attemptId(),"RECORDING",entry.stream()+" | "+entry.status()+" | "+(entry.path()==null?"path pending":entry.path()));
    }
    public synchronized void beginWallBatch(UUID batchId) throws SQLException {
        try (PreparedStatement p=connection.prepareStatement("INSERT OR IGNORE INTO wall_batches(id,started_at) VALUES(?,?)")) {
            p.setString(1,batchId.toString()); p.setString(2,Instant.now().toString()); p.executeUpdate();
        }
    }
    public synchronized void linkAttemptToWallBatch(UUID batchId, UUID attemptId) throws SQLException {
        try (PreparedStatement p=connection.prepareStatement("INSERT OR IGNORE INTO wall_batch_attempts(batch_id,attempt_id) VALUES(?,?)")) {
            p.setString(1,batchId.toString()); p.setString(2,attemptId.toString()); p.executeUpdate();
        }
        try (PreparedStatement p=connection.prepareStatement("SELECT batch_id FROM wall_batch_attempts WHERE attempt_id=?")) {
            p.setString(1,attemptId.toString());
            try (ResultSet r=p.executeQuery()) {
                if (!r.next() || !batchId.toString().equals(r.getString(1)))
                    throw new SQLException("Attempt is already linked to another wall batch");
            }
        }
    }
    public synchronized void completeWallBatch(UUID batchId) throws SQLException {
        try (PreparedStatement p=connection.prepareStatement("UPDATE wall_batches SET completed_at=COALESCE(completed_at,?) WHERE id=?")) {
            p.setString(1,Instant.now().toString()); p.setString(2,batchId.toString());
            if (p.executeUpdate()==0) throw new SQLException("Wall batch metadata is missing");
        }
        audit(null,"WALL_BATCH_COMPLETE",batchId.toString());
    }
    public synchronized boolean isWallBatchComplete(UUID batchId) throws SQLException {
        try (PreparedStatement p=connection.prepareStatement("SELECT completed_at FROM wall_batches WHERE id=?")) {
            p.setString(1,batchId.toString());
            try (ResultSet r=p.executeQuery()) { return r.next() && r.getString(1)!=null; }
        }
    }
    public synchronized boolean wallBatchExists(UUID batchId) throws SQLException {
        try (PreparedStatement p=connection.prepareStatement("SELECT 1 FROM wall_batches WHERE id=?")) {
            p.setString(1,batchId.toString());
            try (ResultSet r=p.executeQuery()) { return r.next(); }
        }
    }
    public synchronized void updateWallBatchDecision(UUID batchId, String decision) throws SQLException {
        try (PreparedStatement p=connection.prepareStatement("UPDATE wall_batches SET decision=? WHERE id=?")) {
            p.setString(1,decision); p.setString(2,batchId.toString()); p.executeUpdate();
        }
        audit(null,"WALL_BATCH_RETENTION",batchId+" | "+decision);
    }
    public synchronized List<Attempt> attemptsForWallBatch(UUID batchId) throws SQLException {
        List<Attempt> out=new ArrayList<>();
        try (PreparedStatement p=connection.prepareStatement("SELECT a.* FROM attempts a JOIN wall_batch_attempts b ON b.attempt_id=a.id WHERE b.batch_id=? ORDER BY a.started_at")) {
            p.setString(1,batchId.toString());
            try (ResultSet r=p.executeQuery()) { while(r.next()) out.add(attempt(r)); }
        }
        return out;
    }
    public synchronized List<RecordingEntry> recentRecordings() throws SQLException {
        List<RecordingEntry> out=new ArrayList<>();
        try (Statement s=connection.createStatement(); ResultSet r=s.executeQuery("SELECT * FROM recordings ORDER BY started_at DESC LIMIT 500")) {
            while(r.next()) out.add(new RecordingEntry(UUID.fromString(r.getString("id")),UUID.fromString(r.getString("session_id")),
                    uuid(r.getString("attempt_id")),r.getString("stream"),r.getString("source_name"),r.getString("filter_name"),
                    Instant.parse(r.getString("started_at")),instant(r.getString("ended_at")),r.getString("path"),
                    r.getString("status"),r.getString("error"),r.getLong("file_size")));
        }
        return out;
    }
    public synchronized List<RecordingEntry> recordingsForAttempt(UUID attemptId) throws SQLException {
        List<RecordingEntry> out=new ArrayList<>();
        try (PreparedStatement p=connection.prepareStatement("SELECT * FROM recordings WHERE attempt_id=? ORDER BY started_at")) {
            p.setString(1,attemptId.toString());
            try (ResultSet r=p.executeQuery()) {
                while(r.next()) out.add(new RecordingEntry(UUID.fromString(r.getString("id")),UUID.fromString(r.getString("session_id")),
                        uuid(r.getString("attempt_id")),r.getString("stream"),r.getString("source_name"),r.getString("filter_name"),
                        Instant.parse(r.getString("started_at")),instant(r.getString("ended_at")),r.getString("path"),
                        r.getString("status"),r.getString("error"),r.getLong("file_size")));
            }
        }
        return out;
    }
    public synchronized List<RecordingEntry> wallRecordingsForBatch(UUID batchId) throws SQLException {
        List<RecordingEntry> out=new ArrayList<>();
        try (PreparedStatement p=connection.prepareStatement("SELECT * FROM recordings WHERE session_id=? AND stream='wall' ORDER BY started_at")) {
            p.setString(1,batchId.toString());
            try (ResultSet r=p.executeQuery()) {
                while(r.next()) out.add(new RecordingEntry(UUID.fromString(r.getString("id")),UUID.fromString(r.getString("session_id")),
                        uuid(r.getString("attempt_id")),r.getString("stream"),r.getString("source_name"),r.getString("filter_name"),
                        Instant.parse(r.getString("started_at")),instant(r.getString("ended_at")),r.getString("path"),
                        r.getString("status"),r.getString("error"),r.getLong("file_size")));
            }
        }
        return out;
    }
    public synchronized void save(Attempt a) throws SQLException {
        try (PreparedStatement p = connection.prepareStatement("INSERT INTO attempts(id,session_id,world_id,started_at,ended_at,nether,nether_at,state,recording_path,decision,important,excluded,error,end_reached) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET ended_at=excluded.ended_at,nether=excluded.nether,nether_at=excluded.nether_at,state=excluded.state,recording_path=excluded.recording_path,decision=excluded.decision,important=excluded.important,excluded=excluded.excluded,error=excluded.error,end_reached=excluded.end_reached")) {
            p.setString(1,a.id().toString()); p.setString(2,a.sessionId().toString()); p.setString(3,a.worldId());
            p.setString(4,a.startedAt().toString()); p.setString(5,str(a.endedAt())); p.setInt(6,a.netherReached()?1:0);
            p.setString(7,str(a.netherAt())); p.setString(8,a.state()); p.setString(9,a.recordingPath());
            p.setString(10,a.retentionDecision()); p.setInt(11,a.important()?1:0); p.setInt(12,a.excluded()?1:0); p.setString(13,a.error()); p.setInt(14,a.endReached()?1:0); p.executeUpdate();
        }
        audit(a.id(), "STATE", a.state());
    }
    public synchronized void audit(UUID id, String event, String detail) throws SQLException {
        try (PreparedStatement p=connection.prepareStatement("INSERT INTO audit VALUES(?,?,?,?)")) { p.setString(1,Instant.now().toString()); p.setString(2,id==null?null:id.toString()); p.setString(3,event); p.setString(4,detail); p.executeUpdate(); }
    }
    public synchronized List<AuditEntry> recentAudit(int limit) throws SQLException {
        int safeLimit=Math.max(1,Math.min(5000,limit));
        List<AuditEntry> out=new ArrayList<>();
        try (PreparedStatement p=connection.prepareStatement("SELECT at,attempt_id,event,detail FROM audit ORDER BY at DESC LIMIT ?")) {
            p.setInt(1,safeLimit);
            try (ResultSet r=p.executeQuery()) {
                while(r.next()) out.add(new AuditEntry(Instant.parse(r.getString("at")),r.getString("attempt_id"),r.getString("event"),r.getString("detail")));
            }
        }
        return out;
    }
    public synchronized List<Attempt> recent() throws SQLException {
        List<Attempt> out=new ArrayList<>();
        try (Statement s=connection.createStatement(); ResultSet r=s.executeQuery("SELECT * FROM attempts ORDER BY started_at DESC LIMIT 500")) {
            while(r.next()) out.add(attempt(r));
        } return out;
    }
    private static Attempt attempt(ResultSet r) throws SQLException {
        return new Attempt(UUID.fromString(r.getString("id")),UUID.fromString(r.getString("session_id")),r.getString("world_id"),
                Instant.parse(r.getString("started_at")),instant(r.getString("ended_at")),r.getInt("nether")!=0,
                r.getInt("end_reached")!=0,instant(r.getString("nether_at")),r.getString("state"),r.getString("recording_path"),
                r.getString("decision"),r.getInt("important")!=0,r.getInt("excluded")!=0,r.getString("error"));
    }
    private static String str(Instant i){return i==null?null:i.toString();} private static Instant instant(String s){return s==null?null:Instant.parse(s);}
    private static UUID uuid(String value){return value==null?null:UUID.fromString(value);}
    @Override public void close() throws SQLException { connection.close(); }
}
