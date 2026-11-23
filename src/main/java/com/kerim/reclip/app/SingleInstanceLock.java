package com.kerim.reclip.app;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.Optional;

/**
 * Advisory file-based single-instance lock to prevent multiple Reclip processes
 * from running simultaneously.
 */
public final class SingleInstanceLock implements AutoCloseable {

    private final FileChannel channel;
    private final FileLock lock;

    private SingleInstanceLock(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    /**
     * Attempts to acquire the default user-scoped instance lock.
     *
     * @return the acquired lock, or empty if another instance holds it
     */
    public static Optional<SingleInstanceLock> tryAcquireDefault() {
        Path lockFile = Path.of(System.getProperty("java.io.tmpdir"), "reclip-instance.lock");
        return tryAcquire(lockFile);
    }

    /**
     * Attempts to acquire an exclusive lock on the given lock file.
     *
     * @param lockFile path to the lock file
     * @return the acquired lock, or empty if another instance already holds it
     */
    public static Optional<SingleInstanceLock> tryAcquire(Path lockFile) {
        Objects.requireNonNull(lockFile, "lockFile must not be null");
        try {
            Path parent = lockFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            FileChannel channel = FileChannel.open(
                    lockFile,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE);
            try {
                FileLock lock = channel.tryLock();
                if (lock == null) {
                    channel.close();
                    return Optional.empty();
                }
                return Optional.of(new SingleInstanceLock(channel, lock));
            } catch (OverlappingFileLockException | IOException e) {
                channel.close();
                return Optional.empty();
            }
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    @Override
    public void close() {
        try {
            if (lock.isValid()) {
                lock.release();
            }
        } catch (IOException ignored) {
            // Best-effort cleanup on shutdown.
        }
        try {
            channel.close();
        } catch (IOException ignored) {
            // Best-effort cleanup on shutdown.
        }
    }
}
