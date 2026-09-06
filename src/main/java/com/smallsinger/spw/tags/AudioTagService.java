package com.smallsinger.spw.tags;

import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;
import org.jaudiotagger.tag.images.Artwork;
import org.jaudiotagger.tag.images.ArtworkFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;

final class AudioTagService {
    private AudioTagService() {}

    static AudioTagData read(File file) throws Exception {
        AudioFile audio = AudioFileIO.read(file);
        var header=audio.getAudioHeader();
        String duration=formatDuration(header.getTrackLength()),bitDepth=header.getBitsPerSample()>0?header.getBitsPerSample()+" bit":"",bitrate=header.getBitRate()==null?"":header.getBitRate()+" kbps";
        Tag tag = audio.getTag();
        if (tag == null) {
            return new AudioTagData(file, "", "", "", "", "", "", "", "", "", "", "", "", null,duration,bitDepth,bitrate);
        }
        Artwork artwork = tag.getFirstArtwork();
        return new AudioTagData(file,
                value(tag, FieldKey.TITLE), value(tag, FieldKey.ARTIST), value(tag, FieldKey.ALBUM),
                optionalValue(tag, FieldKey.ALBUM_ARTIST), optionalValue(tag, FieldKey.LYRICIST), optionalValue(tag, FieldKey.COMPOSER),
                optionalValue(tag, FieldKey.YEAR), optionalValue(tag, FieldKey.TRACK), optionalValue(tag, FieldKey.DISC_NO),
                optionalValue(tag, FieldKey.GENRE), value(tag, FieldKey.LYRICS), optionalValue(tag, FieldKey.COMMENT),
                artwork == null ? null : artwork.getBinaryData(),duration,bitDepth,bitrate);
    }

    static void write(AudioTagData data) throws Exception {
        File target = data.file();
        Path targetPath = target.toPath();
        Path parent = targetPath.toAbsolutePath().getParent();
        if (parent == null)
            throw new IOException("音频文件没有可用的父目录");
        String name = target.getName();
        int extension = name.lastIndexOf('.');
        String suffix = extension >= 0 ? name.substring(extension) : ".tmp";
        Path temporary = Files.createTempFile(parent, "." + name + ".spw-", suffix);
        try {
            Files.copy(targetPath, temporary, StandardCopyOption.REPLACE_EXISTING);
            writeInPlace(new AudioTagData(temporary.toFile(), data.title(),
                    data.artist(), data.album(), data.albumArtist(),
                    data.lyricist(), data.composer(), data.year(), data.track(),
                    data.disc(), data.genre(), data.lyrics(), data.comment(),
                    data.cover(), data.duration(), data.bitDepth(), data.bitrate()));
            verify(temporary.toFile(), data);
            replace(targetPath, temporary);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void writeInPlace(AudioTagData data) throws Exception {
        AudioFile audio = AudioFileIO.read(data.file());
        Tag tag = audio.getTagOrCreateAndSetDefault();
        set(tag, FieldKey.TITLE, data.title());
        set(tag, FieldKey.ARTIST, data.artist());
        set(tag, FieldKey.ALBUM, data.album());
        set(tag, FieldKey.ALBUM_ARTIST, data.albumArtist());
        set(tag, FieldKey.LYRICIST, data.lyricist());
        set(tag, FieldKey.COMPOSER, data.composer());
        set(tag, FieldKey.YEAR, data.year());
        set(tag, FieldKey.TRACK, data.track());
        set(tag, FieldKey.DISC_NO, data.disc());
        set(tag, FieldKey.GENRE, data.genre());
        set(tag, FieldKey.LYRICS, data.lyrics());
        set(tag, FieldKey.COMMENT, data.comment());
        if (data.cover() != null && data.cover().length > 0) {
            Artwork artwork = ArtworkFactory.getNew();
            artwork.setBinaryData(data.cover());
            artwork.setMimeType("image/jpeg");
            tag.deleteArtworkField();
            tag.setField(artwork);
        }
        audio.commit();
    }

    private static void verify(File file, AudioTagData expected) throws Exception {
        AudioTagData actual = read(file);
        if (!Objects.equals(actual.title(), safe(expected.title())) ||
            !Objects.equals(actual.artist(), safe(expected.artist())) ||
            !Objects.equals(actual.album(), safe(expected.album())) ||
            !Objects.equals(actual.lyrics(), safe(expected.lyrics()))) {
            throw new IOException("标签写入后校验失败，原文件未替换");
        }
    }

    private static void replace(Path target, Path temporary) throws IOException {
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                       StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String value(Tag tag, FieldKey key) {
        String value = tag.getFirst(key);
        return value == null ? "" : value;
    }
    private static String optionalValue(Tag tag, FieldKey key) {
        String value=value(tag,key).trim();
        return value.matches("(?i)^(?:0+(?:/0+)?|unknown|null|n/a|-)$")?"":value;
    }

    private static void set(Tag tag, FieldKey key, String value) throws Exception {
        if (value == null || value.isBlank()) {
            tag.deleteField(key);
            return;
        }
        tag.setField(key, value);
    }
    private static String safe(String value) { return value == null ? "" : value; }
    private static String formatDuration(int seconds){return String.format(java.util.Locale.ROOT,"%d:%02d",seconds/60,seconds%60);}
}
