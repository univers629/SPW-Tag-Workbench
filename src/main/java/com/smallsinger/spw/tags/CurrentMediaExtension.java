package com.smallsinger.spw.tags;

import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint;
import org.pf4j.Extension;

import java.io.File;

@Extension
public final class CurrentMediaExtension implements PlaybackExtensionPoint {
    private static volatile File currentFolder;
    private static volatile File currentFile;

    public static File currentFolder() { return currentFolder; }
    public static File currentFile() { return currentFile; }
    static void clear() {
        currentFolder = null;
        currentFile = null;
    }

    @Override
    @SuppressWarnings("deprecation")
    public String updateLyrics(MediaItem mediaItem) {
        capture(mediaItem);
        return null;
    }

    @Override
    public String onBeforeLoadLyrics(MediaItem mediaItem) {
        capture(mediaItem);
        return null;
    }

    @Override
    public String onAfterLoadLyrics(MediaItem mediaItem) {
        capture(mediaItem);
        return null;
    }

    private static void capture(MediaItem mediaItem) {
        if (mediaItem == null || mediaItem.getPath() == null ||
            mediaItem.getPath().isBlank()) return;
        File file = new File(mediaItem.getPath());
        currentFile = file;
        currentFolder = file.getParentFile();
        TagWorkbenchLauncher.onMediaChanged(file);
    }
}
