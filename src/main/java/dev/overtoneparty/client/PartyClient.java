package dev.overtoneparty.client;

import dev.musicplayer.model.Track;
import dev.overtoneparty.OvertoneParty;
import dev.overtoneparty.model.PartyTrack;
import dev.overtoneparty.net.PartyNet;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Клиентская часть: применяет состояние вечеринки к локальному плееру Overtone
 * и отправляет серверу отчёты диджея.
 *
 * <p>Главное правило: команда из экрана вечеринки — это приказ сервера, и диджей
 * тоже обязан его выполнить. Раньше диджей игнорировал такие состояния («у меня
 * играет, значит всё хорошо») и отчётами откатывал очередь назад — из-за этого
 * «Далее» не срабатывала, а потом и «Назад» переставала.
 */
public final class PartyClient {
    public static final long OFFSET_TOLERANCE_MS = 1500L;

    /** Сколько времени после смены трека сервером диджей обязан её применить. */
    private static final long APPLY_WINDOW_MS = 3500L;
    private static final long LOAD_RETRY_MS = 1200L;
    private static final long SEEK_WAIT_MS = 9000L;
    /** Через столько времени без звука считаем, что площадка трек не отдаёт (401, битая ссылка). */
    private static final long STALL_MS = 9000L;

    /** Идентификатор вечеринки, к которой мы сейчас принадлежим. */
    public static volatile UUID partyId = new UUID(0L, 0L);
    /** Состоит ли игрок в вечеринке (даже если её очередь пока пуста). */
    public static volatile boolean member;
    public static volatile boolean privateParty;
    /** Список вечеринок сервера для начального экрана. */
    public static volatile List<PartyNet.PartyEntry> partyList = List.of();

    public static volatile boolean active;
    public static volatile boolean paused;
    public static volatile UUID leader = new UUID(0L, 0L);
    public static volatile String leaderName = "";
    public static volatile List<PartyTrack> tracks = List.of();
    public static volatile int index;
    public static volatile List<String> players = List.of();
    /** Ник того, кто начал вечеринку: только он видит «Диджей +». */
    public static volatile String ownerName = "";
    /** Ники со-диджеев, которых выбрал владелец вечеринки. */
    public static volatile List<String> coDjs = List.of();
    public static volatile int version = -1;

    public static volatile boolean listening = true;

    // Опора таймера: позиция на момент получения пакета и локальное время приёма.
    private static volatile long anchorPos;
    private static volatile long recvMs;
    /** До этого момента смена трека считается командой сервера, а не самодеятельностью диджея. */
    private static volatile long applyWindowUntil;

    private static long lastLoadAttempt;
    private static long pendingSince;
    private static long lastCorrect;
    private static long lastReport;
    private static int pauseAppliedVersion = -1;
    private static int mirroredVersion = -1;
    /** Версия состояния, на которой уже остановили звук из-за пустой очереди. */
    private static int emptyStoppedVersion = -1;
    private static boolean applying;
    private static int joinTicks;
    private static int helloTries;
    private static boolean gotState;

    /** Замены локальных треков (ключ включает исходный трек и площадку поиска). */
    private static final Map<String, Track> substitutes = new ConcurrentHashMap<>();
    /** Для каких треков поиск замены уже запущен. */
    private static final Set<String> substituteSearched = ConcurrentHashMap.newKeySet();
    /** Для каких треков замену найти не удалось — повторно не ищем. */
    private static final Set<String> substituteFailed = ConcurrentHashMap.newKeySet();
    /** Треки, чья площадка уже не смогла их загрузить (401/404/тишина). */
    private static final Set<String> stalledTracks = ConcurrentHashMap.newKeySet();

    private PartyClient() {
    }

    // ---------------------------------------------------------------- state in

