package com.kerim.reclip.clipboard;

import com.kerim.reclip.history.ContentType;
import com.kerim.reclip.platform.macos.MacOsPasteboard;
import com.kerim.reclip.platform.windows.WindowsClipboard;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.HeadlessException;
import java.awt.Image;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * {@link ClipboardProvider} backed by {@link java.awt.datatransfer.Clipboard}.
 */
public class AwtClipboardProvider implements ClipboardProvider {

    private static final int READ_MAX_ATTEMPTS = 2;
    private static final int WRITE_MAX_ATTEMPTS = 4;
    private static final long CONTENTION_BACKOFF_MS = 15L;

    private final Clipboard clipboard;
    private final Path imageCacheDir;
    private final boolean systemClipboard;
    private long lastMacOsChangeCount = Long.MIN_VALUE;
    private long lastWindowsSequence = Long.MIN_VALUE;

    /**
     * Creates a provider using the default system clipboard and a session temp image cache.
     */
    public AwtClipboardProvider() {
        this(Toolkit.getDefaultToolkit().getSystemClipboard(), createDefaultImageCacheDir(), true);
    }

    /**
     * Creates a provider using the given AWT clipboard instance.
     *
     * @param clipboard the clipboard to read from and write to
     */
    public AwtClipboardProvider(Clipboard clipboard) {
        this(clipboard, createDefaultImageCacheDir(), false);
    }

    /**
     * Creates a provider using the given AWT clipboard instance and image cache directory.
     *
     * @param clipboard     the clipboard to read from and write to
     * @param imageCacheDir directory used to store captured clipboard images as PNG files
     */
    public AwtClipboardProvider(Clipboard clipboard, Path imageCacheDir) {
        this(clipboard, imageCacheDir, false);
    }

    private AwtClipboardProvider(Clipboard clipboard, Path imageCacheDir, boolean systemClipboard) {
        this.clipboard = Objects.requireNonNull(clipboard, "clipboard must not be null");
        this.imageCacheDir = Objects.requireNonNull(imageCacheDir, "imageCacheDir must not be null");
        this.systemClipboard = systemClipboard;
    }

    private static Path createDefaultImageCacheDir() {
        try {
            Path dir = Files.createTempDirectory("reclip-images-");
            dir.toFile().deleteOnExit();
            return dir;
        } catch (IOException e) {
            return Path.of(System.getProperty("java.io.tmpdir"), "reclip-images");
        }
    }

