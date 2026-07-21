package com.smallsinger.spw.tags;

import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;
import org.jaudiotagger.tag.images.Artwork;
import org.jaudiotagger.tag.images.ArtworkFactory;

import java.io.File;

final class AudioTagService {
    private AudioTagService() {}

    static AudioTagData read(File file) throws Exception {
        AudioFile audio = AudioFileIO.read(file);
        Tag tag = audio.getTag();
        if (tag == null) {
            return new AudioTagData(file, "", "", "", "", "", "", "", "", "", "", "", "", null);
        }
        Artwork artwork = tag.getFirstArtwork();
        return new AudioTagData(file,
                value(tag, FieldKey.TITLE), value(tag, FieldKey.ARTIST), value(tag, FieldKey.ALBUM),
                value(tag, FieldKey.ALBUM_ARTIST), value(tag, FieldKey.LYRICIST), value(tag, FieldKey.COMPOSER),
                value(tag, FieldKey.YEAR), value(tag, FieldKey.TRACK), value(tag, FieldKey.DISC_NO),
                value(tag, FieldKey.GENRE), value(tag, FieldKey.LYRICS), value(tag, FieldKey.COMMENT),
                artwork == null ? null : artwork.getBinaryData());
    }

    static void write(AudioTagData data) throws Exception {
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

    private static String value(Tag tag, FieldKey key) {
        String value = tag.getFirst(key);
        return value == null ? "" : value;
    }

    private static void set(Tag tag, FieldKey key, String value) throws Exception {
        tag.setField(key, value == null ? "" : value);
    }
}
