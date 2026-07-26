package com.smallsinger.spw.tags;

import java.io.File;

record AudioTagData(
        File file, String title, String artist, String album, String albumArtist,
        String lyricist, String composer, String year, String track, String disc,
        String genre, String lyrics, String comment, byte[] cover,
        String duration, String bitDepth, String bitrate
) {
    String displayName() {
        return title == null || title.isBlank() ? file.getName() : title;
    }
}
