// SPDX-FileCopyrightText: 2026 univers629
// SPDX-FileCopyrightText: 2024-2025 沉默の金 (LDDC parsing and decryption approach)
// SPDX-License-Identifier: GPL-3.0-only
package com.smallsinger.spw.tags;

import com.google.gson.*;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.*;
import java.util.zip.InflaterInputStream;

final class StructuredLyrics {
    private static final byte[] QRC_KEY="!@#)(*$%123ZXC!@!@#)(NHL".getBytes(StandardCharsets.ISO_8859_1);
    private static final byte[] KRC_KEY={64,71,97,119,94,50,116,71,81,54,49,45,(byte)206,(byte)210,110,105};
    private static final String KG_SALT="LnT6xpN3khm36zse0QzvmgTZ3waWdRSA";
    private StructuredLyrics(){}

    static String qq(long id,String title,String album,String artist,int durationSeconds)throws Exception{return qqInternal(id,title,album,artist,durationSeconds,false);}
    static String qqAdvanced(long id,String title,String album,String artist,int durationSeconds)throws Exception{return qqInternal(id,title,album,artist,durationSeconds,true);}
    private static String qqInternal(long id,String title,String album,String artist,int durationSeconds,boolean romanization)throws Exception{
        JsonObject param=new JsonObject();param.addProperty("songID",id);param.addProperty("songName",b64(title));param.addProperty("albumName",b64(album));param.addProperty("singerName",b64(artist));param.addProperty("crypt",1);param.addProperty("qrc",1);param.addProperty("trans",1);param.addProperty("roma",romanization?1:0);param.addProperty("cv",2111);param.addProperty("ct",19);param.addProperty("lrc_t",0);param.addProperty("qrc_t",0);param.addProperty("roma_t",0);param.addProperty("trans_t",0);param.addProperty("type",0);param.addProperty("interval",durationSeconds);
        JsonObject req=new JsonObject();req.addProperty("method","GetPlayLyricInfo");req.addProperty("module","music.musichallSong.PlayLyricInfo");req.add("param",param);
        JsonObject comm=new JsonObject();comm.addProperty("ct","11");comm.addProperty("cv","1003006");comm.addProperty("v","1003006");comm.addProperty("os_ver","15");comm.addProperty("phonetype","24122RKC7C");comm.addProperty("tmeAppID","qqmusiclight");comm.addProperty("nettype","NETWORK_WIFI");
        JsonObject body=new JsonObject();body.add("comm",comm);body.add("req_0",req);
        JsonObject data=json(post("https://u.y.qq.com/cgi-bin/musicu.fcg",body.toString())).getAsJsonObject("req_0").getAsJsonObject("data");
        String original=decodeQrc(str(data,"lyric"));if(original.isBlank())return "";
        List<Line> lines=parseQrc(original);
        String roma=romanization?decodeQrc(str(data,"roma")):"";
        List<TextLine> romaLines=toTextLines(parseQrc(roma));if(romaLines.isEmpty())romaLines=parseLrc(roma);
        return render(lines,parseLrc(decodeQrc(str(data,"trans"))),romaLines);
    }

    static String kugou(String id,String hash,long duration,String keyword)throws Exception{return kugouInternal(id,hash,duration,keyword,false);}
    static String kugouAdvanced(String id,String hash,long duration,String keyword)throws Exception{return kugouInternal(id,hash,duration,keyword,true);}
    private static String kugouInternal(String id,String hash,long duration,String keyword,boolean romanization)throws Exception{
        Map<String,String> search=new HashMap<>();search.put("album_audio_id",id);search.put("duration",String.valueOf(duration));search.put("hash",hash);search.put("keyword",keyword);search.put("lrctxt","1");search.put("man","no");
        JsonObject result=json(get("https://lyrics.kugou.com/v1/search?"+signedQuery(search)));
        JsonArray candidates=result.has("candidates")?result.getAsJsonArray("candidates"):null;if(candidates==null||candidates.isEmpty())return "";
        JsonObject candidate=candidates.get(0).getAsJsonObject();Map<String,String> download=new HashMap<>();download.put("accesskey",str(candidate,"accesskey"));download.put("charset","utf8");download.put("client","mobi");download.put("fmt","krc");download.put("id",str(candidate,"id"));download.put("ver","1");
        JsonObject content=json(get("https://lyrics.kugou.com/download?"+signedQuery(download)));String encoded=str(content,"content");if(encoded.isBlank())return "";
        String plain=content.has("contenttype")&&content.get("contenttype").getAsInt()==2?new String(Base64.getDecoder().decode(encoded),StandardCharsets.UTF_8):decryptKrc(encoded);
        return renderKrc(plain,romanization);
    }

