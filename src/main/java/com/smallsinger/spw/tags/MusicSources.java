package com.smallsinger.spw.tags;

import com.google.gson.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

final class MusicSources {
  private static volatile String appleDeveloperToken = "";
  private static final java.util.concurrent.ConcurrentHashMap<
      String, java.util.concurrent.FutureTask<byte[]>> COVER_CACHE =
      new java.util.concurrent.ConcurrentHashMap<>();
  private static final MusicSource QQ = qq();
  private static final MusicSource NETEASE = netease();
  private static final MusicSource APPLE = apple();
  private static final MusicSource KUGOU = kugou();
  static final List<MusicSource> PROVIDERS =
      List.of(QQ, KUGOU, NETEASE, APPLE);
  static final List<MusicSource> ALL =
      List.of(aggregate(), QQ, KUGOU, NETEASE, APPLE);
  record CoverMatch(String source, String title, String artist,
                    long durationMillis, byte[] cover) {}
  record LyricSearchMatch(String title, String artist, String album,
                          String duration, String source, int relevance,
                          String id, String auxiliary, long durationMillis) {}
  private MusicSources() {}

  private static MusicSource aggregate() {
    return source("聚合源", keyword -> {
      List<MusicSource.Result> results = new ArrayList<>();
      Exception last = null;
      for (MusicSource source : PROVIDERS) {
        try {
          MusicSource.Result result = source.search(keyword);
          if (result != null && candidateMatches(keyword, result.title()))
            results.add(result);
        } catch (Exception ex) {
          last = ex;
        }
        if (aggregateComplete(results))
          break;
      }
      if (results.isEmpty()) {
        if (last != null)
          throw last;
        return null;
      }
      return merge(results);
    });
  }

  static MusicSource.Result searchValidated(
      MusicSource selected, String localTitle, String localArtist,
      String localAlbum,
      long wantedDurationMillis, boolean needLyrics,
      java.util.function.Predicate<MusicSource.Result> validator)
      throws Exception {
    if (!"聚合源".equals(selected.name())) {
      ProviderSearch search =
          searchProvider(selected, localTitle, localArtist, localAlbum,
                         wantedDurationMillis, needLyrics, validator);
      if (search.accepted() != null)
        return search.accepted();
      if (!search.receivedCandidate() && search.lastError() != null)
        throw search.lastError();
      return null;
    }
    List<MusicSource.Result> accepted = new ArrayList<>();
    Exception last = null;
    boolean receivedCandidate = false;
    for (MusicSource provider : PROVIDERS) {
      ProviderSearch search =
          searchProvider(provider, localTitle, localArtist, localAlbum,
                         wantedDurationMillis, needLyrics, validator);
      receivedCandidate |= search.receivedCandidate();
      if (search.lastError() != null)
        last = search.lastError();
      if (search.accepted() != null)
        accepted.add(search.accepted());
      if (aggregateComplete(accepted))
        break;
    }
    if (!accepted.isEmpty())
      return merge(accepted);
    if (!receivedCandidate && last != null)
      throw last;
    return null;
  }

  private record ProviderSearch(MusicSource.Result accepted,
                                boolean receivedCandidate,
                                Exception lastError) {}

  private static ProviderSearch searchProvider(
      MusicSource provider, String localTitle, String localArtist,
      String localAlbum, long wantedDurationMillis, boolean needLyrics,
      java.util.function.Predicate<MusicSource.Result> validator) {
    boolean received = false;
    Exception last = null;
    MusicSource.Result best = null;
    List<String> titleOnly = titleQueries(localTitle);
    for (String query : titleOnly) {
      try {
        MusicSource.Result primary = provider.search(query);
        if (primary != null) {
          received = true;
          if (validator.test(primary)) {
            best = betterResult(best, primary, localTitle, localArtist,
                                localAlbum, wantedDurationMillis, needLyrics);
            if (isExcellent(primary, wantedDurationMillis) &&
                (!needLyrics || hasText(primary.lyrics())))
              return new ProviderSearch(primary, true, last);
          }
        }
      } catch (Exception ex) {
        last = ex;
      }
      MusicSource.Result alternative =
          validatedAlternative(provider, query, localTitle, localArtist,
                               localAlbum, wantedDurationMillis, needLyrics,
                               validator);
      if (alternative != null) {
        received = true;
        best = betterResult(best, alternative, localTitle, localArtist,
                            localAlbum, wantedDurationMillis, needLyrics);
        if (isExcellent(alternative, wantedDurationMillis) &&
            (!needLyrics || hasText(alternative.lyrics())))
          return new ProviderSearch(best, true, last);
      }
    }

    // 艺术家只用于标题搜索无结果后的补充查询，避免一开始就把平台的
    // 检索范围限制得过窄。
    if (best == null && hasText(localArtist)) {
      for (String title : titleOnly) {
        String query = title + " " + localArtist.trim();
        try {
          MusicSource.Result primary = provider.search(query);
          if (primary != null) {
            received = true;
            if (validator.test(primary))
              best = betterResult(best, primary, localTitle, localArtist,
                                  localAlbum, wantedDurationMillis, needLyrics);
          }
        } catch (Exception ex) {
          last = ex;
        }
        MusicSource.Result alternative =
            validatedAlternative(provider, query, localTitle, localArtist,
                                 localAlbum, wantedDurationMillis, needLyrics,
                                 validator);
        if (alternative != null) {
          received = true;
          best = betterResult(best, alternative, localTitle, localArtist,
                              localAlbum, wantedDurationMillis, needLyrics);
        }
        if (best != null)
          break;
      }
    }
    return new ProviderSearch(best, received, last);
  }

