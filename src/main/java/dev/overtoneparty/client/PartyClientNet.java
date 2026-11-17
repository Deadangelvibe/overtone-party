package dev.overtoneparty.client;

import dev.overtoneparty.model.PartyTrack;
import dev.overtoneparty.net.PartyNet;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.network.PacketByteBuf;

import java.util.List;
import java.util.UUID;

/**
 * Клиентская отправка пакетов.
 *
 * <p>Вынесено из {@link PartyNet} намеренно: там остаются только общие кодеки
 * и серверная регистрация. Так выделенный сервер не трогает клиентские классы
 * Minecraft и не падает с NoClassDefFoundError.
 */
public final class PartyClientNet {
    private PartyClientNet() {
    }

    public static void registerClient() {
        ClientPlayNetworking.registerGlobalReceiver(PartyNet.STATE, (client, handler, buf, sender) -> {
            PartyNet.StateMsg m = PartyNet.readState(buf);
            client.execute(() -> PartyClient.onState(m));
        });
        ClientPlayNetworking.registerGlobalReceiver(PartyNet.POS, (client, handler, buf, sender) -> {
            PartyNet.PosMsg m = PartyNet.readPos(buf);
            client.execute(() -> PartyClient.onPos(m));
        });
        ClientPlayNetworking.registerGlobalReceiver(PartyNet.LIST, (client, handler, buf, sender) -> {
            List<PartyNet.PartyEntry> list = PartyNet.readList(buf);
            client.execute(() -> PartyClient.onList(list));
        });
    }

    public static void sendHello() {
        ClientPlayNetworking.send(PartyNet.HELLO, PacketByteBufs.empty());
    }

    public static void sendRequestList() {
        sendHello();
    }

    public static void sendCreate(PartyNet.CreateMsg m) {
        PacketByteBuf b = PacketByteBufs.create();
        PartyNet.writeCreate(b, m);
        ClientPlayNetworking.send(PartyNet.CREATE, b);
    }

    public static void sendJoin(UUID partyId, String password) {
        PacketByteBuf b = PacketByteBufs.create();
        PartyNet.writeJoin(b, new PartyNet.JoinMsg(partyId, password));
        ClientPlayNetworking.send(PartyNet.JOIN, b);
    }

    public static void sendLeave() {
        ClientPlayNetworking.send(PartyNet.LEAVE, PacketByteBufs.empty());
    }

    public static void sendStart(PartyNet.StartMsg m) {
        sendCreate(new PartyNet.CreateMsg(m, false, ""));
    }

    public static void sendReport(PartyNet.ReportMsg m) {
        PacketByteBuf b = PacketByteBufs.create();
        PartyNet.writeReport(b, m);
        ClientPlayNetworking.send(PartyNet.REPORT, b);
    }

    public static void sendCommand(int op) {
        sendCommand(op, 0, 0, 0L, "", List.of());
    }

    public static void sendCommand(int op, int arg) {
        sendCommand(op, arg, 0, 0L, "", List.of());
    }

    public static void sendCommand(int op, int arg, int arg2) {
        sendCommand(op, arg, arg2, 0L, "", List.of());
    }

    public static void sendCommand(int op, long arg) {
        sendCommand(op, 0, 0, arg, "", List.of());
    }

    public static void sendCommand(int op, String arg) {
        sendCommand(op, 0, 0, 0L, arg, List.of());
    }

    public static void sendCommand(int op, List<PartyTrack> tracks) {
        sendCommand(op, 0, 0, 0L, "", tracks);
    }

    private static void sendCommand(int op, int arg, int arg2, long argL, String argS, List<PartyTrack> tracks) {
        try {
            PacketByteBuf b = PacketByteBufs.create();
            b.writeVarInt(op);
            if (op == PartyNet.CMD_PLAY_INDEX || op == PartyNet.CMD_REMOVE) b.writeVarInt(arg);
            if (op == PartyNet.CMD_MOVE) {
                b.writeVarInt(arg);
                b.writeVarInt(arg2);
            }
            if (op == PartyNet.CMD_SEEK) b.writeLong(argL);
            if (op == PartyNet.CMD_TRANSFER || op == PartyNet.CMD_CODJ) b.writeString(argS, 64);
            if (op == PartyNet.CMD_ADD_TRACK) PartyNet.writeTrack(b, tracks.get(0));
            if (op == PartyNet.CMD_ADD_TRACKS) PartyNet.writeTracks(b, tracks);
            ClientPlayNetworking.send(PartyNet.CMD, b);
        } catch (Throwable t) {
            dev.overtoneparty.OvertoneParty.LOGGER.warn("Не удалось отправить команду вечеринки {}", op, t);
        }
    }
}
