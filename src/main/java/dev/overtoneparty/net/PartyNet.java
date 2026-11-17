package dev.overtoneparty.net;

import dev.overtoneparty.model.PartyTrack;
import dev.overtoneparty.server.PartyManager;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Протокол вечеринок.
 *
 * <p>Используется Fabric Networking API v1, который Sinytra Connector транслирует
 * в обычные Forge-пакеты. Никаких классов Minecraft кроме буфера и Identifier
 * здесь нет, поэтому декодирование можно делать в сетевом потоке.
 */
public final class PartyNet {
    public static final String NS = "overtone_party";

    public static final Identifier HELLO = new Identifier(NS, "hello");
    public static final Identifier START = new Identifier(NS, "start");
    public static final Identifier CREATE = new Identifier(NS, "create");
    public static final Identifier JOIN = new Identifier(NS, "join");
    public static final Identifier LEAVE = new Identifier(NS, "leave");
    public static final Identifier LIST = new Identifier(NS, "list");
    public static final Identifier REPORT = new Identifier(NS, "report");
    public static final Identifier CMD = new Identifier(NS, "cmd");
    public static final Identifier STATE = new Identifier(NS, "state");
    public static final Identifier POS = new Identifier(NS, "pos");

    public static final int CMD_STOP = 0;
    public static final int CMD_PAUSE = 1;
    public static final int CMD_RESUME = 2;
    public static final int CMD_NEXT = 3;
    public static final int CMD_PREV = 4;
    public static final int CMD_PLAY_INDEX = 5;
    public static final int CMD_SEEK = 6;
    public static final int CMD_ADD_TRACK = 7;
    public static final int CMD_ADD_TRACKS = 8;
    public static final int CMD_REMOVE = 9;
    public static final int CMD_TRANSFER = 10;
    public static final int CMD_MOVE = 11;
    public static final int CMD_CLEAR = 12;
    public static final int CMD_CODJ = 13;

    public static final int MAX_TRACKS = 400;
    public static final int MAX_PARTIES = 64;

    private PartyNet() {
    }

    // ---------------------------------------------------------------- records

    public record StartMsg(List<PartyTrack> tracks, int index, long pos, boolean paused) {
    }

    /** Создание вечеринки: плейлист создателя + тип доступа и пароль. */
    public record CreateMsg(StartMsg start, boolean privateParty, String password) {
    }

    /** Присоединение: вечеринка и введённый пароль. */
    public record JoinMsg(UUID partyId, String password) {
    }

    /** Строка списка вечеринок в клиенте. */
    public record PartyEntry(UUID id, String name, String ownerName, int memberCount,
                             int trackCount, String nowPlaying, boolean privateParty) {
    }

    public record ReportMsg(int version, String id, String title, String artist, String album,
                            long durationMs, String coverUrl, String provider,
                            boolean paused, long pos, boolean playing, boolean idle) {
    }

    public record StateMsg(UUID partyId, boolean member, boolean privateParty, int version,
                           boolean active, boolean paused, UUID leader, String leaderName,
                           int index, long pos, List<PartyTrack> tracks, List<String> players,
                           String ownerName, List<String> coDjs) {
    }

    public record PosMsg(UUID partyId, int version, boolean paused, int index, long pos,
                         String id, String provider) {
    }

    // ---------------------------------------------------------------- codecs

    public static void writeTrack(PacketByteBuf b, PartyTrack t) {
        b.writeString(nz(t.id()), 256);
        b.writeString(nz(t.title()), 512);
        b.writeString(nz(t.artist()), 512);
        b.writeString(nz(t.album()), 512);
        b.writeLong(t.durationMs());
        b.writeString(nz(t.coverUrl()), 1024);
        b.writeString(nz(t.provider()), 64);
    }

    public static PartyTrack readTrack(PacketByteBuf b) {
        return new PartyTrack(b.readString(256), b.readString(512), b.readString(512),
                b.readString(512), b.readLong(), b.readString(1024), b.readString(64));
    }

    public static void writeTracks(PacketByteBuf b, List<PartyTrack> list) {
        int n = Math.min(list.size(), MAX_TRACKS);
        b.writeVarInt(n);
        for (int i = 0; i < n; i++) writeTrack(b, list.get(i));
    }

