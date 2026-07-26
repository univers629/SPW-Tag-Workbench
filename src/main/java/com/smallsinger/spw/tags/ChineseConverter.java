package com.smallsinger.spw.tags;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.win32.StdCallLibrary;

/**
 * Uses Windows' built-in locale mapper so the plugin does not need to ship a
 * separate Chinese conversion dictionary.
 */
final class ChineseConverter {
  private static final int LCMAP_SIMPLIFIED_CHINESE = 0x02000000;
  private static final int LCMAP_TRADITIONAL_CHINESE = 0x04000000;

  private ChineseConverter() {}

  static String convert(String source, boolean traditional) {
    if (source == null || source.isEmpty())
      return source == null ? "" : source;
    char[] input = source.toCharArray();
    int flag = traditional ? LCMAP_TRADITIONAL_CHINESE
                           : LCMAP_SIMPLIFIED_CHINESE;
    int required = Holder.KERNEL32.LCMapStringEx(
        new WString(traditional ? "zh-TW" : "zh-CN"), flag, input,
        input.length, null, 0, Pointer.NULL, Pointer.NULL, 0);
    if (required <= 0)
      return source;
    char[] output = new char[required];
    int written = Holder.KERNEL32.LCMapStringEx(
        new WString(traditional ? "zh-TW" : "zh-CN"), flag, input,
        input.length, output, output.length, Pointer.NULL, Pointer.NULL, 0);
    return written <= 0 ? source : new String(output, 0, written);
  }

  private static final class Holder {
    private static final Kernel32 KERNEL32 =
        Native.load("kernel32", Kernel32.class);
  }

  private interface Kernel32 extends StdCallLibrary {
    int LCMapStringEx(WString localeName, int mapFlags, char[] source,
                      int sourceLength, char[] destination,
                      int destinationLength, Pointer versionInformation,
                      Pointer reserved, long sortHandle);
  }
}
