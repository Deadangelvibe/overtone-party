package dev.overtoneparty.integration;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Вспомогательные методы для адресов SoundCloud API. */
public final class SoundCloudPaths {
    /** Корень API SoundCloud — нужен и миксину библиотеки для досылки треков. */
    public static final String ROOT = "https://api-v2.soundcloud.com";

    /** Резервный client_id, если Overtone не смог отдать свой. */
    public static final String CLIENT_ID = "qLcgHhXgeEFFaSolTB9I71LBsqncsD93";

    private SoundCloudPaths() {
    }

    /**
     * Превращает {@code next_href} в путь для приватного {@code api()} Overtone:
     * отбрасывает чужой хост и {@code client_id}, который тот добавит сам.
     */
    public static String nextPath(JsonObject page) {
        JsonElement next = page == null ? null : page.get("next_href");
        if (next == null || next.isJsonNull()) return null;
        String href = next.getAsString();
        if (href == null || href.isBlank()) return null;
        String path = href.startsWith(ROOT) ? href.substring(ROOT.length()) : href;
        if (!path.startsWith("/")) return null;
        int q = path.indexOf('?');
        if (q < 0) return path;
        StringBuilder query = new StringBuilder();
        for (String kv : path.substring(q + 1).split("&")) {
            if (kv.isEmpty() || kv.startsWith("client_id=")) continue;
            if (query.length() > 0) query.append('&');
            query.append(kv);
        }
        return query.length() == 0 ? path.substring(0, q) : path.substring(0, q) + "?" + query;
    }
}
