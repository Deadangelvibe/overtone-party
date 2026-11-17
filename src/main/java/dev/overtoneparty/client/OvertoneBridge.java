package dev.overtoneparty.client;

import dev.musicplayer.config.PlayerConfig;
import dev.musicplayer.controller.PlayerController;
import dev.musicplayer.model.Platform;
import dev.musicplayer.model.Track;
import dev.musicplayer.provider.MusicProvider;
import dev.overtoneparty.OvertoneParty;
import dev.overtoneparty.model.PartyTrack;
import net.minecraft.client.MinecraftClient;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Единственное место, где мод трогает классы Overtone.
 *
 * <p>Все вызовы обёрнуты в try/catch(Throwable): если Overtone не установлен
 * или его API не совпал, остальной мод продолжает работать, а музыка просто
 * не синхронизируется.
 *
 * <p>Плейлист Overtone ({@code PlayerController.queue} + {@code index}) не отдаётся
 * публичным API: {@code queueSnapshot()} возвращает отдельную очередь «играть следующим».
 * Поэтому настоящий плейлист читается и правится через reflection — это единственный
 * способ держать очередь вечеринки и список треков в самом Overtone одинаковыми.
 */
public final class OvertoneBridge {
    private static Boolean available;
    private static boolean reflected;
    private static Field queueField;
    private static Field indexField;
    private static Method persistMethod;

    private OvertoneBridge() {
    }

    public static boolean available() {
        if (available == null) {
            try {
                Class.forName("dev.musicplayer.controller.PlayerController");
                available = true;
            } catch (Throwable t) {
                available = false;
                OvertoneParty.LOGGER.warn("Overtone не найден — синхронизация музыки отключена");
            }
        }
        return available;
    }

    public static boolean ready() {
        try {
            return available() && PlayerController.I != null && PlayerController.I.current != null;
        } catch (Throwable t) {
            return false;
        }
    }

    public static Track current() {
        try {
            return available() ? PlayerController.I.current : null;
        } catch (Throwable t) {
            return null;
        }
    }

