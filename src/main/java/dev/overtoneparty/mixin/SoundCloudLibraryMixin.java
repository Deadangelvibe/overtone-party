package dev.overtoneparty.mixin;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.musicplayer.model.MusicEntity;
import dev.musicplayer.model.Track;
import dev.overtoneparty.OvertoneParty;
import dev.overtoneparty.integration.SoundCloudExt;
import dev.overtoneparty.integration.SoundCloudLibrary;
import dev.overtoneparty.integration.SoundCloudPaths;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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

    /** Читает треки плейлиста/альбома; у Overtone для этого нет отдельного метода. */
    @Unique
    private List<Track> overtoneParty$readSet(String id) throws Exception {
        String path = "/playlists/" + URLEncoder.encode(id, StandardCharsets.UTF_8) + "?limit=200";
        List<Track> all = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int pages = 0;
        while (path != null && pages < 50) {
            JsonObject set = this.api("GET", path);
            // tracks() Overtone умеет читать только массив "collection",
            // а детали набора возвращают массив "tracks". Следующие страницы
            // могут сразу прийти с "collection" — тогда обёртка не нужна.
            JsonObject wrapped = set;
            JsonElement collection = set == null ? null : set.get("collection");
            if (collection == null || !collection.isJsonArray()) {
                wrapped = new JsonObject();
                JsonElement list = set == null ? null : set.get("tracks");
                if (list != null && list.isJsonArray()) wrapped.add("collection", list);
            }
            for (Track t : tracks(wrapped)) {
                if (t != null && t.id() != null && seen.add(t.id())) all.add(t);
            }
            pages++;
            path = SoundCloudPaths.nextPath(set);
        }
        OvertoneParty.LOGGER.info("SoundCloud: набор {} — {} треков", id, all.size());
        return all;
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
