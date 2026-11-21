package dev.overtoneparty.mixin;

import dev.musicplayer.compat.Canvas;
import dev.musicplayer.controller.PlayerController;
import dev.musicplayer.model.Page;
import dev.musicplayer.model.Platform;
import dev.musicplayer.ui.PlayerScreen;
import dev.musicplayer.ui.Shapes;
import dev.overtoneparty.client.OvertoneBridge;
import dev.overtoneparty.integration.PartyAddSetAction;
import dev.overtoneparty.integration.SoundCloudLibrary;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * Вкладки «Плейлисты» и «Альбомы» и кнопка «♪+ Всё» в окне Overtone.
 *
 * <p>В Overtone 0.2.2 эти вкладки есть только у Яндекс Музыки и Spotify: для
 * SoundCloud {@code visibleTabs()} сразу возвращает пустой список, а {@code items()}
 * читает только результаты поиска. Здесь мы подменяем оба метода, когда активна
 * наша библиотека наборов. Внутри открытого набора список треков Overtone не трогаем.
 *
 * <p>После открытия набора в его заголовке появляется кнопка «♪+ Всё»: она кладёт
 * в общую очередь весь плейлист/альбом, а не только видимую страницу. Клик и
 * отрисовка сделаны через {@code rowButton} и обычный {@code Canvas}, чтобы кнопка
 * использовала те же цвета и скругления, что и остальной интерфейс Overtone.
 */
@Mixin(value = PlayerScreen.class, remap = false)
public abstract class PlayerScreenLibraryMixin {
    @Shadow
    private PlayerController ctl;

    @Shadow
    private boolean queueMode;

    @Shadow
    private int lx;

    @Shadow
    private int lw;

    @Shadow
    private int mouseX;

    @Shadow
    private int mouseY;

    @Shadow
    private int pageHeaderY;

    @Shadow
    private boolean waveShown() {
        throw new AssertionError();
    }

    @Shadow
    private void rowButton(int x, int y, int w, Runnable action) {
        throw new AssertionError();
    }

    @Unique
    private boolean party$addSetButton;

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

    /** Создаём кликабельную зону рядом с кнопкой «› Назад» и «▶ Играть всё». */
    @Inject(method = "buildMain", at = @At("TAIL"))
    private void overtoneParty$buildAddSetButton(String oldQuery, CallbackInfo ci) {
        this.party$addSetButton = false;
        if (this.ctl == null || this.ctl.platform != Platform.SOUNDCLOUD) return;
        if (this.queueMode || this.waveShown()) return;
        Page page = this.ctl.page();
        if (page == null || page.tracks().isEmpty()) return;
        int w = overtoneParty$addSetWidth();
        if (w <= 0) return;
        this.rowButton(this.lx + 58, this.pageHeaderY, w,
                new PartyAddSetAction(this.ctl));
        this.party$addSetButton = true;
    }

    /** Рисуем кнопку поверх заголовка страницы, в стиле Overtone. */
    @Inject(method = "renderMain", at = @At("TAIL"))
    private void overtoneParty$drawAddSetButton(Canvas g, CallbackInfo ci) {
        if (!this.party$addSetButton) return;
        if (this.ctl == null || this.ctl.platform != Platform.SOUNDCLOUD) return;
        if (this.queueMode || this.waveShown()) return;
        Page page = this.ctl.page();
        if (page == null || page.tracks().isEmpty()) return;
        int w = overtoneParty$addSetWidth();
        if (w <= 0) return;

        int x = this.lx + 58;
        int y = this.pageHeaderY;
        String label = "♪+ Всё";
        OvertoneBridge.Theme th = OvertoneBridge.theme();
        boolean hot = this.mouseX >= x && this.mouseX < x + w
                && this.mouseY >= y && this.mouseY < y + 18;
        int color = hot ? th.accent() : th.trackBg();
        int radius = Math.max(3, Math.min(8, th.radius()));
        try {
            Shapes.fill(g, x, y, w, 18, radius, color);
        } catch (Throwable ignored) {
            // Другая сборка Overtone без Shapes — рисуем обычный прямоугольник.
            g.rect(x, y, w, 18, color);
        }
        g.text(label, x + (w - g.textWidth(label)) / 2, y + 4, 0xFFFFFFFF, false);
    }

    /** Ширина кнопки: между «Назад» и «Играть всё» должно оставаться место. */
    @Unique
    private int overtoneParty$addSetWidth() {
        int w = Math.min(78, this.lw - 58 - 78 - 4);
        return w < 48 ? 0 : w;
    }
}
