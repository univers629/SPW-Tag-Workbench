package com.smallsinger.spw.tags;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.WString;
import com.sun.jna.win32.StdCallLibrary;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.File;
import java.lang.ref.SoftReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Uses the BASS decoder already owned and initialized by SPW. This class never
 * initializes or shuts down BASS globally; it only frees streams that it
 * creates itself.
 */
final class SpectrogramService implements AutoCloseable {
  interface Listener {
    void onProgress(Rendered rendered, int percent);
    void onComplete(Rendered rendered);
    void onFailure(String message, Throwable error);
  }

  record Rendered(BufferedImage image, float[] overviewEnvelope,
                  int channels, int sampleRate, double durationSeconds) {}

  private record CacheKey(String path, long size, long modified, int width,
                          int height) {}

  private static final int BASS_SAMPLE_FLOAT = 0x100;
  private static final int BASS_STREAM_DECODE = 0x200000;
  private static final int BASS_UNICODE = 0x80000000;
  private static final int BASS_POS_BYTE = 0;
  private static final int BASS_DATA_FFT_INDIVIDUAL = 0x10;
  private static final int BASS_DATA_FFT_REMOVEDC = 0x40;
  private static final int BASS_DATA_FFT4096 = 0x80000004;
  private static final int FFT_SIZE = 4096;
  private static final int FFT_BINS = FFT_SIZE / 2;
  private static final int MIN_DB = -80;
  private static final int MAX_DB = -10;

