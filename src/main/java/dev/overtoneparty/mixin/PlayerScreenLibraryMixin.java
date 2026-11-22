package dev.overtoneparty.mixin;

import dev.musicplayer.compat.Canvas;
import dev.musicplayer.controller.PlayerController;
import dev.musicplayer.model.MusicEntity;
import dev.musicplayer.model.Platform;
import dev.musicplayer.ui.PlayerScreen;
import dev.musicplayer.ui.Shapes;
import dev.overtoneparty.client.OvertoneBridge;
import dev.overtoneparty.integration.PartyAddLikedAction;
import dev.overtoneparty.integration.PartyAddSetAction;
import dev.overtoneparty.integration.PartyScreenAccess;
import dev.overtoneparty.integration.PartySets;
import dev.overtoneparty.integration.SoundCloudLibrary;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

import java.util.List;

/**
 * Вкладки наборов, кнопки «♪+» и список лайков в окне Overtone.
 *
 * <p>Для SoundCloud вкладки «Плейлисты» и «Альбомы» в Overtone 0.2.2 отсутствуют,
 * поэтому они создаются здесь. Для Яндекса вкладки уже есть — подгружаем в них
 * лайкнутые альбомы и артистов (см. {@code YandexLikesMixin}).
 *
 * <p>Кнопка целого набора живёт в пустом правом слоте строки: Overtone создаёт
 * там невидимую кнопку, которая для сущностей ничего не делает, — убираем её и
 * ставим свою. В списке лайков первая строка отдаётся кнопке «все лайкнутые»,
 * а треки вместе с их кликабельными зонами сдвигаются на 24 px вниз.
 *
 * <p>Длинные заголовки альбомов и плейлистов сужаются на 36 px, чтобы не
 * залезать под кнопку, и прокручиваются («ездут») внутри своей строки.
 */
@Mixin(value = PlayerScreen.class, remap = false)
public abstract class PlayerScreenLibraryMixin implements PartyScreenAccess {
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
    private int listTop;

    @Shadow
    private int rows;

    @Shadow
    private int page;

    /** Canvas текущей строки — нужен, чтобы измерить ширину заголовка. */
    @Unique
    private Canvas party$rowCanvas;

    /** Список лайков открыт: сдвигаем строки и рисуем кнопку «все лайкнутые». */
    @Unique
    private boolean party$likedShift;

    @Unique
    private boolean party$addingOwnButton;

    @Override
    public int overtoneParty$page() {
        return this.page;
    }

    @Override
    public int overtoneParty$rows() {
        return this.rows;
    }

    @Override
    public boolean overtoneParty$likedShift() {
        return this.party$likedShift;
    }

    @Override
    public int overtoneParty$listTop() {
        return this.listTop;
    }

    @Override
    public int overtoneParty$mouseY() {
        return this.mouseY;
    }

    // ------------------------------------------------------------------ вкладки

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

    // ------------------------------------------------------ список лайков: сдвиг

    @Shadow
    private boolean waveShown() {
        throw new AssertionError();
    }

    @Shadow
    private void rowButton(int x, int y, int w, Runnable action) {
        throw new AssertionError();
    }

    @Shadow
    private void buildMain(String oldQuery) {
        throw new AssertionError();
    }

    /** Открыт ли сейчас список лайков, который нужно оформить кнопкой. */
    @Unique
    private boolean overtoneParty$showLiked() {
        if (this.ctl == null) return false;
        if (this.queueMode || this.waveShown()) return false;
        return dev.overtoneparty.integration.LikedState.isShowing(this.ctl);
    }

    @Inject(method = "buildMain", at = @At("HEAD"))
    private void overtoneParty$buildStart(String oldQuery, CallbackInfo ci) {
        this.party$likedShift = this.overtoneParty$showLiked();
    }

    /**
     * Overtone пересобирает список кнопок только при открытии окна. Лайки грузятся
     * асинхронно, поэтому состояние нужно проверять каждый кадр и при изменении
     * пересобрать список — иначе кнопка появляется только после повторного входа
     * и остаётся на других вкладках.
     */
    @Inject(method = "renderMain", at = @At("HEAD"))
    private void overtoneParty$refreshLiked(Canvas g, CallbackInfo ci) {
        boolean now = this.overtoneParty$showLiked();
        if (now == this.party$likedShift) return;
        this.party$likedShift = now;
        // Не вызываем buildMain напрямую: он лишь дописывает кнопки к старым,
        // и старые зоны продолжают ловить клики. Изменение подписи viewSig
        // заставляет Overtone в следующем тике сделать полную пересборку экрана.
    }

    /**
     * Подпись текущего вида. Overtone сравнивает её с сохранённой в тике и при
     * различии вызывает полную пересборку экрана вместе с очисткой старых
     * кликабельных зон. Добавляем в неё состояние кнопки «все лайкнутые».
     */
    @Inject(method = "viewSig", at = @At("RETURN"), cancellable = true)
    private void overtoneParty$likedViewSig(CallbackInfoReturnable<Integer> cir) {
        if (this.party$likedShift) {
            cir.setReturnValue(cir.getReturnValue() ^ 0x4000_0000);
        }
    }

