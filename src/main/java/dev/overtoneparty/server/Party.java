package dev.overtoneparty.server;

import dev.overtoneparty.model.PartyTrack;
import dev.overtoneparty.net.PartyNet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Одна независимая вечеринка. Игроки сервера разбиваются на такие объекты:
 * у каждой свой владелец, диджей, очередь, позиция и список участников.
 *
 * <p>Вся логика воспроизведения — бывший {@code PartyManager} в одном экземпляре,
 * но состояние теперь не глобальное, а принадлежит этой вечеринке.
 */
public final class Party {
    private static final UUID NIL = new UUID(0L, 0L);

    private final PartyManager manager;
    private final MinecraftServer server;
    private final UUID id;
    private final String name;
    private final boolean privateParty;
    private final String password;
    private final Set<UUID> members = new java.util.LinkedHashSet<>();
    private final List<PartyTrack> tracks = new ArrayList<>();
    /** Игроки, которых владелец вечеринки сделал со-диджеями. */
    private final java.util.LinkedHashMap<UUID, String> coDjs = new java.util.LinkedHashMap<>();

    private UUID owner;
    private String ownerName = "";
    private UUID leader;
    private String leaderName = "";
    private boolean active = true;
    private boolean paused;
    private int index;
    private long anchorPos;
    private long anchorMs;
    private int version;
    private long lastAdvanceMs;

    Party(PartyManager manager, MinecraftServer server, UUID id, String name,
          boolean privateParty, String password, ServerPlayerEntity creator) {
        this.manager = manager;
        this.server = server;
        this.id = id;
        this.name = name;
        this.privateParty = privateParty;
        this.password = password == null ? "" : password;
        this.owner = creator.getUuid();
        this.ownerName = creator.getName().getString();
        this.leader = owner;
        this.leaderName = ownerName;
        this.members.add(owner);
        this.anchorMs = System.currentTimeMillis();
    }

    // ---------------------------------------------------------------- init

    void setInitial(PartyNet.StartMsg m) {
        List<PartyTrack> filtered = new ArrayList<>();
        for (PartyTrack t : m.tracks()) {
            if (filtered.size() >= PartyNet.MAX_TRACKS) break;
            if (t != null && !PartyTrack.isBlank(t)) filtered.add(t);
        }
        tracks.clear();
        tracks.addAll(filtered);
        index = tracks.isEmpty() ? 0 : Math.max(0, Math.min(m.index(), tracks.size() - 1));
        anchorPos = Math.max(0L, m.pos());
        anchorMs = System.currentTimeMillis();
        paused = filtered.isEmpty() ? false : m.paused();
        version++;
    }

    // ---------------------------------------------------------------- tick

    void tick(int tick, long now) {
        if (!active) return;
        if (!paused && tick % 20 == 0) maybeAdvance(now);
    }

    // ---------------------------------------------------------------- members

    boolean hasMember(UUID playerId) {
        return members.contains(playerId);
    }

    Set<UUID> playerIds() {
        return new java.util.LinkedHashSet<>(members);
    }

    int memberCount() {
        return members.size();
    }

    void addMember(ServerPlayerEntity player) {
        members.add(player.getUuid());
    }

    /** Удаляет игрока; возвращает {@code true}, если вечеринка опустела. */
    boolean onMemberLeave(ServerPlayerEntity player) {
        UUID id = player.getUuid();
        members.remove(id);
        coDjs.remove(id);
        if (members.isEmpty()) {
            active = false;
            return true;
        }

        boolean changed = false;
        if (id.equals(leader)) {
            UUID next = pickNextLeader(id);
            leader = next.equals(NIL) ? NIL : next;
            leaderName = leader.equals(NIL) ? "" : nameOf(leader);
            changed = true;
            manager.broadcast(this, Text.literal("Диджей вышел. " + (leaderName.isEmpty()
                    ? "Вечеринка продолжается без диджея." : "Новый диджей: " + leaderName)));
        }
        if (id.equals(owner)) {
            UUID next = leader.equals(NIL) ? pickNextLeader(id) : leader;
            owner = next.equals(NIL) ? NIL : next;
            ownerName = owner.equals(NIL) ? "" : nameOf(owner);
            changed = true;
            if (!ownerName.isEmpty()) {
                manager.broadcast(this, Text.literal("♪ Вечеринку теперь ведёт: " + ownerName));
            }
        }
        if (changed) {
            version++;
        }
        return false;
    }

