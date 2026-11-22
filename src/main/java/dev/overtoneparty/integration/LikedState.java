package dev.overtoneparty.integration;

import dev.musicplayer.controller.PlayerController;
import dev.musicplayer.model.Platform;
import dev.musicplayer.model.Track;

import java.util.List;

/**
 * Признак того, что сейчас открыт список лайкнутых треков.
 *
 * <p>Overtone не помечает такой список отдельно: он просто кладёт результат
 * {@code likedTracks()} в {@code ctl.results}. Поэтому запоминаем сам объект
 * списка, который вернул провайдер лайков, и сравниваем его с текущими
 * результатами по ссылке. Это надёжнее флагов: поиск и переходы между вкладками
 * не могут «залить» кнопку, а открытие лайков всегда определяется верно.
 */
public final class LikedState {
    private static volatile List<Track> liked;

    private LikedState() {
    }

    public static void clear() {
        liked = null;
    }

    /** Провайдер лайков вернул этот список — запоминаем его. */
    public static void cache(List<Track> tracks) {
        if (tracks == liked) return;
        liked = tracks;
    }

    public static boolean isShowing(PlayerController ctl) {
        if (liked == null || ctl == null) return false;
        if (ctl.platform != Platform.SOUNDCLOUD && ctl.platform != Platform.YANDEX) return false;
        if (ctl.tab != PlayerController.Tab.TRACKS) return false;
        if (ctl.page() != null) return false;
        return ctl.results == liked;
    }

    /** Лайкнутые треки, если открыт именно список лайков. */
    public static List<Track> tracks(PlayerController ctl) {
        return isShowing(ctl) ? ctl.results : null;
    }
}
