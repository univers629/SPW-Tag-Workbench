package com.smallsinger.spw.tags;

import javax.swing.SwingUtilities;
import java.io.File;

public final class TagWorkbenchLauncher {
    private static TagWorkbenchWindow window;
    private TagWorkbenchLauncher() {}

    public static void open() {
        SwingUtilities.invokeLater(() -> {
            if (window == null || !window.isDisplayable()) window = new TagWorkbenchWindow();
            window.setVisible(true);
            window.toFront();
            File library = TagWorkbenchPlugin.configuredMusicFolder();
            if (library != null) window.openLibraryFolder(library);
            File current = CurrentMediaExtension.currentFile();
            if (current != null) window.openPlaybackFile(current);
        });
    }

    static void onMediaChanged(java.io.File file) {
        SwingUtilities.invokeLater(() -> {
            if (window != null && window.isDisplayable() && window.isVisible()) window.openPlaybackFile(file);
        });
    }

    public static void close() {
        SwingUtilities.invokeLater(() -> {
            if (window != null) window.dispose();
            window = null;
        });
    }
}
