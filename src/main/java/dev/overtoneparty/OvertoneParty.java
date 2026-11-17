package dev.overtoneparty;

import dev.overtoneparty.net.PartyNet;
import dev.overtoneparty.server.PartyManager;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.server.network.ServerPlayerEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class OvertoneParty implements ModInitializer {
    public static final String MOD_ID = "overtone_party";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        try {
            PartyNet.registerServer();
        } catch (Throwable t) {
            LOGGER.error("Не удалось зарегистрировать сетевые каналы Overtone Party", t);
        }

        ServerLifecycleEvents.SERVER_STARTED.register(PartyManager.INSTANCE::bind);
        ServerLifecycleEvents.SERVER_STOPPED.register(s -> PartyManager.INSTANCE.reset());
        ServerTickEvents.END_SERVER_TICK.register(s -> PartyManager.INSTANCE.tick());
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            if (handler.getPlayer() != null) PartyManager.INSTANCE.onLeave(handler.getPlayer());
        });

        try {
            registerCommands();
        } catch (Throwable t) {
            LOGGER.error("Не удалось зарегистрировать команды Overtone Party", t);
        }

        LOGGER.info("Overtone Party загружен");
    }

    private static void registerCommands() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(CommandManager.literal("party")
                    .then(CommandManager.literal("start").executes(ctx -> {
                        PartyManager.INSTANCE.startEmpty(ctx.getSource().getPlayerOrThrow());
                        return 1;
                    }))
                    .then(CommandManager.literal("stop").executes(ctx -> {
                        PartyManager.INSTANCE.stop(ctx.getSource().getPlayerOrThrow());
                        return 1;
                    }))
                    .then(CommandManager.literal("pause").executes(ctx -> {
                        PartyManager.INSTANCE.pause(ctx.getSource().getPlayerOrThrow());
                        return 1;
                    }))
                    .then(CommandManager.literal("resume").executes(ctx -> {
                        PartyManager.INSTANCE.resume(ctx.getSource().getPlayerOrThrow());
                        return 1;
                    }))
                    .then(CommandManager.literal("skip").executes(ctx -> {
                        PartyManager.INSTANCE.skip(ctx.getSource().getPlayerOrThrow(), 1);
                        return 1;
                    }))
                    .then(CommandManager.literal("prev").executes(ctx -> {
                        PartyManager.INSTANCE.skip(ctx.getSource().getPlayerOrThrow(), -1);
                        return 1;
                    }))
                    .then(CommandManager.literal("info").executes(ctx -> {
                        PartyManager.INSTANCE.info(ctx.getSource());
                        return 1;
                    }))
                    .then(CommandManager.literal("transfer")
                            .then(CommandManager.argument("player", EntityArgumentType.player())
                                    .executes(ctx -> {
                                        ServerPlayerEntity target = EntityArgumentType.getPlayer(ctx, "player");
                                        PartyManager.INSTANCE.transfer(ctx.getSource().getPlayerOrThrow(), target);
                                        return 1;
                                    })))
                    .then(CommandManager.literal("seek")
                            .then(CommandManager.argument("seconds", com.mojang.brigadier.arguments.LongArgumentType.longArg(0))
                                    .executes(ctx -> {
                                        long sec = com.mojang.brigadier.arguments.LongArgumentType.getLong(ctx, "seconds");
                                        PartyManager.INSTANCE.seek(ctx.getSource().getPlayerOrThrow(), sec * 1000L);
                                        return 1;
                                    }))));
        });
    }

    public static void msg(ServerCommandSource source, String text) {
        source.sendFeedback(() -> Text.literal(text), false);
    }
}
