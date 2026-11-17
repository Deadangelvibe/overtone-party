package dev.overtoneparty.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Маленький клиентский конфиг: слушаем ли мы вечеринку. */
public final class PartyConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("overtone-party.json");
    private static boolean loaded;

    private PartyConfig() {
    }

    public static synchronized void load() {
        if (loaded) return;
        loaded = true;
        try {
            if (!Files.exists(PATH)) return;
            String raw = Files.readString(PATH, StandardCharsets.UTF_8);
            JsonObject o = GSON.fromJson(raw, JsonObject.class);
            if (o != null && o.has("listening")) {
                PartyClient.listening = o.get("listening").getAsBoolean();
            }
        } catch (Throwable ignored) {
        }
    }

    public static synchronized void save() {
        try {
            JsonObject o = new JsonObject();
            o.addProperty("listening", PartyClient.listening);
            Files.createDirectories(PATH.getParent());
            Files.writeString(PATH, GSON.toJson(o), StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
        }
    }
}