    public static void onState(PartyNet.StateMsg m) {
        long now = System.currentTimeMillis();
        String prevKey = trackKey(currentTrack());
        int prevIndex = index;
        boolean prevActive = active;
        boolean prevMember = member;
        UUID prevPartyId = partyId;

        partyId = m.partyId() == null ? new UUID(0L, 0L) : m.partyId();
        member = m.member();
        privateParty = m.privateParty();
        version = m.version();
        // Пустая вечеринка всё равно остаётся «нашим» экраном: в ней можно
        // добавлять треки, поэтому member — главный признак, а не active.
        active = m.member() && m.active();
        paused = m.paused();
        leader = m.leader();
        leaderName = m.leaderName();
        tracks = List.copyOf(m.tracks());
        index = m.index();
        players = List.copyOf(m.players());
        ownerName = m.ownerName() == null ? "" : m.ownerName();
        coDjs = List.copyOf(m.coDjs());
        anchorPos = m.pos();
        recvMs = now;
        gotState = true;

        if (prevMember && !member) {
            // Вечеринка остановлена или мы вышли: локальный плеер должен замолчать.
            stopLocalAudio();
        } else if (member && (!prevMember || !prevPartyId.equals(partyId))) {
            resetPlaybackState();
        }

        if (!active) {
            applying = false;
            pendingSince = 0L;
            lastLoadAttempt = 0L;
            applyWindowUntil = 0L;
            emptyStoppedVersion = -1;
            return;
        }
        if (!tracks.isEmpty()) emptyStoppedVersion = -1;
        if (prevIndex != index || !Objects.equals(prevKey, trackKey(currentTrack()))) {
            lastLoadAttempt = 0L;
            applyWindowUntil = now + APPLY_WINDOW_MS;
            stalledTracks.clear();
        }
        if (!prevActive) lastCorrect = 0L;
    }

    public static void onList(List<PartyNet.PartyEntry> list) {
        partyList = list == null ? List.of() : List.copyOf(list);
    }

    public static void onPos(PartyNet.PosMsg m) {
        // Пакеты позиции от других вечеринок не должны двигать наш плеер.
        if (!active || m.partyId() == null || !m.partyId().equals(partyId)) return;
        long now = System.currentTimeMillis();
        String prevKey = trackKey(currentTrack());
        int prevIndex = index;
        version = m.version();
        index = m.index();
        paused = m.paused();
        anchorPos = m.pos();
        recvMs = now;
        if (prevIndex != index || !Objects.equals(prevKey, trackKey(currentTrack()))) {
            lastLoadAttempt = 0L;
            applyWindowUntil = now + APPLY_WINDOW_MS;
            stalledTracks.clear();
        }
        gotState = true;
    }

    private static long expectedPos() {
        long p = paused ? anchorPos : anchorPos + Math.max(0L, System.currentTimeMillis() - recvMs);
        PartyTrack t = currentTrack();
        long dur = t == null ? 0L : t.durationMs();
        if (dur > 0L) p = Math.min(p, dur);
        return Math.max(0L, p);
    }

    /** Позиция для интерфейса (обновляется сама, без пакетов). */
    public static long positionNow() {
        return expectedPos();
    }

    // ---------------------------------------------------------------- tick