  private static MusicSource.Result validatedAlternative(
      MusicSource provider, String keyword, String localTitle,
      String localArtist, String localAlbum, long wantedDurationMillis,
      boolean needLyrics,
      java.util.function.Predicate<MusicSource.Result> validator) {
    List<LyricSearchMatch> candidates =
        lyricCandidates(provider, keyword, 24);
    if (candidates.isEmpty())
      return null;
    Comparator<LyricSearchMatch> order =
        Comparator
            .comparingLong((LyricSearchMatch match) ->
                durationDistance(wantedDurationMillis,
                                 match.durationMillis()))
            .thenComparing(
                Comparator.comparingInt((LyricSearchMatch match) ->
                    candidateAffinity(localTitle, localArtist, localAlbum,
                                      match)).reversed())
            .thenComparing(
                Comparator.comparingInt(LyricSearchMatch::relevance)
                    .reversed());
    candidates.sort(order);
    MusicSource.Result firstValid = null;
    for (LyricSearchMatch candidate : candidates) {
      MusicSource.Result summary = new MusicSource.Result(
          candidate.title(), candidate.artist(), candidate.album(), "", "",
          "", "", "", "", "", candidate.durationMillis(), "", "", null);
      if (!validator.test(summary))
        continue;
      if (!needLyrics)
        return summary;
      String fetched = "";
      try {
        fetched = lyricsFor(candidate);
      } catch (Exception ignored) {
      }
      MusicSource.Result resolved = new MusicSource.Result(
          candidate.title(), candidate.artist(), candidate.album(), "", "",
          "", "", "", "", "", candidate.durationMillis(), fetched, "", null);
      if (firstValid == null)
        firstValid = resolved;
      if (!fetched.isBlank())
        return resolved;
    }
    return firstValid;
  }

  private static List<String> titleQueries(String title) {
    LinkedHashSet<String> queries = new LinkedHashSet<>();
    String full = compactSpaces(title);
    if (!full.isBlank())
      queries.add(full);
    String core = readableCoreTitle(full);
    if (!core.isBlank())
      queries.add(core);
    return List.copyOf(queries);
  }

  private static String readableCoreTitle(String title) {
    if (title == null || title.isBlank())
      return "";
    int cut = title.length();
    for (char marker : new char[] {'(', '（', '[', '【'}) {
      int at = title.indexOf(marker);
      if (at >= 0)
        cut = Math.min(cut, at);
    }
    java.util.regex.Matcher mediaSuffix = java.util.regex.Pattern.compile(
        "[-—–|]\\s*[《『【]").matcher(title);
    if (mediaSuffix.find())
      cut = Math.min(cut, mediaSuffix.start());
    String core = compactSpaces(title.substring(0, cut));
    return normalizeMatchText(core).length() >= 2 ? core : "";
  }

  private static String compactSpaces(String value) {
    return value == null ? "" : value.trim().replaceAll("\\s+", " ");
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }

  private static boolean isExcellent(MusicSource.Result result,
                                     long wantedDurationMillis) {
    if (result == null)
      return false;
    if (wantedDurationMillis <= 0 || result.durationMillis() <= 0)
      return true;
    return Math.abs(wantedDurationMillis - result.durationMillis()) <= 2_500;
  }

  private static MusicSource.Result betterResult(
      MusicSource.Result first, MusicSource.Result second, String localTitle,
      String localArtist, String localAlbum, long wantedDurationMillis,
      boolean needLyrics) {
    if (first == null)
      return second;
    if (second == null)
      return first;
    long firstDistance =
        durationDistance(wantedDurationMillis, first.durationMillis());
    long secondDistance =
        durationDistance(wantedDurationMillis, second.durationMillis());
    boolean comparableDistances =
        firstDistance == Long.MAX_VALUE && secondDistance == Long.MAX_VALUE ||
        firstDistance != Long.MAX_VALUE && secondDistance != Long.MAX_VALUE &&
            Math.abs(firstDistance - secondDistance) <= 3_000;
    if (needLyrics && comparableDistances) {
      boolean firstHasLyrics = hasText(first.lyrics());
      boolean secondHasLyrics = hasText(second.lyrics());
      if (firstHasLyrics != secondHasLyrics)
        return secondHasLyrics ? second : first;
    }
    if (firstDistance != secondDistance)
      return secondDistance < firstDistance ? second : first;
    int firstAffinity =
        resultAffinity(localTitle, localArtist, localAlbum, first);
    int secondAffinity =
        resultAffinity(localTitle, localArtist, localAlbum, second);
    if (firstAffinity != secondAffinity)
      return secondAffinity > firstAffinity ? second : first;
    return resultCompleteness(second) > resultCompleteness(first)
               ? second
               : first;
  }

  private static int resultCompleteness(MusicSource.Result result) {
    int score = 0;
    score += hasText(result.title()) ? 1 : 0;
    score += hasText(result.artist()) ? 1 : 0;
    score += hasText(result.album()) ? 1 : 0;
    score += hasText(result.year()) ? 1 : 0;
    score += hasText(result.track()) ? 1 : 0;
    score += hasText(result.lyrics()) ? 2 : 0;
    score += result.cover() != null && result.cover().length > 0 ? 2 : 0;
    return score;
  }

  private static int resultAffinity(
      String localTitle, String localArtist, String localAlbum,
      MusicSource.Result result) {
    return textAffinity(localTitle, result.title(), 100) +
           textAffinity(localArtist, result.artist(), 45) +
           textAffinity(localAlbum, result.album(), 30);
  }

  private static int candidateAffinity(
      String localTitle, String localArtist, String localAlbum,
      LyricSearchMatch match) {
    return textAffinity(localTitle, match.title(), 100) +
           textAffinity(localArtist, match.artist(), 45) +
           textAffinity(localAlbum, match.album(), 30);
  }

