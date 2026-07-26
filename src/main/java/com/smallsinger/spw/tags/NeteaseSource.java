// SPDX-FileCopyrightText: 2026 univers629
// SPDX-FileCopyrightText: 2024-2025 沉默の金 (LDDC lyric track parsing approach)
// SPDX-License-Identifier: GPL-3.0-only
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
import java.security.MessageDigest;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

final class NeteaseSource {
    private NeteaseSource() {}

    static Match first(String keyword) throws Exception {
        String body = "s=" + URLEncoder.encode(keyword, StandardCharsets.UTF_8) + "&type=1&offset=0&limit=10";
        JsonArray songs;try{songs=eapiSearch(keyword);}catch(Exception ignored){String response = post("https://music.163.com/api/cloudsearch/pc", body);JsonObject root = JsonParser.parseString(response).getAsJsonObject();songs = root.getAsJsonObject("result").getAsJsonArray("songs");}
        if (songs == null || songs.isEmpty()) return null;
        JsonObject song = useOriginalMetadata(bestSong(songs,keyword),keyword);
        JsonObject album = song.has("al") ? song.getAsJsonObject("al") : song.getAsJsonObject("album");
        JsonArray artists = song.has("ar") ? song.getAsJsonArray("ar") : song.getAsJsonArray("artists");
        StringBuilder artist = new StringBuilder();
        for (var item : artists) { if (!artist.isEmpty()) artist.append('/'); artist.append(item.getAsJsonObject().get("name").getAsString()); }
        long id = song.get("id").getAsLong();
        long publish = song.has("publishTime") ? song.get("publishTime").getAsLong() : 0;
        String year = publish > 0 ? String.valueOf(Instant.ofEpochMilli(publish).atZone(java.time.ZoneId.systemDefault()).getYear()) : "";
        String lyrics = lyrics(id);
        String coverUrl = album != null && album.has("picUrl") ? album.get("picUrl").getAsString() : "";
        byte[] cover = coverUrl.isBlank() ? null : MusicSources.coverBytes(coverUrl);
        String track = song.has("no") ? song.get("no").getAsString() : "";
        long duration = song.has("dt") ? song.get("dt").getAsLong()
                : song.has("duration") ? song.get("duration").getAsLong() : 0;
        return new Match(song.get("name").getAsString(), artist.toString(), album == null ? "" : album.get("name").getAsString(), year, track, duration, lyrics, cover);
    }

    static String lyrics(long id) throws Exception {
        return lyrics(id, false);
    }
    static String lyricsWithRomanization(long id) throws Exception {
        return lyrics(id, true);
    }
    private static String lyrics(long id, boolean includeRomanization) throws Exception {
        String json;
        try { json=eapiLyrics(id, includeRomanization); }
        catch(Exception ignored){json = new String(getBytes("https://music.163.com/api/song/lyric?id=" + id + "&lv=-1&tv=-1&rv=" + (includeRomanization ? "-1" : "0") + "&yv=-1"), StandardCharsets.UTF_8);}
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        String romanization = includeRomanization ? lyricText(root,"romalrc") : "";
        if (root.has("yrc") && root.get("yrc").isJsonObject()) {
            String yrc = root.getAsJsonObject("yrc").has("lyric") ? root.getAsJsonObject("yrc").get("lyric").getAsString() : "";
            String translation = lyricText(root,"tlyric");
            String verbatim = yrcToVerbatimLrc(yrc,translation,romanization); if (!verbatim.isBlank()) return verbatim;
        }
        String ordinary=lyricText(root,"lrc");
        return ordinary.isBlank()?"":mergeLineLyrics(ordinary,lyricText(root,"tlyric"),romanization);
    }