    public static void tick(MinecraftClient mc) {
        if (mc.player == null) return;

        joinTicks++;
        if (!gotState && joinTicks % 20 == 1 && helloTries < 15) {
            helloTries++;
            try {
                PartyClientNet.sendHello();
            } catch (Throwable t) {
                // канал ещё не готов — попробуем на следующем тике
            }
        }

        if (!active || !listening || !OvertoneBridge.available()) return;

        if (tracks.isEmpty()) {
            // Очередь очистили: гасим локальный звук, иначе Overtone продолжит
            // играть трек, которого в вечеринке уже нет.
            if (emptyStoppedVersion != version) {
                emptyStoppedVersion = version;
                OvertoneBridge.stop();
                OvertoneBridge.clearPlaylist();
            }
            return;
        }

        PartyTrack want = currentTrack();
        if (want == null) return;

        boolean isLeader = mc.player.getUuid().equals(leader);
        long now = System.currentTimeMillis();

        // Трек друга может быть недоступен здесь: локальный файл остался у него,
        // а для Яндекса/Spotify у этого клиента может не быть входа. Overtone
        // падает с «Файл не найден» или HTTP 401 — ищем тот же трек в площадке
        // этого слушателя и играем найденную версию.
        String wantKey = trackKey(want);
        // Площадка может подтвердить вход, но не отдавать файл (просроченный
        // токен Яндекса, 404). Если за 9 секунд звук не пошёл — ищем замену.
        boolean stalled = pendingSince > 0L && !paused && now - pendingSince > STALL_MS
                && !OvertoneBridge.isPlaying();
        boolean force = stalled && stalledTracks.add(wantKey);

        Track substitute = null;
        if (needsSubstitute(want) || stalledTracks.contains(wantKey)) {
            substitute = substituteFor(want, force);
            if (substitute == null) return;
        }

        Track local = OvertoneBridge.current();
        boolean same = substitute != null ? sameTrack(local, substitute) : sameTrack(local, want);

        long dur = want.durationMs();
        long expected = expectedPos();
        // На паузе сервер трек не переключает, поэтому «хвост» не должен мешать
        // догрузить текущий трек (например, только что зашедшему игроку).
        boolean finished = !paused && dur > 0L && expected >= dur - 1500L;

        if (!same) {
            // Хвост трека уже прошёл — сервер вот-вот переключит, не начинаем заново.
            if (finished) return;

            boolean recentState = now <= applyWindowUntil;
            if (isLeader && !recentState && substitute == null) {
                boolean djActed = local != null && !OvertoneBridge.isIdle()
                        && (OvertoneBridge.isPlaying() || OvertoneBridge.positionMs() > 0L);
                if (djActed) {
                    // Диджей сам переключил/поставил трек в Overtone: его ход — главный.
                    // Отчёт перенесёт это в вечеринку, откатывать не нужно.
                    applying = false;
                    report(now);
                    return;
                }
            }
            if (now - lastLoadAttempt < LOAD_RETRY_MS) return;

            lastLoadAttempt = now;
            applying = true;
            pendingSince = now;
            if (isLeader && substitute == null) {
                OvertoneBridge.playAt(tracks, index);
            } else {
                OvertoneBridge.play(substitute != null ? PartyTrack.from(substitute) : want);
            }
            return;
        }

        if (isLeader) {
            leaderTick(now, expected, finished);
        } else {
            listenerTick(now, expected, dur, finished);
        }
    }

    private static void leaderTick(long now, long expected, boolean finished) {
        boolean playing = OvertoneBridge.isPlaying();

        if (pendingSince > 0L) {
            boolean started = playing || OvertoneBridge.positionMs() > 0L;
            if (!started && now - pendingSince <= SEEK_WAIT_MS) return; // ещё грузим трек
            if (expected > 0L) OvertoneBridge.seek(expected);
            if (paused) OvertoneBridge.pause();
            pendingSince = 0L;
            applying = false;
            lastCorrect = now;
            pauseAppliedVersion = version;
            return;
        }
        applying = false;

        if (paused) {
            if (playing) OvertoneBridge.pause();
        } else if (pauseAppliedVersion != version) {
            if (!playing && !OvertoneBridge.isIdle() && !finished) {
                OvertoneBridge.resume();
                lastCorrect = now;
            }
        }
        pauseAppliedVersion = version;

        if (!paused && !finished && playing && now - lastCorrect > 3000L) {
            long drift = OvertoneBridge.positionMs() - expected;
            if (Math.abs(drift) > 2500L) {
                OvertoneBridge.seek(expected);
                lastCorrect = now;
            }
        }

        // Окно Overtone должно показывать очередь вечеринки, а не старый плейлист.
        if (mirroredVersion != version) {
            mirroredVersion = version;
            OvertoneBridge.mirrorPlaylist(tracks, index);
        }

        report(now);
    }

    private static void listenerTick(long now, long expected, long dur, boolean finished) {
        if (pendingSince > 0L) {
            boolean started = OvertoneBridge.isPlaying() || OvertoneBridge.positionMs() > 0L;
            if (started) {
                OvertoneBridge.seek(expected);
                if (paused) OvertoneBridge.pause();
                pendingSince = 0L;
                lastCorrect = now;
            } else if (now - pendingSince > 20000L) {
                pendingSince = 0L;
            }
            return;
        }

        if (paused) {
            if (OvertoneBridge.isPlaying()) OvertoneBridge.pause();
            return;
        }

        if (!OvertoneBridge.isPlaying() && (dur <= 0L || expected < dur - 1500L)) {
            OvertoneBridge.resume();
        }

        if (now - lastCorrect > 2500L && OvertoneBridge.isPlaying()) {
            long drift = OvertoneBridge.positionMs() - expected;
            if (Math.abs(drift) > OFFSET_TOLERANCE_MS) {
                OvertoneBridge.seek(expected);
                lastCorrect = now;
            }
        }
    }

