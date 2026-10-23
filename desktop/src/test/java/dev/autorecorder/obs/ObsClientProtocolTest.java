package dev.autorecorder.obs;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ObsClientProtocolTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void requestFieldsAreDirectlyInsideTheWebSocketDataPayload() {
        ObjectNode details = mapper.createObjectNode().put("vendorName", "source-record");
        ObjectNode payload = ObsClient.requestPayload(mapper, "CallVendorRequest", "request-1", details);

        assertEquals("CallVendorRequest", payload.path("requestType").asText());
        assertEquals("request-1", payload.path("requestId").asText());
        assertEquals("source-record", payload.path("requestData").path("vendorName").asText());
        assertFalse(payload.path("requestData").has("requestType"));
    }

    @Test void requestWithoutDetailsDoesNotAddAnExtraRequestDataWrapper() {
        ObjectNode payload = ObsClient.requestPayload(mapper, "GetVersion", "request-2", null);

        assertEquals("GetVersion", payload.path("requestType").asText());
        assertEquals("request-2", payload.path("requestId").asText());
        assertFalse(payload.has("requestData"));
    }
}