  private static int textAffinity(String local, String remote, int weight) {
    String wanted = normalizeMatchText(local);
    String found = normalizeMatchText(remote);
    if (wanted.isBlank() || found.isBlank())
      return 0;
    if (wanted.equals(found))
      return weight;
    if (wanted.contains(found) || found.contains(wanted))
      return Math.max(1, weight * 4 / 5);
    return searchRelevance(local, remote) * weight / 100;
  }

  private static long durationDistance(long wanted, long actual) {
    return wanted > 0 && actual > 0 ? Math.abs(wanted - actual)
                                    : Long.MAX_VALUE;
  }

  private static List<LyricSearchMatch> lyricCandidates(
      MusicSource provider, String keyword, int limit) {
    List<LyricSearchMatch> out = new ArrayList<>();
    switch (provider.name()) {
      case "QQ 音乐" -> lyricQqMatches(keyword, limit, out);
      case "酷狗音乐" -> lyricKugouMatches(keyword, limit, out);
      case "网易云音乐" -> lyricNeteaseMatches(keyword, limit, out);
      default -> {
      }
    }
    return out;
  }

  private static MusicSource.Result merge(List<MusicSource.Result> results) {
      String lyrics = "";
      for (MusicSource.Result result : results)
        if (isWordTimed(result.lyrics())) {
          lyrics = result.lyrics();
          break;
        }
      if (lyrics.isBlank())
        for (MusicSource.Result result : results)
          if (result.lyrics() != null && !result.lyrics().isBlank()) {
            lyrics = result.lyrics();
            break;
          }
      byte[] cover = null;
      for (MusicSource.Result result : results)
        if (result.cover() != null && result.cover().length > 0) {
          cover = result.cover();
          break;
        }
      MusicSource.Result albumOwner = results.stream()
          .filter(result -> result.album() != null &&
                            !result.album().isBlank())
          .findFirst()
          .orElse(null);
      return new MusicSource.Result(
          pick(results, MusicSource.Result::title),
          pick(results, MusicSource.Result::artist),
          pick(results, MusicSource.Result::album),
          pick(results, MusicSource.Result::albumArtist),
          pick(results, MusicSource.Result::year),
          albumOwner == null ? "" : cleanIndex(albumOwner.track()),
          albumOwner == null ? "" : cleanIndex(albumOwner.disc()),
          pick(results, MusicSource.Result::genre),
          pick(results, MusicSource.Result::composer),
          pick(results, MusicSource.Result::lyricist),
          pickDuration(results), lyrics,
          pick(results, MusicSource.Result::comment), cover);
  }

  private static long pickDuration(List<MusicSource.Result> results) {
    for (MusicSource.Result result : results)
      if (result.durationMillis() > 0)
        return result.durationMillis();
    return 0;
  }

  private static String cleanIndex(String value) {
    if (value == null)
      return "";
    String cleaned = value.trim();
    return cleaned.matches("^[1-9]\\d*(?:/[1-9]\\d*)?$") ? cleaned : "";
  }

  private static long longValue(String value) {
    try {
      return value == null || value.isBlank() ? 0 : Long.parseLong(value);
    } catch (NumberFormatException ignored) {
      return 0;
    }
  }

  private static boolean aggregateComplete(List<MusicSource.Result> results) {
    if (results.isEmpty())
      return false;
    boolean title = false, artist = false, album = false, cover = false,
            wordLyrics = false;
    for (MusicSource.Result result : results) {
      title |= result.title() != null && !result.title().isBlank();
      artist |= result.artist() != null && !result.artist().isBlank();
      album |= result.album() != null && !result.album().isBlank();
      cover |= result.cover() != null && result.cover().length > 0;
      wordLyrics |= isWordTimed(result.lyrics());
    }
    return title && artist && album && cover && wordLyrics;
  }

  private static boolean candidateMatches(String keyword, String title) {
    String query = normalizeMatchText(keyword);
    String candidate = normalizeMatchText(title);
    if (query.isBlank() || candidate.isBlank())
      return false;
    if (query.contains(candidate) || candidate.contains(query))
      return true;
    int common = 0;
    for (int i = 0; i < candidate.length(); i++)
      if (query.indexOf(candidate.charAt(i)) >= 0)
        common++;
    return candidate.length() >= 4 && common >= candidate.length() * .72;
  }

  private static String normalizeMatchText(String value) {
    return value == null
        ? ""
        : value.toLowerCase(Locale.ROOT)
              .replaceAll("\\([^)]*(?:ver|version|版|edit)[^)]*\\)", "")
              .replaceAll("[\\p{Punct}\\p{IsPunctuation}\\s_]+", "");
  }

  private static String pick(
      List<MusicSource.Result> results,
      java.util.function.Function<MusicSource.Result, String> getter) {
    for (MusicSource.Result result : results) {
      String value = getter.apply(result);
      if (value != null && !value.isBlank())
        return value;
    }
    return "";
  }

  private static boolean isWordTimed(String value) {
    if (value == null || value.isBlank())
      return false;
    for (String line : value.split("\\R")) {
      java.util.regex.Matcher matcher =
          java.util.regex.Pattern.compile("\\[\\d{2,}:\\d{2}[.:]\\d{1,3}]")
              .matcher(line);
      int count = 0;
      while (matcher.find())
        if (++count >= 2)
          return true;
    }
    return false;
  }

  private static MusicSource netease() {
    return source("网易云音乐", keyword -> {
      NeteaseSource.Match m = NeteaseSource.first(keyword);
      if (m == null)
        return null;
      return new MusicSource.Result(m.title(), m.artist(), m.album(), "",
                                    m.year(), m.track(), "", "", "", "",
                                    m.durationMillis(), m.lyrics(), "",
                                    m.cover());
    });
  }

