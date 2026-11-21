package dev.overtoneparty.mixin;

import dev.musicplayer.controller.PlayerController;
import dev.musicplayer.model.Platform;
import dev.musicplayer.ui.PlayerScreen;
import dev.overtoneparty.integration.SoundCloudLibrary;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * Вкладки «Плейлисты» и «Альбомы» в окне Overtone для SoundCloud.
 *
 * <p>В Overtone 0.2.2 эти вкладки есть только у Яндекс Музыки и Spotify: для
 * SoundCloud {@code visibleTabs()} сразу возвращает пустой список, а {@code items()}
 * читает только результаты поиска. Здесь мы подменяем оба метода, когда активна
 * наша библиотека наборов. Внутри открытого набора список треков Overtone не трогаем.
 */
@Mixin(value = PlayerScreen.class, remap = false)
public abstract class PlayerScreenLibraryMixin {
    @Shadow
    private PlayerController ctl;

    @Shadow
    private boolean queueMode;

    @Shadow
    private boolean waveShown() {
        throw new AssertionError();
    }

    @Inject(method = "visibleTabs", at = @At("HEAD"), cancellable = true)
    private void overtoneParty$visibleTabs(CallbackInfoReturnable<List<PlayerController.Tab>> cir) {
        if (this.ctl == null || this.ctl.platform != Platform.SOUNDCLOUD) return;
        // Библиотека грузится один раз при первом открытии окна SoundCloud.
        SoundCloudLibrary.ensureLoaded();
        if (this.queueMode || this.waveShown()) return;
        if (this.ctl.page() != null) return;
        if (!SoundCloudLibrary.hasAny()) return;
        cir.setReturnValue(List.of(PlayerController.Tab.ALBUMS, PlayerController.Tab.PLAYLISTS));
    }

    @Inject(method = "items", at = @At("HEAD"), cancellable = true)
    private void overtoneParty$items(CallbackInfoReturnable<List<?>> cir) {
        if (this.ctl == null || this.ctl.platform != Platform.SOUNDCLOUD) return;
        if (this.ctl.page() != null) return;
        if (this.ctl.tab == PlayerController.Tab.ALBUMS) {
            cir.setReturnValue(SoundCloudLibrary.albums());
        } else if (this.ctl.tab == PlayerController.Tab.PLAYLISTS) {
            cir.setReturnValue(SoundCloudLibrary.playlists());
        }
    }
}