    static String apple(String id)throws Exception{
        if(id==null||id.isBlank())return "";
        JsonObject root=json(getApple("https://lyrics.paxsenix.org/apple-music/lyrics?id="+enc(id)+"&ttml=false"));
        String enhanced=first(root,"elrcMultiPerson","elrc");if(!enhanced.isBlank())return enhanced.replaceAll("<(\\d{1,}:\\d{2}(?:[.:]\\d{1,3})?)>","[$1]");
        JsonArray content=root.has("content")&&root.get("content").isJsonArray()?root.getAsJsonArray("content"):new JsonArray();List<Line> lines=new ArrayList<>();
        for(JsonElement element:content){JsonObject line=element.getAsJsonObject();long start=number(line,"timestamp",0),end=number(line,"endtime",start);List<Word> words=new ArrayList<>();if(line.has("text")&&line.get("text").isJsonArray()){JsonArray items=line.getAsJsonArray("text");for(int i=0;i<items.size();i++){JsonObject word=items.get(i).getAsJsonObject();String text=str(word,"text");if(!text.isEmpty())words.add(new Word(number(word,"timestamp",start),number(word,"endtime",end),text+(needsSpace(text,i+1<items.size()?str(items.get(i+1).getAsJsonObject(),"text"):"")?" ":"")));}}if(words.isEmpty()){String plain=str(line,"plain");if(!plain.isBlank())words.add(new Word(start,end,plain));}if(!words.isEmpty())lines.add(new Line(start,end,words));}
        if(!lines.isEmpty())return render(lines,List.of(),List.of());
        return "";
    }

    private static List<Line> parseQrc(String qrc){
        String content=unescape(qrc);Matcher xml=Pattern.compile("<Lyric_1 LyricType=\"1\" LyricContent=\"([\\s\\S]*?)\"/>").matcher(content);if(xml.find())content=unescape(xml.group(1));
        List<Line> out=new ArrayList<>();Pattern lp=Pattern.compile("^\\[(\\d+),(\\d+)](.*)$"),wp=Pattern.compile("((?:(?!\\(\\d+,\\d+\\)).)*)\\((\\d+),(\\d+)\\)");
        for(String raw:content.split("\\R")){Matcher line=lp.matcher(raw.trim());if(!line.matches())continue;long start=Long.parseLong(line.group(1)),end=start+Long.parseLong(line.group(2));List<Word> words=new ArrayList<>();Matcher word=wp.matcher(line.group(3));while(word.find())words.add(new Word(Long.parseLong(word.group(2)),0,word.group(1)));for(int i=0;i<words.size();i++){Word w=words.get(i);words.set(i,new Word(w.start,i+1<words.size()?words.get(i+1).start:end,w.text));}if(words.isEmpty())words.add(new Word(start,end,line.group(3)));out.add(new Line(start,end,words));}return out;
    }

