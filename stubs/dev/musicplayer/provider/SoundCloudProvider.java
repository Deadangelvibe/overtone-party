package dev.musicplayer.provider;

import dev.musicplayer.model.Track;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Compile-time stub. The real provider lives in Overtone. */
public class SoundCloudProvider {
    public CompletableFuture<List<Track>> search(String query) {
        return null;
    }
}