    // ---------------------------------------------------------------- accessors

    public UUID id() {
        return id;
    }

    public String name() {
        return name;
    }

    public boolean isPrivate() {
        return privateParty;
    }

    boolean checkPassword(String attempt) {
        return !privateParty || password.equals(attempt == null ? "" : attempt);
    }

    public boolean isActive() {
        return active;
    }

    public boolean isPaused() {
        return paused;
    }

    public UUID leaderId() {
        return leader == null ? NIL : leader;
    }

    public String leaderName() {
        return leaderName == null ? "" : leaderName;
    }

    public String ownerName() {
        return ownerName == null ? "" : ownerName;
    }

    public List<String> coDjNames() {
        return new ArrayList<>(coDjs.values());
    }

    public int trackIndex() {
        return index;
    }

    public int version() {
        return version;
    }

    public List<PartyTrack> trackList() {
        return tracks;
    }

    public PartyTrack currentTrack() {
        if (index < 0 || index >= tracks.size()) return null;
        return tracks.get(index);
    }

    public List<String> playerNames() {
        List<String> names = new ArrayList<>();
        for (UUID id : members) {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(id);
            if (p != null) names.add(p.getName().getString());
        }
        return names;
    }

    public long positionNow() {
        return positionAt(System.currentTimeMillis());
    }

    private long positionAt(long now) {
        PartyTrack t = currentTrack();
        long p = paused ? anchorPos : anchorPos + Math.max(0L, now - anchorMs);
        long dur = t == null ? 0L : t.durationMs();
        if (dur > 0L) p = Math.min(p, dur);
        return Math.max(0L, p);
    }

    // ---------------------------------------------------------------- actions

    void stop(ServerPlayerEntity player) {
        if (!active) {
            msg(player, "Эта вечеринка уже остановлена.");
            return;
        }
        if (!canManage(player)) {
            msg(player, "Остановить вечеринку может только владелец, диджей или оператор.");
            return;
        }
        active = false;
        paused = false;
        anchorPos = 0L;
        index = 0;
        version++;
        manager.removeParty(this, Text.literal("♪ Вечеринка «" + name + "» остановлена."));
    }

    void pause(ServerPlayerEntity player) {
        if (!checkLeader(player, "поставить на паузу")) return;
        if (!active || paused) return;
        anchorPos = positionNow();
        anchorMs = System.currentTimeMillis();
        paused = true;
        version++;
        manager.broadcastState(this);
    }

    void resume(ServerPlayerEntity player) {
        if (!checkLeader(player, "снять с паузы")) return;
        if (!active || !paused) return;
        anchorMs = System.currentTimeMillis();
        paused = false;
        version++;
        manager.broadcastState(this);
    }

    void skip(ServerPlayerEntity player, int dir) {
        if (!checkLeader(player, "переключать треки")) return;
        if (!active || tracks.isEmpty()) return;
        jumpTo(index + dir, System.currentTimeMillis());
    }

    void seek(ServerPlayerEntity player, long posMs) {
        if (!checkLeader(player, "перематывать")) return;
        if (!active) return;
        anchorPos = Math.max(0L, posMs);
        anchorMs = System.currentTimeMillis();
        version++;
        manager.broadcastState(this);
    }

