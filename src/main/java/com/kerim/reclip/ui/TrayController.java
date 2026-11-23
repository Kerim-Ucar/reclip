package com.kerim.reclip.ui;

import com.kerim.reclip.app.ReclipApp;
import com.kerim.reclip.platform.macos.MacOsStatusBarTray;
import com.kerim.reclip.platform.windows.WindowsWindowHelper;

import javax.imageio.ImageIO;
import java.awt.AWTException;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.CheckboxMenuItem;
import java.awt.Color;
import java.awt.EventQueue;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Cross-platform system tray / macOS menu-bar controller providing quick access
 * to show the picker, pause monitoring, clear history, and quit.
 */
public final class TrayController implements AutoCloseable {

    private final Runnable onShowRequested;
    private final Runnable onSettingsRequested;
    private final Consumer<Boolean> onPauseToggled;
    private final Runnable onClearHistory;
    private final Runnable onQuitRequested;
    private final boolean initialPaused;

    private MacOsStatusBarTray macOsTray;
    private TrayIcon awtTrayIcon;

    /**
     * Creates a tray controller with callbacks for menu actions.
     *
     * @param onShowRequested     callback to show the picker window
     * @param onSettingsRequested callback to open the settings window
     * @param onPauseToggled      callback invoked with the new paused state
     * @param onClearHistory      callback to clear unpinned history
     * @param onQuitRequested     callback to exit the application
     */
    public TrayController(
            Runnable onShowRequested,
            Runnable onSettingsRequested,
            Consumer<Boolean> onPauseToggled,
            Runnable onClearHistory,
            Runnable onQuitRequested) {
        this(onShowRequested, onSettingsRequested, onPauseToggled, onClearHistory, onQuitRequested, false);
    }

    /**
     * Creates a tray controller with callbacks and initial monitoring pause state.
     *
     * @param onShowRequested     callback to show the main panel
     * @param onSettingsRequested callback to open the settings window
     * @param onPauseToggled      callback invoked with the new paused state
     * @param onClearHistory      callback to clear unpinned history
     * @param onQuitRequested     callback to exit the application
     * @param initialPaused       initial state for the Pause Monitoring menu item
     */
    public TrayController(
            Runnable onShowRequested,
            Runnable onSettingsRequested,
            Consumer<Boolean> onPauseToggled,
            Runnable onClearHistory,
            Runnable onQuitRequested,
            boolean initialPaused) {
        this.onShowRequested = Objects.requireNonNull(onShowRequested, "onShowRequested must not be null");
        this.onSettingsRequested = Objects.requireNonNull(onSettingsRequested, "onSettingsRequested must not be null");
        this.onPauseToggled = Objects.requireNonNull(onPauseToggled, "onPauseToggled must not be null");
        this.onClearHistory = Objects.requireNonNull(onClearHistory, "onClearHistory must not be null");
        this.onQuitRequested = Objects.requireNonNull(onQuitRequested, "onQuitRequested must not be null");
        this.initialPaused = initialPaused;
    }

    /**
     * Installs the tray/menu-bar icon if supported by the current OS.
     *
     * @return {@code true} if installed, {@code false} if unsupported or unavailable
     */
    public boolean install() {
        if (isMacOs()) {
            MacOsStatusBarTray tray = new MacOsStatusBarTray(
                    onShowRequested, onSettingsRequested, onPauseToggled, onClearHistory, onQuitRequested);
            if (tray.install()) {
                this.macOsTray = tray;
                return true;
            }
            return false;
        }
        return installAwtTray();
    }

    private boolean installAwtTray() {
        try {
            if (!SystemTray.isSupported()) {
                return false;
            }
            PopupMenu menu = new PopupMenu();

            MenuItem showItem = new MenuItem("Show Clipboard");
            showItem.addActionListener(e -> onShowRequested.run());

            MenuItem settingsItem = new MenuItem("Settings...");
            settingsItem.addActionListener(e -> onSettingsRequested.run());

            CheckboxMenuItem pauseItem = new CheckboxMenuItem("Pause Monitoring", initialPaused);
            pauseItem.addItemListener(e -> onPauseToggled.accept(pauseItem.getState()));

            MenuItem clearItem = new MenuItem("Clear History");
            clearItem.addActionListener(e -> onClearHistory.run());

            MenuItem quitItem = new MenuItem("Quit " + ReclipApp.APP_NAME);
            quitItem.addActionListener(e -> onQuitRequested.run());

            menu.add(showItem);
            menu.add(settingsItem);
            menu.addSeparator();
            menu.add(pauseItem);
            menu.add(clearItem);
            menu.addSeparator();
            menu.add(quitItem);

            TrayIcon icon = new TrayIcon(createAwtTrayImage(), ReclipApp.APP_NAME, menu);
            icon.setImageAutoSize(true);
            icon.addActionListener(e -> onShowRequested.run());
            icon.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    if (e.getButton() == MouseEvent.BUTTON1 && e.getClickCount() == 1) {
                        onShowRequested.run();
                    }
                }
            });

            SystemTray.getSystemTray().add(icon);
            this.awtTrayIcon = icon;
            return true;
        } catch (AWTException | UnsupportedOperationException | SecurityException e) {
            return false;
        }
    }

    @Override
    public void close() {
        if (macOsTray != null) {
            macOsTray.close();
            macOsTray = null;
        }

        TrayIcon iconToRemove = this.awtTrayIcon;
        this.awtTrayIcon = null;
        if (iconToRemove == null) {
            return;
        }
        Runnable removeTask = () -> {
            try {
                SystemTray.getSystemTray().remove(iconToRemove);
            } catch (UnsupportedOperationException | SecurityException ignored) {
                // Best-effort cleanup.
            }
        };
        if (EventQueue.isDispatchThread()) {
            removeTask.run();
        } else {
            try {
                EventQueue.invokeAndWait(removeTask);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                removeTask.run();
            } catch (InvocationTargetException e) {
                removeTask.run();
            }
        }
    }

    private static boolean isMacOs() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
    }

    private static Image createAwtTrayImage() {
        boolean lightTaskbar = WindowsWindowHelper.isSystemLightTheme();
        try (InputStream stream = TrayController.class.getResourceAsStream("/icons/tray-icon.png")) {
            if (stream != null) {
                BufferedImage loaded = ImageIO.read(stream);
                if (loaded != null) {
                    return lightTaskbar ? tintTrayIcon(loaded, new Color(0x1C, 0x1C, 0x1E)) : loaded;
                }
            }
        } catch (IOException ignored) {
            // Fall back to programmatic icon.
        }
        int size = 32;
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setComposite(AlphaComposite.Clear);
            g.fillRect(0, 0, size, size);
            g.setComposite(AlphaComposite.SrcOver);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(lightTaskbar ? new Color(0x1C, 0x1C, 0x1E) : Color.WHITE);
            g.setStroke(new BasicStroke(2.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.drawRoundRect(6, 7, 20, 21, 5, 5);
            g.fillRoundRect(11, 3, 10, 6, 3, 3);
        } finally {
            g.dispose();
        }
        return image;
    }

    private static BufferedImage tintTrayIcon(BufferedImage source, Color color) {
        int width = source.getWidth();
        int height = source.getHeight();
        BufferedImage tinted = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        int rgbMask = (color.getRed() << 16) | (color.getGreen() << 8) | color.getBlue();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int argb = source.getRGB(x, y);
                int alpha = (argb >>> 24) & 0xFF;
                if (alpha > 0) {
                    tinted.setRGB(x, y, (alpha << 24) | rgbMask);
                }
            }
        }
        return tinted;
    }
}
