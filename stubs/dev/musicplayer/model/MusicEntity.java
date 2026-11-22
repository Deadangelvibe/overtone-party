package dev.musicplayer.model;

/** Compile-time stub. The real record comes from the Overtone mod at runtime. */
public record MusicEntity(Kind kind, String id, String title, String subtitle, String coverUrl) {
    /** Псевдо-сущность «Мои плейлисты» в Overtone. */
    public boolean isMine() {
        return false;
    }

    public enum Kind {
        ARTIST, ALBUM, PLAYLIST, STATION
    }
}
