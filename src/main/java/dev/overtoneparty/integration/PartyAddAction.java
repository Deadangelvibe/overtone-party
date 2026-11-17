package dev.overtoneparty.integration;

import dev.musicplayer.model.Track;
import dev.musicplayer.ui.PlayerScreen;
import dev.overtoneparty.client.PartyClient;
import dev.overtoneparty.model.PartyTrack;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Действие кнопки «в вечеринку» в списке треков Overtone.
 *
 * <p>Трек берётся из строки в момент клика, а не при постройке экрана:
 * результаты поиска и страницы Overtone подгружаются асинхронно, поэтому
 * строки часто появляются уже после того, как кнопки были созданы. Заодно
 * номер страницы читается в момент клика — после «‹»/«›» он меняется,
 * а кнопки остаются те же.
 *
 * <p>Отдельный класс, а не лямбда: код миксина вливается в чужой класс, и
 * лямбды оттуда могут ссылаться на несуществующий класс миксина в рантайме.
 */
public final class PartyAddAction implements Runnable {
    private static Method trackAt;
    private static Field pageField;
    private static Field rowsField;

    private final PlayerScreen screen;
    private final int row;

    /**
     * @param screen экран Overtone (в него влит наш миксин)
     * @param row    номер строки на текущей странице, 0…rows-1
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
            if (trackAt == null) {
                Class<?> c = PlayerScreen.class;
                trackAt = c.getDeclaredMethod("trackAt", int.class);
                trackAt.setAccessible(true);
                pageField = c.getDeclaredField("page");
                pageField.setAccessible(true);
                rowsField = c.getDeclaredField("rows");
                rowsField.setAccessible(true);
            }
            int idx = pageField.getInt(screen) * rowsField.getInt(screen) + row;
            Object value = trackAt.invoke(screen, idx);
            return value instanceof Track t ? t : null;
        } catch (Throwable t) {
            return null;
        }
    }
}