    private static String renderKrc(String krc,boolean includeRomanization){
        Map<String,String> tags=new HashMap<>();List<Line> lines=new ArrayList<>();Pattern tag=Pattern.compile("^\\[(\\w+):([^]]*)]$"),lp=Pattern.compile("^\\[(\\d+),(\\d+)](.*)$"),wp=Pattern.compile("<(\\d+),(\\d+),\\d+>([^<]*)");
        for(String raw:krc.split("\\R")){String s=raw.trim();Matcher tm=tag.matcher(s);if(tm.matches()){tags.put(tm.group(1),tm.group(2));continue;}Matcher lm=lp.matcher(s);if(!lm.matches())continue;long start=Long.parseLong(lm.group(1)),end=start+Long.parseLong(lm.group(2));List<Word> words=new ArrayList<>();Matcher wm=wp.matcher(lm.group(3));while(wm.find()){long ws=start+Long.parseLong(wm.group(1));words.add(new Word(ws,ws+Long.parseLong(wm.group(2)),wm.group(3)));}if(words.isEmpty())words.add(new Word(start,end,lm.group(3)));lines.add(new Line(start,end,words));}
        List<TextLine> translation=new ArrayList<>(),romanization=new ArrayList<>();String language=tags.getOrDefault("language","");if(!language.isBlank())try{JsonElement decoded=JsonParser.parseString(new String(Base64.getDecoder().decode(language),StandardCharsets.UTF_8));JsonArray items=decoded.isJsonArray()?decoded.getAsJsonArray():decoded.getAsJsonObject().getAsJsonArray("content");for(JsonElement element:items){JsonObject item=element.getAsJsonObject();int type=item.get("type").getAsInt();if(type!=1&&!(includeRomanization&&type==0))continue;JsonArray content=item.getAsJsonArray("lyricContent");for(int i=0;i<lines.size()&&i<content.size();i++){JsonElement entry=content.get(i);JsonArray parts=entry.isJsonArray()?entry.getAsJsonArray():new JsonArray();StringJoiner join=new StringJoiner(type==0?" ":"");for(JsonElement part:parts)if(!part.getAsString().isBlank())join.add(part.getAsString().trim());if(!join.toString().isBlank()){Line line=lines.get(i);(type==0?romanization:translation).add(new TextLine(line.start,line.end,join.toString()));}}}}catch(Exception ignored){}
        return render(lines,translation,romanization);
    }

    private static String render(List<Line> lines,List<TextLine> translation,List<TextLine> romanization){StringBuilder out=new StringBuilder();for(Line line:lines){out.append('[').append(time(line.start)).append(']');long lastEnd=line.start;for(Word word:line.words){if(word.start!=lastEnd)out.append('[').append(time(word.start)).append(']');out.append(word.text);if(word.end>=0)out.append('[').append(time(word.end)).append(']');lastEnd=word.end;}out.append('\n');appendTranslation(out,line,translation);appendTranslation(out,line,romanization);}return out.toString().trim();}
    private static void appendTranslation(StringBuilder out,Line line,List<TextLine> candidates){TextLine best=null;long distance=Long.MAX_VALUE;for(TextLine other:candidates){long d=Math.abs(other.start-line.start);if(d<distance){best=other;distance=d;}}if(best==null||distance>1200)return;String translated=best.text.trim(),original=line.words.stream().map(Word::text).reduce("",String::concat).trim();if(translated.isBlank()||translated.matches("^[/／|｜\\\\s]+$")||sameText(translated,original)||isCreditLine(original))return;out.append('[').append(time(line.start)).append(']').append(translated).append('\n');}
    private static boolean sameText(String a,String b){return a.replaceAll("[\\p{Punct}\\p{IsPunctuation}\\s]+","").equalsIgnoreCase(b.replaceAll("[\\p{Punct}\\p{IsPunctuation}\\s]+",""));}
    private static boolean isCreditLine(String text){return text.matches(".*(?:作词|作曲|编曲|词\\s*[:：]|曲\\s*[:：]).*");}
    private static List<TextLine> parseLrc(String lrc){List<TextLine> out=new ArrayList<>();Pattern p=Pattern.compile("^\\[(\\d+):(\\d+(?:\\.\\d+))](.*)$");for(String raw:lrc.split("\\R")){Matcher m=p.matcher(raw.trim());if(!m.matches())continue;long start=(long)(Long.parseLong(m.group(1))*60000+Double.parseDouble(m.group(2))*1000);out.add(new TextLine(start,start+2000,m.group(3)));}return out;}
    private static List<TextLine> toTextLines(List<Line> lines){List<TextLine> out=new ArrayList<>();for(Line line:lines){String text=line.words.stream().map(Word::text).reduce("",String::concat).trim();if(!text.isBlank())out.add(new TextLine(line.start,line.end,text));}return out;}