    void transfer(ServerPlayerEntity player, ServerPlayerEntity target) {
        if (!canManage(player)) {
            msg(player, "Передать диджея может только владелец, диджей или оператор.");
            return;
        }
        if (!active) {
            msg(player, "Вечеринка не запущена.");
            return;
        }
        if (!members.contains(target.getUuid())) {
            msg(player, "Игрок " + target.getName().getString() + " не в этой вечеринке.");
            return;
        }
        leader = target.getUuid();
        leaderName = target.getName().getString();
        version++;
        manager.broadcastState(this);
        manager.broadcast(this, Text.literal("♪ Новый диджей: " + leaderName));
    }

    /** Включает или выключает роль со-диджея (только владелец). */
    void setCoDj(ServerPlayerEntity player, ServerPlayerEntity target) {
        if (!player.getUuid().equals(owner) && !manager.isOp(player)) {
            msg(player, "Выбирать со-диджеев может только владелец вечеринки.");
            return;
        }
        if (!active) {
            msg(player, "Вечеринка не запущена.");
            return;
        }
        UUID id = target.getUuid();
        String targetName = target.getName().getString();
        if (id.equals(owner)) {
            msg(player, "Владелец вечеринки и так управляет ей.");
            return;
        }
        if (!members.contains(id)) {
            msg(player, "Игрок " + targetName + " не в этой вечеринке.");
            return;
        }
        if (coDjs.remove(id) != null) {
            manager.broadcast(this, Text.literal("♪ " + targetName + " больше не со-диджей."));
        } else {
            coDjs.put(id, targetName);
            manager.broadcast(this, Text.literal("♪ " + targetName + " теперь со-диджей."));
        }
        version++;
        manager.broadcastState(this);
    }

    void command(ServerPlayerEntity player, int op, int arg, int arg2,
                 long argL, String argS, List<PartyTrack> list) {
        switch (op) {
            case PartyNet.CMD_STOP -> stop(player);
            case PartyNet.CMD_PAUSE -> pause(player);
            case PartyNet.CMD_RESUME -> resume(player);
            case PartyNet.CMD_NEXT -> {
                if (checkLeader(player, "переключать треки")) jumpTo(index + 1, System.currentTimeMillis());
            }
            case PartyNet.CMD_PREV -> {
                if (checkLeader(player, "переключать треки")) jumpTo(index - 1, System.currentTimeMillis());
            }
            case PartyNet.CMD_PLAY_INDEX -> {
                if (checkLeader(player, "переключать треки")) jumpTo(arg, System.currentTimeMillis());
            }
            case PartyNet.CMD_SEEK -> {
                if (!checkLeader(player, "перематывать") || !active) return;
                anchorPos = Math.max(0L, argL);
                anchorMs = System.currentTimeMillis();
                version++;
                manager.broadcastState(this);
            }
            case PartyNet.CMD_ADD_TRACK, PartyNet.CMD_ADD_TRACKS -> addTracks(player, list);
            case PartyNet.CMD_REMOVE -> removeTrack(player, arg);
            case PartyNet.CMD_MOVE -> moveTrack(player, arg, arg2);
            case PartyNet.CMD_CLEAR -> clearQueue(player);
            case PartyNet.CMD_TRANSFER -> {
                ServerPlayerEntity target = player.getServer() == null ? null
                        : player.getServer().getPlayerManager().getPlayer(argS);
                if (target == null) {
                    msg(player, "Игрок " + argS + " не найден.");
                    return;
                }
                transfer(player, target);
            }
            case PartyNet.CMD_CODJ -> {
                ServerPlayerEntity target = player.getServer() == null ? null
                        : player.getServer().getPlayerManager().getPlayer(argS);
                if (target == null) {
                    msg(player, "Игрок " + argS + " не найден.");
                    return;
                }
                setCoDj(player, target);
            }
            default -> {
            }
        }
    }

