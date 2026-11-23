package com.kerim.reclip.ipc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReclipIpcTest {

    @Test
    void encodesAndDecodesNativeMessagingFrames() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        NativeMessagingHost.writeMessage(out, "{\"action\":\"show\"}");

        ByteArrayInputStream in = new ByteArrayInputStream(out.toByteArray());
        String decoded = NativeMessagingHost.readMessage(in);
        assertEquals("{\"action\":\"show\"}", decoded);
    }

    @Test
    void ipcServerTriggersShowCallbackOverUnixDomainSocket(@TempDir Path tempDir) throws Exception {
        Path socketPath = tempDir.resolve("reclip-test.sock");
        CountDownLatch latch = new CountDownLatch(1);

        try (ReclipIpcServer server = new ReclipIpcServer(socketPath, latch::countDown)) {
            server.start();
            assertTrue(ReclipIpcServer.sendShow(socketPath));
            assertTrue(latch.await(2, TimeUnit.SECONDS));
        }

        assertFalse(ReclipIpcServer.sendShow(socketPath));
    }
}
