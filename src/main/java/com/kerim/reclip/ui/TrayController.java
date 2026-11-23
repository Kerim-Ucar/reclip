package com.kerim.reclip.ui;

import com.kerim.reclip.app.ReclipApp;
import com.kerim.reclip.platform.macos.MacOsStatusBarTray;

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
        this.onShowRequested = Objects.requireNonNull(onShowRequested, "onShowRequested must not be null");
        this.onSettingsRequested = Objects.requireNonNull(onSettingsRequested, "onSettingsRequested must not be null");
        this.onPauseToggled = Objects.requireNonNull(onPauseToggled, "onPauseToggled must not be null");
        this.onClearHistory = Objects.requireNonNull(onClearHistory, "onClearHistory must not be null");
        this.onQuitRequested = Objects.requireNonNull(onQuitRequested, "onQuitRequested must not be null");
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

            CheckboxMenuItem pauseItem = new CheckboxMenuItem("Pause Monitoring", false);
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
        try (InputStream stream = TrayController.class.getResourceAsStream("/icons/tray-icon.png")) {
            if (stream != null) {
                return ImageIO.read(stream);
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
            g.setColor(Color.WHITE);
            g.setStroke(new BasicStroke(2.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.drawRoundRect(6, 7, 20, 21, 5, 5);
            g.fillRoundRect(11, 3, 10, 6, 3, 3);
        } finally {
            g.dispose();
        }
        return image;
    }
}
