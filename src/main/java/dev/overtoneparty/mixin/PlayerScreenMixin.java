package dev.overtoneparty.mixin;

import dev.musicplayer.compat.Canvas;
import dev.musicplayer.controller.PlayerController;
import dev.musicplayer.model.Track;
import dev.musicplayer.ui.PlayerScreen;
import dev.musicplayer.ui.Shapes;
import dev.overtoneparty.client.OvertoneBridge;
import dev.overtoneparty.integration.PartyAddAction;
import dev.overtoneparty.integration.PartyScreenAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Добавляет в список треков Overtone третью кнопку — «в вечеринку».
 *
 * <p>Она стоит слева от «+» (добавить в очередь Overtone) и кладёт трек
 * в общую очередь вечеринки. Чтобы освободить место, строка списка становится
 * на 16 px уже, а текст трека обрезается на те же 16 px.
 *
 * <p>Кнопка всегда доступна, даже если вечеринка ещё не запущена: сервер
 * начнёт её с выбранного трека. Оформление берётся из темы Overtone
 * ({@link OvertoneBridge#theme()}), поэтому кнопка меняет цвет вместе с плеером.
 *
 * <p>Здесь намеренно нет ни одной ссылки на классы Minecraft: миксин трогает
 * только собственные методы и поля Overtone, поэтому не зависит от ремапа
 * Minecraft-имён под Sinytra Connector. По той же причине у миксина
 * {@code remap = false}: методов Overtone нет в маппингах Minecraft.
 */
@Mixin(value = PlayerScreen.class, remap = false)
public abstract class PlayerScreenMixin {
    @Shadow
    private int lx;
    @Shadow
    private int lw;
    @Shadow
    private int listTop;
    @Shadow
    private int rows;
    @Shadow
    private int page;
    @Shadow
    private boolean queueMode;
    @Shadow
    private int mouseX;
    @Shadow
    private int mouseY;

    @Unique
    private boolean party$button;

    /** Сдвиг строк в списке лайков: его задаёт PlayerScreenLibraryMixin. */
    @Unique
    private boolean party$likedShift() {
        return ((PartyScreenAccess) (Object) this).overtoneParty$likedShift();
    }

    @Shadow
    private List<?> items() {
        throw new AssertionError();
    }

    @Shadow
    private void rowButton(int x, int y, int w, Runnable action) {
        throw new AssertionError();
    }

    @Inject(method = "buildMain", at = @At("HEAD"))
    private void party$resetButton(String oldQuery, CallbackInfo ci) {
        this.party$button = false;
    }

    // Кликабельная зона новой кнопки — там же, где её рисуем. Трек и номер
    // страницы PartyAddAction читает уже в момент клика: список Overtone
    // подгружается асинхронно, а страницы листаются без перестройки кнопок.
    @Inject(method = "rowButton", at = @At("HEAD"))
    private void party$addHitbox(int x, int y, int w, Runnable action, CallbackInfo ci) {
        if (this.queueMode) return;
        if (w != 16 || x != this.lx + this.lw - 34) return;
        this.party$button = true;
        int row = (y - this.listTop - (this.party$likedShift() ? 24 : 0)) / 24;
        this.rowButton(this.lx + this.lw - 50, y, 16,
                new PartyAddAction((PlayerScreen) (Object) this, row));
    }

    // Текст трека короче на 16 px, чтобы не залезать под новую кнопку.
    @ModifyVariable(method = "drawList", at = @At("STORE"), index = 6)
    private int party$shrinkText(int value) {
        return this.queueMode ? value : value - 16;
    }

    @Inject(method = "drawList", at = @At("TAIL"))
    private void party$drawButton(Canvas g, CallbackInfo ci) {
        if (!this.party$button || this.queueMode) return;
        List<?> res = this.items();
        OvertoneBridge.Theme th = OvertoneBridge.theme();
        int ax = this.lx + this.lw - 50;
        String glyph = "♪+";
        int gw = g.textWidth(glyph);
        Track cur = PlayerController.I == null ? null : PlayerController.I.current;

        for (int i = 0; i < this.rows; i++) {
            int idx = this.page * this.rows + i;
            if (idx >= res.size()) break;
            if (!(res.get(idx) instanceof Track t)) continue;

            int y = this.listTop + (this.party$likedShift() ? 24 : 0) + i * 24;
            boolean hov = this.mouseX >= this.lx && this.mouseX < this.lx + this.lw
                    && this.mouseY >= y && this.mouseY < y + 22;
            boolean isCur = cur != null && t.id() != null && t.id().equals(cur.id());
            if (!hov && !isCur) continue;

            boolean hot = this.mouseX >= ax && this.mouseX < ax + 16
                    && this.mouseY >= y && this.mouseY < y + 22;
            // Ровно как соседние кнопки Overtone: PlayerTheme.rrect(..., 3, ...),
            // то есть фиксированный радиус 3, без рамки и подсветки.
            try {
                Shapes.fill(g, ax + 1, y + 3, 14, 16, 3, hot ? th.accent() : th.trackBg());
            } catch (Throwable ignored) {
                // Другая сборка Overtone без Shapes — рисуем обычный прямоугольник.
                g.rect(ax + 1, y + 3, 14, 16, hot ? th.accent() : th.trackBg());
            }
            g.text(glyph, ax + 8 - gw / 2, y + 7, 0xFFFFFFFF, false);
        }
    }
}