    public static List<PartyTrack> readTracks(PacketByteBuf b) {
        int n = b.readVarInt();
        if (n < 0 || n > MAX_TRACKS) throw new IllegalArgumentException("bad track count " + n);
        List<PartyTrack> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) list.add(readTrack(b));
        return list;
    }

    public static void writeStart(PacketByteBuf b, StartMsg m) {
        int n = Math.min(m.tracks().size(), MAX_TRACKS);
        writeTracks(b, m.tracks());
        b.writeVarInt(Math.max(0, Math.min(m.index(), Math.max(0, n - 1))));
        b.writeLong(m.pos());
        b.writeBoolean(m.paused());
    }

    public static StartMsg readStart(PacketByteBuf b) {
        List<PartyTrack> tracks = readTracks(b);
        int index = b.readVarInt();
        long pos = b.readLong();
        boolean paused = b.readBoolean();
        return new StartMsg(tracks, index, Math.max(0L, pos), paused);
    }

    public static void writeCreate(PacketByteBuf b, CreateMsg m) {
        writeStart(b, m.start());
        b.writeBoolean(m.privateParty());
        b.writeString(nz(m.password()), 64);
    }

    public static CreateMsg readCreate(PacketByteBuf b) {
        StartMsg start = readStart(b);
        boolean priv = b.readBoolean();
        String password = b.readString(64);
        return new CreateMsg(start, priv, password);
    }

    public static void writeJoin(PacketByteBuf b, JoinMsg m) {
        b.writeUuid(m.partyId() == null ? new UUID(0L, 0L) : m.partyId());
        b.writeString(nz(m.password()), 64);
    }

    public static JoinMsg readJoin(PacketByteBuf b) {
        return new JoinMsg(b.readUuid(), b.readString(64));
    }

    public static void writeList(PacketByteBuf b, List<PartyEntry> list) {
        int n = Math.min(list.size(), MAX_PARTIES);
        b.writeVarInt(n);
        for (int i = 0; i < n; i++) {
            PartyEntry e = list.get(i);
            b.writeUuid(e.id());
            b.writeString(nz(e.name()), 64);
            b.writeString(nz(e.ownerName()), 64);
            b.writeVarInt(e.memberCount());
            b.writeVarInt(e.trackCount());
            b.writeString(nz(e.nowPlaying()), 256);
            b.writeBoolean(e.privateParty());
        }
    }

    public static List<PartyEntry> readList(PacketByteBuf b) {
        int n = b.readVarInt();
        if (n < 0 || n > MAX_PARTIES) throw new IllegalArgumentException("bad party count " + n);
        List<PartyEntry> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            list.add(new PartyEntry(b.readUuid(), b.readString(64), b.readString(64),
                    b.readVarInt(), b.readVarInt(), b.readString(256), b.readBoolean()));
        }
        return list;
    }

    public static void writeReport(PacketByteBuf b, ReportMsg m) {
        b.writeVarInt(m.version());
        writeTrack(b, new PartyTrack(m.id(), m.title(), m.artist(), m.album(),
                m.durationMs(), m.coverUrl(), m.provider()));
        b.writeBoolean(m.paused());
        b.writeLong(m.pos());
        b.writeBoolean(m.playing());
        b.writeBoolean(m.idle());
    }

    public static ReportMsg readReport(PacketByteBuf b) {
        int version = b.readVarInt();
        PartyTrack t = readTrack(b);
        return new ReportMsg(version, t.id(), t.title(), t.artist(), t.album(), t.durationMs(),
                t.coverUrl(), t.provider(), b.readBoolean(), b.readLong(), b.readBoolean(), b.readBoolean());
    }

    public static void writeState(PacketByteBuf b, StateMsg m) {
        b.writeUuid(m.partyId() == null ? new UUID(0L, 0L) : m.partyId());
        b.writeBoolean(m.member());
        b.writeBoolean(m.privateParty());
        b.writeVarInt(m.version());
        b.writeBoolean(m.active());
        b.writeBoolean(m.paused());
        b.writeUuid(m.leader() == null ? new UUID(0L, 0L) : m.leader());
        b.writeString(nz(m.leaderName()), 64);
        b.writeVarInt(m.index());
        b.writeLong(m.pos());
        writeTracks(b, m.tracks());
        List<String> names = m.players();
        b.writeVarInt(names.size());
        for (String s : names) b.writeString(nz(s), 64);
        b.writeString(nz(m.ownerName()), 64);
        List<String> coDjs = m.coDjs();
        b.writeVarInt(coDjs.size());
        for (String s : coDjs) b.writeString(nz(s), 64);
    }

    public static StateMsg readState(PacketByteBuf b) {
        UUID partyId = b.readUuid();
        boolean member = b.readBoolean();
        boolean priv = b.readBoolean();
        int version = b.readVarInt();
        boolean active = b.readBoolean();
        boolean paused = b.readBoolean();
        UUID leader = b.readUuid();
        String leaderName = b.readString(64);
        int index = b.readVarInt();
        long pos = b.readLong();
        List<PartyTrack> tracks = readTracks(b);
        int pn = b.readVarInt();
        if (pn < 0 || pn > 1024) throw new IllegalArgumentException("bad player count " + pn);
        List<String> names = new ArrayList<>(pn);
        for (int i = 0; i < pn; i++) names.add(b.readString(64));
        String ownerName = b.readString(64);
        int cn = b.readVarInt();
        if (cn < 0 || cn > 1024) throw new IllegalArgumentException("bad co-dj count " + cn);
        List<String> coDjs = new ArrayList<>(cn);
        for (int i = 0; i < cn; i++) coDjs.add(b.readString(64));
        return new StateMsg(partyId, member, priv, version, active, paused, leader, leaderName,
                index, Math.max(0L, pos), tracks, names, ownerName, coDjs);
    }

    public static void writePos(PacketByteBuf b, PosMsg m) {
        b.writeUuid(m.partyId() == null ? new UUID(0L, 0L) : m.partyId());
        b.writeVarInt(m.version());
        b.writeBoolean(m.paused());
        b.writeVarInt(m.index());
        b.writeLong(m.pos());
        b.writeString(nz(m.id()), 256);
        b.writeString(nz(m.provider()), 64);
    }

    public static PosMsg readPos(PacketByteBuf b) {
        return new PosMsg(b.readUuid(), b.readVarInt(), b.readBoolean(), b.readVarInt(),
                Math.max(0L, b.readLong()), b.readString(256), b.readString(64));
    }

    // ---------------------------------------------------------------- server side

    public static void registerServer() {
        ServerPlayNetworking.registerGlobalReceiver(HELLO, (server, player, handler, buf, sender) ->
                server.execute(() -> PartyManager.INSTANCE.onHello(player)));

        ServerPlayNetworking.registerGlobalReceiver(CREATE, (server, player, handler, buf, sender) -> {
            CreateMsg m = readCreate(buf);
            server.execute(() -> PartyManager.INSTANCE.create(player, m));
        });

        ServerPlayNetworking.registerGlobalReceiver(JOIN, (server, player, handler, buf, sender) -> {
            JoinMsg m = readJoin(buf);
            server.execute(() -> PartyManager.INSTANCE.join(player, m));
        });

        ServerPlayNetworking.registerGlobalReceiver(LEAVE, (server, player, handler, buf, sender) ->
                server.execute(() -> PartyManager.INSTANCE.leave(player)));

        ServerPlayNetworking.registerGlobalReceiver(REPORT, (server, player, handler, buf, sender) -> {
            ReportMsg m = readReport(buf);
            server.execute(() -> PartyManager.INSTANCE.report(player, m));
        });

        ServerPlayNetworking.registerGlobalReceiver(CMD, (server, player, handler, buf, sender) -> {
            int op = buf.readVarInt();
            int arg = 0;
            int arg2 = 0;
            long argL = 0L;
            String argS = "";
            List<PartyTrack> argT = List.of();
            if (op == CMD_PLAY_INDEX || op == CMD_REMOVE) arg = buf.readVarInt();
            if (op == CMD_MOVE) {
                arg = buf.readVarInt();
                arg2 = buf.readVarInt();
            }
            if (op == CMD_SEEK) argL = buf.readLong();
            if (op == CMD_TRANSFER || op == CMD_CODJ) argS = buf.readString(64);
            if (op == CMD_ADD_TRACK) argT = List.of(readTrack(buf));
            if (op == CMD_ADD_TRACKS) argT = readTracks(buf);
            final int fOp = op, fArg = arg, fArg2 = arg2;
            final long fArgL = argL;
            final String fArgS = argS;
            final List<PartyTrack> fArgT = argT;
            server.execute(() -> PartyManager.INSTANCE.command(player, fOp, fArg, fArg2, fArgL, fArgS, fArgT));
        });
    }

    /** Клиентская часть протокола живёт в {@code dev.overtoneparty.client.PartyClientNet}. */

    public static void sendTo(ServerPlayerEntity player, Identifier channel, PacketByteBuf buf) {
        try {
            ServerPlayNetworking.send(player, channel, buf);
        } catch (Throwable t) {
            dev.overtoneparty.OvertoneParty.LOGGER.debug("Не удалось отправить {} игроку {}", channel, player.getName().getString(), t);
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