  private final ExecutorService executor =
      Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "spw-spectrogram");
        thread.setDaemon(true);
        return thread;
      });
  private final AtomicLong generation = new AtomicLong();
  private final Map<CacheKey, SoftReference<Rendered>> cache =
      new LinkedHashMap<>(8, .75f, true) {
        @Override
        protected boolean removeEldestEntry(
            Map.Entry<CacheKey, SoftReference<Rendered>> eldest) {
          return size() > 6;
        }
      };
  private volatile Future<?> running;
  private volatile int activeStream;
  private volatile BassNative bass;

  void render(File file, int width, int height, Listener listener) {
    cancel();
    long token = generation.incrementAndGet();
    int renderWidth = Math.max(320, Math.min(1800, width));
    int renderHeight = Math.max(220, Math.min(1000, height));
    CacheKey key;
    try {
      Path path = file.toPath().toAbsolutePath().normalize();
      key = new CacheKey(path.toString(), Files.size(path),
                         Files.getLastModifiedTime(path).toMillis(),
                         renderWidth, renderHeight);
    } catch (Exception error) {
      listener.onFailure("无法读取音频文件", error);
      return;
    }
    Rendered cached = cached(key);
    if (cached != null) {
      listener.onProgress(cached, 100);
      listener.onComplete(cached);
      return;
    }
    running = executor.submit(() -> {
      int stream = 0;
      try {
        BassNative nativeBass = bass();
        stream = nativeBass.BASS_StreamCreateFile(
            false, new WString(key.path()), 0, 0,
            BASS_SAMPLE_FLOAT | BASS_STREAM_DECODE | BASS_UNICODE);
        if (stream == 0)
          throw new BassException(nativeBass.BASS_ErrorGetCode());
        activeStream = stream;
        BassChannelInfo info = new BassChannelInfo();
        if (!nativeBass.BASS_ChannelGetInfo(stream, info))
          throw new BassException(nativeBass.BASS_ErrorGetCode());
        int sourceChannels = Math.max(1, info.chans);
        int displayChannels = Math.min(2, sourceChannels);
        long byteLength =
            nativeBass.BASS_ChannelGetLength(stream, BASS_POS_BYTE);
        if (byteLength <= 0)
          throw new IllegalStateException("无法取得音频时长");
        double duration =
            nativeBass.BASS_ChannelBytes2Seconds(stream, byteLength);
        if (!Double.isFinite(duration) || duration <= 0)
          throw new IllegalStateException("音频时长无效");
        BufferedImage image = new BufferedImage(
            renderWidth, renderHeight, BufferedImage.TYPE_INT_ARGB);
        int[] pixels =
            ((DataBufferInt)image.getRaster().getDataBuffer()).getData();
        java.util.Arrays.fill(pixels, 0x00000000);
        byte[] levels = new byte[renderWidth * renderHeight];
        float[] overviewEnvelope = new float[renderWidth];
        float[] fft = new float[FFT_BINS * sourceChannels];
        long expected = Math.max(
            1L, (long)Math.ceil(duration * Math.max(1, info.freq) / FFT_SIZE));
        long frame = 0;
        int lastRenderedX = -1;
        int lastPublished = -1;
        long lastPublishTime = System.nanoTime();
        Rendered rendered =
            new Rendered(image, overviewEnvelope, displayChannels, info.freq,
                         duration);
        while (token == generation.get() &&
               !Thread.currentThread().isInterrupted()) {
          int read = nativeBass.BASS_ChannelGetData(
              stream, fft, BASS_DATA_FFT4096 |
                               BASS_DATA_FFT_INDIVIDUAL |
                               BASS_DATA_FFT_REMOVEDC);
          if (read < 0)
            break;
          int gap = displayChannels == 2 ? 2 : 0;
          int channelWidth = (renderWidth - gap) / displayChannels;
          int x = Math.min(channelWidth - 1,
                           (int)Math.floor(frame * channelWidth /
                                           (double)expected));
          // If a short file advances by more than one display column, repeat
          // the complete FFT slice into the skipped columns. Never interpolate
          // individual frequency pixels horizontally: silence is valid data,
          // not a missing point to connect.
          for (int fill = lastRenderedX + 1; fill < x; fill++)
            applyColumn(levels, pixels, renderWidth, renderHeight, fill, fft,
                        sourceChannels, displayChannels);
          applyColumn(levels, pixels, renderWidth, renderHeight, x, fft,
                      sourceChannels, displayChannels);
          lastRenderedX = Math.max(lastRenderedX, x);
          int overviewX = Math.min(
              renderWidth - 1,
              (int)Math.floor(frame * renderWidth / (double)expected));
          applyEnvelopeColumn(overviewX, fft, sourceChannels,
                              overviewEnvelope);
          frame++;
          long now = System.nanoTime();
          if (x != lastPublished &&
              (x - lastPublished >= 20 ||
               now - lastPublishTime >= 55_000_000L)) {
            lastPublished = x;
            lastPublishTime = now;
            listener.onProgress(
                rendered, Math.min(99, (int)Math.round(
                                         frame * 100d / expected)));
          }
          if (read == 0)
            break;
        }
        if (token != generation.get() ||
            Thread.currentThread().isInterrupted())
          return;
        fillEnvelopeGaps(overviewEnvelope);
        putCached(key, rendered);
        listener.onProgress(rendered, 100);
        listener.onComplete(rendered);
      } catch (Throwable error) {
        if (token == generation.get() &&
            !Thread.currentThread().isInterrupted())
          listener.onFailure(friendlyMessage(error), error);
      } finally {
        if (stream != 0) {
          try {
            bass().BASS_StreamFree(stream);
          } catch (Throwable ignored) {
          }
        }
        if (activeStream == stream)
          activeStream = 0;
      }
    });
  }

  void cancel() {
    generation.incrementAndGet();
    Future<?> task = running;
    if (task != null)
      task.cancel(true);
    running = null;
  }
  void clearResults() {
    cancel();
    synchronized (this) {
      for (SoftReference<Rendered> reference : cache.values()) {
        Rendered rendered = reference.get();
        if (rendered != null)
          rendered.image().flush();
      }
      cache.clear();
    }
  }

  private synchronized Rendered cached(CacheKey key) {
    SoftReference<Rendered> reference = cache.get(key);
    Rendered rendered = reference == null ? null : reference.get();
    if (reference != null && rendered == null)
      cache.remove(key);
    return rendered;
  }

  private synchronized void putCached(CacheKey key, Rendered rendered) {
    cache.put(key, new SoftReference<>(rendered));
  }

  private BassNative bass() {
    BassNative value = bass;
    if (value != null)
      return value;
    synchronized (this) {
      if (bass == null) {
        Path library = locateBass();
        bass = Native.load(library.toString(), BassNative.class,
                           Map.of(Library.OPTION_STRING_ENCODING, "UTF-8"));
      }
      return bass;
    }
  }

  private static Path locateBass() {
    String resources =
        System.getProperty("compose.application.resources.dir", "");
    if (!resources.isBlank()) {
      Path path = Path.of(resources).resolve("bass.dll");
      if (Files.isRegularFile(path))
        return path;
    }
    String command =
        ProcessHandle.current().info().command().orElse("");
    if (!command.isBlank()) {
      Path executable = Path.of(command).toAbsolutePath();
      Path path = executable.getParent()
                      .resolve("app")
                      .resolve("resources")
                      .resolve("bass.dll");
      if (Files.isRegularFile(path))
        return path;
    }
    throw new IllegalStateException("未找到 SPW 内置音频解码器");
  }

  private static void applyColumn(byte[] levels, int[] pixels, int width,
                                  int height, int x, float[] fft,
                                  int sourceChannels, int displayChannels) {
    int gap = displayChannels == 2 ? 2 : 0;
    int channelWidth = (width - gap) / displayChannels;
    for (int channel = 0; channel < displayChannels; channel++) {
      int left = channel * (channelWidth + gap);
      for (int y = 0; y < height; y++) {
        double frequencyRatio =
            (height - 1d - y) / Math.max(1d, height - 1d);
        int bin = Math.max(
            1, Math.min(FFT_BINS - 1,
                        (int)Math.round(frequencyRatio * (FFT_BINS - 1))));
        // The first bins sit at or below the useful audible range and often
        // contain a constant residual floor. Drawing them creates a bright
        // horizontal baseline across the bottom of the entire image.
        if (bin <= 2)
          continue;
        float magnitude = fft[bin * sourceChannels + channel];
        double db = 20d * Math.log10(Math.max(1e-9, magnitude));
        int level = (int)Math.round(
            255d * (db - MIN_DB) / (MAX_DB - MIN_DB));
        level = Math.max(0, Math.min(255, level));
        int index = y * width + left + x;
        if (level > Byte.toUnsignedInt(levels[index])) {
          levels[index] = (byte)level;
          pixels[index] = spectrumColor(level, frequencyRatio);
        }
      }
    }
  }

  private static void applyEnvelopeColumn(int x, float[] fft, int channels,
                                          float[] envelope) {
    double energy = 0;
    int count = 0;
    for (int bin = 1; bin < FFT_BINS; bin += 2) {
      for (int channel = 0; channel < channels; channel++) {
        float magnitude = fft[bin * channels + channel];
        energy += magnitude * magnitude;
        count++;
      }
    }
    double rms = Math.sqrt(energy / Math.max(1, count));
    float value =
        (float)(magnitudeLevel(rms * 10d) / 255d);
    envelope[x] = Math.max(envelope[x], value);
  }

  private static int magnitudeLevel(double magnitude) {
    double db = 20d * Math.log10(Math.max(1e-9, magnitude));
    int level = (int)Math.round(
        255d * (db - MIN_DB) / (MAX_DB - MIN_DB));
    return Math.max(0, Math.min(255, level));
  }

  private static void fillEnvelopeGaps(float[] envelope) {
    int previous = -1;
    for (int x = 0; x < envelope.length; x++) {
      if (envelope[x] > 0f) {
        if (previous >= 0 && x - previous > 1) {
          float left = envelope[previous], right = envelope[x];
          for (int fill = previous + 1; fill < x; fill++)
            envelope[fill] =
                left + (right - left) * (fill - previous) /
                           (float)(x - previous);
        }
        previous = x;
      }
    }
  }

  private static int spectrumColor(int level, double frequencyRatio) {
    // Frequency controls the hue, using the requested square-root progression
    // from the bottom to the top instead of a uniform linear gradient.
    double t = Math.sqrt(
        Math.max(0d, Math.min(1d, frequencyRatio)));
    int[] a, b;
    double local;
    if (t < .14) {
      a = new int[] {255, 92, 24};
      b = new int[] {255, 218, 0};
      local = t / .14;
    } else if (t < .36) {
      a = new int[] {255, 218, 0};
      b = new int[] {31, 222, 76};
      local = (t - .14) / .22;
    } else if (t < .62) {
      a = new int[] {31, 222, 76};
      b = new int[] {0, 190, 215};
      local = (t - .36) / .26;
    } else if (t < .82) {
      a = new int[] {0, 190, 215};
      b = new int[] {42, 72, 204};
      local = (t - .62) / .20;
    } else {
      a = new int[] {42, 72, 204};
      b = new int[] {91, 27, 156};
      local = (t - .82) / .18;
    }
    // As in Spek, dB level drives visual intensity. Strong, spatially dense
    // bands approach a warm highlight instead of remaining a flat hue.
    double strength = Math.max(0d, Math.min(1d, level / 255d));
    double highlight =
        Math.pow(Math.max(0d, (strength - .58d) / .42d), 1.7d);
    double whiteMix = .42d * highlight;
    int baseRed = (int)Math.round(a[0] + (b[0] - a[0]) * local);
    int baseGreen = (int)Math.round(a[1] + (b[1] - a[1]) * local);
    int baseBlue = (int)Math.round(a[2] + (b[2] - a[2]) * local);
    int red = (int)Math.round(baseRed + (255 - baseRed) * whiteMix);
    int green =
        (int)Math.round(baseGreen + (248 - baseGreen) * whiteMix);
    int blue = (int)Math.round(baseBlue + (222 - baseBlue) * whiteMix);
    int alpha = Math.max(0, Math.min(
        255, (int)Math.round(255d * Math.pow(strength, .88d))));
    return alpha << 24 | red << 16 | green << 8 | blue;
  }

  private static String friendlyMessage(Throwable error) {
    if (error instanceof BassException bassError)
      return switch (bassError.code) {
        case 3 -> "无法打开音频文件";
        case 6 -> "SPW 音频引擎尚未初始化";
        case 20 -> "当前音频格式不受 SPW 解码器支持";
        case 21 -> "音频编码器不可用";
        case 41 -> "音频文件无法解码";
        default -> "频谱解码失败（BASS " + bassError.code + "）";
      };
    String message = error.getMessage();
    return message == null || message.isBlank() ? "频谱生成失败" : message;
  }

  @Override
  public void close() {
    clearResults();
    executor.shutdownNow();
    bass = null;
  }

  private static final class BassException extends RuntimeException {
    final int code;
    BassException(int code) {
      super("BASS error " + code);
      this.code = code;
    }
  }

  @Structure.FieldOrder(
      {"freq", "chans", "flags", "ctype", "origres", "plugin", "sample",
       "filename"})
  public static final class BassChannelInfo extends Structure {
    public int freq;
    public int chans;
    public int flags;
    public int ctype;
    public int origres;
    public int plugin;
    public int sample;
    public Pointer filename;
  }

  private interface BassNative extends StdCallLibrary {
    int BASS_StreamCreateFile(boolean memory, WString file, long offset,
                              long length, int flags);
    boolean BASS_ChannelGetInfo(int handle, BassChannelInfo info);
    long BASS_ChannelGetLength(int handle, int mode);
    double BASS_ChannelBytes2Seconds(int handle, long position);
    int BASS_ChannelGetData(int handle, float[] buffer, int flags);
    boolean BASS_StreamFree(int handle);
    int BASS_ErrorGetCode();
  }
}
