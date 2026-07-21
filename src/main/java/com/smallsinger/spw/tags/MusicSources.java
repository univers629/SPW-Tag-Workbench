package com.smallsinger.spw.tags;

import com.google.gson.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

final class MusicSources {
    static final List<MusicSource> ALL = List.of(netease(), qq(), kugou(), apple());
    private MusicSources() {}

    private static MusicSource netease() { return source("网易云音乐", keyword -> {
        NeteaseSource.Match m = NeteaseSource.first(keyword); if (m == null) return null;
        return new MusicSource.Result(m.title(),m.artist(),m.album(),"",m.year(),m.track(),"","","","",m.lyrics(),"",m.cover());
    }); }

    private static MusicSource qq() { return source("QQ 音乐", keyword -> {
        JsonObject root=json(get("https://c.y.qq.com/soso/fcgi-bin/client_search_cp?format=json&p=1&n=1&w="+enc(keyword)));
        JsonArray list=root.getAsJsonObject("data").getAsJsonObject("song").getAsJsonArray("list"); if(list.isEmpty())return null;
        JsonObject s=list.get(0).getAsJsonObject(); String artist=join(s.getAsJsonArray("singer"),"name"); String mid=str(s,"songmid");
        String albumMid=str(s,"albummid"); byte[] cover=albumMid.isBlank()?null:getBytes("https://y.gtimg.cn/music/photo_new/T002R500x500M000"+albumMid+".jpg");
        String lyric=""; try{JsonObject l=json(get("https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?format=json&nobase64=1&songmid="+enc(mid)));lyric=str(l,"lyric");}catch(Exception ignored){}
        return new MusicSource.Result(str(s,"songname"),artist,str(s,"albumname"),"",year(str(s,"pubtime")),str(s,"track"),"",str(s,"genre"),"","",lyric,"",cover);
    }); }

    private static MusicSource kugou() { return source("酷狗音乐", keyword -> {
        JsonObject root=json(get("https://songsearch.kugou.com/song_search_v2?page=1&pagesize=1&keyword="+enc(keyword)));
        JsonArray lists=root.getAsJsonObject("data").getAsJsonArray("lists"); if(lists.isEmpty())return null; JsonObject s=lists.get(0).getAsJsonObject();
        String hash=str(s,"FileHash"); JsonObject detail=json(get("https://wwwapi.kugou.com/yy/index.php?r=play/getdata&hash="+enc(hash))).getAsJsonObject("data");
        String image=str(detail,"img").replace("{size}","500"); byte[] cover=image.isBlank()?null:getBytes(image);
        return new MusicSource.Result(clean(str(s,"SongName")),clean(str(s,"SingerName")),clean(str(s,"AlbumName")),"","","","","","","",str(detail,"lyrics"),"",cover);
    }); }

    private static MusicSource apple() { return source("Apple Music", keyword -> {
        JsonObject root=json(get("https://itunes.apple.com/search?entity=song&limit=1&term="+enc(keyword))); JsonArray results=root.getAsJsonArray("results"); if(results.isEmpty())return null; JsonObject s=results.get(0).getAsJsonObject();
        String image=str(s,"artworkUrl100").replace("100x100bb","600x600bb"); byte[] cover=image.isBlank()?null:getBytes(image);
        return new MusicSource.Result(str(s,"trackName"),str(s,"artistName"),str(s,"collectionName"),str(s,"artistName"),year(str(s,"releaseDate")),str(s,"trackNumber"),str(s,"discNumber"),str(s,"primaryGenreName"),"","","","",cover);
    }); }

    private interface Search { MusicSource.Result run(String keyword) throws Exception; }
    private static MusicSource source(String name, Search search){return new MusicSource(){public String name(){return name;}public MusicSource.Result search(String keyword)throws Exception{return search.run(keyword);}public String toString(){return name;}};}
    private static String get(String url)throws Exception{return new String(getBytes(url),StandardCharsets.UTF_8);}
    private static byte[] getBytes(String url)throws Exception{HttpURLConnection c=(HttpURLConnection)URI.create(url).toURL().openConnection();c.setConnectTimeout(10000);c.setReadTimeout(15000);c.setRequestProperty("User-Agent","Mozilla/5.0");c.setRequestProperty("Referer","https://y.qq.com/");try(var in=c.getInputStream()){return in.readAllBytes();}}
    private static JsonObject json(String s){return JsonParser.parseString(s).getAsJsonObject();}
    private static String enc(String s){return URLEncoder.encode(s,StandardCharsets.UTF_8);}
    private static String str(JsonObject o,String key){return o!=null&&o.has(key)&&!o.get(key).isJsonNull()?o.get(key).getAsString():"";}
    private static String join(JsonArray a,String key){if(a==null)return "";StringJoiner j=new StringJoiner("/");for(JsonElement e:a)j.add(str(e.getAsJsonObject(),key));return j.toString();}
    private static String clean(String s){return s.replaceAll("<[^>]+>","");}
    private static String year(String s){return s!=null&&s.length()>=4?s.substring(0,4):"";}
}
