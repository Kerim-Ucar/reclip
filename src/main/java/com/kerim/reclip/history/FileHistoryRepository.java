package com.kerim.reclip.history;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link HistoryRepository} backed by {@link InMemoryHistoryRepository} with optional
 * local file persistence across restarts (no external database required).
 */
public final class FileHistoryRepository implements HistoryRepository {

    private final InMemoryHistoryRepository delegate = new InMemoryHistoryRepository();
    private final Path storagePath;
    private volatile boolean persistenceEnabled;

    public FileHistoryRepository(Path storagePath, boolean persistenceEnabled) {
        this.storagePath = Objects.requireNonNull(storagePath, "storagePath must not be null");
        this.persistenceEnabled = persistenceEnabled;
        if (persistenceEnabled) {
            loadFromDisk();
        }
    }

    public static Path defaultHistoryPath() {
        return Path.of(System.getProperty("user.home"), ".reclip", "history.dat");
    }

    public synchronized void setPersistenceEnabled(boolean enabled) {
        this.persistenceEnabled = enabled;
        if (enabled) {
            flushToDisk();
        } else {
            deleteStorageFile();
        }
    }

    public boolean isPersistenceEnabled() {
        return persistenceEnabled;
    }

    @Override
    public synchronized void save(ClipboardEntry entry) {
        delegate.save(entry);
        if (persistenceEnabled) {
            flushToDisk();
        }
    }

    @Override
    public synchronized Optional<ClipboardEntry> findById(UUID id) {
        return delegate.findById(id);
    }

    @Override
    public synchronized Optional<ClipboardEntry> findByContent(ContentType contentType, String content) {
        return delegate.findByContent(contentType, content);
    }

    @Override
    public synchronized List<ClipboardEntry> findAll() {
        return delegate.findAll();
    }

    @Override
    public synchronized List<ClipboardEntry> search(String query) {
        return delegate.search(query);
    }

    @Override
    public synchronized boolean deleteById(UUID id) {
        boolean removed = delegate.deleteById(id);
        if (removed && persistenceEnabled) {
            flushToDisk();
        }
        return removed;
    }

    @Override
    public synchronized void clearUnpinned() {
        delegate.clearUnpinned();
        if (persistenceEnabled) {
            flushToDisk();
        }
    }

    @Override
    public synchronized int pruneUnpinned(int maxUnpinned) {
        int removed = delegate.pruneUnpinned(maxUnpinned);
        if (removed > 0 && persistenceEnabled) {
            flushToDisk();
        }
        return removed;
    }

    private void loadFromDisk() {
        if (!Files.exists(storagePath)) {
            return;
        }
        try {
            List<String> lines = Files.readAllLines(storagePath, StandardCharsets.UTF_8);
            for (String line : lines) {
                parseLine(line).ifPresent(delegate::save);
            }
        } catch (IOException ignored) {
            // Start cleanly if local history file is unreadable.
        }
    }

    private void flushToDisk() {
        try {
            Path parent = storagePath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            List<ClipboardEntry> entries = delegate.findAll();
            List<String> lines = new ArrayList<>(entries.size());
            for (ClipboardEntry entry : entries) {
                lines.add(formatLine(entry));
            }
            Path tempFile = storagePath.resolveSibling(storagePath.getFileName() + ".tmp");
            Files.write(tempFile, lines, StandardCharsets.UTF_8);
            Files.move(tempFile, storagePath, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
            // Avoid crashing background clipboard observer on transient disk errors.
        }
    }

    private void deleteStorageFile() {
        try {
            Files.deleteIfExists(storagePath);
        } catch (IOException ignored) {
            // Best-effort cleanup when disabling persistence.
        }
    }

    private static String formatLine(ClipboardEntry entry) {
        String encodedContent = Base64.getEncoder()
                .encodeToString(entry.content().getBytes(StandardCharsets.UTF_8));
        return entry.id() + "\t"
                + entry.contentType().name() + "\t"
                + entry.createdAt().toEpochMilli() + "\t"
                + entry.lastUsedAt().toEpochMilli() + "\t"
                + entry.pinned() + "\t"
                + encodedContent;
    }

    private static Optional<ClipboardEntry> parseLine(String line) {
        if (line == null || line.isBlank()) {
            return Optional.empty();
        }
        String[] parts = line.split("\t", 6);
        if (parts.length != 6) {
            return Optional.empty();
        }
        try {
            UUID id = UUID.fromString(parts[0]);
            ContentType type = ContentType.valueOf(parts[1]);
            Instant createdAt = Instant.ofEpochMilli(Long.parseLong(parts[2]));
            Instant lastUsedAt = Instant.ofEpochMilli(Long.parseLong(parts[3]));
            boolean pinned = Boolean.parseBoolean(parts[4]);
            String content = new String(Base64.getDecoder().decode(parts[5]), StandardCharsets.UTF_8);
            if (content.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new ClipboardEntry(id, content, type, createdAt, lastUsedAt, pinned));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
