package dev.autorecorder.recording;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.autorecorder.obs.ObsApi;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

/** Controls Source Record filters through its documented OBS WebSocket vendor requests. */
public final class SourceRecordService {
    private static final String FILTER_KIND = "source_record_filter";
    private static final String VENDOR = "source-record";
    private static final Duration START_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration FINALIZE_TIMEOUT = Duration.ofSeconds(30);
    private final ObsApi obs;
    private final ObjectMapper json = new ObjectMapper();

    public SourceRecordService(ObsApi obs) { this.obs = obs; }

    public CompletableFuture<Boolean> isAvailable() {
        // Probe the plugin's registered vendor request directly. Some OBS/plugin
        // combinations return empty filter defaults even though the filter and
        // vendor API are loaded. A random, nonexistent source makes record_stop
        // harmless; Source Record answers with "source not found" when present.
        String probeName = "MCSR_AutoRecorder_Probe_" + UUID.randomUUID();
        return obs.request("CallVendorRequest", object("vendorName", VENDOR,
                        "requestType", "record_stop",
                        "requestData", object("source", probeName, "filter", probeName)))
                .thenApply(response -> {
                    JsonNode vendorResponse = response.path("responseData").path("responseData");
                    return !vendorResponse.path("success").asBoolean(true)
                            && "source not found".equalsIgnoreCase(vendorResponse.path("error").asText());
                });
    }

    public Handle start(UUID sessionId, UUID attemptId, String stream, String sourceName, String outputRoot) throws IOException {
        return start(sessionId, attemptId, stream, sourceName, outputRoot, ignored -> { });
    }

    public Handle start(UUID sessionId, UUID attemptId, String stream, String sourceName, String outputRoot,
                        Consumer<Handle> preparedListener) throws IOException {
        return start(sessionId, attemptId, stream, sourceName, outputRoot, null, preparedListener);
    }

    public Handle start(UUID sessionId, UUID attemptId, String stream, String sourceName, String outputRoot,
                        String requestedStem, Consumer<Handle> preparedListener) throws IOException {
        if (sourceName == null || sourceName.isBlank()) throw new IOException("OBS source name is not configured for " + stream);
        Path root = Path.of(outputRoot).toAbsolutePath().normalize();
        Files.createDirectories(root);
        root = root.toRealPath();

        String safeStream = stream.toLowerCase(Locale.ROOT);
        Path directory = root.resolve(safeStream).normalize();
        if (!directory.startsWith(root)) throw new IOException("Recording directory escaped its configured folder");
        Files.createDirectories(directory);
        directory = directory.toRealPath();
        if (!directory.startsWith(root)) throw new IOException("Recording folder resolves outside the configured directory");

        UUID id = UUID.randomUUID();
        String baseStem = requestedStem == null || requestedStem.isBlank()
                ? "MCSR_" + safeStream.toUpperCase(Locale.ROOT) + "_" + id.toString().replace("-", "")
                : safeFilenameStem(requestedStem);
        String stem = baseStem;
        String filterName = "MCSR_AutoRecorder_" + id.toString().replace("-", "");
        Instant startedAt = Instant.now();

        JsonNode defaultsResponse = request("GetSourceFilterDefaultSettings", object("filterKind", FILTER_KIND));
        JsonNode defaults = defaultsResponse.path("responseData").path("defaultFilterSettings");
        if (!defaults.isObject() || defaults.isEmpty())
            throw new IOException("OBS Source Record filter is unavailable; install/enable the Source Record plugin");
        String extension = extension(defaults.path("rec_format").asText(""));
        if (extension == null) throw new IOException("Source Record has an unsupported output format: " + defaults.path("rec_format").asText("unknown"));

        ObjectNode filterSettings = ((ObjectNode) defaults).deepCopy();
        filterSettings.put("path", directory.toString());
        filterSettings.put("filename_formatting", stem);
        filterSettings.put("remove_after_record", true);
        filterSettings.put("record_mode", 0);
        // Source Record documents crackling when capturing a source's own audio.
        // Use OBS's shared mixer instead; track numbers in the plugin are 1-based.
        // Track 1 is also the usual track for the regular OBS recording output.
        filterSettings.put("different_audio", true);
        filterSettings.put("audio_track", 1);

        Path expectedFile;
        int collisionSuffix = 1;
        while (true) {
            expectedFile = directory.resolve(stem + "." + extension).normalize();
            if (!expectedFile.startsWith(directory)) throw new IOException("Generated recording path escaped its output folder");
            if (!Files.exists(expectedFile)) break;
            if (++collisionSuffix > 10_000) throw new IOException("Could not find an unused recording filename in " + directory);
            stem = baseStem + " (" + collisionSuffix + ")";
        }

        ObjectNode create = object("sourceName", sourceName, "filterName", filterName,
                "filterKind", FILTER_KIND, "filterSettings", filterSettings);
        request("CreateSourceFilter", create);
        Handle handle = new Handle(id, sessionId, attemptId, stream, sourceName, filterName,
                startedAt, expectedFile, directory);
        try { preparedListener.accept(handle); }
        catch (RuntimeException failure) {
            try { request("RemoveSourceFilter", object("sourceName", sourceName, "filterName", filterName)); }
            catch (IOException cleanupError) { failure.addSuppressed(cleanupError); }
            throw new IOException("Could not persist recording metadata before starting OBS", failure);
        }

        ObjectNode startData = object("source", sourceName, "filter", filterName, "filename", stem);
        try {
            JsonNode response = request("CallVendorRequest", object("vendorName", VENDOR,
                    "requestType", "record_start", "requestData", startData));
            JsonNode vendorResponse = response.path("responseData").path("responseData");
            if (!vendorResponse.path("success").asBoolean(false)) {
                String message = vendorResponse.path("error").asText("Source Record did not accept the start request");
                throw new IOException(message);
            }
            awaitStarted(expectedFile, START_TIMEOUT);
            return handle;
        } catch (Exception failure) {
            boolean stopped = stopBestEffort(sourceName, filterName);
            throw new StartFailure("Could not verify that OBS started " + stream + " recording. File: " + expectedFile,
                    unwrap(failure), handle, stopped);
        }
    }

