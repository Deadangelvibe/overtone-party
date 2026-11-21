package dev.musicplayer.model;

/** Compile-time stub. The real record comes from the Overtone mod at runtime. */
public record MusicEntity(Kind kind, String id, String title, String subtitle, String coverUrl) {
    public enum Kind {
        ARTIST, ALBUM, PLAYLIST, STATION
    }
}
