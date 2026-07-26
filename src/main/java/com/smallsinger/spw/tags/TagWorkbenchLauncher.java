package com.smallsinger.spw.tags;

import com.xuncorp.spw.workshop.api.WorkshopApi;
import javax.swing.SwingUtilities;

public final class TagWorkbenchLauncher {
    private static TagWorkbenchWindow window;
    private TagWorkbenchLauncher() {}

    public static void open() {
        SwingUtilities.invokeLater(() -> {
            try {
                if (window == null || !window.isDisplayable())
                    window = TagWorkbenchWindow.create();
                window.setVisible(true);
                window.toFront();
                window.preloadPersistentViews();
                window.openLibraryFolders(TagWorkbenchPlugin.configuredMusicFolders());
            } catch (Throwable error) {
                TagWorkbenchWindow failed = window;
                window = null;
                if (failed != null) failed.dispose();
                WorkshopApi.ui().toast(
                    "标签工作台打开失败：" +
                        (error.getMessage() == null
                             ? error.getClass().getSimpleName()
                             : error.getMessage()),
                    WorkshopApi.Ui.ToastType.Error);
            }
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

    static void onWindowDisposed(TagWorkbenchWindow disposed) {
        if (window == disposed) window = null;
    }
}