    public Path stop(Handle handle) throws IOException {
        boolean stopAccepted = false;
        boolean filterMissing = false;
        try {
            JsonNode response = request("CallVendorRequest", object("vendorName", VENDOR,
                    "requestType", "record_stop", "requestData", object("source", handle.sourceName(), "filter", handle.filterName())));
            JsonNode vendorResponse = response.path("responseData").path("responseData");
            if (!vendorResponse.path("success").asBoolean(false)) {
                String error = vendorResponse.path("error").asText("Source Record did not accept the stop request");
                // Source Record can remove its filter automatically after an output stops.
                // In that case OBS answers "filter not found" to a later stop request even
                // though the video may already be complete. Only accept that state after
                // independently verifying that the expected file exists and is stable.
                if (isMissingFilter(error)) filterMissing = true;
                else throw new IOException(error);
            } else stopAccepted = true;
        } catch (IOException error) {
            throw new IOException("OBS did not confirm stopping the owned Source Record output; preserving it for recovery", error);
        }
        // A successful stop means the plugin output is no longer writing. If it
        // never created the expected file, waiting 30 seconds and retrying this
        // dead filter only blocks the next wall interval.
        if ((stopAccepted || filterMissing) && !Files.exists(handle.file()))
            throw new OutputMissingException("Source Record stopped, but no video file was created: " + handle.file());
        try {
            awaitStable(handle.file(), FINALIZE_TIMEOUT);
        } catch (IOException failure) {
            if ((stopAccepted || filterMissing) && !Files.exists(handle.file()))
                throw new OutputMissingException("Source Record stopped, but its video file is missing: " + handle.file(), failure);
            throw failure;
        }
        return handle.file();
    }

    private boolean stopBestEffort(String source, String filter) {
        try {
            JsonNode response = request("CallVendorRequest", object("vendorName", VENDOR, "requestType", "record_stop",
                    "requestData", object("source", source, "filter", filter)));
            return response.path("responseData").path("responseData").path("success").asBoolean(false);
        } catch (IOException error) {
            return false;
        }
    }

    private JsonNode request(String type, ObjectNode data) throws IOException {
        try { return obs.request(type, data).join(); }
        catch (CompletionException e) {
            Throwable cause = unwrap(e);
            throw new IOException(cause.getMessage() == null ? type + " failed" : cause.getMessage(), cause);
        }
    }

