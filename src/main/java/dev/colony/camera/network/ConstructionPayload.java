package dev.colony.camera.network;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Vanilla serverbound custom payloads allow only 32767 bytes, including channel headers. */
public final class ConstructionPayload {
    public static final int MAX_JSON = 262144;
    public static final int MAX_WIRE = 32000;
    private ConstructionPayload() {}

    public static byte[] encode(String json) {
        byte[] raw = json.getBytes(StandardCharsets.UTF_8);
        if (raw.length > MAX_JSON) throw new IllegalArgumentException("large");
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (GZIPOutputStream gzip = new GZIPOutputStream(output)) { gzip.write(raw); }
            byte[] compressed = output.toByteArray();
            if (compressed.length > MAX_WIRE) throw new IllegalArgumentException("large");
            return compressed;
        } catch (IOException failure) { throw new IllegalArgumentException("Invalid construction payload", failure); }
    }

    public static String decode(byte[] compressed) {
        if (compressed.length > MAX_WIRE) throw new IllegalArgumentException("large");
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            for (int length; (length = gzip.read(buffer)) != -1;) {
                if (output.size() + length > MAX_JSON) throw new IllegalArgumentException("large");
                output.write(buffer, 0, length);
            }
            return output.toString(StandardCharsets.UTF_8);
        } catch (IOException failure) { throw new IllegalArgumentException("Invalid construction payload", failure); }
    }
}
