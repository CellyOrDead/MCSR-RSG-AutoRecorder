package dev.autorecorder.obs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/** Minimal OBS WebSocket v5 client with explicit request status handling. */
public final class ObsClient implements ObsApi, AutoCloseable {
    private final ObjectMapper json = new ObjectMapper();
    private final Map<String, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final HttpClient http = HttpClient.newHttpClient();
    private volatile WebSocket socket;
    private volatile String password;
    private volatile CompletableFuture<Void> identified;
    private volatile String handshakeStage = "starting connection";

    public CompletableFuture<Void> connect(String host, int port, String password) {
        if (host == null || host.isBlank()) return CompletableFuture.failedFuture(new IllegalArgumentException("OBS host is empty"));
        if (port < 1 || port > 65535) return CompletableFuture.failedFuture(new IllegalArgumentException("OBS port is invalid"));

        WebSocket old = socket;
        socket = null;
        if (old != null) old.sendClose(WebSocket.NORMAL_CLOSURE, "reconnecting");
        failPending(new IllegalStateException("OBS connection replaced"));
        this.password = password == null ? "" : password;
        handshakeStage = "opening WebSocket";
        CompletableFuture<Void> ready = new CompletableFuture<>();
        identified = ready;

        return http.newWebSocketBuilder().buildAsync(URI.create("ws://" + host.trim() + ":" + port), new WebSocket.Listener() {
            private final StringBuilder buffer = new StringBuilder();

            @Override public void onOpen(WebSocket webSocket) {
                socket = webSocket;
                handshakeStage = "socket opened; waiting for OBS Hello";
                webSocket.request(1);
            }

            @Override public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                buffer.append(data);
                if (last) {
                    try { handle(json.readTree(buffer.toString())); }
                    catch (Exception e) {
                        if (socket == webSocket) socket = null;
                        webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "OBS handshake failed");
                        ready.completeExceptionally(new IllegalStateException("Could not process OBS WebSocket message", e));
                    } finally { buffer.setLength(0); }
                }
                webSocket.request(1);
                return null;
            }

            @Override public void onError(WebSocket webSocket, Throwable error) {
                socket = null;
                ready.completeExceptionally(error);
                failPending(error);
            }

            @Override public CompletionStage<?> onClose(WebSocket webSocket, int code, String reason) {
                if (socket == webSocket) socket = null;
                IllegalStateException error = new IllegalStateException("OBS closed the WebSocket before or during the session (code " + code + "): " + reason);
                ready.completeExceptionally(error);
                failPending(error);
                return null;
            }
        }).thenCompose(webSocket -> {
            socket = webSocket;
            return ready.orTimeout(8, TimeUnit.SECONDS).exceptionallyCompose(error ->
                    CompletableFuture.failedFuture(new IllegalStateException(
                            "OBS WebSocket handshake timed out (" + handshakeStage + ")", error)))
                    .whenComplete((ignored, error) -> {
                        if (error != null && socket == webSocket) {
                            socket = null;
                            webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "OBS handshake failed");
                        }
                    });
        });
    }

    private void handle(JsonNode message) throws Exception {
        int opcode = message.path("op").asInt();
        JsonNode data = message.path("d");
        if (opcode == 0) {
            handshakeStage = "OBS Hello received; sending Identify/authentication";
            ObjectNode identify = json.createObjectNode();
            identify.put("rpcVersion", 1);
            identify.put("eventSubscriptions", 0);
            JsonNode auth = data.path("authentication");
            if (!auth.isMissingNode() && !auth.isNull()) {
                if (password == null || password.isBlank()) throw new IllegalStateException("OBS requires a WebSocket password");
                identify.put("authentication", auth(password, auth.path("salt").asText(), auth.path("challenge").asText()));
            }
            send(1, identify).exceptionally(error -> {
                CompletableFuture<Void> ready = identified;
                if (ready != null) ready.completeExceptionally(error);
                return null;
            });
        } else if (opcode == 2) {
            handshakeStage = "authenticated";
            CompletableFuture<Void> ready = identified;
            if (ready != null) ready.complete(null);
        } else if (opcode == 7) {
            String id = data.path("requestId").asText();
            CompletableFuture<JsonNode> future = pending.remove(id);
            if (future == null) return;
            JsonNode status = data.path("requestStatus");
            if (!status.path("result").asBoolean(false)) {
                String comment = status.path("comment").asText("request rejected");
                String code = status.path("code").asText("");
                future.completeExceptionally(new IllegalStateException("OBS request failed"
                        + (code.isBlank() ? "" : " (" + code + ")") + ": " + comment));
            } else {
                future.complete(data);
            }
        }
    }

    @Override public CompletableFuture<JsonNode> request(String type, ObjectNode requestData) {
        WebSocket webSocket = socket;
        if (webSocket == null) return CompletableFuture.failedFuture(new IllegalStateException("OBS is disconnected"));

        String requestId = UUID.randomUUID().toString();
        ObjectNode request = requestPayload(json, type, requestId, requestData);

        CompletableFuture<JsonNode> response = new CompletableFuture<>();
        pending.put(requestId, response);
        send(6, request).whenComplete((ignored, error) -> {
            if (error != null) {
                pending.remove(requestId);
                response.completeExceptionally(error);
            }
        });
        response.orTimeout(8, TimeUnit.SECONDS).whenComplete((ignored, error) -> pending.remove(requestId));
        return response;
    }

    static ObjectNode requestPayload(ObjectMapper mapper, String type, String requestId, ObjectNode requestData) {
        ObjectNode request = mapper.createObjectNode();
        request.put("requestType", type);
        request.put("requestId", requestId);
        if (requestData != null) request.set("requestData", requestData);
        return request;
    }

    public CompletableFuture<JsonNode> callVendor(String vendorName, String requestType, ObjectNode vendorData) {
        ObjectNode request = json.createObjectNode();
        request.put("vendorName", vendorName);
        request.put("requestType", requestType);
        if (vendorData != null) request.set("requestData", vendorData);
        return request("CallVendorRequest", request);
    }

    public CompletableFuture<Boolean> recording() {
        return request("GetRecordStatus", null).thenApply(r -> r.path("responseData").path("outputActive").asBoolean(false));
    }

    public CompletableFuture<Boolean> start() {
        return recording().thenCompose(active -> active ? CompletableFuture.completedFuture(true)
                : request("StartRecord", null).thenCompose(ignored -> recording()));
    }

    public CompletableFuture<Boolean> stop() {
        return recording().thenCompose(active -> !active ? CompletableFuture.completedFuture(false)
                : request("StopRecord", null).thenCompose(ignored -> recording()));
    }

    public boolean isConnected() { return socket != null; }

    private CompletableFuture<Void> send(int opcode, JsonNode data) {
        WebSocket webSocket = socket;
        if (webSocket == null) return CompletableFuture.failedFuture(new IllegalStateException("OBS is disconnected"));
        try {
            ObjectNode message = json.createObjectNode();
            message.put("op", opcode);
            message.set("d", data);
            return webSocket.sendText(json.writeValueAsString(message), true).thenApply(ignored -> null);
        } catch (Exception error) {
            return CompletableFuture.failedFuture(error);
        }
    }

    private void failPending(Throwable error) {
        pending.values().forEach(future -> future.completeExceptionally(error));
        pending.clear();
    }

    private static String auth(String password, String salt, String challenge) throws Exception {
        String secret = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256")
                .digest((password + salt).getBytes(StandardCharsets.UTF_8)));
        return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256")
                .digest((secret + challenge).getBytes(StandardCharsets.UTF_8)));
    }

    @Override public void close() {
        WebSocket webSocket = socket;
        socket = null;
        if (webSocket != null) webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "application shutdown");
        failPending(new IllegalStateException("OBS connection closed"));
    }
}