    private static JsonObject bestSong(JsonArray songs,String keyword){
        String wanted=normalize(keyword);JsonObject best=songs.get(0).getAsJsonObject();int bestScore=Integer.MIN_VALUE;
        for(var element:songs){JsonObject candidate=element.getAsJsonObject();String name=candidate.has("name")?candidate.get("name").getAsString():"";JsonArray artists=candidate.has("ar")?candidate.getAsJsonArray("ar"):candidate.getAsJsonArray("artists");int score=wanted.contains(normalize(name))?100:0;if(artists!=null)for(var a:artists){String artist=a.getAsJsonObject().get("name").getAsString();if(wanted.contains(normalize(artist)))score+=60;else score-=15;}if(candidate.has("originSongSimpleData")&&candidate.get("originSongSimpleData").isJsonObject()){JsonObject origin=candidate.getAsJsonObject("originSongSimpleData");if(wanted.contains(normalize(origin.get("name").getAsString())))score+=120;for(var a:origin.getAsJsonArray("artists"))if(wanted.contains(normalize(a.getAsJsonObject().get("name").getAsString())))score+=240;}if(score>bestScore){bestScore=score;best=candidate;}}
        return best;
    }
    private static JsonObject useOriginalMetadata(JsonObject song,String keyword){if(!song.has("originSongSimpleData")||!song.get("originSongSimpleData").isJsonObject())return song;JsonObject origin=song.getAsJsonObject("originSongSimpleData");String wanted=normalize(keyword);boolean matches=wanted.contains(normalize(origin.get("name").getAsString()));for(var a:origin.getAsJsonArray("artists"))matches|=wanted.contains(normalize(a.getAsJsonObject().get("name").getAsString()));if(!matches)return song;JsonObject resolved=song.deepCopy();resolved.addProperty("id",origin.get("songId").getAsLong());resolved.addProperty("name",origin.get("name").getAsString());resolved.add("ar",origin.getAsJsonArray("artists").deepCopy());JsonObject album=new JsonObject();JsonObject meta=origin.getAsJsonObject("albumMeta");album.addProperty("id",meta.get("id").getAsLong());album.addProperty("name",meta.get("name").getAsString());album.addProperty("picUrl",song.getAsJsonObject("al").get("picUrl").getAsString());resolved.add("al",album);return resolved;}
    private static String normalize(String value){return value==null?"":value.toLowerCase(java.util.Locale.ROOT).replaceAll("[\\p{Punct}\\p{IsPunctuation}\\s]+","");}

    private static String lyricText(JsonObject root,String key){return root.has(key)&&root.get(key).isJsonObject()&&root.getAsJsonObject(key).has("lyric")?root.getAsJsonObject(key).get("lyric").getAsString():"";}

    private static String yrcToVerbatimLrc(String yrc,String translation,String romanization) {
        java.util.List<TimedLine> originals=new java.util.ArrayList<>();
        java.util.regex.Pattern linePattern = java.util.regex.Pattern.compile("^\\[(\\d+),(\\d+)](.*)$");
        java.util.regex.Pattern wordPattern = java.util.regex.Pattern.compile("\\((\\d+),(\\d+),\\d+\\)(.*?)(?=\\(\\d+,\\d+,\\d+\\)|$)");
        for (String raw : yrc.split("\\R")) {
            java.util.regex.Matcher line = linePattern.matcher(raw.trim()); if (!line.matches()) continue;
            long lineStart = Long.parseLong(line.group(1)); java.util.regex.Matcher word = wordPattern.matcher(line.group(3));
            StringBuilder content = new StringBuilder(); long end = lineStart + Long.parseLong(line.group(2)),lastEnd=lineStart; boolean found = false;
            while (word.find()) { found = true; long start = Long.parseLong(word.group(1)); end = start + Long.parseLong(word.group(2));if(start!=lastEnd)content.append('[').append(timestamp(start)).append(']');content.append(word.group(3)).append('[').append(timestamp(end)).append(']');lastEnd=end; }
            if(found)originals.add(new TimedLine(lineStart,end,content.toString()));
        }
        StringBuilder out=new StringBuilder();java.util.List<TimedLine> ts=parseLrc(translation),roma=parseLrc(romanization);
        for(TimedLine line:originals){out.append('[').append(timestamp(line.start)).append(']').append(line.text).append('\n');appendAligned(out,line,ts);appendAligned(out,line,roma);}
        return out.toString().trim();
    }

    private static void appendAligned(StringBuilder out,TimedLine original,java.util.List<TimedLine> other){TimedLine best=null;long distance=Long.MAX_VALUE;for(TimedLine line:other){long d=Math.abs(line.start-original.start);if(d<distance){best=line;distance=d;}}if(best==null||distance>1200)return;String text=best.text.trim(),plain=original.text.replaceAll("\\[[^]]+]","").trim();if(text.isBlank()||text.matches("^[/／|｜\\s]+$")||text.replaceAll("[\\p{Punct}\\p{IsPunctuation}\\s]+","").equalsIgnoreCase(plain.replaceAll("[\\p{Punct}\\p{IsPunctuation}\\s]+",""))||plain.matches(".*(?:作词|作曲|编曲|词\\s*[:：]|曲\\s*[:：]).*"))return;out.append('[').append(timestamp(original.start)).append(']').append(text).append('\n');}
    private static String mergeLineLyrics(String original,String translation,String romanization){java.util.List<TimedLine> orig=parseLrc(original),ts=parseLrc(translation),roma=parseLrc(romanization);if(orig.isEmpty())return original;StringBuilder out=new StringBuilder();for(int i=0;i<orig.size();i++){TimedLine line=orig.get(i);out.append('[').append(timestamp(line.start)).append(']').append(line.text).append('\n');appendAligned(out,new TimedLine(line.start,i+1<orig.size()?orig.get(i+1).start:line.start+5000,line.text),ts);appendAligned(out,new TimedLine(line.start,i+1<orig.size()?orig.get(i+1).start:line.start+5000,line.text),roma);}return out.toString().trim();}
    private static java.util.List<TimedLine> parseLrc(String lrc){java.util.List<TimedLine> lines=new java.util.ArrayList<>();java.util.regex.Pattern p=java.util.regex.Pattern.compile("\\[(\\d+):(\\d{1,2})(?:[.:](\\d{1,3}))?](.*)");for(String raw:lrc.split("\\R")){java.util.regex.Matcher m=p.matcher(raw.trim());if(!m.matches())continue;long fraction=m.group(3)==null?0:Long.parseLong(m.group(3));if(m.group(3)!=null&&m.group(3).length()==2)fraction*=10;else if(m.group(3)!=null&&m.group(3).length()==1)fraction*=100;long start=Long.parseLong(m.group(1))*60000+Long.parseLong(m.group(2))*1000+fraction;lines.add(new TimedLine(start,start,m.group(4).trim()));}return lines;}
    private record TimedLine(long start,long end,String text){}

