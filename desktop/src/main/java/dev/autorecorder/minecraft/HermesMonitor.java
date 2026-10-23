package dev.autorecorder.minecraft;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Read-only integration with already-installed approved mods. It reads
 * Hermes instance/world state and SpeedRunIGT's allowed record.json, never
 * Hermes's restricted per-world play log or raw SpeedRunIGT event log.
 */
public final class HermesMonitor implements AutoCloseable {
    private static final String PROTOCOL = "mcsr-autorecorder/1";
    private static final long MAX_LOG_BYTES_PER_POLL = 1024 * 1024;

    private final Path instancesDirectory;
    private final Consumer<JsonNode> eventSink;
    private final Consumer<String> statusSink;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "hermes-monitor");
        thread.setDaemon(true);
        return thread;
    });

    private long monitoredPid = -1;
    private String activeWorld;
    private Path activeGameDir;
    private final Set<String> reportedMilestones = new HashSet<>();
    private boolean recordValidThisCheck;
    private boolean recordNetherThisCheck;
    private Path monitoredLog;
    private long logOffset;
    private byte[] pendingLogBytes = new byte[0];
    private String lastSeedQueueState;
    private String lastStatus;

    public HermesMonitor(Path instancesDirectory, Consumer<JsonNode> eventSink, Consumer<String> statusSink) {
        this.instancesDirectory = Objects.requireNonNull(instancesDirectory);
        this.eventSink = Objects.requireNonNull(eventSink);
        this.statusSink = Objects.requireNonNull(statusSink);
    }

    public static Path defaultInstancesDirectory() {
        String local = System.getenv("LOCALAPPDATA");
        if (local != null && !local.isBlank()) return Path.of(local, "MCSRHermes", "instances");
        String roaming = System.getenv("APPDATA");
        if (roaming != null && !roaming.isBlank()) return Path.of(roaming, "MCSRHermes", "instances");
        String xdg = System.getenv("XDG_RUNTIME_DIR");
        if (xdg != null && !xdg.isBlank()) return Path.of(xdg, "MCSRHermes", "instances");
        return Path.of(System.getProperty("user.home"), ".local", "share", "MCSRHermes", "instances");
    }

    public void start() {
        executor.scheduleWithFixedDelay(this::pollSafely, 0, 1500, TimeUnit.MILLISECONDS);
    }

    private void pollSafely() {
        try {
            pollOnce();
        } catch (Exception e) {
            updateStatus("Minecraft: Hermes monitoring error — " + safeMessage(e));
        }
    }

    /** One scan, exposed to make the file reader testable without a live game. */
    synchronized void pollOnce() throws IOException {
        List<Instance> instances = findInstances();
        Instance instance = chooseInstance(instances);
        if (instance == null) {
            if (monitoredPid != -1) {
                // Hermes can briefly rewrite/remove its instance descriptor while
                // the game process remains alive. Do not end a SeedQueue video
                // (or mark the attempt interrupted) on that transient gap.
                if (ProcessHandle.of(monitoredPid).map(ProcessHandle::isAlive).orElse(false)) {
                    updateStatus("Minecraft: Hermes instance metadata is temporarily unavailable; keeping the current session");
                    return;
                }
                emit("SESSION_END", activeWorld);
                resetInstance();
            }
            updateStatus("Minecraft: waiting for an approved Hermes instance");
            return;
        }

        if (instance.pid != monitoredPid) {
            if (monitoredPid != -1) emit("SESSION_END", activeWorld);
            resetInstance();
            monitoredPid = instance.pid;
            activeGameDir = instance.gameDir;
            emit("SESSION_START", null);
        }
        activeGameDir = instance.gameDir;
        readSeedQueueState(instance.gameDir);

        String status = "Minecraft: Hermes connected · "
                + (instance.gameVersion.isBlank() ? "game version unknown" : instance.gameVersion)
                + " · PID " + instance.pid;
        if (instances.size() > 1) status += " · multiple instances found; monitoring one";
        updateStatus(status);

        JsonNode state = readJson(instance.gameDir.resolve("hermes").resolve("state.json"));
        if (state == null || !state.isObject()) {
            updateStatus("Minecraft: Hermes instance found; waiting for a complete state.json");
            return;
        }

        Path worldLog = findLatestWorldLog(instance.gameDir);
        boolean logAvailable = worldLog != null;
        if (!Objects.equals(worldLog, monitoredLog)) {
            monitoredLog = worldLog;
            pendingLogBytes = new byte[0];
            logOffset = worldLog == null ? 0 : Files.size(worldLog); // Ignore old transitions on attach/restart.
        }

        String currentWorld = worldId(state.path("world"));
        if (activeWorld == null && currentWorld != null) startAttempt(currentWorld);

        if (logAvailable) {
            readWorldLog(worldLog);
        } else if (!Objects.equals(activeWorld, currentWorld)) {
            if (activeWorld != null) {
                readSpeedRunRecord(instance.gameDir);
                endAttempt(activeWorld);
            }
            if (currentWorld != null) startAttempt(currentWorld);
        }
        readSpeedRunRecord(instance.gameDir);
    }

    private List<Instance> findInstances() throws IOException {
        if (!Files.isDirectory(instancesDirectory)) return List.of();
        List<Instance> found = new ArrayList<>();
        try (Stream<Path> files = Files.list(instancesDirectory)) {
            for (Path file : files.filter(p -> p.getFileName().toString().endsWith(".json")).toList()) {
                JsonNode metadata = readJson(file);
                if (metadata == null || !metadata.isObject()) continue;
                long pid = metadata.path("pid").asLong(parsePid(file));
                if (pid <= 0 || !ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) continue;
                String gameDir = text(metadata, "game_dir", "gameDir");
                if (gameDir.isBlank()) continue;
                Path normalizedGameDir;
                try { normalizedGameDir = Path.of(gameDir).toAbsolutePath().normalize(); }
                catch (InvalidPathException ignored) { continue; }
                found.add(new Instance(pid, normalizedGameDir, text(metadata, "game_version", "gameVersion")));
            }
        }
        return found;
    }

    private Instance chooseInstance(List<Instance> instances) {
        if (instances.isEmpty()) return null;
        for (Instance candidate : instances) if (candidate.pid == monitoredPid) return candidate;
        return instances.stream().max(Comparator.comparingLong(i -> {
            try { return Files.getLastModifiedTime(i.gameDir.resolve("hermes/state.json")).toMillis(); }
            catch (IOException ignored) { return 0L; }
        })).orElse(null);
    }

    private void readWorldLog(Path file) throws IOException {
        long size = Files.size(file);
        if (size < logOffset) { // A truncation is ambiguous; skip its contents to avoid replaying old attempts.
            logOffset = size;
            pendingLogBytes = new byte[0];
            return;
        }
        long available = size - logOffset;
        if (available <= 0) return;
        if (available > MAX_LOG_BYTES_PER_POLL) {
            logOffset = size;
            pendingLogBytes = new byte[0];
            updateStatus("Minecraft: Hermes connected; skipped an oversized world-log update for safety");
            return;
        }

        byte[] chunk = new byte[(int) available];
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            channel.position(logOffset);
            ByteBuffer buffer = ByteBuffer.wrap(chunk);
            while (buffer.hasRemaining() && channel.read(buffer) >= 0) { }
            logOffset = channel.position();
        }
        byte[] combined = new byte[pendingLogBytes.length + chunk.length];
        System.arraycopy(pendingLogBytes, 0, combined, 0, pendingLogBytes.length);
        System.arraycopy(chunk, 0, combined, pendingLogBytes.length, chunk.length);

        int lineStart = 0;
        for (int i = 0; i < combined.length; i++) {
            if (combined[i] != '\n') continue;
            String line = new String(combined, lineStart, i - lineStart, StandardCharsets.UTF_8).trim();
            if (!line.isEmpty()) parseWorldLogLine(line).ifPresent(this::applyWorldLogEntry);
            lineStart = i + 1;
        }
        pendingLogBytes = Arrays.copyOfRange(combined, lineStart, combined.length);
    }

    private void applyWorldLogEntry(WorldLogEntry entry) {
        if (entry.worldId == null || entry.worldId.isBlank()) return;
        if ("entering".equals(entry.type)) {
            if (Objects.equals(activeWorld, entry.worldId)) return;
            if (activeWorld != null) endAttempt(activeWorld);
            startAttempt(entry.worldId);
        } else if ("leave".equals(entry.type) && Objects.equals(activeWorld, entry.worldId)) {
            readSpeedRunRecord(activeGameDir);
            endAttempt(entry.worldId);
        }
    }

    private void startAttempt(String worldId) {
        if (Objects.equals(activeWorld, worldId)) return;
        activeWorld = worldId;
        reportedMilestones.clear();
        recordValidThisCheck = false;
        recordNetherThisCheck = false;
        emit("ATTEMPT_START", worldId);
    }

    private void endAttempt(String worldId) {
        if (!Objects.equals(activeWorld, worldId)) return;
        ObjectNode event = mapper.createObjectNode();
        event.put("protocol", PROTOCOL);
        event.put("type", "ATTEMPT_END");
        event.put("worldId", worldId);
        event.put("source", "hermes");
        event.put("netherKnown", recordValidThisCheck);
        if (recordValidThisCheck) event.put("netherReached", recordNetherThisCheck);
        eventSink.accept(event);
        activeWorld = null;
        reportedMilestones.clear();
        recordValidThisCheck = false;
        recordNetherThisCheck = false;
    }

    private void readSpeedRunRecord(Path gameDir) {
        recordValidThisCheck = false;
        recordNetherThisCheck = false;
        if (activeWorld == null) return;
        Path recordFile = speedRunRecordFile(gameDir, activeWorld);
        if (recordFile == null) return;
        JsonNode record = readJson(recordFile);
        if (record == null || !record.isObject()) return; // Missing/partial records remain unknown.
        JsonNode timelines = record.path("timelines");
        if (!timelines.isArray()) return;
        recordValidThisCheck = true;
        for (JsonNode timeline : timelines) {
            String name = timeline.path("name").asText("");
            if ("enter_nether".equals(name)) {
                recordNetherThisCheck = true;
                if (reportedMilestones.add(name)) emitDimension("minecraft:the_nether");
            }
            else if ("enter_end".equals(name) && reportedMilestones.add(name))
                emitDimension("minecraft:the_end");
        }
    }

    private void readSeedQueueState(Path gameDir) {
        Optional<String> observed = readStateOutput(gameDir);
        if (observed.isEmpty() || observed.get().equals(lastSeedQueueState)) return;
        lastSeedQueueState = observed.get();

        ObjectNode event = mapper.createObjectNode();
        event.put("protocol", PROTOCOL);
        event.put("type", "SEEDQUEUE_STATE");
        event.put("state", lastSeedQueueState);
        event.put("onWall", "wall".equals(lastSeedQueueState));
        event.put("source", "state-output");
        eventSink.accept(event);
    }

    /** Reads State Output's approved wpstateout.txt marker from the Hermes game directory. */
    static Optional<String> readStateOutput(Path gameDir) {
        try {
            Path root = gameDir.toAbsolutePath().normalize();
            Path output = root.resolve("wpstateout.txt").normalize();
            if (!output.startsWith(root) || Files.isSymbolicLink(output) || !Files.isRegularFile(output))
                return Optional.empty();
            long size = Files.size(output);
            if (size < 1 || size > 128) return Optional.empty();
            String state = Files.readString(output, StandardCharsets.UTF_8).trim();
            if (!state.matches("[a-z]+(?:,[a-z0-9]+)*")) return Optional.empty();
            return Optional.of(state);
        } catch (IOException | InvalidPathException ignored) {
            return Optional.empty();
        }
    }

    private Path speedRunRecordFile(Path gameDir, String worldId) {
        try {
            Path saves = gameDir.resolve("saves").toAbsolutePath().normalize();
            Path world = Path.of(worldId);
            if (!world.isAbsolute()) world = gameDir.resolve(world);
            world = world.toAbsolutePath().normalize();
            if (world.equals(saves) || !world.startsWith(saves)) return null;
            Path record = world.resolve("speedrunigt").resolve("record.json").normalize();
            return record.startsWith(saves) && Files.isRegularFile(record) ? record : null;
        } catch (InvalidPathException ignored) {
            return null;
        }
    }

    private Path findLatestWorldLog(Path gameDir) {
        Path hermesDir = gameDir.resolve("hermes");
        Path latest = hermesDir.resolve("latest_world_log.txt");
        try {
            String name = Files.readString(latest, StandardCharsets.UTF_8).trim();
            if (name.isBlank() || !Path.of(name).getFileName().toString().equals(name)
                    || !name.startsWith("worlds_") || !name.endsWith(".log")) return null;
            Path directory = hermesDir.resolve("world_logs").toAbsolutePath().normalize();
            Path log = directory.resolve(name).normalize();
            return log.getParent().equals(directory) && Files.isRegularFile(log) ? log : null;
        } catch (IOException | InvalidPathException ignored) {
            return null;
        }
    }

    private JsonNode readJson(Path file) {
        try { return Files.isRegularFile(file) ? mapper.readTree(file.toFile()) : null; }
        catch (IOException ignored) { return null; } // Hermes replaces state.json in place; partial writes are retried next scan.
    }

    static String worldId(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) return null;
        String value;
        if (node.isTextual()) value = node.asText();
        else if (node.isObject()) {
            JsonNode path = node.get("path");
            if (path == null || !path.isTextual()) return null;
            value = path.asText();
        } else return null;
        if (value.isBlank()) return null;
        try { return Path.of(value).normalize().toString(); }
        catch (InvalidPathException ignored) { return value; }
    }

    static Optional<WorldLogEntry> parseWorldLogLine(String line) {
        try {
            JsonNode node = new ObjectMapper().readTree(line);
            String type = node.path("type").asText("");
            if (!type.equals("entering") && !type.equals("leave")) return Optional.empty();
            String world = worldId(node.path("world"));
            return world == null ? Optional.empty() : Optional.of(new WorldLogEntry(type, world));
        } catch (IOException | RuntimeException ignored) {
            return Optional.empty();
        }
    }

    private long parsePid(Path file) {
        String name = file.getFileName().toString();
        try { return Long.parseLong(name.substring(0, name.length() - 5)); }
        catch (RuntimeException ignored) { return -1; }
    }

    private static String text(JsonNode node, String... keys) {
        for (String key : keys) {
            JsonNode value = node.get(key);
            if (value != null && value.isTextual()) return value.asText();
        }
        return "";
    }

    private void emit(String type, String worldId) {
        ObjectNode event = mapper.createObjectNode();
        event.put("protocol", PROTOCOL);
        event.put("type", type);
        if (worldId != null) event.put("worldId", worldId);
        event.put("source", "hermes");
        eventSink.accept(event);
    }

    private void emitDimension(String dimension) {
        ObjectNode event = mapper.createObjectNode();
        event.put("protocol", PROTOCOL);
        event.put("type", "DIMENSION");
        event.put("dimension", dimension);
        event.put("source", "speedrunigt-record");
        eventSink.accept(event);
    }

    private void updateStatus(String status) {
        if (Objects.equals(status, lastStatus)) return;
        lastStatus = status;
        statusSink.accept(status);
    }

    private void resetInstance() {
        monitoredPid = -1;
        activeWorld = null;
        activeGameDir = null;
        reportedMilestones.clear();
        recordValidThisCheck = false;
        recordNetherThisCheck = false;
        monitoredLog = null;
        logOffset = 0;
        pendingLogBytes = new byte[0];
        lastSeedQueueState = null;
    }

    private static String safeMessage(Exception e) {
        return e.getMessage() == null || e.getMessage().isBlank() ? e.getClass().getSimpleName() : e.getMessage();
    }

    @Override public void close() { executor.shutdownNow(); }

    record WorldLogEntry(String type, String worldId) { }
    private record Instance(long pid, Path gameDir, String gameVersion) { }
}
