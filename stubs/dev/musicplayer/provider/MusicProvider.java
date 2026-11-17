package dev.musicplayer.provider;

import dev.musicplayer.model.Platform;
import dev.musicplayer.model.Track;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Compile-time stub. The real interface has more methods; only these are called. */
public interface MusicProvider {
    Platform platform();

    boolean isLoggedIn();

    CompletableFuture<List<Track>> search(String query);

    CompletableFuture<Void> play(Track t);

    void stop();

    void pause();

    void resume();

    void seek(long positionMs);

    long positionMs();

    boolean isPlaying();

    void setVolume(float volume01);
}
