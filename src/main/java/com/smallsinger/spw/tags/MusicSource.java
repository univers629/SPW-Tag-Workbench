package com.smallsinger.spw.tags;

interface MusicSource {
    String name();
    Result search(String keyword) throws Exception;

    record Result(String title, String artist, String album, String albumArtist, String year,
                  String track, String disc, String genre, String composer, String lyricist,
                  long durationMillis, String lyrics, String comment, byte[] cover) {}
}