    /**
     * Deletes cached session image files created by this provider.
     */
    public void clearImageCache() {
        if (!Files.exists(imageCacheDir)) {
            return;
        }
        try (var stream = Files.list(imageCacheDir)) {
            stream.forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Best-effort cleanup.
                }
            });
            Files.deleteIfExists(imageCacheDir);
        } catch (IOException ignored) {
            // Best-effort cleanup.
        }
    }

    @Override
    public Optional<String> readText() {
        for (int attempt = 1; attempt <= READ_MAX_ATTEMPTS; attempt++) {
            try {
                if (!clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) {
                    return Optional.empty();
                }
                Object data = clipboard.getData(DataFlavor.stringFlavor);
                if (data instanceof String text) {
                    return Optional.of(text);
                }
                return Optional.empty();
            } catch (IllegalStateException e) {
                if (attempt == READ_MAX_ATTEMPTS || !sleepQuietly(CONTENTION_BACKOFF_MS)) {
                    return Optional.empty();
                }
            } catch (UnsupportedFlavorException | IOException | HeadlessException | SecurityException e) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    @Override
    public boolean writeText(String text) {
        Objects.requireNonNull(text, "text must not be null");
        return setClipboardContents(new StringSelection(text));
    }

    @Override
    public Optional<ClipboardPayload> readContent() {
        boolean sensitive = false;
        long currentMacOsCount = Long.MIN_VALUE;
        boolean hasMacOsCount = false;
        long currentWindowsSeq = Long.MIN_VALUE;
        boolean hasWindowsSeq = false;
        if (systemClipboard) {
            var changeCount = MacOsPasteboard.changeCount();
            if (changeCount.isPresent()) {
                currentMacOsCount = changeCount.getAsLong();
                if (currentMacOsCount == lastMacOsChangeCount) {
                    return Optional.empty();
                }
                hasMacOsCount = true;
            } else {
                var winSeq = WindowsClipboard.sequenceNumber();
                if (winSeq.isPresent()) {
                    currentWindowsSeq = winSeq.getAsLong();
                    if (currentWindowsSeq == lastWindowsSequence) {
                        return Optional.empty();
                    }
                    hasWindowsSeq = true;
                }
            }
            sensitive = MacOsPasteboard.hasConcealedContent()
                    || MacOsPasteboard.isPasswordManagerFrontmost()
                    || WindowsClipboard.hasConcealedContent()
                    || WindowsClipboard.isPasswordManagerFrontmost();
        }
        for (int attempt = 1; attempt <= READ_MAX_ATTEMPTS; attempt++) {
            try {
                if (clipboard.isDataFlavorAvailable(DataFlavor.javaFileListFlavor)) {
                    Object data = clipboard.getData(DataFlavor.javaFileListFlavor);
                    Optional<ClipboardPayload> filesPayload = toFilesPayload(data);
                    if (filesPayload.isPresent()) {
                        if (hasMacOsCount) {
                            lastMacOsChangeCount = currentMacOsCount;
                        }
                        if (hasWindowsSeq) {
                            lastWindowsSequence = currentWindowsSeq;
                        }
                        return Optional.of(filesPayload.get().withSensitive(sensitive));
                    }
                }
                if (clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) {
                    Object data = clipboard.getData(DataFlavor.stringFlavor);
                    if (data instanceof String text && !text.isEmpty()) {
                        if (hasMacOsCount) {
                            lastMacOsChangeCount = currentMacOsCount;
                        }
                        if (hasWindowsSeq) {
                            lastWindowsSequence = currentWindowsSeq;
                        }
                        return Optional.of(new ClipboardPayload(ContentType.TEXT, text, "", sensitive));
                    }
                }
                if (clipboard.isDataFlavorAvailable(DataFlavor.imageFlavor)) {
                    Object data = clipboard.getData(DataFlavor.imageFlavor);
                    Optional<ClipboardPayload> imagePayload = toImagePayload(data);
                    if (imagePayload.isPresent()) {
                        if (hasMacOsCount) {
                            lastMacOsChangeCount = currentMacOsCount;
                        }
                        if (hasWindowsSeq) {
                            lastWindowsSequence = currentWindowsSeq;
                        }
                        return Optional.of(imagePayload.get().withSensitive(sensitive));
                    }
                }
                if (systemClipboard) {
                    Optional<String> nativeText = MacOsPasteboard.readPlainText();
                    if (nativeText.isPresent()) {
                        if (hasMacOsCount) {
                            lastMacOsChangeCount = currentMacOsCount;
                        }
                        return Optional.of(new ClipboardPayload(ContentType.TEXT, nativeText.get(), "", sensitive));
                    }
                }
                if (hasMacOsCount) {
                    lastMacOsChangeCount = currentMacOsCount;
                }
                if (hasWindowsSeq) {
                    lastWindowsSequence = currentWindowsSeq;
                }
                return Optional.empty();
            } catch (IllegalStateException e) {
                if (attempt == READ_MAX_ATTEMPTS || !sleepQuietly(CONTENTION_BACKOFF_MS)) {
                    return Optional.empty();
                }
            } catch (UnsupportedFlavorException | IOException | HeadlessException | SecurityException e) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    @Override
    public boolean writeContent(ContentType contentType, String content) {
        Objects.requireNonNull(contentType, "contentType must not be null");
        Objects.requireNonNull(content, "content must not be null");
        return switch (contentType) {
            case TEXT -> writeText(content);
            case FILES -> writeFiles(content);
            case IMAGE -> writeImage(content);
        };
    }

    private boolean writeFiles(String joinedPaths) {
        List<File> files = new ArrayList<>();
        for (String line : joinedPaths.split("\\R")) {
            if (!line.isBlank()) {
                files.add(new File(line));
            }
        }
        if (files.isEmpty()) {
            return false;
        }
        return setClipboardContents(new FileListTransferable(files));
    }

    private boolean writeImage(String imageRef) {
        try {
            Path candidate = Path.of(imageRef);
            if (systemClipboard && Files.isRegularFile(candidate) && MacOsPasteboard.writePngFile(candidate)) {
                return true;
            }
            BufferedImage image;
            if (Files.isRegularFile(candidate)) {
                image = ImageIO.read(candidate.toFile());
            } else {
                byte[] pngBytes = Base64.getDecoder().decode(imageRef);
                image = ImageIO.read(new ByteArrayInputStream(pngBytes));
            }
            if (image == null) {
                return false;
            }
            return setClipboardContents(new ImageTransferable(image));
        } catch (IllegalArgumentException | IOException e) {
            return false;
        }
    }

    private boolean setClipboardContents(Transferable transferable) {
        for (int attempt = 1; attempt <= WRITE_MAX_ATTEMPTS; attempt++) {
            try {
                clipboard.setContents(transferable, null);
                return true;
            } catch (IllegalStateException e) {
                if (attempt == WRITE_MAX_ATTEMPTS || !sleepQuietly(CONTENTION_BACKOFF_MS)) {
                    return false;
                }
            } catch (HeadlessException | SecurityException e) {
                return false;
            }
        }
        return false;
    }

    private static Optional<ClipboardPayload> toFilesPayload(Object data) {
        if (!(data instanceof List<?> rawList) || rawList.isEmpty()) {
            return Optional.empty();
        }
        List<File> files = new ArrayList<>();
        for (Object item : rawList) {
            if (item instanceof File file) {
                files.add(file);
            }
        }
        if (files.isEmpty()) {
            return Optional.empty();
        }
        String joinedPaths = files.stream()
                .map(File::getAbsolutePath)
                .collect(Collectors.joining("\n"));
        return Optional.of(new ClipboardPayload(ContentType.FILES, joinedPaths, formatFilesLabel(files)));
    }

    private static String formatFilesLabel(List<File> files) {
        if (files.size() == 1) {
            return "File: " + files.getFirst().getName();
        }
        String firstTwo = files.stream()
                .limit(2)
                .map(File::getName)
                .collect(Collectors.joining(", "));
        String suffix = files.size() > 2 ? ", …" : "";
        return "Files (" + files.size() + "): " + firstTwo + suffix;
    }

    private Optional<ClipboardPayload> toImagePayload(Object data) {
        if (!(data instanceof Image awtImage)) {
            return Optional.empty();
        }
        BufferedImage buffered = toBufferedImage(awtImage);
        if (buffered == null || buffered.getWidth() <= 0 || buffered.getHeight() <= 0) {
            return Optional.empty();
        }
        Path tempFile = null;
        try {
            Files.createDirectories(imageCacheDir);
            tempFile = Files.createTempFile(imageCacheDir, "img-", ".png.tmp");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (OutputStream fileOut = Files.newOutputStream(tempFile);
                 DigestOutputStream digestOut = new DigestOutputStream(fileOut, digest)) {
                if (!ImageIO.write(buffered, "png", digestOut)) {
                    Files.deleteIfExists(tempFile);
                    return Optional.empty();
                }
            }
            String hash = HexFormat.of().formatHex(digest.digest());
            Path targetFile = imageCacheDir.resolve(hash + ".png");
            if (!Files.exists(targetFile)) {
                Files.move(tempFile, targetFile, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.deleteIfExists(tempFile);
            }
            targetFile.toFile().deleteOnExit();
            String label = "Image (" + buffered.getWidth() + "×" + buffered.getHeight() + ")";
            return Optional.of(new ClipboardPayload(
                    ContentType.IMAGE,
                    targetFile.toAbsolutePath().toString(),
                    label));
        } catch (IOException | NoSuchAlgorithmException e) {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException ignored) {
                    // Best-effort cleanup.
                }
            }
            return Optional.empty();
        }
    }

    private static BufferedImage toBufferedImage(Image image) {
        if (image instanceof BufferedImage buffered) {
            return buffered;
        }
        int width = image.getWidth(null);
        int height = image.getHeight(null);
        if (width <= 0 || height <= 0) {
            return null;
        }
        BufferedImage buffered = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = buffered.createGraphics();
        try {
            graphics.drawImage(image, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        return buffered;
    }

    private record ImageTransferable(Image image) implements Transferable {
        @Override
        public DataFlavor[] getTransferDataFlavors() {
            return new DataFlavor[]{DataFlavor.imageFlavor};
        }

        @Override
        public boolean isDataFlavorSupported(DataFlavor flavor) {
            return DataFlavor.imageFlavor.equals(flavor);
        }

        @Override
        public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
            if (!isDataFlavorSupported(flavor)) {
                throw new UnsupportedFlavorException(flavor);
            }
            return image;
        }
    }

    private record FileListTransferable(List<File> files) implements Transferable {
        @Override
        public DataFlavor[] getTransferDataFlavors() {
            return new DataFlavor[]{DataFlavor.javaFileListFlavor};
        }

        @Override
        public boolean isDataFlavorSupported(DataFlavor flavor) {
            return DataFlavor.javaFileListFlavor.equals(flavor);
        }

        @Override
        public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
            if (!isDataFlavorSupported(flavor)) {
                throw new UnsupportedFlavorException(flavor);
            }
            return List.copyOf(files);
        }
    }

    private static boolean sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
