package dev.overtoneparty.integration;

import dev.musicplayer.controller.PlayerController;
import dev.musicplayer.model.Platform;
import dev.musicplayer.model.Track;

import java.util.List;

/** Действие кнопки «♪+ Все лайкнутые» в начале списка лайков. */
public final class PartyAddLikedAction implements Runnable {
    private final PlayerController ctl;

    public PartyAddLikedAction(PlayerController ctl) {
        this.ctl = ctl;
    }

    @Override
    public void run() {
        List<Track> tracks = LikedState.tracks(this.ctl);
        if (tracks == null || tracks.isEmpty()) return;
        String label = this.ctl.platform == Platform.YANDEX
                ? "Яндекс: лайкнутые треки" : "SoundCloud: лайкнутые треки";
        PartyQueue.addTracks(tracks, label);
    }
}
