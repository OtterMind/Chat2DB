package ai.chat2db.community.jcef.menus;

import com.formdev.flatlaf.FlatClientProperties;
import org.junit.jupiter.api.Test;

import javax.swing.JRootPane;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Chat2DBMenuBarTest {

    @Test
    void shouldNotInstallSwingMenuBarOverFullWindowContent() {
        JRootPane rootPane = new JRootPane();

        assertTrue(Chat2DBMenuBar.shouldInstallMenuBar(rootPane));

        rootPane.putClientProperty(FlatClientProperties.FULL_WINDOW_CONTENT, true);

        assertFalse(Chat2DBMenuBar.shouldInstallMenuBar(rootPane));
    }
}
