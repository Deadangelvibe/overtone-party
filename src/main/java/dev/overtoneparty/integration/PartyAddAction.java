package dev.overtoneparty.integration;

import dev.musicplayer.model.Track;
import dev.musicplayer.ui.PlayerScreen;
import dev.overtoneparty.client.PartyClient;
import dev.overtoneparty.model.PartyTrack;

import java.lang.reflect.Method;

/**
 * Действие кнопки «в вечеринку» в списке треков Overtone.
 *
 * <p>Номер строки определяется в момент клика по фактической позиции мыши,
 * а не по сохранённому при сборке экрана номеру. Overtone пересобирает кнопки
 * только при открытии окна, а список лайков ещё и сдвинут на строку вниз —
 * поэтому позиция мыши надёжнее любого кэшированного индекса.
 *
 * <p>Отдельный класс, а не лямбда: код миксина вливается в чужой класс, и
 * лямбды оттуда могут ссылаться на несуществующий класс миксина в рантайме.
 */
public final class PartyAddAction implements Runnable {
    private static Method trackAt;

    private final PlayerScreen screen;
    private final int row;

    /**
     * @param screen экран Overtone (в него влит наш миксин)
     * @param row    номер строки на текущей странице, 0…rows-1 (резерв)
     */
    public PartyAddAction(PlayerScreen screen, int row) {
        this.screen = screen;
        this.row = row;
    }

    @Override
    public void run() {
        Track track = trackAt();
        PartyTrack pt = PartyTrack.from(track);
        if (pt != null && !PartyTrack.isBlank(pt)) PartyClient.addTrack(pt);
    }

    private Track trackAt() {
        try {
            PartyScreenAccess access = (PartyScreenAccess) screen;
            int shift = access.overtoneParty$likedShift() ? 24 : 0;
            int row = (access.overtoneParty$mouseY() - access.overtoneParty$listTop() - shift) / 24;
            if (row < 0 || row >= access.overtoneParty$rows()) row = this.row;
            int idx = access.overtoneParty$page() * access.overtoneParty$rows() + row;

            if (trackAt == null) {
                trackAt = PlayerScreen.class.getDeclaredMethod("trackAt", int.class);
                trackAt.setAccessible(true);
            }
            Object value = trackAt.invoke(screen, idx);
            return value instanceof Track t ? t : null;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
