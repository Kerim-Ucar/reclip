package com.kerim.reclip.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SingleInstanceLockTest {

    @Test
    void preventsSecondInstanceWhileLockIsHeldAndReleasesOnClose(@TempDir Path tempDir) {
        Path lockFile = tempDir.resolve("reclip-test.lock");

        Optional<SingleInstanceLock> first = SingleInstanceLock.tryAcquire(lockFile);
        assertTrue(first.isPresent());

        try (SingleInstanceLock held = first.get()) {
            Optional<SingleInstanceLock> second = SingleInstanceLock.tryAcquire(lockFile);
            assertTrue(second.isEmpty());
        }

        Optional<SingleInstanceLock> reacquired = SingleInstanceLock.tryAcquire(lockFile);
        assertTrue(reacquired.isPresent());
        reacquired.get().close();
    }
}