    private static String timestamp(long millis) { long minutes=millis/60000, seconds=(millis%60000)/1000, ms=millis%1000; return String.format(java.util.Locale.ROOT,"%02d:%02d.%03d",minutes,seconds,ms); }

    private static JsonArray eapiSearch(String keyword)throws Exception{JsonObject params=new JsonObject();params.addProperty("limit","10");params.addProperty("offset","0");params.addProperty("keyword",keyword);params.addProperty("scene","NORMAL");params.addProperty("needCorrect","true");JsonObject root=eapi("/eapi/search/song/list/page","/api/search/song/list/page",params);JsonArray resources=root.getAsJsonObject("data").getAsJsonArray("resources"),songs=new JsonArray();for(var resource:resources)songs.add(resource.getAsJsonObject().getAsJsonObject("baseInfo").getAsJsonObject("simpleSongData"));return songs;}
    private static String eapiLyrics(long id,boolean romanization)throws Exception{JsonObject params=new JsonObject();params.addProperty("id",id);params.addProperty("lv","-1");params.addProperty("tv","-1");params.addProperty("rv",romanization?"-1":"0");params.addProperty("yv","-1");return eapi("/eapi/song/lyric/v1","/api/song/lyric/v1",params).toString();}
    private static JsonObject eapi(String path,String apiPath,JsonObject params)throws Exception{
        JsonObject header=new JsonObject();header.addProperty("clientSign","00:11:22:33:44:55@@@SPWTAGS1@@@@@@"+java.util.UUID.randomUUID().toString().replace("-",""));header.addProperty("osver","Microsoft-Windows-10--build-26100-64bit");header.addProperty("deviceId",java.util.UUID.randomUUID().toString().replace("-",""));header.addProperty("os","pc");header.addProperty("appver","3.1.3.203419");header.addProperty("requestId",String.valueOf(System.currentTimeMillis()));
        params.addProperty("header",header.toString());params.addProperty("e_r",true);String text=params.toString();String digest=java.util.HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(("nobody"+apiPath+"use"+text+"md5forencrypt").getBytes(StandardCharsets.UTF_8)));String data=apiPath+"-36cd479b6b5-"+text+"-36cd479b6b5-"+digest;
        Cipher cipher=Cipher.getInstance("AES/ECB/PKCS5Padding");SecretKeySpec key=new SecretKeySpec("e82ckenh8dichen8".getBytes(StandardCharsets.UTF_8),"AES");cipher.init(Cipher.ENCRYPT_MODE,key);String body="params="+java.util.HexFormat.of().formatHex(cipher.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        HttpURLConnection connection=open("https://interface.music.163.com"+path);connection.setRequestMethod("POST");connection.setDoOutput(true);connection.setRequestProperty("Content-Type","application/x-www-form-urlencoded");connection.setRequestProperty("Cookie","os=pc; appver=3.1.3.203419; deviceId="+header.get("deviceId").getAsString());try(var output=connection.getOutputStream()){output.write(body.getBytes(StandardCharsets.UTF_8));}byte[] encrypted;try(var input=connection.getInputStream()){encrypted=input.readAllBytes();}cipher.init(Cipher.DECRYPT_MODE,key);
        return JsonParser.parseString(new String(cipher.doFinal(encrypted),StandardCharsets.UTF_8)).getAsJsonObject();
    }

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

    record Match(String title, String artist, String album, String year, String track, long durationMillis, String lyrics, byte[] cover) {}
}
