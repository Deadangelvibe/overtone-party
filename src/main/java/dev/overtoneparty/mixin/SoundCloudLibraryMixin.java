package dev.overtoneparty.mixin;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.musicplayer.model.MusicEntity;
import dev.musicplayer.model.Track;
import dev.overtoneparty.OvertoneParty;
import dev.overtoneparty.integration.SoundCloudExt;
import dev.overtoneparty.integration.SoundCloudLibrary;
import dev.overtoneparty.integration.SoundCloudPaths;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Плейлисты и альбомы SoundCloud для вкладок Overtone.
 *
 * <p>У Overtone для SoundCloud есть только поиск и лайки треков. Здесь мы
 * используем приватные {@code api()} и {@code tracks()} самого провайдера,
 * чтобы собрать три списка: свои публичные плейлисты, чужие лайкнутые
 * плейлисты и лайкнутые альбомы. При открытии набора читаем его треки и
 * отдаём их Overtone как обычную страницу.
 */
@Mixin(targets = "dev.musicplayer.provider.SoundCloudProvider", remap = false)
public abstract class SoundCloudLibraryMixin implements SoundCloudExt {
    @Shadow
    private volatile String userId;

    @Shadow
    private JsonObject api(String method, String path) throws Exception {
        throw new AssertionError();
    }

    @Shadow
    private static List<Track> tracks(JsonObject page) {
        throw new AssertionError();
    }

    @Shadow
    private String clientId() throws Exception {
        throw new AssertionError();
    }

    @Override
    public CompletableFuture<Void> overtoneParty$loadLibrary() {
        String uid = this.userId;
        if (uid == null) {
            // Ещё нет входа — не блокируем будущие попытки.
            SoundCloudLibrary.resetLoading();
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.runAsync(() -> {
            try {
                this.overtoneParty$loadAll(uid);
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        });
    }

    @Override
    public CompletableFuture<List<Track>> overtoneParty$loadSet(MusicEntity entity) {
        if (entity == null || entity.id() == null) {
            return CompletableFuture.completedFuture(List.of());
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                return this.overtoneParty$readSet(entity.id());
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        });
    }

    @Unique
    private void overtoneParty$loadAll(String uid) throws Exception {
        List<MusicEntity> own = new ArrayList<>();
        List<MusicEntity> foreign = new ArrayList<>();
        List<MusicEntity> albums = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        // Свои публичные наборы. Альбомы среди них возможны, но редки.
        this.overtoneParty$collect(uid, "/users/" + uid + "/playlists?limit=200", null,
                own, foreign, albums, seen);
        // Чужие наборы, которые пользователь лайкнул. Именно тут обычно лежат альбомы.
        this.overtoneParty$collect(uid, "/users/" + uid + "/playlist_likes?limit=200", "playlist",
                own, foreign, albums, seen);

        List<MusicEntity> playlists = new ArrayList<>(own);
        playlists.addAll(foreign);
        SoundCloudLibrary.set(albums, playlists);
        OvertoneParty.LOGGER.info("SoundCloud: плейлистов {}, альбомов {}",
                playlists.size(), albums.size());
    }

    /** Собирает наборы с одной страницы и всех последующих. */
    @Unique
    private void overtoneParty$collect(String uid, String start, String wrapper,
                                       List<MusicEntity> own, List<MusicEntity> foreign,
                                       List<MusicEntity> albums, Set<String> seen) throws Exception {
        String path = start;
        int pages = 0;
        while (path != null && pages < 50) {
            JsonObject page = this.api("GET", path);
            JsonElement collection = page == null ? null : page.get("collection");
            if (collection != null && collection.isJsonArray()) {
                for (JsonElement item : collection.getAsJsonArray()) {
                    if (!item.isJsonObject()) continue;
                    JsonObject set = wrapper == null ? item.getAsJsonObject()
                            : item.getAsJsonObject().getAsJsonObject(wrapper);
                    if (set == null || !"playlist".equals(overtoneParty$str(set, "kind"))) continue;
                    if (!overtoneParty$bool(set, "public", true)) continue;
                    String id = overtoneParty$str(set, "id");
                    if (id.isEmpty() || !seen.add(id)) continue;

                    String ownerName = "";
                    String ownerId = overtoneParty$str(set, "user_id");
                    JsonElement user = set.get("user");
                    if (user != null && user.isJsonObject()) {
                        JsonObject u = user.getAsJsonObject();
                        ownerName = overtoneParty$str(u, "username");
                        if (ownerId.isEmpty()) ownerId = overtoneParty$str(u, "id");
                    }
                    // У набора нет поля username на верхнем уровне: свой/чужой
                    // определяем только по id владельца.
                    boolean mine = uid.equals(ownerId);
                    String subtitle = mine ? "Мой плейлист" : ownerName;
                    if (subtitle.isEmpty()) subtitle = overtoneParty$str(set, "set_type");
                    MusicEntity entity = new MusicEntity(
                            overtoneParty$bool(set, "is_album", false)
                                    ? MusicEntity.Kind.ALBUM : MusicEntity.Kind.PLAYLIST,
                            id,
                            overtoneParty$str(set, "title"),
                            subtitle,
                            overtoneParty$str(set, "artwork_url"));

                    if (entity.kind() == MusicEntity.Kind.ALBUM) albums.add(entity);
                    else if (mine) own.add(entity);
                    else foreign.add(entity);
                }
            }
            pages++;
            path = SoundCloudPaths.nextPath(page);
        }
    }

    /**
     * Читает треки плейлиста/альбома; у Overtone для этого нет отдельного метода.
     *
     * <p>Важно: endpoint {@code /playlists/<id>} возвращает для большинства треков
     * только заглушки ({@code id}, {@code kind}, {@code policy}) без названия. Парсер
     * Overtone такие записи отбрасывает, поэтому раньше в наборе показывалось
     * 5 треков из 61. Здесь мы собираем все id и досылаем их пачками в
     * {@code /tracks?ids=...}, чтобы получить полноценные объекты с названиями,
     * исполнителями и длительностью.
     */
    @Unique
    private List<Track> overtoneParty$readSet(String id) throws Exception {
        String path = "/playlists/" + URLEncoder.encode(id, StandardCharsets.UTF_8) + "?limit=200";
        List<String> ids = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();
        int pages = 0;
        while (path != null && pages < 50) {
            JsonObject set = this.api("GET", path);
            for (String trackId : overtoneParty$trackIds(set)) {
                if (seenIds.add(trackId)) ids.add(trackId);
            }
            pages++;
            path = SoundCloudPaths.nextPath(set);
        }

        Map<String, JsonObject> full = overtoneParty$fullTracks(ids);
        JsonArray collection = new JsonArray();
        for (String trackId : ids) {
            JsonObject track = full.get(trackId);
            if (track != null) collection.add(track);
        }
        JsonObject wrapped = new JsonObject();
        wrapped.add("collection", collection);

        List<Track> all = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Track t : tracks(wrapped)) {
            if (t != null && t.id() != null && seen.add(t.id())) all.add(t);
        }
        OvertoneParty.LOGGER.info("SoundCloud: набор {} — id {}, загружено {}, треков {}",
                id, ids.size(), full.size(), all.size());
        return all;
    }