    void addTracks(ServerPlayerEntity player, List<PartyTrack> list) {
        if (!active) return;
        boolean wasEmpty = tracks.isEmpty();
        int added = 0;
        for (PartyTrack t : list) {
            if (tracks.size() >= PartyNet.MAX_TRACKS) break;
            if (t == null || PartyTrack.isBlank(t)) continue;
            tracks.add(t);
            added++;
        }
        if (added == 0) return;
        if (wasEmpty) {
            index = 0;
            anchorPos = 0L;
            anchorMs = System.currentTimeMillis();
            lastAdvanceMs = 0L;
        }
        version++;
        manager.broadcastState(this);
        PartyTrack last = tracks.get(tracks.size() - 1);
        manager.broadcast(this, Text.literal("♪ " + player.getName().getString()
                + " добавил в очередь «" + name + "»: " + last.displayName()));
    }

    private void removeTrack(ServerPlayerEntity player, int at) {
        if (!checkLeader(player, "менять очередь")) return;
        if (at < 0 || at >= tracks.size()) return;
        PartyTrack removed = tracks.remove(at);
        boolean wasCurrent = at == index;
        if (tracks.isEmpty()) {
            index = 0;
            paused = false;
            anchorPos = 0L;
        } else if (at < index) {
            index--;
        } else if (wasCurrent) {
            index = Math.min(index, tracks.size() - 1);
            anchorPos = 0L;
            anchorMs = System.currentTimeMillis();
        }
        version++;
        manager.broadcastState(this);
        manager.broadcast(this, Text.literal("♪ " + player.getName().getString()
                + " убрал из очереди: " + removed.displayName()));
    }

    private void moveTrack(ServerPlayerEntity player, int from, int to) {
        if (!checkLeader(player, "менять очередь")) return;
        int n = tracks.size();
        if (from < 0 || from >= n) return;
        int target = Math.max(0, Math.min(to, n - 1));
        if (from == target) return;
        PartyTrack moved = tracks.remove(from);
        tracks.add(target, moved);
        if (index == from) {
            index = target;
        } else if (from < index && target >= index) {
            index--;
        } else if (from > index && target <= index) {
            index++;
        }
        version++;
        manager.broadcastState(this);
    }

    private void clearQueue(ServerPlayerEntity player) {
        if (!checkLeader(player, "менять очередь")) return;
        tracks.clear();
        index = 0;
        anchorPos = 0L;
        anchorMs = System.currentTimeMillis();
        paused = false;
        lastAdvanceMs = 0L;
        version++;
        manager.broadcastState(this);
        manager.broadcast(this, Text.literal("♪ " + player.getName().getString() + " очистил очередь."));
    }

    void info(StringBuilder out) {
        PartyTrack t = currentTrack();
        out.append("Вечеринка: ").append(name).append(privateParty ? " (приватная)" : " (открытая)").append('\n');
        out.append("Владелец: ").append(ownerName.isEmpty() ? "нет" : ownerName).append('\n');
        out.append("Диджей: ").append(leaderName.isEmpty() ? "нет" : leaderName).append('\n');
        List<String> dj = coDjNames();
        if (!dj.isEmpty()) out.append("Со-диджеи: ").append(String.join(", ", dj)).append('\n');
        out.append("Участников: ").append(memberCount()).append('\n');
        out.append("Сейчас: ").append(t == null ? "—" : t.displayName()).append('\n');
        out.append("Очередь: ").append(tracks.size()).append(" треков, играет №").append(index + 1)
                .append(paused ? ", на паузе" : "");
    }

    // ---------------------------------------------------------------- leader reports