  private static MusicSource qq() {
    return source("QQ 音乐", keyword -> {
      JsonArray list = qqSearch(keyword);
      if (list.isEmpty())
        return null;
      List<JsonObject> candidates = rankedQqCandidates(list, keyword);
      JsonObject s = candidates.get(0);
      String lyric = "";
      for (int i = 0; i < Math.min(8, candidates.size()); i++) {
        JsonObject candidate = candidates.get(i),
                   candidateAlbum =
                       candidate.has("album") &&
                               candidate.get("album").isJsonObject()
                           ? candidate.getAsJsonObject("album")
                           : new JsonObject();
        String candidateArtist =
                   join(candidate.getAsJsonArray("singer"), "name"),
               candidateTitle = first(candidate, "name", "title");
        try {
          String songId = str(candidate, "id");
          String fetched =
              songId.isBlank()
                  ? ""
                  : StructuredLyrics.qq(
                        Long.parseLong(songId), candidateTitle,
                        first(candidateAlbum, "name", "title"), candidateArtist,
                        Integer.parseInt(str(candidate, "interval")));
          if (!fetched.isBlank()) {
            s = candidate;
            lyric = fetched;
            break;
          }
        } catch (Exception ignored) {
        }
      }
      String artist = join(s.getAsJsonArray("singer"), "name"),
             title = first(s, "name", "title"), mid = str(s, "mid");
      JsonObject album = s.has("album") && s.get("album").isJsonObject()
                             ? s.getAsJsonObject("album")
                             : new JsonObject();
      String albumName = first(album, "name", "title"), albumMid =
                                                            str(album, "mid");
      byte[] cover =
          albumMid.isBlank()
              ? null
              : coverBytes(
                    "https://y.gtimg.cn/music/photo_new/T002R500x500M000" +
                    albumMid + ".jpg");
      return new MusicSource.Result(title, artist, albumName, "",
                                    year(first(s, "time_public", "pubtime")),
                                    str(s, "index_album"), str(s, "index_cd"),
                                    str(s, "genre"), "", "",
                                    longValue(str(s, "interval")) * 1000,
                                    lyric, "", cover);
    });
  }

  private static MusicSource kugou() {
    return source("酷狗音乐", keyword -> {
      JsonObject root = json(get("https://songsearch.kugou.com/" +
                                 "song_search_v2?page=1&pagesize=1&keyword=" +
                                 enc(keyword)));
      JsonArray lists = root.getAsJsonObject("data").getAsJsonArray("lists");
      if (lists.isEmpty())
        return null;
      JsonObject s = lists.get(0).getAsJsonObject();
      String hash = str(s, "FileHash");
      JsonObject detail = new JsonObject();
      try {
        JsonElement data = json(get("https://wwwapi.kugou.com/yy/" +
                                    "index.php?r=play/getdata&hash=" +
                                    enc(hash)))
                               .get("data");
        if (data != null && data.isJsonObject())
          detail = data.getAsJsonObject();
      } catch (Exception ignored) {
      }
      String image = str(detail, "img").replace("{size}", "500");
      byte[] cover = image.isBlank() ? null : coverBytes(image);
      String title = clean(str(s, "SongName")), artist =
                                                    clean(str(s, "SingerName"));
      String lyric = "";
      long duration = longValue(str(s, "Duration")) * 1000;
      try {
        lyric = StructuredLyrics.kugou(str(s, "ID"), hash, duration,
                                       artist + " - " + title);
      } catch (Exception ignored) {
      }
      return new MusicSource.Result(title, artist, clean(str(s, "AlbumName")),
                                    "", "", "", "", "", "", "", duration,
                                    lyric, "", cover);
    });
  }

  private static MusicSource apple() {
    return source("Apple Music", keyword -> {
      JsonArray results = appleSongs(keyword, 1);
      if (results.isEmpty())
        return null;
      JsonObject s = results.get(0).getAsJsonObject();
      String image = appleArtwork(s, 600);
      byte[] cover = image.isBlank() ? null : coverBytes(image);
      return new MusicSource.Result(
          str(s, "trackName"), str(s, "artistName"), str(s, "collectionName"),
          str(s, "artistName"), year(str(s, "releaseDate")),
          str(s, "trackNumber"), str(s, "discNumber"),
          str(s, "primaryGenreName"), "", "",
          longValue(first(s, "trackTimeMillis", "durationInMillis")),
          "", "", cover);
    });
  }

