package com.kerim.reclip.ipc;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * Chrome Native Messaging host entrypoint. Reads length-prefixed JSON messages
 * from {@code stdin}, forwards {@code SHOW} requests to the running Reclip
 * instance over a Unix domain socket, and writes a length-prefixed JSON reply.
 */
public final class NativeMessagingHost {

    private static final int MAX_MESSAGE_BYTES = 1024 * 1024;

    private NativeMessagingHost() {
    }

    public static void main(String[] args) throws IOException {
        Path socketPath = ReclipIpcServer.defaultSocketPath();
        String message = readMessage(System.in);
        if (message == null) {
            return;
        }
        boolean ok = ReclipIpcServer.sendShow(socketPath);
        String response = ok
                ? "{\"ok\":true}"
                : "{\"ok\":false,\"error\":\"Reclip is not running\"}";
        writeMessage(System.out, response);
    }

    static String readMessage(InputStream in) throws IOException {
        byte[] lengthBytes = in.readNBytes(4);
        if (lengthBytes.length == 0) {
            return null;
        }
        if (lengthBytes.length < 4) {
            throw new EOFException("Truncated native messaging header");
        }
        int length = ByteBuffer.wrap(lengthBytes).order(ByteOrder.LITTLE_ENDIAN).getInt();
        if (length < 0 || length > MAX_MESSAGE_BYTES) {
            throw new IOException("Invalid native messaging length: " + length);
        }
        byte[] payload = in.readNBytes(length);
        if (payload.length < length) {
            throw new EOFException("Truncated native messaging payload");
        }
        return new String(payload, StandardCharsets.UTF_8);
    }

    static void writeMessage(OutputStream out, String json) throws IOException {
        byte[] payload = json.getBytes(StandardCharsets.UTF_8);
        byte[] header = ByteBuffer.allocate(4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(payload.length)
                .array();
        out.write(header);
        out.write(payload);
        out.flush();
    }
}
