package ai.chat2db.community.jcef.menus;

import ai.chat2db.community.jcef.context.JcefContext;
import ai.chat2db.community.jcef.utils.OSOperateUtil;
import com.formdev.flatlaf.FlatClientProperties;
import lombok.extern.slf4j.Slf4j;
import org.cef.OS;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;

import javax.swing.*;
import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;


@Slf4j
public class Chat2DBMenuBar {


    public static void setupMenuBar() {
        refreshMenuBar();
    }


    public static void refreshMenuBar() {
        JFrame frame = JcefContext.getInstance().getFrame_();
        if (frame == null) {
            log.error("Unable to refresh the menu bar because the main window (JFrame) instance is null.");
            return;
        }
        SwingUtilities.invokeLater(() -> {
            JMenuBar menuBar = shouldInstallMenuBar(frame.getRootPane()) ? createMenuBar() : null;
            frame.setJMenuBar(menuBar);
            frame.validate();
            frame.repaint();
        });
    }

    static boolean shouldInstallMenuBar(JRootPane rootPane) {
        // FlatLaf reserves a separate row for JMenuBar in full-window-content mode.
        return !Boolean.TRUE.equals(rootPane.getClientProperty(FlatClientProperties.FULL_WINDOW_CONTENT));
    }


    private static JMenuBar createMenuBar() {
        CefBrowser cefBrowser = JcefContext.getInstance().getBrowser_();
        JFrame frame = JcefContext.getInstance().getFrame_();

        JMenuBar menuBar = new JMenuBar();
        JMenu editMenu = new JMenu(MenuI18n.getString("menu.edit"));

        JMenuItem cutItem = new JMenuItem(MenuI18n.getString("menu.edit.cut"));
        cutItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_X, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        cutItem.addActionListener(e -> {
            if (cefBrowser != null) {
                CefFrame focusedFrame = cefBrowser.getFocusedFrame();
                if (focusedFrame != null) focusedFrame.cut();
            }
        });
        editMenu.add(cutItem);

        JMenuItem copyItem = new JMenuItem(MenuI18n.getString("menu.edit.copy"));
        copyItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_C, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        copyItem.addActionListener(e -> {
            if (cefBrowser != null) {
                CefFrame focusedFrame = cefBrowser.getFocusedFrame();
                if (focusedFrame != null) focusedFrame.copy();
            }
        });
        editMenu.add(copyItem);

        JMenuItem pasteItem = new JMenuItem(MenuI18n.getString("menu.edit.paste"));
        pasteItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_V, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        pasteItem.addActionListener(e -> {
            if (cefBrowser != null) {
                CefFrame focusedFrame = cefBrowser.getFocusedFrame();
                if (focusedFrame != null) focusedFrame.paste();
            }
        });
        editMenu.add(pasteItem);
        JMenu viewMenu = new JMenu(MenuI18n.getString("menu.view"));

        JMenuItem refreshItem = new JMenuItem(MenuI18n.getString("menu.view.refresh"));
        refreshItem.addActionListener(e -> {
            if (cefBrowser != null) {
                cefBrowser.reload();
            }
        });
        viewMenu.add(refreshItem);

        JMenuItem fullscreenItem = new JMenuItem(MenuI18n.getString("menu.view.fullscreen"));
        fullscreenItem.addActionListener(e -> {
            if (frame != null) {
                log.info("Fullscreen toggle invoked.");
            }
        });
        JMenu helpMenu = new JMenu(MenuI18n.getString("menu.help"));

        JMenuItem openLogItem = new JMenuItem(MenuI18n.getString("menu.help.openLog"));
        openLogItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_T, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx() | InputEvent.SHIFT_DOWN_MASK));
        openLogItem.addActionListener(e -> OSOperateUtil.openLog(frame));
        helpMenu.add(openLogItem);

        helpMenu.addSeparator();

        addUrlMenuItem(helpMenu, "menu.help.visitWebsite", "https://chat2db.ai/");
        addUrlMenuItem(helpMenu, "menu.help.viewDocs", "https://docs.chat2db.ai/");
        addUrlMenuItem(helpMenu, "menu.help.viewChangelog", "https://github.com/chat2db/Chat2DB/releases");
        menuBar.add(viewMenu);
        if (OS.isMacintosh()) {
            menuBar.add(Box.createHorizontalGlue());
        }

        menuBar.add(helpMenu);

        return menuBar;
    }


    private static void addUrlMenuItem(JMenu menu, String i18nKey, final String urlString) {
        JMenuItem menuItem = new JMenuItem(MenuI18n.getString(i18nKey));
        menuItem.addActionListener(e -> {
            try {
                if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                    Desktop.getDesktop().browse(new URI(urlString));
                } else {
                    log.warn("The current platform does not support opening links in an external browser.");
                }
            } catch (IOException | URISyntaxException ex) {
                log.error("Error opening URL: {}", urlString, ex);
            }
        });
        menu.add(menuItem);
    }
}
