package dev.overtoneparty.mixin;

import dev.musicplayer.controller.PlayerController;
import dev.musicplayer.model.MusicEntity;
import dev.musicplayer.model.Page;
import dev.musicplayer.model.Platform;
import dev.musicplayer.provider.SoundCloudProvider;
import dev.musicplayer.provider.YandexMusicProvider;
import dev.overtoneparty.integration.LikedState;
import dev.overtoneparty.integration.SoundCloudExt;
import dev.overtoneparty.integration.SoundCloudLibrary;
import dev.overtoneparty.integration.YandexExt;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.concurrent.CompletionException;

/**
 * Открытие наборов и отслеживание списка лайков.
 *
 * <p>Для SoundCloud наших сущностей оригинальный {@code open(MusicEntity)} не
 * работает: треки грузятся через провайдер и кладутся в обычную {@link Page}.
 * Для Яндекса открытие альбома/артиста оставляем Overtone, но подгружаем его
 * лайкнутые альбомы и артистов отдельным миксином провайдера.
 */
@Mixin(value = PlayerController.class, remap = false)
public abstract class PlayerControllerLibraryMixin {
    // Модификатор final у целевых полей повторять не нужно: мы только читаем поле.
    @Shadow
    public SoundCloudProvider soundcloud;

    @Shadow
    public YandexMusicProvider yandex;

    @Shadow
    private List<Page> trail;

    @Shadow
    public volatile PlayerController.Tab tab;

    @Shadow
    public volatile String status;

    @Shadow
    public volatile boolean waveView;

    @Inject(method = "open", at = @At("HEAD"), cancellable = true)
    private void overtoneParty$openSet(MusicEntity entity, CallbackInfo ci) {
        LikedState.clear();
        if (!SoundCloudLibrary.isOurEntity(entity)) return;
        ci.cancel();
        if (!(this.soundcloud instanceof SoundCloudExt ext)) return;

        this.status = "Загрузка набора…";
        ext.overtoneParty$loadSet(entity).whenComplete((tracks, error) -> {
            // Возвращаемся в игровой поток: Overtone читает эти поля из UI-потока.
            MinecraftClient.getInstance().execute(() -> {
                if (error != null) {
                    Throwable cause = error instanceof CompletionException && error.getCause() != null
                            ? error.getCause() : error;
                    this.status = "Не удалось загрузить набор: " + cause.getMessage();
                    return;
                }
                this.waveView = false;
                Page page = new Page(entity, tracks == null ? List.of() : tracks, List.of());
                this.trail.add(page);
                this.tab = PlayerController.Tab.TRACKS;
                this.status = page.tracks().isEmpty() ? "В наборе нет треков" : "";
            });
        });
    }

    @Inject(method = "selectPlatform", at = @At("TAIL"))
    private void overtoneParty$selectPlatform(Platform platform, CallbackInfo ci) {
        // Любая смена площадки закрывает прежний список лайков.
        LikedState.clear();
        if (platform == Platform.YANDEX && this.yandex instanceof YandexExt ext) {
            ext.overtoneParty$loadLikedSets();
        }
    }

    @Inject(method = "loadLiked", at = @At("HEAD"))
    private void overtoneParty$loadLiked(CallbackInfo ci) {
        LikedState.clear();
        if (this.yandex instanceof YandexExt ext) ext.overtoneParty$loadLikedSets();
    }

    @Inject(method = "back", at = @At("HEAD"))
    private void overtoneParty$back(CallbackInfo ci) {
        LikedState.clear();
    }
}