    /** Кликабельные зоны строк едут вниз вместе с их отрисовкой. */
    @ModifyArgs(method = "buildMain", at = @At(value = "INVOKE",
            target = "Ldev/musicplayer/ui/PlayerScreen;rowButton(IIILjava/lang/Runnable;)V", ordinal = 0))
    private void overtoneParty$shiftRowButton0(Args args) {
        // Основная кнопка строки: освобождаем 16 px под кнопку вечеринки.
        // Раньше это делал отдельный @ModifyArgs на том же вызове, из-за чего
        // сдвиг по Y не применялся и клик попадал в строку ниже.
        if (!this.queueMode) args.set(2, (int) args.get(2) - 16);
        this.overtoneParty$shiftRowButton(args);
    }

    @ModifyArgs(method = "buildMain", at = @At(value = "INVOKE",
            target = "Ldev/musicplayer/ui/PlayerScreen;rowButton(IIILjava/lang/Runnable;)V", ordinal = 1))
    private void overtoneParty$shiftRowButton1(Args args) {
        this.overtoneParty$shiftRowButton(args);
    }

    @ModifyArgs(method = "buildMain", at = @At(value = "INVOKE",
            target = "Ldev/musicplayer/ui/PlayerScreen;rowButton(IIILjava/lang/Runnable;)V", ordinal = 2))
    private void overtoneParty$shiftRowButton2(Args args) {
        this.overtoneParty$shiftRowButton(args);
    }

    /** Overtone создаёт три кнопки на строку: выбор, «+» и «play next». */
    @Unique
    private void overtoneParty$shiftRowButton(Args args) {
        if (!this.party$likedShift) return;
        int y = (int) args.get(1);
        args.set(1, y + 24);
    }

    /** Первая строка — под кнопку «все лайкнутые», на всю ширину списка. */
    @Inject(method = "buildMain", at = @At("TAIL"))
    private void overtoneParty$buildLikedButton(String oldQuery, CallbackInfo ci) {
        if (!this.party$likedShift) return;
        this.rowButton(this.lx, this.listTop, this.lw - 2,
                new PartyAddLikedAction(this.ctl));
    }

    /** Отрисовка треков тоже сдвигается: локальная переменная 11 — y строки. */
    @ModifyVariable(method = "drawList", at = @At("STORE"), index = 11)
    private int overtoneParty$shiftRowY(int y) {
        return this.party$likedShift ? y + 24 : y;
    }

    @Inject(method = "drawList", at = @At("TAIL"))
    private void overtoneParty$drawLikedButton(Canvas g, CallbackInfo ci) {
        if (!this.party$likedShift) return;
        OvertoneBridge.Theme th = OvertoneBridge.theme();
        int x = this.lx + 1;
        int y = this.listTop + 1;
        int w = this.lw - 4;
        boolean hot = this.mouseX >= x && this.mouseX < x + w
                && this.mouseY >= y && this.mouseY < y + 22;
        int color = hot ? th.accent() : th.trackBg();
        int radius = Math.max(3, Math.min(8, th.radius()));
        try {
            Shapes.fill(g, x, y, w, 22, radius, color);
        } catch (Throwable ignored) {
            g.rect(x, y, w, 22, color);
        }
        String label = "♪+ Все лайкнутые";
        g.text(label, x + 10, y + 7, 0xFFFFFFFF, false);
    }

    // -------------------------------------------------- кнопка целого набора ♪+

    /**
     * Пустая кнопка Overtone в правом слоте перехватывает клик раньше нашей.
     * Для строк альбомов и плейлистов она всё равно ничего не делает — убираем её.
     */
    @Inject(method = "rowButton", at = @At("HEAD"), cancellable = true)
    private void overtoneParty$skipEmptySmallButtons(int x, int y, int w, Runnable action, CallbackInfo ci) {
        if (this.party$addingOwnButton) return;
        if (overtoneParty$sets() == null) return;
        int right = this.lx + this.lw;
        if (x != right - 34 && x != right - 16) return;
        int top = this.listTop + (this.party$likedShift ? 24 : 0);
        if (y < top || y >= top + this.rows * 24) return;
        ci.cancel();
    }

    /** Маленькая кнопка в каждой видимой строке альбома, плейлиста или артиста. */
    @Inject(method = "buildMain", at = @At("TAIL"))
    private void overtoneParty$buildSetButtons(String oldQuery, CallbackInfo ci) {
        List<MusicEntity> sets = overtoneParty$sets();
        if (sets == null) return;
        int count = overtoneParty$visibleCount(sets);
        if (count <= 0) return;

        this.party$addingOwnButton = true;
        try {
            for (int i = 0; i < count; i++) {
                MusicEntity entity = overtoneParty$entityAt(sets, i);
                // «Мои плейлисты» — служебная строка, а не набор треков.
                if (entity == null || entity.isMine()) continue;
                // Ширина 15, а не 16: миксин списка треков добавляет свою кнопку
                // только в 16-пиксельный слот и не должен цеплять наш слот набора.
                int y = this.listTop + (this.party$likedShift ? 24 : 0) + i * 24;
                this.rowButton(this.lx + this.lw - 34, y, 15,
                        new PartyAddSetAction(this.ctl, this, i));
            }
        } finally {
            this.party$addingOwnButton = false;
        }
    }