    private static void report(long now) {
        if (now - lastReport < 1000L) return;
        Track local = OvertoneBridge.current();
        if (local == null) return;
        boolean playing = OvertoneBridge.isPlaying();
        boolean idle = OvertoneBridge.isIdle();
        long pos = OvertoneBridge.positionMs();
        boolean pz = !playing && !idle && pos > 0L;
        // Трек закончился или остановлен — не шлём позицию 0, иначе сервер
        // решит, что диджей перемотал в начало.
        if (!playing && !pz) return;
        lastReport = now;
        try {
            PartyClientNet.sendReport(new PartyNet.ReportMsg(
                    version, local.id(), local.title(), local.artist(), local.album(), local.durationMs(),
                    local.coverUrl(), local.provider(), pz, pos, playing, idle));
        } catch (Throwable t) {
            OvertoneParty.LOGGER.debug("Отчёт диджея не отправлен", t);
        }
    }

    // ---------------------------------------------------------------- actions

    /** Создать открытую вечеринку с текущим плейлистом (для старых вызовов). */
    public static void startParty() {
        createParty(false, "");
    }

    /** Создать свою вечеринку: открытую или приватную с паролем. */
    public static void createParty(boolean priv, String password) {
        List<PartyTrack> list = new ArrayList<>();
        List<Track> nativeList = OvertoneBridge.playlist();
        if (nativeList.isEmpty()) {
            // Старый/нестандартный Overtone: остаётся только очередь «играть следующим».
            nativeList = OvertoneBridge.queueSnapshot();
        }
        for (Track t : nativeList) {
            PartyTrack pt = PartyTrack.from(t);
            if (pt != null && !PartyTrack.isBlank(pt)) list.add(pt);
        }
        int idx = Math.max(0, Math.min(OvertoneBridge.playlistIndex(), Math.max(0, list.size() - 1)));

        Track cur = OvertoneBridge.current();
        if (cur != null) {
            int found = indexOf(list, cur);
            if (found >= 0) {
                idx = found;
            } else {
                // Трек из «очереди на следующий» или радио: он играет сейчас,
                // поэтому ставим его в начало плейлиста вечеринки.
                list.add(0, PartyTrack.from(cur));
                idx = 0;
            }
        }

        // Очередь Overtone недоступна — подстрахуемся прошлым плейлистом вечеринки.
        if (list.size() <= 1 && tracks.size() > 1) {
            list = new ArrayList<>(tracks);
            int found = cur == null ? -1 : indexOf(list, cur);
            idx = found >= 0 ? found : 0;
        }

        if (list.isEmpty()) {
            // Пустая вечеринка: любой сможет добавлять треки в общую очередь.
            PartyClientNet.sendCreate(new PartyNet.CreateMsg(
                    new PartyNet.StartMsg(List.of(), 0, 0L, false), priv, password));
            return;
        }

        // Иначе Overtone уедет в свою очередь «играть следующим» и сломает синхрон.
        OvertoneBridge.clearUserQueue();

        long pos = OvertoneBridge.positionMs();
        boolean pz = OvertoneBridge.isPaused();
        try {
            PartyClientNet.sendCreate(new PartyNet.CreateMsg(
                    new PartyNet.StartMsg(list, idx, pos, pz), priv, password));
        } catch (Throwable t) {
            OvertoneParty.LOGGER.warn("Не удалось отправить создание вечеринки", t);
        }
    }

    /** Присоединиться к другой вечеринке (для приватной нужен пароль). */
    public static void joinParty(UUID id, String password) {
        if (id == null) return;
        PartyClientNet.sendJoin(id, password == null ? "" : password);
    }

    /** Выйти из текущей вечеринки, оставаясь в мире. */
    public static void leaveParty() {
        PartyClientNet.sendLeave();
    }

    public static void stopParty() {
        PartyClientNet.sendCommand(PartyNet.CMD_STOP);
    }

    public static void togglePause() {
        PartyClientNet.sendCommand(paused ? PartyNet.CMD_RESUME : PartyNet.CMD_PAUSE);
    }

    public static void next() {
        PartyClientNet.sendCommand(PartyNet.CMD_NEXT);
    }

    public static void prev() {
        PartyClientNet.sendCommand(PartyNet.CMD_PREV);
    }

    public static void playIndex(int i) {
        PartyClientNet.sendCommand(PartyNet.CMD_PLAY_INDEX, i);
    }

