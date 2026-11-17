package dev.overtoneparty.server;

import dev.overtoneparty.OvertoneParty;
import dev.overtoneparty.model.PartyTrack;
import dev.overtoneparty.net.PartyNet;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Реестр вечеринок сервера. Один игрок состоит максимум в одной вечеринке,
 * но вечеринок одновременно может быть много и они не влияют друг на друга.
 */
public final class PartyManager {
    public static final PartyManager INSTANCE = new PartyManager();
    public static final int MAX_PARTIES = 64;
    public static final int MAX_MEMBERS = 64;

    private static final UUID NIL = new UUID(0L, 0L);

    private final Set<UUID> modded = new HashSet<>();
    private final Map<UUID, Party> parties = new LinkedHashMap<>();
    private final Map<UUID, UUID> membership = new HashMap<>();

    private MinecraftServer server;
    private int tick;

    private PartyManager() {
    }

    // ---------------------------------------------------------------- lifecycle

    public void bind(MinecraftServer s) {
        reset();
        server = s;
        OvertoneParty.LOGGER.info("Серверная часть Overtone Party готова (несколько вечеринок)");
    }

    public void reset() {
        modded.clear();
        parties.clear();
        membership.clear();
        server = null;
        tick = 0;
    }

    public void tick() {
        MinecraftServer s = server;
        if (s == null) return;
        tick++;
        long now = System.currentTimeMillis();
        for (Party p : new ArrayList<>(parties.values())) {
            p.tick(tick, now);
            if (tick % 20 == 0) broadcastPos(p);
        }
    }

    public void onHello(ServerPlayerEntity player) {
        modded.add(player.getUuid());
        Party p = partyOf(player);
        if (p == null) {
            sendIdleState(player);
        } else {
            sendState(p, player);
        }
        sendList(player);
    }

    public void onLeave(ServerPlayerEntity player) {
        UUID id = player.getUuid();
        modded.remove(id);
        UUID partyId = membership.remove(id);
        if (partyId != null) {
            Party p = parties.get(partyId);
            if (p != null) {
                boolean empty = p.onMemberLeave(player);
                if (empty) {
                    removeParty(p, null);
                } else {
                    broadcastState(p);
                }
            }
        }
        broadcastList();
    }

    // ---------------------------------------------------------------- creation / join

    public void create(ServerPlayerEntity player, PartyNet.CreateMsg m) {
        if (membership.containsKey(player.getUuid())) {
            msg(player, "Вы уже в вечеринке. Сначала выйдите из неё.");
            return;
        }
        if (server == null) return;
        if (parties.size() >= MAX_PARTIES) {
            msg(player, "На сервере уже максимальное количество вечеринок (" + MAX_PARTIES + ").");
            return;
        }
        String password = m.password() == null ? "" : m.password();
        if (password.length() > 64) password = password.substring(0, 64);
        String name = "Вечеринка " + player.getName().getString();
        if (m.privateParty() && password.isEmpty()) {
            msg(player, "Для приватной вечеринки нужен пароль.");
            return;
        }
        UUID id = UUID.randomUUID();
        Party p = new Party(this, server, id, name, m.privateParty(), password, player);
        p.setInitial(m.start());
        parties.put(id, p);
        membership.put(player.getUuid(), id);
        sendState(p, player);
        broadcastList();
        broadcast(p, Text.literal("♪ " + player.getName().getString() + " создал «" + name + "»"
                + (m.privateParty() ? " (приватная)" : " (открытая)")));
        OvertoneParty.LOGGER.info("Создана вечеринка {} игроком {}", id, player.getName().getString());
    }

    public void join(ServerPlayerEntity player, PartyNet.JoinMsg m) {
        Party p = parties.get(m.partyId());
        if (p == null || !p.isActive()) {
            msg(player, "Вечеринка не найдена. Обновите список.");
            return;
        }
        if (!p.checkPassword(m.password())) {
            msg(player, "Неверный пароль.");
            return;
        }
        if (p.hasMember(player.getUuid())) {
            msg(player, "Вы уже в этой вечеринке.");
            return;
        }
        if (p.memberCount() >= MAX_MEMBERS) {
            msg(player, "Вечеринка заполнена (" + MAX_MEMBERS + " участников).");
            return;
        }
        UUID oldId = membership.remove(player.getUuid());
        if (oldId != null && !oldId.equals(p.id())) {
            Party old = parties.get(oldId);
            if (old != null) {
                boolean empty = old.onMemberLeave(player);
                if (empty) removeParty(old, null);
                else broadcastState(old);
            }
        }
        p.addMember(player);
        membership.put(player.getUuid(), p.id());
        broadcastState(p);
        sendList(player);
        broadcastList();
        broadcast(p, Text.literal("♪ " + player.getName().getString() + " присоединился к «" + p.name() + "»"));
    }

