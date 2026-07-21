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

    @Override
    public String onBeforeLoadLyrics(MediaItem mediaItem) {
        File file = new File(mediaItem.getPath());
        currentFile = file;
        currentFolder = file.getParentFile();
        TagWorkbenchLauncher.onMediaChanged(file);
        return null;
    }
}
