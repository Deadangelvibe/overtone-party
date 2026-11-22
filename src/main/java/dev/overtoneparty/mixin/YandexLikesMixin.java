package dev.overtoneparty.mixin;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.musicplayer.controller.PlayerController;
import dev.musicplayer.model.MusicEntity;
import dev.musicplayer.model.Track;
import dev.musicplayer.provider.YandexMusicProvider;
import dev.overtoneparty.OvertoneParty;
import dev.overtoneparty.integration.LikedState;
import dev.overtoneparty.integration.YandexExt;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Лайкнутые альбомы и артисты Яндекс Музыки.
 *
 * <p>В Overtone 0.2.2 вкладки «Артисты» и «Альбомы» заполняются только результатами
 * поиска: кнопка «Мне нравится» загружает одни треки. Здесь мы через приватный
 * {@code getResultArray()} того же провайдера читаем {@code /likes/albums} и
 * {@code /likes/artists} и кладём сущности в список вкладок.
 */
@Mixin(targets = "dev.musicplayer.provider.YandexMusicProvider", remap = false)
public abstract class YandexLikesMixin implements YandexExt {
    private static volatile String party$loadedUid;
    private static volatile boolean party$loading;

    @Shadow
    private volatile String uid;

    /**
     * В Overtone есть два приватных метода: getResult возвращает JsonObject,
     * а getResultArray — JsonArray. Ответы /likes/* приходят массивом в поле
     * result, поэтому нужен именно второй.
     */
    @Shadow
    private JsonArray getResultArray(String url) throws Exception {
        throw new AssertionError();
    }

    /** Возвращает поле result ответа API как объект. */
    @Shadow
    private JsonObject getResult(String url) throws Exception {
        throw new AssertionError();
    }

    @Shadow
    private static MusicEntity albumOf(JsonObject o) {
        throw new AssertionError();
    }

    @Shadow
    private static MusicEntity artistOf(JsonObject o) {
        throw new AssertionError();
    }

    @Inject(method = "likedTracks", at = @At("HEAD"))
    private void overtoneParty$likedTracksAlsoLoadSets(
            CallbackInfoReturnable<CompletableFuture<List<Track>>> cir) {
        this.overtoneParty$loadLikedSets();
    }

    /** Запоминаем список лайков, чтобы кнопка появлялась именно на нём. */
    @Inject(method = "likedTracks", at = @At("RETURN"))
    private void overtoneParty$cacheLikedTracks(
            CallbackInfoReturnable<CompletableFuture<List<Track>>> cir) {
        CompletableFuture<List<Track>> future = cir.getReturnValue();
        if (future != null) future.thenAccept(LikedState::cache);
    }

    @Override
    public CompletableFuture<Void> overtoneParty$loadLikedSets() {
        String id = this.uid;
        if (id == null || id.isBlank() || id.equals(party$loadedUid) || party$loading) {
            return CompletableFuture.completedFuture(null);
        }
        party$loading = true;
        return CompletableFuture.runAsync(() -> {
            try {
                List<MusicEntity> albums = this.overtoneParty$read("albums", true);
                List<MusicEntity> artists = this.overtoneParty$read("artists", false);
                MinecraftClient.getInstance().execute(() -> {
                    PlayerController.I.albums = albums;
                    PlayerController.I.artists = artists;
                });
                party$loadedUid = id;
            } catch (Exception e) {
                OvertoneParty.LOGGER.warn("Яндекс: не удалось загрузить лайкнутые альбомы и артистов: {}",
                        String.valueOf(e.getMessage()));
            }
        }).whenComplete((unused, error) -> party$loading = false);
    }

    /** Читает один раздел лайков и превращает его в список сущностей Overtone. */
    @Unique
    private List<MusicEntity> overtoneParty$read(String section, boolean album) throws Exception {
        JsonArray array = this.getResultArray("https://api.music.yandex.net/users/"
                + this.uid + "/likes/" + section);
        List<MusicEntity> result = new ArrayList<>();
        if (array == null) return result;
        for (JsonElement element : array) {
            if (element == null || !element.isJsonObject()) continue;
            JsonObject object = element.getAsJsonObject();
            JsonElement wrapped = object.get(album ? "album" : "artist");
            if (wrapped != null && wrapped.isJsonObject()) object = wrapped.getAsJsonObject();
            // /likes/albums возвращает только {id, timestamp}: догружаем альбом
            // через тот же endpoint /albums/<id>/with-tracks, что и Overtone.
            if (album && overtoneParty$findText(object, "title").isBlank()) {
                String id = overtoneParty$text(object, "id");
                if (!id.isBlank()) {
                    JsonObject detail = this.getResult(
                            "https://api.music.yandex.net/albums/" + id + "/with-tracks");
                    if (detail != null) object = detail;
                }
            }
            // Overtone уже умеет разбирать эти объекты — берём от него id, kind и
            // обложку. Но его title — несуществующий перевод entity.album/artist,
            // поэтому подставляем реальное название из ответа API.
            MusicEntity base = album ? albumOf(object) : artistOf(object);
            if (base == null || base.id() == null || base.id().isBlank()) continue;
            String title = overtoneParty$findText(object, album ? "title" : "name");
            if (title.isBlank()) title = base.title();
            if (title.isBlank()) title = overtoneParty$findText(object, "name");
            if (title.isBlank()) title = overtoneParty$findText(object, "title");
            if (title.isBlank()) title = "(" + base.id() + ")";
            String subtitle = album ? overtoneParty$artists(object) : "";
            if (subtitle.isBlank()) subtitle = base.subtitle();
            result.add(new MusicEntity(base.kind(), base.id(), title, subtitle, base.coverUrl()));
        }
        return result;
    }

    /** Безопасное чтение строкого поля ответа API. */
    @Unique
    private static String overtoneParty$text(JsonObject o, String key) {
        JsonElement value = o == null ? null : o.get(key);
        return value != null && value.isJsonPrimitive() && !value.isJsonNull() ? value.getAsString() : "";
    }

    /** Ищет поле и во вложенных объектах ответа API. */
    @Unique
    private static String overtoneParty$findText(JsonObject o, String key) {
        if (o == null) return "";
        String direct = overtoneParty$text(o, key);
        if (!direct.isBlank()) return direct;
        for (Map.Entry<String, JsonElement> entry : o.entrySet()) {
            JsonElement value = entry.getValue();
            if (value != null && value.isJsonObject()) {
                String nested = overtoneParty$findText(value.getAsJsonObject(), key);
                if (!nested.isBlank()) return nested;
            }
        }
        return "";
    }

    /** Имена артистов альбома через запятую. */
    @Unique
    private static String overtoneParty$artists(JsonObject o) {
        JsonArray array = o == null ? null : o.getAsJsonArray("artists");
        if (array == null) return "";
        StringBuilder out = new StringBuilder();
        for (JsonElement element : array) {
            if (element == null || !element.isJsonObject()) continue;
            String name = overtoneParty$text(element.getAsJsonObject(), "name");
            if (name.isBlank()) continue;
            if (out.length() > 0) out.append(", ");
            out.append(name);
        }
        return out.toString();
    }
}
