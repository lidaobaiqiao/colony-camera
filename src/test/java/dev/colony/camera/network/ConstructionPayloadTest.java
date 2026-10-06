package dev.colony.camera.network;

import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.zip.GZIPOutputStream;
import static org.junit.jupiter.api.Assertions.*;

class ConstructionPayloadTest {
    @Test void chineseAndEmptyRequestsRoundTrip() {
        for (String text : new String[]{"", "{\"name\":\"殖民地道路\"}", "0123456789".repeat(20000)}) {
            byte[] encoded = ConstructionPayload.encode(text);
            assertTrue(encoded.length <= ConstructionPayload.MAX_WIRE);
            assertEquals(text, ConstructionPayload.decode(encoded));
        }
    }
    @Test void serverRejectsDamagedCompression() {
        assertThrows(IllegalArgumentException.class, () -> ConstructionPayload.decode(new byte[]{1, 2, 3}));
        assertThrows(IllegalArgumentException.class, () -> ConstructionPayload.decode(new byte[ConstructionPayload.MAX_WIRE + 1]));
    }
    @Test void decompressionCannotExceedJsonBudget() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(output)) {
            gzip.write("x".repeat(ConstructionPayload.MAX_JSON + 1).getBytes(StandardCharsets.UTF_8));
        }
        assertTrue(output.size() < ConstructionPayload.MAX_WIRE);
        assertThrows(IllegalArgumentException.class, () -> ConstructionPayload.decode(output.toByteArray()));
        assertThrows(IllegalArgumentException.class, () -> ConstructionPayload.encode("中".repeat(ConstructionPayload.MAX_JSON / 3 + 1)));
    }
    @Test void poorlyCompressingDataIsRejectedBeforeSending() {
        byte[] bytes = new byte[60000]; new Random(42).nextBytes(bytes);
        String text = java.util.Base64.getEncoder().encodeToString(bytes);
        assertThrows(IllegalArgumentException.class, () -> ConstructionPayload.encode(text));
    }
}
