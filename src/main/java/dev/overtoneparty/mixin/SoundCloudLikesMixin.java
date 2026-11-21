package dev.overtoneparty.mixin;

import com.google.gson.JsonObject;
import dev.musicplayer.model.Track;
import dev.overtoneparty.OvertoneParty;
import dev.overtoneparty.integration.SoundCloudLibrary;
import dev.overtoneparty.integration.SoundCloudPaths;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Полный список лайков SoundCloud в Overtone.
 *
 * <p>Оригинальный {@code SoundCloudProvider.likedTracks()} запрашивает только первую
 * страницу: {@code /users/<id>/track_likes?limit=200}, а {@code next_href} игнорирует.
 * Из-за этого у аккаунтов с 200+ лайками пропадают самые старые треки (у deadangelvibe
 * видно 194 из 261).
 *
 * <p>Миксин перехватывает метод в самом начале и возвращает свой future: он идёт по
 * всем страницам через {@code next_href}, склеивает результаты и убирает дубликаты.
 * Всё остальное (разбор треков, обновление сердечек, ошибки) остаётся за Overtone.
 */
@Mixin(targets = "dev.musicplayer.provider.SoundCloudProvider", remap = false)
public abstract class SoundCloudLikesMixin {
    @Shadow
    private volatile String userId;

    @Shadow
    @Final
    private Set<String> likedIds;

    @Shadow
    private JsonObject api(String method, String path) throws Exception {
        throw new AssertionError();
    }

    @Shadow
    private static List<Track> tracks(JsonObject page) {
        throw new AssertionError();
    }

    @Shadow
    public abstract boolean canLike();

    @Inject(method = "likedTracks", at = @At("HEAD"), cancellable = true)
    private void overtoneParty$likedTracks(CallbackInfoReturnable<CompletableFuture<List<Track>>> cir) {
        // Не залогинен — пусть Overtone сам покажет свою ошибку входа.
        String uid = this.userId;
        if (uid == null) return;
        cir.setReturnValue(CompletableFuture.supplyAsync(() -> {
            try {
                return this.overtoneParty$loadAll(uid);
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }));
    }

    @Unique
    private List<Track> overtoneParty$loadAll(String uid) throws Exception {
        // Плейлисты и альбомы грузим параллельно с лайками: они появятся
        // отдельными вкладками в окне Overtone.
        SoundCloudLibrary.ensureLoaded();
        List<Track> all = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String path = "/users/" + uid + "/track_likes?limit=200";
        int pages = 0;
        // Ограничение от бесконечного цикла, если SoundCloud начнёт отдавать один и тот же next_href.
        while (path != null && pages < 50) {
            JsonObject page = this.api("GET", path);
            for (Track t : tracks(page)) {
                if (t != null && t.id() != null && seen.add(t.id())) all.add(t);
            }
            pages++;
            path = SoundCloudPaths.nextPath(page);
        }
        if (this.canLike()) {
            for (Track t : all) this.likedIds.add(t.id());
        }
        OvertoneParty.LOGGER.info("Лайки SoundCloud: загружено {} треков ({} страниц)",
                all.size(), pages);
        return all;
    }
}