    private static void awaitStarted(Path file, Duration timeout) throws IOException {
        long end = System.nanoTime() + timeout.toNanos();
        long lastSize = -1;
        int growthSamples = 0;
        while (System.nanoTime() < end) {
            if (validManagedFile(file)) {
                long size = Files.size(file);
                if (size > 0 && size > lastSize) growthSamples++;
                else if (size <= 0) growthSamples = 0;
                if (growthSamples >= 2) return;
                lastSize = size;
            }
            pause(400);
        }
        throw new IOException("OBS accepted the start request, but the video file did not keep growing; check that the configured Minecraft source is visible and active");
    }

    private static boolean isMissingFilter(String error) {
        String normalized = error == null ? "" : error.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("filter not found") || normalized.contains("failed to find filter");
    }

    private static void awaitStable(Path file, Duration timeout) throws IOException {
        long end = System.nanoTime() + timeout.toNanos();
        long lastSize = -1;
        int stableSamples = 0;
        while (System.nanoTime() < end) {
            if (validManagedFile(file)) {
                long size = Files.size(file);
                if (size > 0 && size == lastSize) stableSamples++;
                else stableSamples = 0;
                lastSize = size;
                if (stableSamples >= 4) return;
            }
            pause(500);
        }
        throw new IOException("Recording file did not appear or finish writing: " + file);
    }

    private static boolean validManagedFile(Path file) throws IOException {
        if (!Files.isRegularFile(file) || Files.isSymbolicLink(file)) return false;
        Path parent = file.getParent().toRealPath();
        return file.toAbsolutePath().normalize().getParent().equals(parent);
    }

    private static void pause(long millis) throws IOException {
        try { Thread.sleep(millis); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("Recording operation interrupted", e); }
    }

    private ObjectNode object(Object... pairs) {
        ObjectNode node = json.createObjectNode();
        for (int i = 0; i < pairs.length; i += 2) {
            String key = (String) pairs[i];
            Object value = pairs[i + 1];
            if (value instanceof String text) node.put(key, text);
            else if (value instanceof Boolean bool) node.put(key, bool);
            else if (value instanceof Integer number) node.put(key, number);
            else if (value instanceof JsonNode jsonNode) node.set(key, jsonNode);
        }
        return node;
    }

    private static String extension(String format) {
        return switch (format) {
            case "flv" -> "flv";
            case "mp4", "hybrid_mp4", "fragmented_mp4" -> "mp4";
            case "mov", "hybrid_mov", "fragmented_mov" -> "mov";
            case "mkv" -> "mkv";
            case "hls" -> "m3u8";
            case "mpegts" -> "ts";
            default -> null;
        };
    }

    private static String safeFilenameStem(String requested) throws IOException {
        String safe = requested.trim().replaceAll("[<>:\"/\\\\|?*\\p{Cntrl}]", "_")
                .replaceAll("[. ]+$", "");
        if (safe.isBlank() || safe.equals(".") || safe.equals(".."))
            throw new IOException("Recording filename template produced an empty or invalid name");
        if (safe.length() > 120) safe = safe.substring(0, 120).replaceAll("[. ]+$", "");
        return safe;
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while ((current instanceof CompletionException || current.getCause() != null) && current.getCause() != null)
            current = current.getCause();
        return current;
    }

    public record Handle(UUID id, UUID sessionId, UUID attemptId, String stream,
                         String sourceName, String filterName, Instant startedAt,
                         Path file, Path directory) { }

    public static final class StartFailure extends IOException {
        private final Handle handle;
        private final boolean stopConfirmed;
        public StartFailure(String message, Throwable cause, Handle handle, boolean stopConfirmed) {
            super(message, cause);
            this.handle = handle;
            this.stopConfirmed = stopConfirmed;
        }
        public Handle handle() { return handle; }
        public boolean stopConfirmed() { return stopConfirmed; }
    }

    /** OBS confirmed that the output is stopped, but its expected file is absent. */
    public static final class OutputMissingException extends IOException {
        public OutputMissingException(String message) { super(message); }
        public OutputMissingException(String message, Throwable cause) { super(message, cause); }
    }
}