    /** Убрать трек из общей очереди (диджей/оператор). */
    public static void removeTrack(int i) {
        PartyClientNet.sendCommand(PartyNet.CMD_REMOVE, i);
    }

    /** Переставить трек в очереди: from -> to (диджей/оператор). */
    public static void moveTrack(int from, int to) {
        PartyClientNet.sendCommand(PartyNet.CMD_MOVE, from, to);
    }

    /**
     * Добавить конкретный трек в очередь текущей вечеринки (кнопка «♪+» в Overtone).
     * Если не в вечеринке — подсказываем, что сначала нужно создать или выбрать её.
     */
    public static void addTrack(PartyTrack t) {
        if (PartyTrack.isBlank(t)) return;
        if (!member) {
            notify("Сначала создайте свою вечеринку или присоединитесь к другой (клавиша P).");
            return;
        }
        PartyClientNet.sendCommand(PartyNet.CMD_ADD_TRACK, List.of(t));
    }

    /** Очистить общую очередь (диджей/оператор). */
    public static void clearQueue() {
        PartyClientNet.sendCommand(PartyNet.CMD_CLEAR);
    }

    public static void transferNext() {
        if (players.size() < 2) return;
        int at = players.indexOf(leaderName);
        String target = players.get((at + 1 + players.size()) % players.size());
        if (!target.equals(leaderName)) PartyClientNet.sendCommand(PartyNet.CMD_TRANSFER, target);
    }

    /**
     * Включить или выключить со-диджея. Доступно только владельцу вечеринки;
     * сервер всё равно перепроверит права.
     */
    public static void toggleCoDj(String name) {
        if (name == null || name.isEmpty()) return;
        PartyClientNet.sendCommand(PartyNet.CMD_CODJ, name);
    }

    public static boolean isCoDj(String name) {
        return name != null && !name.isEmpty() && coDjs.contains(name);
    }

    public static void setListening(boolean value) {
        listening = value;
        PartyConfig.save();
        lastLoadAttempt = 0L;
        pendingSince = 0L;
    }

    public static void toggleListening() {
        setListening(!listening);
        if (!listening) return;
        lastLoadAttempt = 0L;
    }

    // ---------------------------------------------------------------- session

    public static void onJoin() {
        partyId = new UUID(0L, 0L);
        member = false;
        privateParty = false;
        partyList = List.of();
        active = false;
        paused = false;
        leader = new UUID(0L, 0L);
        leaderName = "";
        tracks = List.of();
        players = List.of();
        ownerName = "";
        coDjs = List.of();
        index = 0;
        version = -1;
        pauseAppliedVersion = -1;
        mirroredVersion = -1;
        emptyStoppedVersion = -1;
        applying = false;
        gotState = false;
        joinTicks = 0;
        helloTries = 0;
        lastLoadAttempt = 0L;
        pendingSince = 0L;
        lastCorrect = 0L;
        applyWindowUntil = 0L;
        substitutes.clear();
        substituteSearched.clear();
        substituteFailed.clear();
        stalledTracks.clear();
        if (ClientPlayNetworking.canSend(PartyNet.HELLO)) {
            try {
                PartyClientNet.sendHello();
                helloTries = 1;
            } catch (Throwable ignored) {
            }
        }
    }

    public static void onDisconnect() {
        partyId = new UUID(0L, 0L);
        member = false;
        privateParty = false;
        partyList = List.of();
        active = false;
        paused = false;
        tracks = List.of();
        players = List.of();
        ownerName = "";
        coDjs = List.of();
        gotState = false;
        pendingSince = 0L;
        applying = false;
        pauseAppliedVersion = -1;
        mirroredVersion = -1;
        emptyStoppedVersion = -1;
        substitutes.clear();
        substituteSearched.clear();
        substituteFailed.clear();
        stalledTracks.clear();
    }

    /** Сбросить локальные таймеры при смене вечеринки. */
    private static void resetPlaybackState() {
        lastLoadAttempt = 0L;
        pendingSince = 0L;
        lastCorrect = 0L;
        lastReport = 0L;
        applyWindowUntil = 0L;
        pauseAppliedVersion = -1;
        mirroredVersion = -1;
        emptyStoppedVersion = -1;
        applying = false;
        substitutes.clear();
        substituteSearched.clear();
        substituteFailed.clear();
        stalledTracks.clear();
    }