    public static MusicProvider engine() {
        try {
            return available() ? PlayerController.I.engine() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Настоящий плейлист Overtone (тот, что виден в его окне), а не очередь
     * «играть следующим». Пустой список, если Overtone старый/нестандартный.
     */
    public static List<Track> playlist() {
        try {
            if (!available()) return List.of();
            reflect();
            PlayerController pc = PlayerController.I;
            if (pc == null || queueField == null) return List.of();
            Object value = queueField.get(pc);
            if (!(value instanceof List<?> list)) return List.of();
            List<Track> out = new ArrayList<>(list.size());
            for (Object o : list) {
                if (o instanceof Track t) out.add(t);
            }
            return out;
        } catch (Throwable t) {
            return List.of();
        }
    }

    /**
     * Очередь «играть следующим» (публичный API Overtone). Это не плейлист,
     * но при недоступном reflection она лучше, чем ничего.
     */
    public static List<Track> queueSnapshot() {
        try {
            if (!available()) return List.of();
            List<Track> q = PlayerController.I.queueSnapshot();
            return q == null ? List.of() : new ArrayList<>(q);
        } catch (Throwable t) {
            return List.of();
        }
    }

    /** Индекс текущего трека внутри {@link #playlist()}. */
    public static int playlistIndex() {
        try {
            if (!available()) return 0;
            reflect();
            PlayerController pc = PlayerController.I;
            if (pc == null || indexField == null) return 0;
            return indexField.getInt(pc);
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * Тихо подменяет плейлист Overtone на очередь вечеринки, не трогая играющий
     * трек. Нужно диджею: он видит в окне Overtone ровно то, что играет у всех.
     */
    public static void mirrorPlaylist(List<PartyTrack> list, int index) {
        try {
            if (!available() || list == null || list.isEmpty()) return;
            reflect();
            PlayerController pc = PlayerController.I;
            if (pc == null || queueField == null) return;

            List<Track> built = new ArrayList<>(list.size());
            for (PartyTrack p : list) {
                if (p != null && !PartyTrack.isBlank(p)) built.add(p.toTrack());
            }
            if (built.isEmpty()) return;
            if (sameIds(playlist(), built)) return;

            Track cur = pc.current;
            int curAt = cur == null ? -1 : indexOfIdentity(built, cur);
            // Текущий трек не входит в очередь вечеринки (например, из «играть
            // следующим») — не трогаем список, иначе у Overtone поедет next/prev.
            if (cur != null && curAt < 0) return;

            queueField.set(pc, built);
            if (curAt >= 0 && indexField != null) {
                indexField.setInt(pc, curAt);
            } else if (index >= 0 && index < built.size() && indexField != null) {
                indexField.setInt(pc, index);
            }
            persist();
        } catch (Throwable t) {
            OvertoneParty.LOGGER.debug("Не удалось обновить плейлист Overtone", t);
        }
    }

    public static void play(PartyTrack t) {
        try {
            if (!available() || t == null) return;
            List<Track> one = new ArrayList<>(1);
            one.add(t.toTrack());
            PlayerController.I.clearQueue();
            PlayerController.I.playFrom(one, 0);
        } catch (Throwable e) {
            OvertoneParty.LOGGER.warn("Не удалось включить трек вечеринки {}", t == null ? "?" : t.id(), e);
        }
    }

    /**
     * Диджею ставим сразу весь плейлист, чтобы его собственный интерфейс Overtone
     * показывал очередь и умел next/prev. Слушателям достаточно одного трека.
     */
    public static void playAt(List<PartyTrack> list, int index) {
        try {
            if (!available() || list == null || list.isEmpty()) return;
            int at = Math.max(0, Math.min(index, list.size() - 1));
            List<Track> all = new ArrayList<>(list.size());
            for (PartyTrack t : list) {
                if (t != null && !PartyTrack.isBlank(t)) all.add(t.toTrack());
            }
            if (all.isEmpty()) return;
            PlayerController.I.clearQueue();
            PlayerController.I.playFrom(all, Math.min(at, all.size() - 1));
        } catch (Throwable e) {
            OvertoneParty.LOGGER.warn("Не удалось включить плейлист вечеринки", e);
        }
    }

    // ---------------------------------------------------------------- local files

    private static boolean localReflected;
    private static Object localProvider;
    private static Method localPathOf;

    /**
     * Есть ли на этом компьютере локальный файл с таким id.
     *
     * <p>Overtone опознаёт локальные треки по хешу абсолютного пути к файлу,
     * поэтому один и тот же трек на другом ПК получает другой id, а сам файл
     * по сети не передаётся. Если проверить не удалось, считаем, что файл есть:
     * пусть Overtone попробует сам, как раньше.
     */
    private static boolean localCheckWarned;

    public static boolean localFileExists(String id) {
        if (id == null || id.isEmpty()) return false;
        reflectLocal();
        if (localPathOf == null || localProvider == null) {
            warnLocalCheck("локальная библиотека Overtone недоступна");
            return false;
        }
        try {
            Object path = localPathOf.invoke(localProvider, id);
            return path instanceof Path p && Files.isRegularFile(p);
        } catch (Throwable t) {
            warnLocalCheck("не удалось проверить путь: " + t);
            return false;
        }
    }

    private static void warnLocalCheck(String reason) {
        if (localCheckWarned) return;
        localCheckWarned = true;
        OvertoneParty.LOGGER.warn("Проверка локальных файлов не работает ({}). "
                + "Треки диджея из «Моих файлов» будем искать в онлайн-площадке.", reason);
    }

    private static void reflectLocal() {
        if (localReflected || !available()) return;
        PlayerController pc;
        try {
            pc = PlayerController.I;
        } catch (Throwable t) {
            return;
        }
        if (pc == null) return;
        localReflected = true;
        try {
            Field f = PlayerController.class.getDeclaredField("local");
            f.setAccessible(true);
            localProvider = f.get(pc);
            Field lib = localProvider.getClass().getDeclaredField("library");
            lib.setAccessible(true);
            Object library = lib.get(localProvider);
            localPathOf = library.getClass().getMethod("pathOf", String.class);
            localPathOf.setAccessible(true);
        } catch (Throwable t) {
            localProvider = null;
            localPathOf = null;
            OvertoneParty.LOGGER.debug("Локальная библиотека Overtone недоступна", t);
        }
    }

    /** Площадка, в которой этот клиент будет искать замену локальному треку. */
    public record SearchService(String id, String displayName) {
    }

    private static final SearchService SEARCH_YANDEX = new SearchService("yandex", "Яндекс Музыка");
    private static final SearchService SEARCH_SOUNDCLOUD = new SearchService("soundcloud", "SoundCloud");
    private static final SearchService SEARCH_SPOTIFY = new SearchService("spotify", "Spotify");

    /**
     * Выбранная площадка конкретного пользователя Overtone.
     *
     * <p>У каждого участника это значение своё, поэтому один и тот же локальный
     * трек может быть заменён треком из Яндекс Музыки у одного слушателя и из
     * SoundCloud у другого. Если пользователь стоит на «Моих файлах», пробуем
     * площадку текущего трека, а когда определить её нельзя — SoundCloud,
     * который умеет искать без входа.
     */
    public static SearchService preferredSearchService() {
        try {
            if (!available()) return SEARCH_SOUNDCLOUD;
            PlayerController pc = PlayerController.I;
            if (pc == null) return SEARCH_SOUNDCLOUD;

            SearchService selected = searchService(pc.platform);
            if (selected != null && canSearch(pc, selected)) return selected;

            Track current = pc.current;
            SearchService playing = current == null ? null : searchService(current.provider());
            return playing != null && canSearch(pc, playing) ? playing : SEARCH_SOUNDCLOUD;
        } catch (Throwable t) {
            return SEARCH_SOUNDCLOUD;
        }
    }

    /** Ищет трек только в выбранной для этого клиента площадке. */
    public static CompletableFuture<List<Track>> search(SearchService service, String query) {
        if (query == null || query.isBlank()) return CompletableFuture.completedFuture(List.of());
        SearchService target = service == null ? preferredSearchService() : service;
        try {
            if (!available()) return CompletableFuture.completedFuture(List.of());
            PlayerController pc = PlayerController.I;
            Platform platform = searchPlatform(target.id());
            MusicProvider provider = pc == null || platform == null ? null : pc.provider(platform);
            if (provider == null || !provider.isLoggedIn()) {
                return CompletableFuture.completedFuture(List.of());
            }
            CompletableFuture<List<Track>> found = provider.search(query);
            return found == null ? CompletableFuture.completedFuture(List.of()) : found;
        } catch (Throwable t) {
            OvertoneParty.LOGGER.debug("Поиск через {} не удался", target.displayName(), t);
            return CompletableFuture.completedFuture(List.of());
        }
    }

    /** Старый вход оставлен для совместимости с уже написанным кодом интеграции. */
    public static CompletableFuture<List<Track>> searchSoundCloud(String query) {
        return search(SEARCH_SOUNDCLOUD, query);
    }

    private static SearchService searchService(Platform platform) {
        if (platform == Platform.YANDEX) return SEARCH_YANDEX;
        if (platform == Platform.SOUNDCLOUD) return SEARCH_SOUNDCLOUD;
        if (platform == Platform.SPOTIFY) return SEARCH_SPOTIFY;
        return null;
    }

    private static SearchService searchService(String provider) {
        String key = provider == null ? "" : provider.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (key) {
            case "yandex" -> SEARCH_YANDEX;
            case "soundcloud" -> SEARCH_SOUNDCLOUD;
            case "spotify" -> SEARCH_SPOTIFY;
            default -> null;
        };
    }

    private static Platform searchPlatform(String provider) {
        String key = provider == null ? "" : provider.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (key) {
            case "yandex" -> Platform.YANDEX;
            case "soundcloud" -> Platform.SOUNDCLOUD;
            case "spotify" -> Platform.SPOTIFY;
            default -> null;
        };
    }

    private static boolean canSearch(PlayerController pc, SearchService service) {
        try {
            Platform platform = searchPlatform(service.id());
            MusicProvider provider = platform == null ? null : pc.provider(platform);
            // SoundCloud сообщает true даже без привязанного аккаунта: его поиск
            // публичный. Яндекс и Spotify вернут false, пока вход не выполнен.
            return provider != null && provider.isLoggedIn();
        } catch (Throwable t) {
            return false;
        }
    }

    /** SoundCloud — запасная площадка, её поиск работает без аккаунта. */
    public static SearchService soundCloudService() {
        return SEARCH_SOUNDCLOUD;
    }

    /**
     * Может ли этот клиент воспроизвести трек указанного провайдера напрямую.
     *
     * <p>Локальные файлы проверяются отдельно (нужен id трека). Для онлайн-площадок
     * смотрим вход в аккаунт: трек из Яндекс Музыки, присланный другом, у которого
     * Яндекс не привязан, иначе уходит в Overtone и падает с HTTP 401.
     */
    public static boolean providerUsable(String provider) {
        String key = provider == null ? "" : provider.trim().toLowerCase(java.util.Locale.ROOT);
        if (key.isEmpty()) key = "soundcloud";
        if ("local".equals(key)) return false;
        try {
            PlayerController pc = PlayerController.I;
            Platform platform = searchPlatform(key);
            MusicProvider p = pc == null || platform == null ? null : pc.provider(platform);
            return p != null && p.isLoggedIn();
        } catch (Throwable t) {
            return false;
        }
    }

    /** Очищает очередь «играть следующим», иначе Overtone уедет в неё и сломает синхрон. */
    public static void clearUserQueue() {
        try {
            if (available()) PlayerController.I.clearQueue();
        } catch (Throwable t) {
            // ignore
        }
    }

    /** Глушит звук: очередь вечеринки очищена, играть больше нечего. */
    public static void stop() {
        try {
            if (!available()) return;
            MusicProvider p = engine();
            if (p != null) p.stop();
        } catch (Throwable t) {
            // ignore
        }
    }

    /**
     * Очищает настоящий плейлист Overtone, чтобы его окно тоже стало пустым
     * после кнопки «Очистить очередь». Текущий трек не трогаем: Overtone
     * сам перерисует нижнюю панель.
     */
    public static void clearPlaylist() {
        try {
            if (!available()) return;
            reflect();
            PlayerController pc = PlayerController.I;
            if (pc == null || queueField == null) return;
            queueField.set(pc, new ArrayList<Track>());
            if (indexField != null) indexField.setInt(pc, 0);
            persist();
        } catch (Throwable t) {
            OvertoneParty.LOGGER.debug("Не удалось очистить плейлист Overtone", t);
        }
    }

    public static long positionMs() {
        try {
            MusicProvider p = engine();
            return p == null ? 0L : Math.max(0L, p.positionMs());
        } catch (Throwable t) {
            return 0L;
        }
    }

    public static boolean isPlaying() {
        try {
            MusicProvider p = engine();
            return p != null && p.isPlaying();
        } catch (Throwable t) {
            return false;
        }
    }

    /** Трек доиграл до конца: Overtone оставил current, но выставил idle. */
    public static boolean isIdle() {
        try {
            if (!available()) return false;
            PlayerController pc = PlayerController.I;
            return pc.current != null && pc.idle;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Пауза, выставленная вручную в плеере (Overtone не отдаёт её напрямую). */
    public static boolean isPaused() {
        try {
            if (!available()) return false;
            PlayerController pc = PlayerController.I;
            if (pc.current == null || pc.idle) return false;
            MusicProvider p = pc.engine();
            if (p == null) return false;
            return !p.isPlaying() && p.positionMs() > 0L;
        } catch (Throwable t) {
            return false;
        }
    }

    public static void pause() {
        try {
            MusicProvider p = engine();
            if (p != null) p.pause();
        } catch (Throwable t) {
            // ignore
        }
    }

    public static void resume() {
        try {
            MusicProvider p = engine();
            if (p != null) p.resume();
        } catch (Throwable t) {
            // ignore
        }
    }

    public static void seek(long ms) {
        try {
            MusicProvider p = engine();
            if (p != null) p.seek(Math.max(0L, ms));
        } catch (Throwable t) {
            // ignore
        }
    }

    public static void setVolume(float v) {
        try {
            if (available()) PlayerController.I.setVolume(Math.max(0f, Math.min(1f, v)));
        } catch (Throwable t) {
            // ignore
        }
    }

    public static MinecraftClient client() {
        return MinecraftClient.getInstance();
    }

    // ---------------------------------------------------------------- theme

    /**
     * Цвета интерфейса Overtone.
     *
     * <p>Значения читаются прямо из {@code PlayerTheme.current()}, поэтому смена
     * акцента, оттенка или прозрачности в настройках плеера сразу видна и в
     * панели вечеринки. Если класс темы недоступен (другая версия Overtone),
     * используется та же формула, что и в {@code PlayerTheme}.
     */
    public record Theme(int accent, int accentHover, int panelTop, int panel2,
                        int fieldBg, int trackBg, int hover, int rowCurrent,
                        int border, int dim, int radius) {
    }

    private static final Theme FALLBACK_THEME = new Theme(
            0xFF9AE6B4, 0xFFB4F0C8, 0xE0141418, 0xFF1B1B21, 0xFF0E0E12,
            0xFF24242A, 0xFF303038, 0xFF1A1A20, 0x1EFFFFFF, 0x80000000, 2);

    private static boolean themeReflected;
    private static Method themeCurrent;
    private static Field thAccent;
    private static Field thAccentHover;
    private static Field thPanelTop;
    private static Field thPanel2;
    private static Field thFieldBg;
    private static Field thTrackBg;
    private static Field thHover;
    private static Field thRowCurrent;
    private static Field thBorder;
    private static Field thDim;
    private static Field thRadius;

    public static Theme theme() {
        Theme live = liveTheme();
        return live != null ? live : computedTheme();
    }

    /** Настоящая палитра открытого окна Overtone (включая смену темы на лету). */
    private static Theme liveTheme() {
        if (!available()) return null;
        if (!themeReflected) {
            themeReflected = true;
            try {
                Class<?> c = Class.forName("dev.musicplayer.ui.PlayerTheme");
                themeCurrent = c.getDeclaredMethod("current");
                themeCurrent.setAccessible(true);
                thAccent = themeField(c, "accent");
                thAccentHover = themeField(c, "accentHover");
                thPanelTop = themeField(c, "panelTop");
                thPanel2 = themeField(c, "panel2");
                thFieldBg = themeField(c, "fieldBg");
                thTrackBg = themeField(c, "trackBg");
                thHover = themeField(c, "hover");
                thRowCurrent = themeField(c, "rowCurrent");
                thBorder = themeField(c, "border");
                thDim = themeField(c, "dim");
                thRadius = themeField(c, "radius");
            } catch (Throwable t) {
                themeCurrent = null;
                OvertoneParty.LOGGER.debug("Тема Overtone недоступна, беру расчётную палитру", t);
            }
        }
        if (themeCurrent == null) return null;
        try {
            Object th = themeCurrent.invoke(null);
            if (th == null) return null;
            return new Theme(
                    thAccent.getInt(th),
                    thAccentHover.getInt(th),
                    thPanelTop.getInt(th),
                    thPanel2.getInt(th),
                    thFieldBg.getInt(th),
                    thTrackBg.getInt(th),
                    thHover.getInt(th),
                    thRowCurrent.getInt(th),
                    thBorder.getInt(th),
                    thDim.getInt(th),
                    thRadius.getInt(th));
        } catch (Throwable t) {
            return null;
        }
    }

    private static Field themeField(Class<?> c, String name) throws NoSuchFieldException {
        Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    /** Резервный расчёт: повторяет формулы {@code PlayerTheme} из Overtone. */
    private static Theme computedTheme() {
        try {
            if (!available()) return FALLBACK_THEME;
            PlayerConfig pc = PlayerConfig.get();
            if (pc == null) return FALLBACK_THEME;
            int accentRgb = pc.accentRgb() & 0xFFFFFF;
            int bg = Math.round(pc.windowOpacity * 2.55F);
            float k = (float) pc.tint * 0.05F;
            return new Theme(
                    0xFF000000 | accentRgb,
                    0xFF000000 | mix(accentRgb, 0xFFFFFF, 0.15F),
                    argb(mix(0x111115, accentRgb, k * 0.6F), Math.round((float) bg * 0.94F)),
                    argb(mix(526346, accentRgb, k * 0.4F), Math.min(255, bg)),
                    0xFF000000 | mix(592140, accentRgb, k * 0.3F),
                    0xFF000000 | mix(0x24242A, accentRgb, k * 0.5F),
                    0xFF000000 | mix(0x303038, accentRgb, k * 0.5F + 0.08F),
                    0xFF000000 | mix(1710624, accentRgb, 0.3F),
                    argb(0xFFFFFF, 30),
                    argb(0, Math.round((float) pc.dim * 2.55F)),
                    (pc.cornerRadius + 1) / 2);
        } catch (Throwable t) {
            return FALLBACK_THEME;
        }
    }

    public static int accentRgb() {
        return theme().accent() & 0xFFFFFF;
    }

    /**
     * Стиль анимации окон из настроек Overtone ({@code ZOOM}, {@code SLIDE},
     * {@code POP}, {@code UNFOLD}, {@code OFF}). Если поле недоступно, берём
     * {@code ZOOM} — тот же стиль, что Overtone использует по умолчанию.
     */
    public static String windowAnim() {
        try {
            if (!available()) return "ZOOM";
            PlayerConfig pc = PlayerConfig.get();
            if (pc == null || pc.windowAnim == null) return "ZOOM";
            return pc.windowAnim.name();
        } catch (Throwable t) {
            return "ZOOM";
        }
    }

    public static int withAlpha(int color, int alpha) {
        return (alpha & 0xFF) << 24 | color & 0xFFFFFF;
    }

    private static int argb(int rgb, int alpha) {
        return (Math.max(0, Math.min(255, alpha)) & 0xFF) << 24 | rgb & 0xFFFFFF;
    }

    private static int mix(int from, int to, float t) {
        t = Math.max(0.0F, Math.min(1.0F, t));
        int r = Math.round((float) (from >> 16 & 0xFF) + (float) ((to >> 16 & 0xFF) - (from >> 16 & 0xFF)) * t);
        int g = Math.round((float) (from >> 8 & 0xFF) + (float) ((to >> 8 & 0xFF) - (from >> 8 & 0xFF)) * t);
        int b = Math.round((float) (from & 0xFF) + (float) ((to & 0xFF) - (from & 0xFF)) * t);
        return r << 16 | g << 8 | b;
    }

    // ---------------------------------------------------------------- reflection

    private static void reflect() {
        if (reflected) return;
        reflected = true;
        try {
            queueField = PlayerController.class.getDeclaredField("queue");
            queueField.setAccessible(true);
            indexField = PlayerController.class.getDeclaredField("index");
            indexField.setAccessible(true);
        } catch (Throwable t) {
            queueField = null;
            indexField = null;
            OvertoneParty.LOGGER.warn("Плейлист Overtone недоступен ({}). "
                    + "Синхронизация ограничится текущим треком.", t.toString());
        }
        try {
            persistMethod = PlayerController.class.getDeclaredMethod("persist");
            persistMethod.setAccessible(true);
        } catch (Throwable ignored) {
            persistMethod = null;
        }
    }

    private static void persist() {
        try {
            if (persistMethod != null && PlayerController.I != null) persistMethod.invoke(PlayerController.I);
        } catch (Throwable ignored) {
            // не критично: состояние ещё сохранится при следующем обычном действии
        }
    }

    private static boolean sameIds(List<Track> a, List<Track> b) {
        if (a == null || b == null || a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (!same(a.get(i), b.get(i))) return false;
        }
        return true;
    }

    private static boolean same(Track a, Track b) {
        if (a == null || b == null) return false;
        return Objects.equals(a.id(), b.id())
                && PartyTrack.providerKey(a.provider()).equals(PartyTrack.providerKey(b.provider()));
    }

    private static int indexOfIdentity(List<Track> list, Track t) {
        for (int i = 0; i < list.size(); i++) {
            if (same(list.get(i), t)) return i;
        }
        return -1;
    }
}
