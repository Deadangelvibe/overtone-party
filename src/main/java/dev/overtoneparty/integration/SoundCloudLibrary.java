package dev.overtoneparty.integration;

import dev.musicplayer.controller.PlayerController;
import dev.musicplayer.model.MusicEntity;

import java.util.List;

/**
 * Плейлисты и альбомы SoundCloud, показанные в окне Overtone отдельными вкладками.
 *
 * <p>Списки намеренно живут здесь, а не в самом Overtone: его {@code PlayerController}
 * держит такие списки только для Яндекса и Spotify, а для SoundCloud вкладки
 * попросту не рисует. Наши миксины подменяют содержимое списка и visibleTabs.
 */
public final class SoundCloudLibrary {
    private static volatile List<MusicEntity> albums = List.of();
    private static volatile List<MusicEntity> playlists = List.of();
    private static boolean loaded;
    private static boolean loading;

    private SoundCloudLibrary() {
    }

    public static List<MusicEntity> albums() {
        return albums;
    }

    public static List<MusicEntity> playlists() {
        return playlists;
    }

    public static boolean hasAny() {
        return !albums.isEmpty() || !playlists.isEmpty();
    }

    /** Свои наборы помечаем, чтобы не трогать открытие альбомов других площадок. */
    public static boolean isOurEntity(MusicEntity entity) {
        if (entity == null || entity.id() == null) return false;
        return contains(albums, entity.id()) || contains(playlists, entity.id());
    }

    private static boolean contains(List<MusicEntity> list, String id) {
        for (MusicEntity e : list) {
            if (id.equals(e.id())) return true;
        }
        return false;
    }

    /** Запускает однократную загрузку через провайдер Overtone. */
    public static synchronized void ensureLoaded() {
        if (loaded || loading) return;
        PlayerController ctl = PlayerController.I;
        if (ctl == null || !(ctl.soundcloud instanceof SoundCloudExt ext)) return;
        loading = true;
        try {
            ext.overtoneParty$loadLibrary().whenComplete((unused, error) -> {
                if (error != null) {
                    synchronized (SoundCloudLibrary.class) {
                        loading = false;
                    }
                }
            });
        } catch (Throwable t) {
            synchronized (SoundCloudLibrary.class) {
                loading = false;
            }
        }
    }

    public static synchronized void set(List<MusicEntity> newAlbums, List<MusicEntity> newPlaylists) {
        albums = List.copyOf(newAlbums);
        playlists = List.copyOf(newPlaylists);
        loaded = true;
        loading = false;
    }

    /** Пользователь ещё не вошёл в SoundCloud — попробуем позже. */
    public static synchronized void resetLoading() {
        loading = false;
    }
}
