package dev.overtoneparty.integration;

import dev.musicplayer.controller.PlayerController;
import dev.musicplayer.model.Page;
import dev.musicplayer.model.Platform;
import dev.musicplayer.model.Track;
import dev.overtoneparty.client.PartyClient;
import dev.overtoneparty.model.PartyTrack;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Действие кнопки «♪+ Всё» в заголовке открытого плейлиста или альбома.
 *
 * <p>Список берётся из страницы Overtone в момент клика: треки набора
 * подгружаются асинхронно, поэтому кнопка создаётся заранее, а содержимое
 * страницы к моменту нажатия может измениться. Отдельный класс нужен, потому
 * что лямбды внутри миксина могут ссылаться на несуществующий в рантайме
 * класс самого миксина.
 */
public final class PartyAddSetAction implements Runnable {
    private final PlayerController ctl;

    public PartyAddSetAction(PlayerController ctl) {
        this.ctl = ctl;
    }

    @Override
    public void run() {
        if (this.ctl == null || this.ctl.platform != Platform.SOUNDCLOUD) return;
        Page page = this.ctl.page();
        if (page == null) return;

        List<PartyTrack> tracks = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Track t : page.tracks()) {
            PartyTrack pt = PartyTrack.from(t);
            if (PartyTrack.isBlank(pt)) continue;
            String key = PartyTrack.providerKey(pt.provider()) + '|' + pt.id();
            if (seen.add(key)) tracks.add(pt);
        }
        if (tracks.isEmpty()) return;
        PartyClient.addTracks(tracks);
    }
}