    void report(ServerPlayerEntity player, PartyNet.ReportMsg m) {
        if (!active || !player.getUuid().equals(leader)) return;
        if (m.version() < version) return;

        long now = System.currentTimeMillis();
        int found = findTrack(m.id(), m.provider());
        if (found < 0) {
            PartyTrack t = new PartyTrack(m.id(), m.title(), m.artist(), m.album(),
                    m.durationMs(), m.coverUrl(), m.provider());
            if (PartyTrack.isBlank(t)) return;
            if (!m.playing() && m.pos() <= 0L) return;
            int at = Math.min(index + 1, tracks.size());
            tracks.add(at, t);
            index = at;
            anchorPos = Math.max(0L, m.pos());
            anchorMs = now;
            paused = m.paused();
            version++;
            manager.broadcastState(this);
            return;
        }

        PartyTrack t = tracks.get(found);
        long dur = t.durationMs();
        boolean atEnd = dur > 0L && m.pos() >= dur - 1200L;
        if (atEnd && (!m.playing() || found != index)) return;

        boolean looped = found == index && playingNearEnd(m, now, dur);
        if (looped) {
            jumpTo(index + 1, now);
            return;
        }

        if (found != index) {
            index = found;
            anchorPos = Math.max(0L, m.pos());
            anchorMs = now;
            paused = m.paused();
            version++;
            manager.broadcastState(this);
            return;
        }

        boolean changed = false;
        if (m.paused() && !paused) {
            paused = true;
            anchorPos = Math.max(0L, m.pos());
            anchorMs = now;
            changed = true;
        } else if (m.paused() && paused) {
            anchorPos = Math.max(0L, m.pos());
            anchorMs = now;
        } else if (!m.paused() && paused) {
            paused = false;
            anchorPos = Math.max(0L, m.pos());
            anchorMs = now;
            changed = true;
        } else if (Math.abs(positionAt(now) - m.pos()) > 1500L) {
            anchorPos = Math.max(0L, m.pos());
            anchorMs = now;
        }
        if (changed) {
            version++;
            manager.broadcastState(this);
        }
    }

    private boolean playingNearEnd(PartyNet.ReportMsg m, long now, long dur) {
        if (dur <= 0L || !m.playing()) return false;
        if (positionAt(now) < dur - 3000L) return false;
        return m.pos() < 2000L;
    }

    private int findTrack(String id, String provider) {
        if (id == null || id.isEmpty()) return -1;
        String key = PartyTrack.providerKey(provider);
        for (int i = 0; i < tracks.size(); i++) {
            PartyTrack t = tracks.get(i);
            if (id.equals(t.id()) && key.equals(PartyTrack.providerKey(t.provider()))) return i;
        }
        return -1;
    }

    // ---------------------------------------------------------------- internals

    private void maybeAdvance(long now) {
        PartyTrack t = currentTrack();
        if (t == null) return;
        long dur = t.durationMs();
        if (dur <= 0L) return;
        if (positionAt(now) < dur - 300L) return;
        if (now - lastAdvanceMs < 900L) return;
        jumpTo(index + 1, now);
    }

    private void jumpTo(int at, long now) {
        if (tracks.isEmpty()) return;
        int n = tracks.size();
        index = ((at % n) + n) % n;
        anchorPos = 0L;
        anchorMs = now;
        paused = false;
        lastAdvanceMs = now;
        version++;
        manager.broadcastState(this);
    }

    /** Полное управление: владелец, диджей или оператор. */
    private boolean canManage(ServerPlayerEntity player) {
        return canControl(player);
    }

    /** Воспроизведение и очередь: владелец, диджей, со-диджей или оператор. */
    private boolean canControl(ServerPlayerEntity player) {
        return player.getUuid().equals(owner)
                || player.getUuid().equals(leader)
                || coDjs.containsKey(player.getUuid())
                || manager.isOp(player);
    }

    private boolean checkLeader(ServerPlayerEntity player, String what) {
        if (!active) {
            msg(player, "Вечеринка не запущена.");
            return false;
        }
        if (!canControl(player)) {
            msg(player, "Так может только диджей или оператор.");
            return false;
        }
        return true;
    }

    private UUID pickNextLeader(UUID except) {
        for (UUID id : members) {
            if (!id.equals(except) && server.getPlayerManager().getPlayer(id) != null) return id;
        }
        return NIL;
    }

    private String nameOf(UUID id) {
        if (id.equals(NIL)) return "";
        ServerPlayerEntity p = server.getPlayerManager().getPlayer(id);
        return p == null ? "" : p.getName().getString();
    }

    private void msg(ServerPlayerEntity p, String text) {
        p.sendMessage(Text.literal(text), false);
    }
}
