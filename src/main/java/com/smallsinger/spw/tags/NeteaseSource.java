package com.smallsinger.spw.tags;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.URLEncoder;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

final class NeteaseSource {
    private NeteaseSource() {}

    static Match first(String keyword) throws Exception {
        String body = "s=" + URLEncoder.encode(keyword, StandardCharsets.UTF_8) + "&type=1&offset=0&limit=10";
        String response = post("https://music.163.com/api/cloudsearch/pc", body);
        JsonObject root = JsonParser.parseString(response).getAsJsonObject();
        JsonArray songs = root.getAsJsonObject("result").getAsJsonArray("songs");
        if (songs == null || songs.isEmpty()) return null;
        JsonObject song = songs.get(0).getAsJsonObject();
        JsonObject album = song.has("al") ? song.getAsJsonObject("al") : song.getAsJsonObject("album");
        JsonArray artists = song.has("ar") ? song.getAsJsonArray("ar") : song.getAsJsonArray("artists");
        StringBuilder artist = new StringBuilder();
        for (var item : artists) { if (!artist.isEmpty()) artist.append('/'); artist.append(item.getAsJsonObject().get("name").getAsString()); }
        long id = song.get("id").getAsLong();
        long publish = song.has("publishTime") ? song.get("publishTime").getAsLong() : 0;
        String year = publish > 0 ? String.valueOf(Instant.ofEpochMilli(publish).atZone(java.time.ZoneId.systemDefault()).getYear()) : "";
        String lyrics = lyrics(id);
        String coverUrl = album != null && album.has("picUrl") ? album.get("picUrl").getAsString() : "";
        byte[] cover = coverUrl.isBlank() ? null : getBytes(coverUrl);
        String track = song.has("no") ? song.get("no").getAsString() : "";
        return new Match(song.get("name").getAsString(), artist.toString(), album == null ? "" : album.get("name").getAsString(), year, track, lyrics, cover);
    }

    private static String lyrics(long id) throws Exception {
        String json = new String(getBytes("https://music.163.com/api/song/lyric?id=" + id + "&lv=-1&tv=-1"), StandardCharsets.UTF_8);
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        if (root.has("yrc") && root.get("yrc").isJsonObject()) {
            String yrc = root.getAsJsonObject("yrc").has("lyric") ? root.getAsJsonObject("yrc").get("lyric").getAsString() : "";
            String enhanced = yrcToEnhancedLrc(yrc); if (!enhanced.isBlank()) return enhanced;
        }
        return root.has("lrc") && root.getAsJsonObject("lrc").has("lyric") ? root.getAsJsonObject("lrc").get("lyric").getAsString() : "";
    }

    private static String yrcToEnhancedLrc(String yrc) {
        StringBuilder out = new StringBuilder();
        java.util.regex.Pattern linePattern = java.util.regex.Pattern.compile("^\\[(\\d+),(\\d+)](.*)$");
        java.util.regex.Pattern wordPattern = java.util.regex.Pattern.compile("\\((\\d+),(\\d+),\\d+\\)([^()]*)");
        for (String raw : yrc.split("\\R")) {
            java.util.regex.Matcher line = linePattern.matcher(raw.trim()); if (!line.matches()) continue;
            long lineStart = Long.parseLong(line.group(1)); java.util.regex.Matcher word = wordPattern.matcher(line.group(3));
            StringBuilder content = new StringBuilder(); long end = lineStart + Long.parseLong(line.group(2)); boolean found = false;
            while (word.find()) { found = true; long start = Long.parseLong(word.group(1)); end = start + Long.parseLong(word.group(2)); content.append('<').append(timestamp(start)).append('>').append(word.group(3)); }
            if (found) out.append('[').append(timestamp(lineStart)).append("] ").append(content).append('<').append(timestamp(end)).append('>').append('\n');
        }
        return out.toString().trim();
    }

    private static String timestamp(long millis) { long minutes=millis/60000, seconds=(millis%60000)/1000, ms=millis%1000; return String.format(java.util.Locale.ROOT,"%02d:%02d.%03d",minutes,seconds,ms); }

    private static String post(String url, String body) throws Exception {
        HttpURLConnection connection = open(url);
        connection.setRequestMethod("POST"); connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        try (var output = connection.getOutputStream()) { output.write(body.getBytes(StandardCharsets.UTF_8)); }
        try (var input = connection.getInputStream()) { return new String(input.readAllBytes(), StandardCharsets.UTF_8); }
    }

    private static byte[] getBytes(String url) throws Exception {
        HttpURLConnection connection = open(url); connection.setRequestMethod("GET");
        try (var input = connection.getInputStream()) { return input.readAllBytes(); }
    }

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
        connection.setConnectTimeout(10_000); connection.setReadTimeout(15_000);
        connection.setRequestProperty("User-Agent", "Mozilla/5.0");
        connection.setRequestProperty("Referer", "https://music.163.com/");
        return connection;
    }

    record Match(String title, String artist, String album, String year, String track, String lyrics, byte[] cover) {}
}
