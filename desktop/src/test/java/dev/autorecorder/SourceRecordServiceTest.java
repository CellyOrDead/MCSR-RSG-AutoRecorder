package dev.autorecorder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.autorecorder.obs.ObsApi;
import dev.autorecorder.recording.SourceRecordService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class SourceRecordServiceTest {
    @TempDir Path temp;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void checksTheRegisteredSourceRecordVendorWithoutTouchingRealSources() {
        FakeObs obs = new FakeObs();
        SourceRecordService service = new SourceRecordService(obs);

        assertTrue(service.isAvailable().join());
        assertTrue(obs.probeSource.startsWith("MCSR_AutoRecorder_Probe_"));
        assertTrue(obs.requests.contains("CallVendorRequest:record_stop"));
    }

    @Test void startsAndFinalizesOnlyItsSourceRecordOutput() throws Exception {
        FakeObs obs = new FakeObs();
        SourceRecordService service = new SourceRecordService(obs);
        UUID attempt = UUID.randomUUID();

        SourceRecordService.Handle handle = service.start(UUID.randomUUID(), attempt, "gameplay",
                "Minecraft Game", temp.toString());

        assertTrue(handle.file().startsWith(temp.resolve("gameplay")));
        assertTrue(Files.size(handle.file()) > 0);
        assertEquals(handle.file(), service.stop(handle));
        assertTrue(obs.requests.contains("CallVendorRequest:record_start"));
        assertTrue(obs.requests.contains("CallVendorRequest:record_stop"));
        assertTrue(obs.requests.contains("CreateSourceFilter"));
        assertFalse(obs.requests.contains("StopRecord"));
        assertEquals("source_record_filter", obs.createdFilterKind);
    }

    @Test void refusesAnOutputDirectoryOutsideTheConfiguredRoot() {
        FakeObs obs = new FakeObs();
        SourceRecordService service = new SourceRecordService(obs);
        assertThrows(java.io.IOException.class, () -> service.start(UUID.randomUUID(), UUID.randomUUID(),
                "../../outside", "Minecraft Game", temp.toString()));
        assertTrue(obs.requests.isEmpty());
    }

    @Test void vendorRejectionIsReportedAsUnverifiedAndCleanupIsRequested() {
        FakeObs obs = new FakeObs();
        obs.rejectStart = true;
        SourceRecordService service = new SourceRecordService(obs);
        SourceRecordService.StartFailure error = assertThrows(SourceRecordService.StartFailure.class,
                () -> service.start(UUID.randomUUID(), UUID.randomUUID(), "gameplay", "Minecraft Game", temp.toString()));
        assertTrue(error.stopConfirmed());
        assertNotNull(error.handle());
        assertTrue(obs.requests.contains("CallVendorRequest:record_stop"));
    }

    @Test void stoppingGameplayDoesNotStopTheSeparateWallFilter() throws Exception {
        FakeObs obs = new FakeObs();
        SourceRecordService service = new SourceRecordService(obs);
        SourceRecordService.Handle wall = service.start(UUID.randomUUID(), null, "wall", "SeedQueue Wall", temp.toString());
        SourceRecordService.Handle gameplay = service.start(UUID.randomUUID(), UUID.randomUUID(), "gameplay", "Game", temp.toString());
        service.stop(gameplay);
        assertTrue(Files.exists(wall.file()));
        assertFalse(obs.stoppedFilters.contains(wall.filterName()));
        assertTrue(obs.stoppedFilters.contains(gameplay.filterName()));
    }

    private final class FakeObs implements ObsApi {
        final List<String> requests = new ArrayList<>();
        final List<String> stoppedFilters = new ArrayList<>();
        String createdFilterKind;
        String probeSource = "";
        Path outputDirectory;
        boolean rejectStart;

        @Override public CompletableFuture<JsonNode> request(String type, ObjectNode data) {
            try {
                requests.add(type);
                if ("GetSourceFilterDefaultSettings".equals(type)) {
                    ObjectNode defaults = mapper.createObjectNode().put("rec_format", "mkv").put("path", "unused");
                    return completed(mapper.createObjectNode().set("responseData",
                            mapper.createObjectNode().set("defaultFilterSettings", defaults)));
                }
                if ("CreateSourceFilter".equals(type)) {
                    createdFilterKind = data.path("filterKind").asText();
                    outputDirectory = Path.of(data.path("filterSettings").path("path").asText());
                    return completed(mapper.createObjectNode().set("responseData", mapper.createObjectNode()));
                }
                if ("CallVendorRequest".equals(type)) {
                    String requestType = data.path("requestType").asText();
                    requests.add(type + ":" + requestType);
                    if ("record_stop".equals(requestType)
                            && data.path("requestData").path("source").asText().startsWith("MCSR_AutoRecorder_Probe_")) {
                        probeSource = data.path("requestData").path("source").asText();
                        ObjectNode vendor = mapper.createObjectNode().put("success", false).put("error", "source not found");
                        return completed(mapper.createObjectNode().set("responseData",
                                mapper.createObjectNode().set("responseData", vendor)));
                    }
                    if ("record_stop".equals(requestType))
                        stoppedFilters.add(data.path("requestData").path("filter").asText());
                    if ("record_start".equals(requestType) && !rejectStart) {
                        String filename = data.path("requestData").path("filename").asText();
                        Files.createDirectories(outputDirectory);
                        Files.write(outputDirectory.resolve(filename + ".mkv"), new byte[]{1, 2, 3});
                    }
                    ObjectNode vendor = mapper.createObjectNode().put("success", !rejectStart || "record_stop".equals(requestType));
                    if (rejectStart && "record_start".equals(requestType)) vendor.put("error", "rejected start");
                    return completed(mapper.createObjectNode().set("responseData",
                            mapper.createObjectNode().set("responseData", vendor)));
                }
                return CompletableFuture.failedFuture(new AssertionError("Unexpected OBS request " + type));
            } catch (Exception error) {
                return CompletableFuture.failedFuture(error);
            }
        }

        private CompletableFuture<JsonNode> completed(JsonNode node) {
            return CompletableFuture.completedFuture(node);
        }
    }
}
