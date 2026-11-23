package com.kerim.reclip.ipc;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Local Unix-domain-socket IPC server allowing local integrations (such as the
 * Chrome Native Messaging host) to trigger Reclip actions without an HTTP server.
 */
public final class ReclipIpcServer implements AutoCloseable {

    private final Path socketPath;
    private final Runnable onShowRequested;
    private final Runnable onShowPanelRequested;
    private ServerSocketChannel serverChannel;
    private Thread acceptThread;
    private volatile boolean running;

    public ReclipIpcServer(Path socketPath, Runnable onShowRequested) {
        this(socketPath, onShowRequested, onShowRequested);
    }

    public ReclipIpcServer(Path socketPath, Runnable onShowRequested, Runnable onShowPanelRequested) {
        this.socketPath = Objects.requireNonNull(socketPath, "socketPath must not be null");
        this.onShowRequested = Objects.requireNonNull(onShowRequested, "onShowRequested must not be null");
        this.onShowPanelRequested = Objects.requireNonNull(onShowPanelRequested, "onShowPanelRequested must not be null");
    }

    public static Path defaultSocketPath() {
        return Path.of(System.getProperty("user.home"), ".reclip", "reclip-ipc.sock");
    }

    /**
     * Starts listening on the Unix domain socket in a background daemon thread.
     *
     * @throws IOException if the socket cannot be bound
     */
    public void start() throws IOException {
        if (running) {
            return;
        }
        Path parent = socketPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.deleteIfExists(socketPath);
        ServerSocketChannel channel = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        channel.bind(UnixDomainSocketAddress.of(socketPath));
        this.serverChannel = channel;
        this.running = true;

        this.acceptThread = new Thread(this::acceptLoop, "reclip-ipc-server");
        this.acceptThread.setDaemon(true);
        this.acceptThread.start();
    }

    /**
     * Sends a {@code SHOW} command to a running {@link ReclipIpcServer}.
     *
     * @param socketPath the server socket path
     * @return {@code true} if the command was delivered
     */
    public static boolean sendShow(Path socketPath) {
        return sendCommand(socketPath, "SHOW");
    }

    /**
     * Sends a command to a running {@link ReclipIpcServer}.
     *
     * @param socketPath the server socket path
     * @param command    command string (e.g. {@code "SHOW"} or {@code "SHOW_PANEL"})
     * @return {@code true} if the command was delivered
     */
    public static boolean sendCommand(Path socketPath, String command) {
        Objects.requireNonNull(socketPath, "socketPath must not be null");
        Objects.requireNonNull(command, "command must not be null");
        if (!Files.exists(socketPath)) {
            return false;
        }
        try (SocketChannel client = SocketChannel.open(UnixDomainSocketAddress.of(socketPath))) {
            byte[] payload = (command + "\n").getBytes(StandardCharsets.UTF_8);
            ByteBuffer writeBuf = ByteBuffer.wrap(payload);
            while (writeBuf.hasRemaining()) {
                client.write(writeBuf);
            }
            ByteBuffer readBuf = ByteBuffer.allocate(16);
            int read = client.read(readBuf);
            return read > 0;
        } catch (IOException e) {
            return false;
        }
    }

    private void acceptLoop() {
        ByteBuffer buffer = ByteBuffer.allocate(64);
        while (running) {
            try (SocketChannel client = serverChannel.accept()) {
                buffer.clear();
                int read = client.read(buffer);
                if (read > 0) {
                    buffer.flip();
                    String command = StandardCharsets.UTF_8.decode(buffer).toString().trim();
                    if ("SHOW".equals(command)) {
                        onShowRequested.run();
                    } else if ("SHOW_PANEL".equals(command)) {
                        onShowPanelRequested.run();
                    }
                    ByteBuffer ack = ByteBuffer.wrap("OK\n".getBytes(StandardCharsets.UTF_8));
                    while (ack.hasRemaining()) {
                        client.write(ack);
                    }
                }
            } catch (IOException e) {
                if (running) {
                    // Continue accepting unless stopped.
                }
            }
        }
    }

    @Override
    public void close() {
        running = false;
        if (serverChannel != null) {
            try {
                serverChannel.close();
            } catch (IOException ignored) {
                // Best-effort cleanup.
            }
            serverChannel = null;
        }
        try {
            Files.deleteIfExists(socketPath);
        } catch (IOException ignored) {
            // Best-effort cleanup.
        }
    }
}
