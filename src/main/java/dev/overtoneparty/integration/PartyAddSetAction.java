package dev.overtoneparty.integration;

import dev.musicplayer.controller.PlayerController;
import dev.musicplayer.model.MusicEntity;
import dev.musicplayer.model.Page;
import dev.musicplayer.model.Platform;
import dev.musicplayer.model.Track;
import dev.overtoneparty.OvertoneParty;
import net.minecraft.client.MinecraftClient;

import java.util.List;
import java.util.concurrent.CompletionException;

/**
 * Действие маленькой кнопки {@code ♪+} справа от строки альбома, плейлиста или
 * артиста. Работает и с SoundCloud, и с Яндекс Музыкой.
 *
 * <p>Кнопки строк создаются один раз, а прокрутка в Overtone меняет страницу без
 * пересборки экрана. Поэтому действие хранит только номер видимой строки, а нужный
 * набор определяет в момент нажатия через {@link PartyScreenAccess}.
 */
public final class PartyAddSetAction implements Runnable {
    private final PlayerController ctl;
    private final PartyScreenAccess screen;
    private final int row;

    public PartyAddSetAction(PlayerController ctl, PartyScreenAccess screen, int row) {
        this.ctl = ctl;
        this.screen = screen;
        this.row = row;
    }

    @Override
    public void run() {
        if (this.ctl == null) return;
        List<MusicEntity> sets = PartySets.forCurrentTab(this.ctl);
        if (sets == null) return;

        int index = this.screen.overtoneParty$page() * this.screen.overtoneParty$rows() + this.row;
        if (index < 0 || index >= sets.size()) return;
        MusicEntity entity = sets.get(index);
        if (entity.isMine()) return; // «Мои плейлисты» — не набор треков.
        String label = (this.ctl.platform == Platform.YANDEX ? "Яндекс" : "SoundCloud")
                + ": " + entity.title();

        if (this.ctl.platform == Platform.SOUNDCLOUD) {
            if (!(this.ctl.soundcloud instanceof SoundCloudExt ext)) return;
            ext.overtoneParty$loadSet(entity).whenComplete((tracks, error) ->
                    overtoneParty$loaded(label, tracks, error));
        } else if (this.ctl.platform == Platform.YANDEX) {
            this.ctl.yandex.open(entity).whenComplete((page, error) -> {
                List<Track> tracks = error == null && page != null ? page.tracks() : null;
                this.overtoneParty$loaded(label, tracks, error);
            });
        }
    }

    /** Обработка результата: ошибки только логируем, очередь трогаем в игровом потоке. */
    private void overtoneParty$loaded(String label, List<Track> tracks, Throwable error) {
        if (error != null) {
            Throwable cause = error instanceof CompletionException && error.getCause() != null
                    ? error.getCause() : error;
            OvertoneParty.LOGGER.warn("Не удалось загрузить набор «{}»: {}",
                    label, String.valueOf(cause.getMessage()));
            return;
        }
        if (tracks == null || tracks.isEmpty()) {
            OvertoneParty.LOGGER.warn("Набор «{}» пуст", label);
            return;
        }
        // Загрузка идёт в потоке провайдера, отправку в сеть делаем в игровом потоке.
        MinecraftClient.getInstance().execute(() -> PartyQueue.addTracks(tracks, label));
    }
}