    public void leave(ServerPlayerEntity player) {
        Party p = partyOf(player);
        if (p == null) {
            msg(player, "Вы не в вечеринке.");
            return;
        }
        UUID id = p.onMemberLeave(player) ? p.id() : null;
        membership.remove(player.getUuid());
        sendIdleState(player);
        sendList(player);
        if (id != null) {
            removeParty(p, Text.literal("♪ Вечеринка «" + p.name() + "» завершена."));
        } else {
            broadcastState(p);
            broadcastList();
            broadcast(p, Text.literal("♪ " + player.getName().getString() + " вышел из вечеринки."));
        }
    }

    /** Удаляет вечеринку и рассылает пустое состояние её бывшим участникам. */
    void removeParty(Party p, Text notice) {
        List<UUID> former = new ArrayList<>(p.playerIds());
        for (UUID id : former) {
            membership.remove(id, p.id());
        }
        parties.remove(p.id());
        for (UUID id : former) {
            ServerPlayerEntity player = server == null ? null
                    : server.getPlayerManager().getPlayer(id);
            if (player != null && modded.contains(id)) sendIdleState(player);
        }
        if (notice != null) {
            Text text = notice;
            for (UUID id : former) {
                ServerPlayerEntity player = server == null ? null
                        : server.getPlayerManager().getPlayer(id);
                if (player != null) player.sendMessage(text, false);
            }
        }
        broadcastList();
        OvertoneParty.LOGGER.info("Вечеринка {} удалена", p.id());
    }

    // ---------------------------------------------------------------- routing

    private Party partyOf(ServerPlayerEntity player) {
        UUID id = membership.get(player.getUuid());
        return id == null ? null : parties.get(id);
    }

    public void command(ServerPlayerEntity player, int op, int arg, int arg2,
                        long argL, String argS, List<PartyTrack> list) {
        Party p = partyOf(player);
        if (p == null) {
            if (op != PartyNet.CMD_ADD_TRACK && op != PartyNet.CMD_ADD_TRACKS) {
                msg(player, "Вы не в вечеринке. Создайте её или присоединитесь (P).");
            }
            return;
        }
        p.command(player, op, arg, arg2, argL, argS, list);
    }

    public void report(ServerPlayerEntity player, PartyNet.ReportMsg m) {
        Party p = partyOf(player);
        if (p != null) p.report(player, m);
    }

    // ---------------------------------------------------------------- commands from console / chat

    public void startEmpty(ServerPlayerEntity player) {
        create(player, new PartyNet.CreateMsg(
                new PartyNet.StartMsg(List.of(), 0, 0L, false), false, ""));
    }

    public void stop(ServerPlayerEntity player) {
        Party p = partyOf(player);
        if (p == null) msg(player, "Вы не в вечеринке.");
        else p.stop(player);
    }

    public void pause(ServerPlayerEntity player) {
        Party p = partyOf(player);
        if (p == null) msg(player, "Вы не в вечеринке.");
        else p.pause(player);
    }

    public void resume(ServerPlayerEntity player) {
        Party p = partyOf(player);
        if (p == null) msg(player, "Вы не в вечеринке.");
        else p.resume(player);
    }

    public void skip(ServerPlayerEntity player, int dir) {
        Party p = partyOf(player);
        if (p == null) msg(player, "Вы не в вечеринке.");
        else p.skip(player, dir);
    }

    public void seek(ServerPlayerEntity player, long posMs) {
        Party p = partyOf(player);
        if (p == null) msg(player, "Вы не в вечеринке.");
        else p.seek(player, posMs);
    }

    public void transfer(ServerPlayerEntity player, ServerPlayerEntity target) {
        Party p = partyOf(player);
        if (p == null) msg(player, "Вы не в вечеринке.");
        else p.transfer(player, target);
    }

