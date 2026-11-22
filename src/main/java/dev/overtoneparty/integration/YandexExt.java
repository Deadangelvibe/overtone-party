package dev.overtoneparty.integration;

import java.util.concurrent.CompletableFuture;

/** Метод, который миксин добавляет в YandexMusicProvider Overtone. */
public interface YandexExt {
    /** Подгрузить лайкнутые альбомы и артистов в список вкладок Overtone. */
    CompletableFuture<Void> overtoneParty$loadLikedSets();
}