    /** Остановить звук и очистить плейлист Overtone при выходе из вечеринки. */
    private static void stopLocalAudio() {
        try {
            OvertoneBridge.stop();
            OvertoneBridge.clearPlaylist();
        } catch (Throwable t) {
            OvertoneParty.LOGGER.debug("Не удалось остановить локальный плеер при выходе", t);
        }
        resetPlaybackState();
    }

    // ---------------------------------------------------------------- helpers

    public static PartyTrack currentTrack() {
        List<PartyTrack> list = tracks;
        int i = index;
        if (i < 0 || i >= list.size()) return null;
        return list.get(i);
    }

    public static boolean canControl(MinecraftClient mc) {
        return member && mc != null && mc.player != null
                && (mc.player.getUuid().equals(leader)
                || coDjs.contains(mc.player.getGameProfile().getName())
                || isOwner(mc)
                || mc.player.hasPermissionLevel(2));
    }

    /** Является ли игрок владельцем вечеринки (тем, кто её начал). */
    public static boolean isOwner(MinecraftClient mc) {
        return mc != null && mc.player != null && !ownerName.isEmpty()
                && ownerName.equals(mc.player.getGameProfile().getName());
    }

    private static String trackKey(PartyTrack t) {
        return t == null ? null : t.id() + "|" + t.provider();
    }

    private static int indexOf(List<PartyTrack> list, Track t) {
        PartyTrack want = PartyTrack.from(t);
        for (int i = 0; i < list.size(); i++) {
            if (PartyTrack.sameTrack(list.get(i), want)) return i;
        }
        return -1;
    }

    private static boolean sameTrack(Track local, PartyTrack want) {
        return local != null && want != null
                && Objects.equals(local.id(), want.id())
                && PartyTrack.providerKey(local.provider()).equals(PartyTrack.providerKey(want.provider()));
    }

    private static boolean sameTrack(Track local, Track want) {
        return local != null && want != null
                && Objects.equals(local.id(), want.id())
                && PartyTrack.providerKey(local.provider()).equals(PartyTrack.providerKey(want.provider()));
    }

    // ---------------------------------------------------------------- fallback

    /**
     * Нужно ли искать замену: локального файла нет, площадка трека не доступна
     * этому клиенту (нет входа/токен просрочен) или трек уже не загрузился.
     */
    private static boolean needsSubstitute(PartyTrack t) {
        if (t == null) return false;
        String provider = PartyTrack.providerKey(t.provider());
        if ("local".equals(provider)) return !OvertoneBridge.localFileExists(t.id());
        String selected = OvertoneBridge.preferredSearchService().id();
        if (!provider.equals(selected)) return true;
        return !OvertoneBridge.providerUsable(provider);
    }

    /**
     * Замена трека из площадки, выбранной у этого пользователя.
     * Поиск идёт в фоне; пока он не закончился, возвращается {@code null}.
     */
    private static Track substituteFor(PartyTrack want, boolean force) {
        OvertoneBridge.SearchService service = OvertoneBridge.preferredSearchService();
        String base = trackKey(want) + "|search=" + service.id();
        String key = force ? base + "|retry" : base;
        if (force) {
            substitutes.remove(key);
            substituteFailed.remove(key);
            substituteSearched.remove(key);
        }
        Track known = substitutes.get(key);
        if (known != null) return known;
        if (substituteFailed.contains(key)) return null;
        if (substituteSearched.add(key)) {
            searchSubstitute(want, key, service);
        }
        return null;
    }

