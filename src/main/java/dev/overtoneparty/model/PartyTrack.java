package dev.overtoneparty.model;

import dev.musicplayer.model.Track;

/**
 * Копия трека Overtone, пригодная для отправки по сети и хранения на сервере.
 *
 * <p>Класс сознательно не содержит ссылок на Minecraft: серверная часть живёт
 * в отдельном пакете.
 */
public record PartyTrack(String id, String title, String artist, String album,
                         long durationMs, String coverUrl, String provider) {

    public static PartyTrack from(Track t) {
        if (t == null) return null;
        return new PartyTrack(t.id(), t.title(), t.artist(), t.album(),
                t.durationMs(), t.coverUrl(), t.provider());
    }

    public Track toTrack() {
        return new Track(nz(id), nz(title), nz(artist), nz(album),
                Math.max(0L, durationMs), nz(coverUrl),
                nz(provider).isEmpty() ? "soundcloud" : provider);
    }

    public String displayName() {
        if (title == null || title.isEmpty()) return id == null ? "?" : id;
        if (artist == null || artist.isEmpty()) return title;
        return title + " — " + artist;
    }

    public static boolean isBlank(PartyTrack t) {
        return t == null || t.id() == null || t.id().isEmpty();
    }

    public static boolean sameTrack(PartyTrack a, PartyTrack b) {
        return a != null && b != null && nz(a.id()).equals(nz(b.id()))
                && providerKey(a.provider()).equals(providerKey(b.provider()));
    }

    /**
     * Провайдер для сравнения треков. {@link #toTrack()} подставляет soundcloud
     * трекам без провайдера, поэтому сравнивать «» и «soundcloud» как разные
     * нельзя — иначе только что загруженный трек навсегда считается «другим»
     * и клиент перезапускает его по кругу.
     */
    public static String providerKey(String provider) {
        return provider == null || provider.isEmpty() ? "soundcloud" : provider;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
