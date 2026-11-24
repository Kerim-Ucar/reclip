package com.kerim.reclip.clipboard;

import com.kerim.reclip.history.ContentType;
import org.junit.jupiter.api.Test;

import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.ClipboardOwner;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AwtClipboardProviderTest {

    @Test
    void retriesWriteAndReadOnTransientWindowsClipboardContention() {
        ContendedClipboard clipboard = new ContendedClipboard(2, 1);
        AwtClipboardProvider provider = new AwtClipboardProvider(clipboard);

        assertTrue(provider.writeText("recovered-snippet"));
        assertEquals(3, clipboard.writeCalls.get());

        assertEquals(Optional.of("recovered-snippet"), provider.readText());
        assertEquals(2, clipboard.readCalls.get());
    }

    @Test
    void returnsFalseOrEmptyWhenClipboardRemainsLocked() {
        ContendedClipboard lockedClipboard = new ContendedClipboard(10, 10);
        AwtClipboardProvider provider = new AwtClipboardProvider(lockedClipboard);

        assertFalse(provider.writeText("blocked"));
        assertEquals(Optional.empty(), provider.readText());
    }

    @Test
    void writesAndReadsImageAndFilesPayloads(@TempDir Path tempDir) throws IOException {
        Clipboard clipboard = new Clipboard("test-typed-clipboard");
        AwtClipboardProvider provider = new AwtClipboardProvider(clipboard, tempDir.resolve("images"));

        BufferedImage img = new BufferedImage(4, 3, BufferedImage.TYPE_INT_ARGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(img, "png", out));
        String encodedPng = Base64.getEncoder().encodeToString(out.toByteArray());

        assertTrue(provider.writeContent(ContentType.IMAGE, encodedPng));
        ClipboardPayload imagePayload = provider.readContent().orElseThrow();
        assertEquals(ContentType.IMAGE, imagePayload.contentType());
        assertEquals("Image (4×3)", imagePayload.displayLabel());
        assertTrue(Files.isRegularFile(Path.of(imagePayload.content())));

        // Verify file-backed image can be written back to clipboard without size caps
        assertTrue(provider.writeContent(ContentType.IMAGE, imagePayload.content()));
        ClipboardPayload reReadImage = provider.readContent().orElseThrow();
        assertEquals(imagePayload.content(), reReadImage.content());

        String joinedFiles = Path.of("/tmp/alpha.txt") + "\n" + Path.of("/tmp/beta.png");
        assertTrue(provider.writeContent(ContentType.FILES, joinedFiles));
        ClipboardPayload filesPayload = provider.readContent().orElseThrow();
        assertEquals(ContentType.FILES, filesPayload.contentType());
        assertEquals("Files (2): alpha.txt, beta.png", filesPayload.displayLabel());
    }

    private static final class ContendedClipboard extends Clipboard {

        private final int writeFailuresBeforeSuccess;
        private final int readFailuresBeforeSuccess;
        private final AtomicInteger writeCalls = new AtomicInteger();
        private final AtomicInteger readCalls = new AtomicInteger();

        ContendedClipboard(int writeFailuresBeforeSuccess, int readFailuresBeforeSuccess) {
            super("test-contended-clipboard");
            this.writeFailuresBeforeSuccess = writeFailuresBeforeSuccess;
            this.readFailuresBeforeSuccess = readFailuresBeforeSuccess;
        }

        @Override
        public synchronized void setContents(Transferable contents, ClipboardOwner owner) {
            if (writeCalls.incrementAndGet() <= writeFailuresBeforeSuccess) {
                throw new IllegalStateException("cannot open system clipboard");
            }
            super.setContents(contents, owner);
        }

        @Override
        public synchronized boolean isDataFlavorAvailable(DataFlavor flavor) {
            if (readCalls.incrementAndGet() <= readFailuresBeforeSuccess) {
                throw new IllegalStateException("cannot open system clipboard");
            }
            return super.isDataFlavorAvailable(flavor);
        }
    }
}