    private static void searchSubstitute(PartyTrack want, String key,
                                         OvertoneBridge.SearchService service) {
        String artist = want.artist() == null ? "" : want.artist();
        String title = want.title() == null ? "" : want.title();
        String full = (artist + " " + title).trim();
        OvertoneParty.LOGGER.info("Трек недоступен напрямую ({}), ищу через {}: {}",
                PartyTrack.providerKey(want.provider()), service.displayName(), full);

        // Разные площадки по-разному понимают составной запрос. Поэтому пробуем
        // «исполнитель + название», затем название и исполнителя по отдельности,
        // а лишние результаты отсеиваем оценкой похожести. Если выбранная площадка
        // не SoundCloud, дополнительно пробуем SoundCloud как запасную.
        List<OvertoneBridge.SearchService> services = new ArrayList<>(2);
        services.add(service);
        if (!"soundcloud".equals(service.id())) services.add(OvertoneBridge.soundCloudService());

        List<CompletableFuture<List<Track>>> searches = new ArrayList<>(6);
        for (OvertoneBridge.SearchService s : services) {
            if (!full.isEmpty()) searches.add(OvertoneBridge.search(s, full));
            if (!title.isEmpty() && !title.equals(full)) searches.add(OvertoneBridge.search(s, title));
            if (!artist.isEmpty()) searches.add(OvertoneBridge.search(s, artist));
        }

        CompletableFuture<?>[] all = searches.toArray(new CompletableFuture<?>[0]);
        CompletableFuture.allOf(all).whenComplete((ignored, error) -> {
            List<Track> candidates = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (CompletableFuture<List<Track>> f : searches) {
                List<Track> part;
                try {
                    part = f.getNow(List.of());
                } catch (Throwable t) {
                    part = List.of();
                }
                for (Track t : part) {
                    String candidateKey = t == null ? "" : PartyTrack.providerKey(t.provider()) + "|" + t.id();
                    if (t != null && t.id() != null && seen.add(candidateKey)) candidates.add(t);
                }
            }
            Track best = bestMatch(want, candidates);
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc == null) return;
            mc.execute(() -> {
                if (best != null) {
                    substitutes.put(key, best);
                    notify("Трек недоступен здесь — включаю версию из " + service.displayName()
                            + ": " + label(best));
                } else if (error != null && candidates.isEmpty()) {
                    // Сеть подвела, а не «трека нет» — попробуем ещё раз позже.
                    substituteSearched.remove(key);
                } else {
                    substituteFailed.add(key);
                    notify("Трек «" + want.displayName() + "» не нашёлся в "
                            + service.displayName() + ": замена не найдена.");
                }
            });
        });
    }

    /** Лучшее совпадение по названию и исполнителю; слишком слабые не берём. */
    private static Track bestMatch(PartyTrack want, List<Track> list) {
        if (list == null || list.isEmpty()) return null;
        String wantTitle = normalize(want.title());
        String wantArtist = normalize(want.artist());
        Track best = null;
        double bestScore = 0.0;
        for (Track t : list) {
            if (t == null) continue;
            double score = similarity(wantTitle, normalize(t.title())) * 0.7
                    + similarity(wantArtist, normalize(t.artist())) * 0.3;
            if (want.durationMs() > 0L && t.durationMs() > 0L) {
                long diff = Math.abs(want.durationMs() - t.durationMs());
                if (diff <= 8000L) score += 0.15;
                else if (diff > 60000L) score -= 0.2;
            }
            if (score > bestScore) {
                bestScore = score;
                best = t;
            }
        }
        return bestScore >= 0.55 ? best : null;
    }

    private static String normalize(String s) {
        if (s == null) return "";
        String x = s.toLowerCase(Locale.ROOT)
                .replaceAll("\\([^)]*\\)", " ")
                .replaceAll("\\[[^]]*]", " ")
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .trim();
        return x;
    }

    private static double similarity(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) return 0.0;
        if (a.equals(b)) return 1.0;
        if (a.contains(b) || b.contains(a)) return 0.85;
        Set<String> sa = new HashSet<>(Arrays.asList(a.split(" ")));
        Set<String> sb = new HashSet<>(Arrays.asList(b.split(" ")));
        Set<String> intersection = new HashSet<>(sa);
        intersection.retainAll(sb);
        Set<String> union = new HashSet<>(sa);
        union.addAll(sb);
        return union.isEmpty() ? 0.0 : (double) intersection.size() / union.size();
    }

    private static String label(Track t) {
        if (t == null) return "?";
        String artist = t.artist() == null ? "" : t.artist();
        String title = t.title() == null ? "?" : t.title();
        return artist.isEmpty() ? title : title + " — " + artist;
    }

    private static void notify(String text) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.inGameHud == null) return;
        mc.inGameHud.getChatHud().addMessage(Text.literal("[Вечеринка] " + text));
    }

    public static String localTrackLabel() {
        Track t = OvertoneBridge.current();
        return t == null ? "" : t.title() + (t.artist() == null || t.artist().isEmpty() ? "" : " — " + t.artist());
    }
}
