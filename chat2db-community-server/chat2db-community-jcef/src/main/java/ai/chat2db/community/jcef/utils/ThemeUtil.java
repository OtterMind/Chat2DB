package ai.chat2db.community.jcef.utils;


import ai.chat2db.community.tools.config.SystemSettingConstant;
import ai.chat2db.community.jcef.context.JcefContext;
import ai.chat2db.community.jcef.enums.AppThemeEnum;
import ai.chat2db.community.jcef.menus.Chat2DBMenuBar;
import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.themes.FlatMacDarkLaf;
import com.formdev.flatlaf.themes.FlatMacLightLaf;
import org.apache.commons.lang3.StringUtils;
import org.cef.OS;

import javax.swing.*;
import java.awt.*;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.prefs.Preferences;


public class ThemeUtil {

    private static boolean isWindowsDarkMode() {
        try {
            Preferences prefs = Preferences.userRoot().node("HKEY_CURRENT_USER\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize");
            return prefs.getInt("AppsUseLightTheme", 1) == 0;
        } catch (Exception e) {
            return false;
        }
    }


    private static boolean isMacDarkMode() {
        try {
            Process process = Runtime.getRuntime().exec("defaults read -g AppleInterfaceStyle");
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line = reader.readLine();
                return line != null && line.equals("Dark");
            }
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isLinuxDarkMode() {
        try {
            Process process = Runtime.getRuntime().exec("gsettings get org.gnome.desktop.interface gtk-theme");
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String theme = reader.readLine();
                return theme != null && theme.toLowerCase().contains("dark");
            }
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean isSystemInDarkMode() {
        boolean isDarkMode = true;
        if (OS.isWindows()) {
            isDarkMode = isWindowsDarkMode();
        } else if (OS.isMacintosh()) {
            isDarkMode = isMacDarkMode();
        } else {
            isDarkMode = isLinuxDarkMode();
        }
        return isDarkMode;
    }

    public static Color getThemeColor() {
        String appearance = (String) SystemSettingsUtil.getProperty(SystemSettingConstant.SYSTEM_APPEARANCE);
        if (StringUtils.isNotBlank(appearance)) {
            AppThemeEnum appThemeEnum = AppThemeEnum.fromString(appearance);
            if (appThemeEnum != null) {
                return appThemeEnum.getBackgroundColor();
            }
        }
        return isSystemInDarkMode() ? Color.DARK_GRAY : Color.WHITE;
    }

    public static void setThemeColor(AppThemeEnum appThemeEnum) {
        Color bgColor = appThemeEnum.getBackgroundColor();
        SwingUtilities.invokeLater(() -> {
            JcefContext instance = JcefContext.getInstance();
            instance.getFrame_().setBackground(bgColor);
            instance.getBrowserUI_().setBackground(bgColor);
            instance.getSplitPane().setBackground(bgColor);
            instance.getDevToolsPanel().setBackground(bgColor);

            if (!OS.isMacintosh()) {
                try {
                    if (AppThemeEnum.DARK.equals(appThemeEnum)) {
                        UIManager.setLookAndFeel(new FlatDarkLaf());
                        instance.getFrame_().initDarkModeMenuUI();
                    } else if (AppThemeEnum.DARK_DIMMED.equals(appThemeEnum)) {
                        UIManager.setLookAndFeel(new FlatMacDarkLaf());
                        instance.getFrame_().initDarkModeMenuUI();
                    } else {
                        UIManager.setLookAndFeel(new FlatMacLightLaf());
                        instance.getFrame_().initLightModeMenuUI();
                    }
                    FlatLaf.updateUI();
                    Chat2DBMenuBar.refreshMenuBar();
                } catch (UnsupportedLookAndFeelException e) {
                    throw new RuntimeException(e);
                }
            }
        });
    }
}
