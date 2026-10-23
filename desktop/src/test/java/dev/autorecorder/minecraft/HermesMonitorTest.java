package dev.autorecorder.minecraft;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HermesMonitorTest {
    @TempDir Path temp;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void readsStateOutputWallMarkerAndTracksLeavingTheWall() throws Exception {
        long pid = ProcessHandle.current().pid();
        Path instances = Files.createDirectories(temp.resolve("instances"));
        Path gameDir = Files.createDirectories(temp.resolve("game"));
        Path hermesDir = Files.createDirectories(gameDir.resolve("hermes"));
        Files.writeString(hermesDir.resolve("state.json"), "{}");
        Files.writeString(instances.resolve(pid + ".json"), "{\"pid\":" + pid + ",\"game_dir\":\""
                + gameDir.toString().replace("\\", "\\\\") + "\",\"game_version\":\"1.16.1\"}");
        Path output = gameDir.resolve("wpstateout.txt");
        Files.writeString(output, "wall");

        List<JsonNode> events = new ArrayList<>();
        try (HermesMonitor monitor = new HermesMonitor(instances, events::add, ignored -> { })) {
            monitor.pollOnce();
            JsonNode wall = events.stream().filter(e -> "SEEDQUEUE_STATE".equals(e.path("type").asText())).findFirst().orElseThrow();
            assertTrue(wall.path("onWall").asBoolean());
            assertEquals("wall", wall.path("state").asText());

            Files.writeString(output, "inworld,unpaused");
            monitor.pollOnce();
            List<JsonNode> states = events.stream().filter(e -> "SEEDQUEUE_STATE".equals(e.path("type").asText())).toList();
            assertEquals(2, states.size());
            assertFalse(states.get(1).path("onWall").asBoolean());
            assertEquals("inworld,unpaused", states.get(1).path("state").asText());
        }
    }

    @Test void missingOrMalformedStateOutputIsUnknown() throws Exception {
        Path gameDir = Files.createDirectories(temp.resolve("state-output-game"));
        assertTrue(HermesMonitor.readStateOutput(gameDir).isEmpty());
        Files.writeString(gameDir.resolve("wpstateout.txt"), "wall\nnot-a-state");
        assertTrue(HermesMonitor.readStateOutput(gameDir).isEmpty());
    }

    @Test void readsHermesWorldPathAndIgnoresUnsupportedLogTypes() throws Exception {
        JsonNode path = mapper.readTree("{\"path\":\"saves/Attempt-1\",\"relative\":true}");
        assertEquals(Path.of("saves", "Attempt-1").toString(), HermesMonitor.worldId(path));
        assertTrue(HermesMonitor.parseWorldLogLine("{\"type\":\"play_log_saved\",\"world\":\"w\"}").isEmpty());
        var entering = HermesMonitor.parseWorldLogLine(
                "{\"type\":\"entering\",\"world\":{\"path\":\"saves/Attempt-1\",\"relative\":true},\"time\":1}");
        assertTrue(entering.isPresent());
        assertEquals("entering", entering.get().type());
        assertEquals(Path.of("saves", "Attempt-1").toString(), entering.get().worldId());
    }

    @Test void detectsWorldStartAndLeaveFromHermesStateFiles() throws Exception {
        long pid = ProcessHandle.current().pid();
        Path instances = Files.createDirectories(temp.resolve("instances"));
        Path gameDir = Files.createDirectories(temp.resolve("game"));
        Path hermesDir = Files.createDirectories(gameDir.resolve("hermes"));
        Path logs = Files.createDirectories(hermesDir.resolve("world_logs"));
        String world = "saves/Attempt-42";
        Path save = Files.createDirectories(gameDir.resolve(world));
        Path worldLog = logs.resolve("worlds_1.log");
        Files.writeString(worldLog, "");
        Files.writeString(hermesDir.resolve("latest_world_log.txt"), worldLog.getFileName().toString());
        Files.writeString(hermesDir.resolve("state.json"), "{\"world\":{\"path\":\"" + world + "\",\"relative\":true}}");
        Files.writeString(instances.resolve(pid + ".json"), "{\"pid\":" + pid + ",\"game_dir\":\""
                + gameDir.toString().replace("\\", "\\\\") + "\",\"game_version\":\"1.16.1\"}");

        List<JsonNode> events = new ArrayList<>();
        try (HermesMonitor monitor = new HermesMonitor(instances, events::add, ignored -> { })) {
            monitor.pollOnce();
            assertEquals(List.of("SESSION_START", "ATTEMPT_START"), events.stream().map(e -> e.path("type").asText()).toList());
            assertEquals(Path.of(world).toString(), events.get(1).path("worldId").asText());

            Path recordFolder = Files.createDirectories(save.resolve("speedrunigt"));
            Files.writeString(recordFolder.resolve("record.json"),
                    "{\"timelines\":[{\"name\":\"enter_nether\",\"igt\":125000,\"rta\":130000}]}");
            monitor.pollOnce();
            assertEquals("DIMENSION", events.get(2).path("type").asText());
            assertEquals("minecraft:the_nether", events.get(2).path("dimension").asText());
            assertEquals("speedrunigt-record", events.get(2).path("source").asText());

            Files.writeString(worldLog, "{\"type\":\"leave\",\"world\":{\"path\":\"" + world
                    + "\",\"relative\":true},\"time\":2}\n");
            monitor.pollOnce();
            assertEquals("ATTEMPT_END", events.get(3).path("type").asText());
            assertTrue(events.get(3).path("netherKnown").asBoolean());
            assertTrue(events.get(3).path("netherReached").asBoolean());
        }
    }

    @Test void validSpeedRunRecordWithoutNetherConfirmsNegativeOnlyAtCleanWorldLeave() throws Exception {
        long pid = ProcessHandle.current().pid();
        Path instances = Files.createDirectories(temp.resolve("instances"));
        Path gameDir = Files.createDirectories(temp.resolve("game"));
        Path hermesDir = Files.createDirectories(gameDir.resolve("hermes"));
        Path logs = Files.createDirectories(hermesDir.resolve("world_logs"));
        String world = "saves/Attempt-no-nether";
        Path save = Files.createDirectories(gameDir.resolve(world));
        Path worldLog = logs.resolve("worlds_1.log");
        Files.writeString(worldLog, "");
        Files.writeString(hermesDir.resolve("latest_world_log.txt"), worldLog.getFileName().toString());
        Files.writeString(hermesDir.resolve("state.json"), "{\"world\":{\"path\":\"" + world + "\",\"relative\":true}}");
        Files.writeString(instances.resolve(pid + ".json"), "{\"pid\":" + pid + ",\"game_dir\":\""
                + gameDir.toString().replace("\\", "\\\\") + "\",\"game_version\":\"1.16.1\"}");
        Files.createDirectories(save.resolve("speedrunigt"));
        Files.writeString(save.resolve("speedrunigt/record.json"), "{\"timelines\":[]}");

        List<JsonNode> events = new ArrayList<>();
        try (HermesMonitor monitor = new HermesMonitor(instances, events::add, ignored -> { })) {
            monitor.pollOnce();
            Files.writeString(worldLog, "{\"type\":\"leave\",\"world\":{\"path\":\"" + world
                    + "\",\"relative\":true},\"time\":2}\n");
            monitor.pollOnce();
        }
        JsonNode ended = events.stream().filter(e -> "ATTEMPT_END".equals(e.path("type").asText())).findFirst().orElseThrow();
        assertTrue(ended.path("netherKnown").asBoolean());
        assertFalse(ended.path("netherReached").asBoolean());
    }

    @Test void missingRecordKeepsEndResultUnknown() throws Exception {
        long pid = ProcessHandle.current().pid();
        Path instances = Files.createDirectories(temp.resolve("instances"));
        Path gameDir = Files.createDirectories(temp.resolve("game"));
        Path hermesDir = Files.createDirectories(gameDir.resolve("hermes"));
        Path logs = Files.createDirectories(hermesDir.resolve("world_logs"));
        String world = "saves/Attempt-unknown";
        Files.createDirectories(gameDir.resolve(world));
        Path worldLog = logs.resolve("worlds_1.log");
        Files.writeString(worldLog, "");
        Files.writeString(hermesDir.resolve("latest_world_log.txt"), worldLog.getFileName().toString());
        Files.writeString(hermesDir.resolve("state.json"), "{\"world\":{\"path\":\"" + world + "\",\"relative\":true}}");
        Files.writeString(instances.resolve(pid + ".json"), "{\"pid\":" + pid + ",\"game_dir\":\""
                + gameDir.toString().replace("\\", "\\\\") + "\",\"game_version\":\"1.16.1\"}");
        List<JsonNode> events = new ArrayList<>();
        try (HermesMonitor monitor = new HermesMonitor(instances, events::add, ignored -> { })) {
            monitor.pollOnce();
            Files.writeString(worldLog, "{\"type\":\"leave\",\"world\":{\"path\":\"" + world
                    + "\",\"relative\":true},\"time\":2}\n");
            monitor.pollOnce();
        }
        JsonNode ended = events.stream().filter(e -> "ATTEMPT_END".equals(e.path("type").asText())).findFirst().orElseThrow();
        assertFalse(ended.path("netherKnown").asBoolean());
    }
}