    private static String decodeQrc(String value){if(value==null||value.isBlank())return "";try{return inflate(QrcDecryptor.decrypt(HexFormat.of().parseHex(value.replaceAll("[^0-9A-Fa-f]","")),QRC_KEY));}catch(Exception ignored){}try{return new String(Base64.getDecoder().decode(value),StandardCharsets.UTF_8);}catch(Exception ignored){return value;}}
    private static String decryptKrc(String base64)throws Exception{byte[] raw=Base64.getDecoder().decode(base64);byte[] encrypted=Arrays.copyOfRange(raw,4,raw.length);for(int i=0;i<encrypted.length;i++)encrypted[i]^=KRC_KEY[i%KRC_KEY.length];return inflate(encrypted);}
    private static String inflate(byte[] bytes)throws Exception{try(var in=new InflaterInputStream(new ByteArrayInputStream(bytes))){return new String(in.readAllBytes(),StandardCharsets.UTF_8);}}
    private static String signedQuery(Map<String,String> custom)throws Exception{Map<String,String> params=new HashMap<>();params.put("appid","3116");params.put("clientver","11070");params.putAll(custom);StringBuilder raw=new StringBuilder();params.keySet().stream().sorted().forEach(k->raw.append(k).append('=').append(params.get(k)));params.put("signature",md5(KG_SALT+raw+KG_SALT));StringJoiner query=new StringJoiner("&");params.keySet().stream().sorted().forEach(k->query.add(enc(k)+"="+enc(params.get(k))));return query.toString();}
    private static String md5(String s)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8)));}
    private static String get(String url)throws Exception{HttpURLConnection c=open(url);try(var in=c.getInputStream()){return new String(in.readAllBytes(),StandardCharsets.UTF_8);}}
    private static String getApple(String url)throws Exception{HttpURLConnection c=(HttpURLConnection)URI.create(url).toURL().openConnection();c.setConnectTimeout(10000);c.setReadTimeout(15000);c.setRequestProperty("Accept","application/json");c.setRequestProperty("User-Agent","Lyrico/1.0 (github.com/Replica0110/Lyrico)");try(var in=c.getInputStream()){return new String(in.readAllBytes(),StandardCharsets.UTF_8);}}
    private static String post(String url,String body)throws Exception{HttpURLConnection c=open(url);c.setRequestMethod("POST");c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json; charset=utf-8");try(var out=c.getOutputStream()){out.write(body.getBytes(StandardCharsets.UTF_8));}try(var in=c.getInputStream()){return new String(in.readAllBytes(),StandardCharsets.UTF_8);}}
    private static HttpURLConnection open(String url)throws Exception{HttpURLConnection c=(HttpURLConnection)URI.create(url).toURL().openConnection();c.setConnectTimeout(10000);c.setReadTimeout(15000);c.setRequestProperty("User-Agent","Android14-1070-11070-201-0-SearchSong-wifi");return c;}
    private static JsonObject json(String s){return JsonParser.parseString(s).getAsJsonObject();}
    private static String str(JsonObject o,String key){return o!=null&&o.has(key)&&!o.get(key).isJsonNull()?o.get(key).getAsString():"";}
    private static String first(JsonObject o,String...keys){for(String key:keys){String value=str(o,key);if(!value.isBlank())return value;}return "";}
    private static long number(JsonObject o,String key,long fallback){try{return o.has(key)?Math.round(o.get(key).getAsDouble()):fallback;}catch(Exception ignored){return fallback;}}
    private static boolean needsSpace(String text,String next){if(text.isBlank()||next.isBlank())return false;int last=text.codePointBefore(text.length()),first=next.codePointAt(0);return Character.UnicodeScript.of(last)==Character.UnicodeScript.LATIN&&Character.UnicodeScript.of(first)==Character.UnicodeScript.LATIN;}
    private static String b64(String s){return Base64.getEncoder().encodeToString((s==null?"":s).getBytes(StandardCharsets.UTF_8));}
    private static String enc(String s){return URLEncoder.encode(s,StandardCharsets.UTF_8);}
    private static String unescape(String s){return s.replace("&quot;","\"").replace("&apos;","'").replace("&lt;","<").replace("&gt;",">").replace("&amp;","&");}
    private static String time(long ms){return String.format(Locale.ROOT,"%02d:%02d.%03d",ms/60000,(ms%60000)/1000,ms%1000);}
    private record Word(long start,long end,String text){}
    private record Line(long start,long end,List<Word> words){}
    private record TextLine(long start,long end,String text){}
}
