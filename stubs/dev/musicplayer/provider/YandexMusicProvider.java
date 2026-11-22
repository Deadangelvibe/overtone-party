package dev.musicplayer.provider;

import dev.musicplayer.model.MusicEntity;
import dev.musicplayer.model.Page;

import java.util.concurrent.CompletableFuture;

/** Compile-time stub of Overtone's Yandex provider. Not shipped in the jar. */
public class YandexMusicProvider {
    public CompletableFuture<Page> open(MusicEntity entity) {
        return CompletableFuture.completedFuture(null);
    }
}
