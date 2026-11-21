package dev.overtoneparty.integration;

import dev.musicplayer.model.MusicEntity;
import dev.musicplayer.model.Track;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Методы, которые миксин добавляет в SoundCloudProvider Overtone.
 *
 * <p>Overtone умеет искать и лайкать только треки; плейлистов и альбомов у него
 * нет. Через этот интерфейс наши миксины просят сам провайдер (у него есть
 * приватные {@code api()} и {@code tracks()}) загрузить наборы и их содержимое.
 */
public interface SoundCloudExt {
    /** Загрузить свои и лайкнутые плейлисты/альбомы. */
    CompletableFuture<Void> overtoneParty$loadLibrary();

    /** Прочитать все треки плейлиста или альбома. */
    CompletableFuture<List<Track>> overtoneParty$loadSet(MusicEntity entity);
}