    /** Рисуем плашку поверх строки, в стиле Overtone. */
    @Inject(method = "renderMain", at = @At("TAIL"))
    private void overtoneParty$drawSetButtons(Canvas g, CallbackInfo ci) {
        List<MusicEntity> sets = overtoneParty$sets();
        if (sets == null) return;
        int count = overtoneParty$visibleCount(sets);
        if (count <= 0) return;

        OvertoneBridge.Theme th = OvertoneBridge.theme();
        String label = "♪+";
        for (int i = 0; i < count; i++) {
            MusicEntity entity = overtoneParty$entityAt(sets, i);
            if (entity == null || entity.isMine()) continue;
            int x = this.lx + this.lw - 34;
            int y = this.listTop + (this.party$likedShift ? 24 : 0) + i * 24;
            boolean hot = this.mouseX >= x && this.mouseX < x + 16
                    && this.mouseY >= y && this.mouseY < y + 22;
            int color = hot ? th.accent() : th.trackBg();
            int radius = Math.max(3, Math.min(7, th.radius()));
            try {
                Shapes.fill(g, x, y + 3, 16, 16, radius, color);
            } catch (Throwable ignored) {
                // Другая сборка Overtone без Shapes — рисуем обычный прямоугольник.
                g.rect(x, y + 3, 16, 16, color);
            }
            g.text(label, x + 8 - g.textWidth(label) / 2, y + 7, 0xFFFFFFFF, false);
        }
    }

    /** Список наборов, для которого показываем кнопки, или {@code null}. */
    @Unique
    private List<MusicEntity> overtoneParty$sets() {
        if (this.queueMode || this.waveShown()) return null;
        return PartySets.forCurrentTab(this.ctl);
    }

    /** Сущность в видимой строке i с учётом текущей страницы. */
    @Unique
    private MusicEntity overtoneParty$entityAt(List<MusicEntity> sets, int row) {
        int index = this.page * this.rows + row;
        return index >= 0 && index < sets.size() ? sets.get(index) : null;
    }

    /** Сколько кнопок реально нужно: по числу сущностей на текущей странице. */
    @Unique
    private int overtoneParty$visibleCount(List<MusicEntity> sets) {
        int first = this.page * this.rows;
        if (first < 0 || first >= sets.size()) return 0;
        return Math.min(this.rows, sets.size() - first);
    }

    // -------------------------------------------- ездящие заголовки наборов

    @Inject(method = "drawEntityRow", at = @At("HEAD"))
    private void overtoneParty$rowCanvas(Canvas g, MusicEntity entity, int y,
                                         boolean selected, CallbackInfo ci) {
        this.party$rowCanvas = g;
    }

    /** Локальная переменная 11 — доступная ширина текста в строке набора. */
    @ModifyVariable(method = "drawEntityRow", at = @At("STORE"), index = 11)
    private int overtoneParty$narrowEntityText(int width) {
        return overtoneParty$sets() == null ? width : width - 36;
    }

    /** Локальная переменная 12 — заголовок: прокручиваем, если он не помещается. */
    @ModifyVariable(method = "drawEntityRow", at = @At("STORE"), index = 12)
    private String overtoneParty$marqueeTitle(String title) {
        if (overtoneParty$sets() == null) return title;
        Canvas g = this.party$rowCanvas;
        if (g == null || title == null || title.isEmpty()) return title;

        int width = this.lw - 70;
        if (g.textWidth(title) <= width) return title;

        String loop = title + "        ";
        int loopWidth = g.textWidth(loop);
        if (loopWidth <= 0) return title;

        int speed = 22; // пикселей в секунду
        int phase = Math.abs(title.hashCode()) % loopWidth;
        int shift = (int) ((System.currentTimeMillis() * speed / 1000L + phase) % loopWidth);

        // Два повтора строки, чтобы прокрутка была непрерывной на стыке.
        String twice = loop + loop;
        int at = 0;
        int passed = 0;
        while (at < twice.length() && passed < shift) {
            int cp = twice.codePointAt(at);
            int len = Character.charCount(cp);
            passed += Math.max(1, g.textWidth(twice.substring(at, at + len)));
            at += len;
        }

        StringBuilder out = new StringBuilder();
        int used = 0;
        while (at < twice.length() && used < width) {
            int cp = twice.codePointAt(at);
            int len = Character.charCount(cp);
            int cw = Math.max(1, g.textWidth(twice.substring(at, at + len)));
            if (used + cw > width) break;
            out.appendCodePoint(cp);
            used += cw;
            at += len;
        }
        return out.length() == 0 ? title.substring(0, 1) : out.toString();
    }
}
