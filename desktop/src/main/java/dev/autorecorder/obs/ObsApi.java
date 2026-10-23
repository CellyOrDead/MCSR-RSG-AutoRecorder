package dev.autorecorder.obs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.concurrent.CompletableFuture;

/** Small seam used by OBS integration services and their tests. */
public interface ObsApi {
    CompletableFuture<JsonNode> request(String type, ObjectNode requestData);
}
