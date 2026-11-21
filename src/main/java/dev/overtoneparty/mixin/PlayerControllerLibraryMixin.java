package dev.overtoneparty.mixin;

import dev.musicplayer.controller.PlayerController;
import dev.musicplayer.model.MusicEntity;
import dev.musicplayer.model.Page;
import dev.musicplayer.provider.SoundCloudProvider;
import dev.overtoneparty.integration.SoundCloudExt;
import dev.overtoneparty.integration.SoundCloudLibrary;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.concurrent.CompletionException;

/**
 * Открытие плейлиста или альбома SoundCloud внутри Overtone.
 *
 * <p>Оригинальный {@code open(MusicEntity)} умеет работать только с Яндексом и
 * Spotify. Для наших сущностей мы грузим треки через провайдер SoundCloud и
 * кладём обычную {@link Page} в историю Overtone — после этого окно отображает
 * список треков, кнопки «играть всё», «назад» и нашу кнопку ♪+ как обычно.
 */
@Mixin(value = PlayerController.class, remap = false)
public abstract class PlayerControllerLibraryMixin {
    // Модификатор final у целевого поля повторять не нужно: мы только читаем поле.
    @Shadow
    public SoundCloudProvider soundcloud;

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
}