  private interface Search {
    MusicSource.Result run(String keyword) throws Exception;
  }
  private static MusicSource source(String name, Search search) {
    return new MusicSource() {
      public String name() { return name; }
      public MusicSource.Result search(String keyword) throws Exception {
        return search.run(keyword);
      }
      public String toString() { return name; }
    };
  }
  private static String get(String url) throws Exception {
    return new String(getBytes(url), StandardCharsets.UTF_8);
  }
  private static byte[] getBytes(String url) throws Exception {
    HttpURLConnection c =
        (HttpURLConnection)URI.create(url).toURL().openConnection();
    c.setConnectTimeout(10000);
    c.setReadTimeout(15000);
    c.setRequestProperty("User-Agent", "Mozilla/5.0");
    c.setRequestProperty("Referer", "https://y.qq.com/");
    try (var in = c.getInputStream()) {
      return in.readAllBytes();
    }
  }
  static byte[] coverBytes(String url) throws Exception {
    if (url == null || url.isBlank())
      return new byte[0];
    var created = new java.util.concurrent.FutureTask<byte[]>(
        () -> getBytes(url));
    var task = COVER_CACHE.putIfAbsent(url, created);
    if (task == null) {
      task = created;
      created.run();
    }
    try {
      return task.get();
    } catch (java.util.concurrent.ExecutionException ex) {
      COVER_CACHE.remove(url, task);
      Throwable cause = ex.getCause();
      if (cause instanceof Exception exception)
        throw exception;
      throw new RuntimeException(cause);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw ex;
    }
  }
  static void clearCoverCache() {
    COVER_CACHE.clear();
  }
  private static JsonArray qqSearch(String keyword) throws Exception {
    JsonObject param = new JsonObject();
    param.addProperty("search_id",
                      String.valueOf(36028797018963968L +
                                     System.currentTimeMillis() % 86400000));
    param.addProperty("remoteplace", "search.android.keyboard");
    param.addProperty("query", keyword);
    param.addProperty("search_type", 0);
    param.addProperty("num_per_page", 20);
    param.addProperty("page_num", 1);
    param.addProperty("highlight", 0);
    param.addProperty("nqc_flag", 0);
    param.addProperty("page_id", 1);
    param.addProperty("grp", 1);
    JsonObject request = new JsonObject();
    request.addProperty("method", "DoSearchForQQMusicLite");
    request.addProperty("module", "music.search.SearchCgiService");
    request.add("param", param);
    JsonObject comm = new JsonObject();
    comm.addProperty("ct", 11);
    comm.addProperty("cv", "1003006");
    comm.addProperty("v", "1003006");
    comm.addProperty("os_ver", "15");
    comm.addProperty("phonetype", "24122RKC7C");
    comm.addProperty("tmeAppID", "qqmusiclight");
    comm.addProperty("nettype", "NETWORK_WIFI");
    comm.addProperty("udid", "0");
    JsonObject body = new JsonObject();
    body.add("comm", comm);
    body.add("request", request);
    JsonObject root =
        json(post("https://u.y.qq.com/cgi-bin/musicu.fcg", body.toString()));
    JsonObject response =
        root.has("request") ? root.getAsJsonObject("request") : null;
    if (response == null || !response.has("data"))
      return new JsonArray();
    JsonObject data = response.getAsJsonObject("data"),
               result = data.getAsJsonObject("body");
    return result != null && result.has("item_song")
        ? result.getAsJsonArray("item_song")
        : new JsonArray();
  }
  private static List<JsonObject> rankedQqCandidates(JsonArray list,
                                                     String keyword) {
    String wanted = normalize(keyword);
    List<JsonObject> songs = new ArrayList<>();
    for (JsonElement element : list)
      songs.add(element.getAsJsonObject());
    songs.sort(
        Comparator.comparingInt((JsonObject song) -> qqScore(song, wanted))
            .reversed());
    return songs;
  }
  private static int qqScore(JsonObject song, String wanted) {
    String title = normalize(first(song, "name", "title")),
           artist = normalize(join(song.getAsJsonArray("singer"), "name"));
    int score = 0;
    if (!title.isBlank()) {
      if (wanted.contains(title))
        score += 240;
      else if (title.contains(wanted))
        score += 160;
    }
    if (!artist.isBlank() && wanted.contains(artist))
      score += 360;
    String extra = normalize(first(song, "title_extra", "subtitle", "desc"));
    if (!extra.isBlank() && wanted.contains(extra))
      score += 50;
    return score;
  }
  private static String post(String url, String body) throws Exception {
    HttpURLConnection c =
        (HttpURLConnection)URI.create(url).toURL().openConnection();
    c.setConnectTimeout(10000);
    c.setReadTimeout(15000);
    c.setRequestMethod("POST");
    c.setDoOutput(true);
    c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
    c.setRequestProperty("Cookie", "tmeLoginType=-1;");
    c.setRequestProperty("User-Agent", "okhttp/3.14.9");
    try (var out = c.getOutputStream()) {
      out.write(body.getBytes(StandardCharsets.UTF_8));
    }
    try (var in = c.getInputStream()) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
  private static JsonObject json(String s) {
    return JsonParser.parseString(s).getAsJsonObject();
  }
  private static String enc(String s) {
    return URLEncoder.encode(s, StandardCharsets.UTF_8);
  }
  private static String str(JsonObject o, String key) {
    return o != null && o.has(key) && !o.get(key).isJsonNull()
        ? o.get(key).getAsString()
        : "";
  }
  private static String join(JsonArray a, String key) {
    if (a == null)
      return "";
    StringJoiner j = new StringJoiner("/");
    for (JsonElement e : a)
      j.add(str(e.getAsJsonObject(), key));
    return j.toString();
  }
  private static String clean(String s) { return s.replaceAll("<[^>]+>", ""); }
  private static String year(String s) {
    return s != null && s.length() >= 4 ? s.substring(0, 4) : "";
  }
  private static String first(JsonObject o, String... keys) {
    for (String key : keys) {
      String value = str(o, key);
      if (!value.isBlank())
        return value;
    }
    return "";
  }
  private static String normalize(String value) {
    return value == null
        ? ""
        : value.toLowerCase(Locale.ROOT)
              .replaceAll("[\\p{Punct}\\p{IsPunctuation}\\s]+", "");
  }
  static List<CoverMatch> coverMatches(MusicSource source, String keyword,
                                       int limit) throws Exception {
    if (source == null || limit <= 0)
      return List.of();
    return switch (source.name()) {
      case "QQ 音乐" -> qqCovers(keyword, limit);
      case "网易云音乐" -> neteaseCovers(keyword, limit);
      case "酷狗音乐" -> kugouCovers(keyword, limit);
      case "Apple Music" -> appleCovers(keyword, limit);
      default -> List.of();
    };
  }
  static List<LyricSearchMatch> lyricSearchMatches(String keyword, int perSource,
                                                   long wantedDurationMillis)
      throws Exception {
    int limit = Math.max(1, perSource);
    List<LyricSearchMatch> out =
        Collections.synchronizedList(new ArrayList<>());
    java.util.concurrent.CompletableFuture.allOf(
        java.util.concurrent.CompletableFuture.runAsync(
            () -> lyricQqMatches(keyword, limit, out)),
        java.util.concurrent.CompletableFuture.runAsync(
            () -> lyricNeteaseMatches(keyword, limit, out)),
        java.util.concurrent.CompletableFuture.runAsync(
            () -> lyricKugouMatches(keyword, limit, out))).join();
    Comparator<LyricSearchMatch> order;
    if (wantedDurationMillis > 0)
      order = Comparator
          .comparingLong((LyricSearchMatch match) ->
              match.durationMillis() > 0
                  ? Math.abs(match.durationMillis() - wantedDurationMillis)
                  : Long.MAX_VALUE)
          .thenComparing(
              Comparator.comparingInt(LyricSearchMatch::relevance).reversed())
          .thenComparingInt(match -> sourceRank(match.source()));
    else
      order = Comparator
          .comparingInt(LyricSearchMatch::relevance).reversed()
          .thenComparingInt(match -> sourceRank(match.source()));
    out.sort(order);
    return List.copyOf(out);
  }
  private static int sourceRank(String source) {
    return switch (source) {
      case "QQ 音乐" -> 0;
      case "酷狗音乐" -> 1;
      case "网易云音乐" -> 2;
      default -> 3;
    };
  }
  private static void lyricQqMatches(String keyword, int limit,
                                    List<LyricSearchMatch> out) {
    try {
      int count = 0;
      for (JsonObject song : rankedQqCandidates(qqSearch(keyword), keyword)) {
        JsonObject album = song.has("album") && song.get("album").isJsonObject()
            ? song.getAsJsonObject("album") : new JsonObject();
        out.add(new LyricSearchMatch(
            first(song, "name", "title"),
            join(song.getAsJsonArray("singer"), "name"),
            first(album, "name", "title"),
            duration(str(song, "interval")), "QQ 音乐",
            searchRelevance(keyword, first(song, "name", "title")),
            str(song, "id"), "", parseLong(str(song, "interval")) * 1000));
        if (++count >= limit)
          break;
      }
    } catch (Exception ignored) {
    }
  }
  private static void lyricNeteaseMatches(String keyword, int limit,
                                          List<LyricSearchMatch> out) {
    try {
      String body = "s=" + enc(keyword) + "&type=1&offset=0&limit=" +
                    Math.max(20, limit);
      JsonObject root = json(
          postForm("https://music.163.com/api/cloudsearch/pc", body));
      JsonObject result = root.getAsJsonObject("result");
      JsonArray songs =
          result == null ? null : result.getAsJsonArray("songs");
      if (songs == null)
        return;
      int count = 0;
      for (JsonElement element : songs) {
        JsonObject song = element.getAsJsonObject();
        JsonObject album = song.has("al") ? song.getAsJsonObject("al")
                                          : song.getAsJsonObject("album");
        JsonArray artists = song.has("ar") ? song.getAsJsonArray("ar")
                                           : song.getAsJsonArray("artists");
        String name = str(song, "name");
        String millis = str(song, "dt");
        if (millis.isBlank())
          millis = str(song, "duration");
        out.add(new LyricSearchMatch(
            name, join(artists, "name"), album == null ? "" : str(album, "name"),
            durationMillis(millis), "网易云音乐",
            searchRelevance(keyword, name), str(song, "id"), "",
            parseLong(millis)));
        if (++count >= limit)
          break;
      }
    } catch (Exception ignored) {
    }
  }
  private static void lyricKugouMatches(String keyword, int limit,
                                        List<LyricSearchMatch> out) {
    try {
      JsonObject root = json(
          get("https://songsearch.kugou.com/song_search_v2?page=1&pagesize=" +
              Math.max(20, limit) + "&keyword=" + enc(keyword)));
      JsonArray songs = root.getAsJsonObject("data").getAsJsonArray("lists");
      int count = 0;
      for (JsonElement element : songs) {
        JsonObject song = element.getAsJsonObject();
        String name = clean(str(song, "SongName"));
        out.add(new LyricSearchMatch(
            name, clean(str(song, "SingerName")),
            clean(str(song, "AlbumName")), duration(str(song, "Duration")),
            "酷狗音乐", searchRelevance(keyword, name), str(song, "ID"),
            str(song, "FileHash"), parseLong(str(song, "Duration")) * 1000));
        if (++count >= limit)
          break;
      }
    } catch (Exception ignored) {
    }
  }
  private static int searchRelevance(String keyword, String title) {
    String wanted = normalizeMatchText(keyword), found = normalizeMatchText(title);
    if (wanted.isBlank() || found.isBlank())
      return 0;
    if (wanted.equals(found))
      return 100;
    if (found.contains(wanted) || wanted.contains(found))
      return Math.max(55, 100 - Math.abs(wanted.length() - found.length()) * 3);
    int common = 0;
    Set<Integer> used = new HashSet<>();
    for (int i = 0; i < wanted.length(); i++) {
      int at = found.indexOf(wanted.charAt(i));
      if (at >= 0 && used.add(at))
        common++;
    }
    return Math.min(99, Math.round(common * 100f /
                                   Math.max(wanted.length(), found.length())));
  }
  private static String duration(String seconds) {
    try {
      long value = Long.parseLong(seconds);
      return String.format(Locale.ROOT, "%02d:%02d", value / 60, value % 60);
    } catch (Exception ignored) {
      return "";
    }
  }
  private static String durationMillis(String millis) {
    try {
      return duration(String.valueOf(Long.parseLong(millis) / 1000));
    } catch (Exception ignored) {
      return "";
    }
  }
  private static long parseLong(String value) {
    try {
      return Long.parseLong(value);
    } catch (Exception ignored) {
      return 0;
    }
  }
  static String lyricsFor(LyricSearchMatch match) throws Exception {
    if (match == null || match.id().isBlank())
      return "";
    return switch (match.source()) {
      case "QQ 音乐" -> StructuredLyrics.qqAdvanced(
          Long.parseLong(match.id()), match.title(), match.album(),
          match.artist(), (int)(match.durationMillis() / 1000));
      case "网易云音乐" ->
          NeteaseSource.lyricsWithRomanization(Long.parseLong(match.id()));
      case "酷狗音乐" -> StructuredLyrics.kugouAdvanced(
          match.id(), match.auxiliary(), match.durationMillis(),
          match.artist() + " - " + match.title());
      default -> "";
    };
  }
  private static List<CoverMatch> qqCovers(String keyword, int limit)
      throws Exception {
    List<CoverMatch> out = new ArrayList<>();
    for (JsonObject song : rankedQqCandidates(qqSearch(keyword), keyword)) {
      JsonObject album = song.has("album") && song.get("album").isJsonObject()
                             ? song.getAsJsonObject("album")
                             : new JsonObject();
      String mid = str(album, "mid");
      if (mid.isBlank())
        continue;
      try {
        byte[] bytes = firstAvailableBytes(
            "https://y.gtimg.cn/music/photo_new/T002R0x0M000" + mid + ".jpg",
            "https://y.gtimg.cn/music/photo_new/T002R800x800M000" + mid +
                ".jpg");
        if (bytes.length > 0)
          out.add(new CoverMatch(
              "QQ 音乐", first(song, "name", "title"),
              join(song.getAsJsonArray("singer"), "name"),
              longValue(str(song, "interval")) * 1000, bytes));
      } catch (Exception ignored) {
      }
      if (out.size() >= limit)
        break;
    }
    return out;
  }
  private static List<CoverMatch> neteaseCovers(String keyword, int limit)
      throws Exception {
    String body =
        "s=" + enc(keyword) + "&type=1&offset=0&limit=" + Math.max(limit, 10);
    JsonObject root = json(
                   postForm("https://music.163.com/api/cloudsearch/pc", body)),
               result = root.getAsJsonObject("result");
    JsonArray songs =
        result == null ? new JsonArray() : result.getAsJsonArray("songs");
    List<CoverMatch> out = new ArrayList<>();
    if (songs == null)
      return out;
    for (JsonElement element : songs) {
      JsonObject song = element.getAsJsonObject(),
                 album = song.has("al") ? song.getAsJsonObject("al")
                                        : song.getAsJsonObject("album");
      JsonArray artists = song.has("ar") ? song.getAsJsonArray("ar")
                                         : song.getAsJsonArray("artists");
      String url = album == null ? "" : str(album, "picUrl");
      if (url.isBlank())
        continue;
      try {
        out.add(new CoverMatch(
            "网易云音乐", str(song, "name"), join(artists, "name"),
            longValue(first(song, "dt", "duration")), coverBytes(url)));
      } catch (Exception ignored) {
      }
      if (out.size() >= limit)
        break;
    }
    return out;
  }
  private static List<CoverMatch> kugouCovers(String keyword, int limit)
      throws Exception {
    JsonObject root = json(
        get("https://songsearch.kugou.com/song_search_v2?page=1&pagesize=" +
            Math.max(limit, 10) + "&keyword=" + enc(keyword)));
    JsonArray songs = root.getAsJsonObject("data").getAsJsonArray("lists");
    List<CoverMatch> out = new ArrayList<>();
    for (JsonElement element : songs) {
      JsonObject song = element.getAsJsonObject();
      String url = str(song, "Image");
      try {
        JsonObject detail = json(get("https://wwwapi.kugou.com/yy/" +
                                     "index.php?r=play/getdata&hash=" +
                                     enc(str(song, "FileHash"))))
                                .getAsJsonObject("data");
        String detailUrl = str(detail, "img");
        if (!detailUrl.isBlank())
          url = detailUrl;
      } catch (Exception ignored) {
      }
      url = url.replaceFirst("^http:", "https:");
      try {
        if (!url.isBlank()) {
          byte[] bytes = url.contains("{size}")
                             ? firstAvailableBytes(url.replace("{size}", "0"),
                                                   url.replace("{size}", "500"))
                             : coverBytes(url);
          out.add(new CoverMatch(
              "酷狗音乐", clean(str(song, "SongName")),
              clean(str(song, "SingerName")),
              longValue(str(song, "Duration")) * 1000, bytes));
        }
      } catch (Exception ignored) {
      }
      if (out.size() >= limit)
        break;
    }
    return out;
  }
  private static List<CoverMatch> appleCovers(String keyword, int limit)
      throws Exception {
    JsonArray songs = appleSongs(keyword, Math.max(limit, 10));
    List<CoverMatch> out = new ArrayList<>();
    for (JsonElement element : songs) {
      JsonObject song = element.getAsJsonObject();
      String sourceUrl = appleArtwork(song, 3000);
      String originalUrl = appleArtwork(song, 9999);
      if (sourceUrl.isBlank())
        continue;
      try {
        byte[] bytes = firstAvailableBytes(
            originalUrl, sourceUrl, appleArtwork(song, 1000));
        out.add(new CoverMatch(
            "Apple Music", str(song, "trackName"), str(song, "artistName"),
            longValue(first(song, "trackTimeMillis", "durationInMillis")),
            bytes));
      } catch (Exception ignored) {
      }
      if (out.size() >= limit)
        break;
    }
    return out;
  }
  private static JsonArray appleSongs(String keyword, int limit)
      throws Exception {
    try {
      String token = appleToken();
      if (!token.isBlank()) {
        String url =
            "https://amp-api.music.apple.com/v1/catalog/cn/search?term=" +
            enc(keyword) + "&types=songs&limit=" + Math.max(1, limit) +
            "&l=zh-Hans&platform=web&format%5Bresources%5D=map";
        JsonObject headers = new JsonObject();
        headers.addProperty("Authorization", "Bearer " + token);
        headers.addProperty("Origin", "https://music.apple.com");
        headers.addProperty("Referer", "https://music.apple.com/");
        JsonObject root = json(get(url, headers));
        JsonObject results = root.getAsJsonObject("results");
        JsonObject songsResult =
            results == null ? null : results.getAsJsonObject("songs");
        JsonArray data =
            songsResult == null ? null : songsResult.getAsJsonArray("data");
        if (data != null && !data.isEmpty()) {
          JsonArray mapped = new JsonArray();
          JsonObject resources =
              root.has("resources") &&
                      root.getAsJsonObject("resources").has("songs")
                  ? root.getAsJsonObject("resources").getAsJsonObject("songs")
                  : new JsonObject();
          for (JsonElement element : data) {
            JsonObject item = element.getAsJsonObject();
            String id = str(item, "id");
            JsonObject song =
                resources.has(id) ? resources.getAsJsonObject(id) : item;
            JsonObject attrs =
                song.has("attributes") ? song.getAsJsonObject("attributes")
                                       : new JsonObject();
            JsonObject normalized = new JsonObject();
            normalized.addProperty("trackId", id);
            normalized.addProperty("trackName", str(attrs, "name"));
            normalized.addProperty("artistName", str(attrs, "artistName"));
            normalized.addProperty("collectionName", str(attrs, "albumName"));
            normalized.addProperty("releaseDate", str(attrs, "releaseDate"));
            normalized.addProperty("trackNumber", str(attrs, "trackNumber"));
            normalized.addProperty("discNumber", str(attrs, "discNumber"));
            normalized.addProperty("trackTimeMillis",
                                   str(attrs, "durationInMillis"));
            if (attrs.has("genreNames") &&
                attrs.get("genreNames").isJsonArray())
              normalized.addProperty(
                  "primaryGenreName",
                  joinValues(attrs.getAsJsonArray("genreNames")));
            if (attrs.has("artwork") && attrs.get("artwork").isJsonObject())
              normalized.addProperty(
                  "artworkTemplate",
                  str(attrs.getAsJsonObject("artwork"), "url"));
            mapped.add(normalized);
          }
          return mapped;
        }
      }
    } catch (Exception ignored) {
    }
    JsonObject fallback =
        json(get("https://itunes.apple.com/search?entity=song&limit=" +
                 Math.max(1, limit) + "&term=" + enc(keyword)));
    return fallback.getAsJsonArray("results");
  }
  private static String appleToken() throws Exception {
    if (!appleDeveloperToken.isBlank())
      return appleDeveloperToken;
    JsonObject headers = new JsonObject();
    headers.addProperty(
        "Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
    String home = get("https://music.apple.com", headers);
    java.util.regex.Matcher asset = java.util.regex.Pattern.compile(
        "/assets/index(?:-legacy)?[~\\-][^\"']+\\.js").matcher(home);
    if (!asset.find())
      return "";
    headers = new JsonObject();
    headers.addProperty("Accept", "*/*");
    headers.addProperty("Referer", "https://music.apple.com/");
    String javascript =
        get("https://music.apple.com" + asset.group(), headers);
    java.util.regex.Matcher tokens = java.util.regex.Pattern.compile(
        "([A-Za-z0-9_-]{20,}\\.[A-Za-z0-9_-]{20,}\\.[A-Za-z0-9_-]{20,})")
        .matcher(javascript);
    while (tokens.find()) {
      String token = tokens.group(1);
      String[] parts = token.split("\\.");
      try {
        String header = new String(Base64.getUrlDecoder().decode(parts[0]),
                                   StandardCharsets.UTF_8);
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]),
                                    StandardCharsets.UTF_8);
        if (header.contains("\"kid\":\"WebPlayKid\"") &&
            payload.contains("\"iss\":\"AMPWebPlay\"")) {
          appleDeveloperToken = token;
          return token;
        }
      } catch (Exception ignored) {
      }
    }
    return "";
  }
  private static String appleArtwork(JsonObject song, int size) {
    String template = str(song, "artworkTemplate");
    if (!template.isBlank())
      return template.replace("{w}", String.valueOf(size))
          .replace("{h}", String.valueOf(size)).replace("{f}", "jpg");
    return str(song, "artworkUrl100")
        .replaceFirst("\\d+x\\d+bb", size + "x" + size + "bb");
  }
  private static String joinValues(JsonArray values) {
    StringJoiner joiner = new StringJoiner(" / ");
    for (JsonElement value : values)
      if (!value.isJsonNull())
        joiner.add(value.getAsString());
    return joiner.toString();
  }
  private static String get(String url, JsonObject headers) throws Exception {
    HttpURLConnection c =
        (HttpURLConnection)URI.create(url).toURL().openConnection();
    c.setConnectTimeout(10000);
    c.setReadTimeout(15000);
    c.setRequestProperty(
        "User-Agent",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120 Safari/537.36");
    if (headers != null)
      for (Map.Entry<String, JsonElement> entry : headers.entrySet())
        c.setRequestProperty(entry.getKey(), entry.getValue().getAsString());
    try (var in = c.getInputStream()) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
  private static byte[] firstAvailableBytes(String... urls) throws Exception {
    Exception last = null;
    for (String url : urls) {
      if (url == null || url.isBlank())
        continue;
      try {
        byte[] bytes = coverBytes(url);
        if (bytes.length > 0)
          return bytes;
      } catch (Exception ex) {
        last = ex;
      }
    }
    if (last != null)
      throw last;
    return new byte[0];
  }
  private static String postForm(String url, String body) throws Exception {
    HttpURLConnection c =
        (HttpURLConnection)URI.create(url).toURL().openConnection();
    c.setConnectTimeout(10000);
    c.setReadTimeout(15000);
    c.setRequestMethod("POST");
    c.setDoOutput(true);
    c.setRequestProperty("Content-Type",
                         "application/x-www-form-urlencoded; charset=utf-8");
    c.setRequestProperty("User-Agent", "Mozilla/5.0");
    try (var out = c.getOutputStream()) {
      out.write(body.getBytes(StandardCharsets.UTF_8));
    }
    try (var in = c.getInputStream()) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
