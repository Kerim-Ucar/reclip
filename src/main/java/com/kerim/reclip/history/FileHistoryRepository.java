package com.kerim.reclip.history;

import com.kerim.reclip.config.LocalEncryption;

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
    private final LocalEncryption encryption;
    private volatile boolean persistenceEnabled;

    public FileHistoryRepository(Path storagePath, boolean persistenceEnabled) {
        this(storagePath, persistenceEnabled, LocalEncryption.disabled());
    }

    public FileHistoryRepository(Path storagePath, boolean persistenceEnabled, LocalEncryption encryption) {
        this.storagePath = Objects.requireNonNull(storagePath, "storagePath must not be null");
        this.encryption = Objects.requireNonNull(encryption, "encryption must not be null");
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
            byte[] raw = Files.readAllBytes(storagePath);
            String decoded = encryption.decrypt(raw);
            for (String line : decoded.split("\\R")) {
                parseLine(line).ifPresent(delegate::save);
            }
        } catch (IOException | IllegalStateException ignored) {
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
                if (!entry.sensitive()) {
                    lines.add(formatLine(entry));
                }
            }
            byte[] payload = encryption.encrypt(String.join("\n", lines));
            Path tempFile = storagePath.resolveSibling(storagePath.getFileName() + ".tmp");
            Files.write(tempFile, payload);
            LocalEncryption.setOwnerOnlyPermissions(tempFile);
            Files.move(tempFile, storagePath, StandardCopyOption.REPLACE_EXISTING);
            LocalEncryption.setOwnerOnlyPermissions(storagePath);
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
        String encodedLabel = Base64.getEncoder()
                .encodeToString(entry.label().getBytes(StandardCharsets.UTF_8));
        return entry.id() + "\t"
                + entry.contentType().name() + "\t"
                + entry.createdAt().toEpochMilli() + "\t"
                + entry.lastUsedAt().toEpochMilli() + "\t"
                + entry.pinned() + "\t"
                + encodedContent + "\t"
                + encodedLabel;
    }

    private static Optional<ClipboardEntry> parseLine(String line) {
        if (line == null || line.isBlank()) {
            return Optional.empty();
        }
        String[] parts = line.split("\t", 7);
        if (parts.length < 6) {
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
            String label = parts.length == 7
                    ? new String(Base64.getDecoder().decode(parts[6]), StandardCharsets.UTF_8)
                    : "";
            return Optional.of(new ClipboardEntry(id, content, type, createdAt, lastUsedAt, pinned, label));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
