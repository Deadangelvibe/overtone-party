package dev.overtoneparty.integration;

import dev.musicplayer.controller.PlayerController;
import dev.musicplayer.model.MusicEntity;
import dev.musicplayer.model.Platform;

import java.util.List;

/** Список сущностей, для которого показываем кнопку добавления целого набора. */
public final class PartySets {
    private PartySets() {
    }

    public static List<MusicEntity> forCurrentTab(PlayerController ctl) {
        if (ctl == null || LikedState.isShowing(ctl)) return null;

        // «Мои плейлисты» в Яндексе — обычная страница со списком сущностей.
        // Добавляем кнопки туда, где Overtone показывает именно сущности.
        if (ctl.page() != null) {
            List<MusicEntity> entities = ctl.page().entities();
            List<?> tracks = ctl.page().tracks();
            if (entities == null || entities.isEmpty()) return null;
            if (tracks != null && !tracks.isEmpty() && ctl.tab != PlayerController.Tab.ALBUMS) return null;
            return entities;
        }

        if (ctl.platform == Platform.SOUNDCLOUD) {
            SoundCloudLibrary.ensureLoaded();
            if (ctl.tab == PlayerController.Tab.ALBUMS) return SoundCloudLibrary.albums();
            if (ctl.tab == PlayerController.Tab.PLAYLISTS) return SoundCloudLibrary.playlists();
        } else if (ctl.platform == Platform.YANDEX) {
            if (ctl.tab == PlayerController.Tab.ALBUMS) return ctl.albums;
            if (ctl.tab == PlayerController.Tab.ARTISTS) return ctl.artists;
            if (ctl.tab == PlayerController.Tab.PLAYLISTS) return ctl.playlists;
        }
        return null;
    }
}