    /** Все id треков на одной странице набора, в порядке следования. */
    @Unique
    private static List<String> overtoneParty$trackIds(JsonObject set) {
        List<String> ids = new ArrayList<>();
        if (set == null) return ids;
        JsonElement list = set.get("tracks");
        if (list == null || !list.isJsonArray()) list = set.get("collection");
        if (list == null || !list.isJsonArray()) return ids;
        for (JsonElement e : list.getAsJsonArray()) {
            if (!e.isJsonObject()) continue;
            JsonObject o = e.getAsJsonObject();
            JsonElement nested = o.get("track");
            if (nested != null && nested.isJsonObject()) o = nested.getAsJsonObject();
            String trackId = overtoneParty$str(o, "id");
            if (!trackId.isEmpty()) ids.add(trackId);
        }
        return ids;
    }

    /**
     * Подгружает полные данные треков пачками по 50: API SoundCloud не принимает
     * больше id в одном запросе, а {@code api()} Overtone не умеет читать массив
     * верхнего уровня, поэтому здесь свой HTTP-запрос.
     */
    @Unique
    private Map<String, JsonObject> overtoneParty$fullTracks(List<String> ids) throws Exception {
        Map<String, JsonObject> result = new HashMap<>();
        if (ids.isEmpty()) return result;
        String client;
        try {
            client = this.clientId();
        } catch (Throwable t) {
            client = "";
        }
        if (client == null || client.isBlank()) client = SoundCloudPaths.CLIENT_ID;

        for (int from = 0; from < ids.size(); from += 50) {
            int to = Math.min(from + 50, ids.size());
            String url = SoundCloudPaths.ROOT + "/tracks?ids=" + String.join(",", ids.subList(from, to))
                    + "&client_id=" + client;
            JsonElement parsed = JsonParser.parseString(overtoneParty$http(url));
            if (parsed == null || !parsed.isJsonArray()) continue;
            for (JsonElement e : parsed.getAsJsonArray()) {
                if (!e.isJsonObject()) continue;
                JsonObject o = e.getAsJsonObject();
                String trackId = overtoneParty$str(o, "id");
                if (!trackId.isEmpty()) result.put(trackId, o);
            }
        }
        return result;
    }

    @Unique
    private static String overtoneParty$http(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(20000);
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("User-Agent", "OvertoneParty/0.3.3");
        try {
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) throw new IOException("HTTP " + code + " for " + url);
            StringBuilder body = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) body.append(line);
            }
            return body.toString();
        } finally {
            conn.disconnect();
        }
    }

    @Unique
    private static String overtoneParty$str(JsonObject o, String key) {
        JsonElement e = o == null ? null : o.get(key);
        return e == null || e.isJsonNull() ? "" : e.getAsString();
    }

    @Unique
    private static boolean overtoneParty$bool(JsonObject o, String key, boolean fallback) {
        JsonElement e = o == null ? null : o.get(key);
        return e == null || e.isJsonNull() ? fallback : e.getAsBoolean();
    }
}
