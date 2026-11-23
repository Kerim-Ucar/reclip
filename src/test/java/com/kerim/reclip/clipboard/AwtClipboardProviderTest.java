package com.kerim.reclip.clipboard;

import org.junit.jupiter.api.Test;

import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.ClipboardOwner;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

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