    public void info(ServerCommandSource source) {
        MinecraftServer s = server;
        ServerPlayerEntity player = source.getPlayer();
        Party p = player == null ? null : partyOf(player);
        if (p == null) {
            List<PartyNet.PartyEntry> list = listEntries();
            if (list.isEmpty()) {
                OvertoneParty.msg(source, "Вечеринок нет. Откройте экран клавишей P.");
                return;
            }
            OvertoneParty.msg(source, "Вечеринки на сервере:");
            for (PartyNet.PartyEntry e : list) {
                OvertoneParty.msg(source, "  " + e.name() + " — " + e.ownerName()
                        + ", участников: " + e.memberCount()
                        + (e.privateParty() ? " [приватная]" : ""));
            }
            return;
        }
        StringBuilder out = new StringBuilder();
        p.info(out);
        OvertoneParty.msg(source, out.toString());
    }

    // ---------------------------------------------------------------- list

    private List<PartyNet.PartyEntry> listEntries() {
        List<PartyNet.PartyEntry> out = new ArrayList<>();
        for (Party p : parties.values()) {
            if (!p.isActive()) continue;
            PartyTrack t = p.currentTrack();
            String now = t == null ? "" : t.displayName();
            out.add(new PartyNet.PartyEntry(p.id(), p.name(), p.ownerName(),
                    p.memberCount(), p.trackList().size(), now, p.isPrivate()));
        }
        return out;
    }

    private void sendList(ServerPlayerEntity player) {
        if (!modded.contains(player.getUuid())) return;
        try {
            PacketByteBuf b = PacketByteBufs.create();
            PartyNet.writeList(b, listEntries());
            PartyNet.sendTo(player, PartyNet.LIST, b);
        } catch (Throwable t) {
            OvertoneParty.LOGGER.debug("Не удалось отправить список вечеринок", t);
        }
    }

    private void broadcastList() {
        MinecraftServer s = server;
        if (s == null) return;
        for (ServerPlayerEntity p : s.getPlayerManager().getPlayerList()) {
            if (modded.contains(p.getUuid())) sendList(p);
        }
    }

    // ---------------------------------------------------------------- state / position

    void broadcastState(Party p) {
        for (UUID id : p.playerIds()) {
            ServerPlayerEntity player = server == null ? null
                    : server.getPlayerManager().getPlayer(id);
            if (player != null && modded.contains(id)) sendState(p, player);
        }
    }

    private void sendState(Party p, ServerPlayerEntity player) {
        PartyNet.StateMsg msg = stateMsg(p, player.getUuid());
        PacketByteBuf b = PacketByteBufs.create();
        PartyNet.writeState(b, msg);
        PartyNet.sendTo(player, PartyNet.STATE, b);
    }

    private void sendIdleState(ServerPlayerEntity player) {
        PartyNet.StateMsg msg = new PartyNet.StateMsg(NIL, false, false, 0,
                false, false, NIL, "", 0, 0L, List.of(), List.of(), "", List.of());
        PacketByteBuf b = PacketByteBufs.create();
        PartyNet.writeState(b, msg);
        PartyNet.sendTo(player, PartyNet.STATE, b);
    }

    private PartyNet.StateMsg stateMsg(Party p, UUID viewer) {
        return new PartyNet.StateMsg(p.id(), true, p.isPrivate(), p.version(),
                p.isActive(), p.isPaused(), p.leaderId(), p.leaderName(),
                p.trackIndex(), p.positionNow(), p.trackList(), p.playerNames(),
                p.ownerName(), p.coDjNames());
    }

    private void broadcastPos(Party p) {
        if (server == null || !p.isActive()) return;
        PartyTrack t = p.currentTrack();
        PartyNet.PosMsg msg = new PartyNet.PosMsg(p.id(), p.version(), p.isPaused(),
                p.trackIndex(), p.positionNow(), t == null ? "" : t.id(),
                t == null ? "" : t.provider());
        for (UUID id : p.playerIds()) {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(id);
            if (player != null && modded.contains(id)) {
                PacketByteBuf b = PacketByteBufs.create();
                PartyNet.writePos(b, msg);
                PartyNet.sendTo(player, PartyNet.POS, b);
            }
        }
    }

    // ---------------------------------------------------------------- misc

    boolean isOp(ServerPlayerEntity player) {
        MinecraftServer s = server;
        return s != null && s.getPlayerManager().isOperator(player.getGameProfile());
    }

    void broadcast(Party p, Text text) {
        for (UUID id : p.playerIds()) {
            ServerPlayerEntity player = server == null ? null
                    : server.getPlayerManager().getPlayer(id);
            if (player != null) player.sendMessage(text, false);
        }
    }

    private void msg(ServerPlayerEntity p, String text) {
        p.sendMessage(Text.literal(text), false);
    }
}
