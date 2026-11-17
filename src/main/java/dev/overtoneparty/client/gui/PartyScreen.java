package dev.overtoneparty.client.gui;

import dev.overtoneparty.client.OvertoneBridge;
import dev.overtoneparty.client.PartyClient;
import dev.overtoneparty.client.PartyClientNet;
import dev.overtoneparty.model.PartyTrack;
import dev.overtoneparty.net.PartyNet;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Экран вечеринок.
 *
 * <p>Если игрок ещё ни в одной вечеринке — это обзор: список чужих вечеринок,
 * кнопки «Начать свою вечеринку» и «Присоединиться» (вторая активна только после
 * выбора строки). Если игрок в вечеринке — знакомая панель с очередью и управлением.
 *
 * <p>Очередь — настоящий список треков: строки подсвечиваются, у текущего трека
 * слева акцентная полоса, при наведении справа появляются кнопки действий.
 * Цвета, скругление и анимация берутся из настроек Overtone.
 */
public class PartyScreen extends Screen {
    private static final int PANEL_W = 460;
    private static final int PANEL_H = 360;
    private static final int ROW_H = 22;
    private static final int PARTY_ROW_H = 30;
    private static final int ACTION_W = 16;
    private static final int ACTION_GAP = 2;
    private static final int FOOTER_BTN = 20;
    private static final int FOOTER_GAP = 4;

    private static final float OPEN_NS = 3.4E8f;
    private static final float CLOSE_NS = 2.2E8f;
    private static final float TRANSITION_NS = 2.04E8f;

    private static final int C_TEXT = 0xFFFFFFFF;
    private static final int C_DIM = 0xFFB4B4C0;
    private static final int C_DIMMER = 0xFF6E6E7C;
    private static final int C_WARN = 0xFFFFC46B;
    private static final int C_ERROR = 0xFFFF8080;

    private static final String[] ACTION_TOOLTIPS = {"Включить этот трек", "Выше", "Ниже", "Удалить из очереди"};
    private static final String[] ACTION_GLYPHS = {">", "^", "v", "x"};

    private int x0;
    private int y0;
    private int w;
    private int h;

    private int listX;
    private int listY;
    private int listW;
    private int listH;
    private int listBottomY;
    private double scroll;
    private double scrollTarget;

    private int hoverRow = -1;
    private int hoverAction = -1;
    private int lastIndex = -1;
    private final int[] actionX = new int[4];
    private final float[] actionGlow = new float[4];
    private float actionsGlow;
    private int lastActionRow = -1;
    private float[] rowGlow = new float[64];

    // Выбор вечеринки в обзорном экране.
    private UUID selectedPartyId = new UUID(0L, 0L);
    private boolean lastBrowser = true;

    // Анимация окна.
    private long shownAt;
    private long closeAt;
    private long lastFrameNs;
    private Runnable afterClose;
    private float dt = 0.016f;
    private float animAlpha = 1f;
    private String animStyle = "ZOOM";
    private float tfKx = 1f;
    private float tfKy = 1f;
    private float tfTy;
    private float progress;

    // Палитра из настроек Overtone.
    private int cPanel = 0xE0141418;
    private int cPanel2 = 0xFF1B1B21;
    private int cBorder = 0x1EFFFFFF;
    private int cAccent = 0xFF9AE6B4;
    private int cAccentHover = 0xFFB4F0C8;
    private int cTrackBg = 0xFF24242A;
    private int cHover = 0xFF303038;
    private int cRowCurrent = 0xFF1A1A20;
    private int cDim = 0x80000000;
    private int radius = 2;

    private final List<PButton> buttons = new ArrayList<>();
    private PButton bStart;
    private PButton bJoin;
    private PButton bRefresh;
    private PButton bBrowserClose;
    private PButton bPause;
    private PButton bNext;
    private PButton bPrev;
    private PButton bClear;
    private PButton bDj;
    private PButton bListen;
    private PButton bExit;
    private PButton bClose;

    // Диалоги создания и входа в приватную вечеринку.
    private enum Dialog { NONE, CREATE, JOIN }
    private Dialog dialog = Dialog.NONE;
    private boolean createPrivate;
    private TextFieldWidget passwordField;
    private int dialogX;
    private int dialogY;
    private int dialogW;
    private int dialogH;

    // Выбор со-диджеев: только для владельца вечеринки.
    private static final int PICK_ROW_H = 20;
    private boolean pickerOpen;
    private int pickerX;
    private int pickerY;
    private int pickerW;
    private int pickerH;
    private int pickerHover = -1;

    public PartyScreen() {
        super(Text.literal("Overtone Party"));
    }

    @Override
    protected void init() {
        w = Math.min(PANEL_W, this.width - 16);
        h = Math.min(PANEL_H, this.height - 16);
        x0 = (this.width - w) / 2;
        y0 = (this.height - h) / 2;
        if (y0 < 4) y0 = 4;

        int bw = Math.max(60, (w - 32) / 3);
        int x1 = x0 + 8;
        int x2 = x1 + bw + 8;
        int x3 = x2 + bw + 8;

        int y3 = y0 + h - 8 - FOOTER_BTN;
        int y2 = y3 - (FOOTER_BTN + FOOTER_GAP);
        int y1 = y2 - (FOOTER_BTN + FOOTER_GAP);

        buttons.clear();
        // Обзор: 2 кнопки сверху, обновление и закрытие снизу.
        bStart = addButton(new PButton(x1, y1, bw * 2 + 8, FOOTER_BTN,
                "Начать свою вечеринку", this::openCreateDialog));
        bJoin = addButton(new PButton(x3, y1, bw, FOOTER_BTN,
                "Присоединиться", this::doJoin));
        bRefresh = addButton(new PButton(x1, y2, bw * 2 + 8, FOOTER_BTN,
                "Обновить список", PartyClientNet::sendRequestList));
        bBrowserClose = addButton(new PButton(x1, y3, bw * 3 + 16, FOOTER_BTN,
                "Закрыть", this::close));

        // В вечеринке: знакомые кнопки управления + выход.
        bPause = addButton(new PButton(x1, y1, bw, FOOTER_BTN, "Пауза", PartyClient::togglePause));
        bNext = addButton(new PButton(x2, y1, bw, FOOTER_BTN, "Далее", PartyClient::next));
        bPrev = addButton(new PButton(x3, y1, bw, FOOTER_BTN, "Назад", PartyClient::prev));
        bClear = addButton(new PButton(x1, y2, bw, FOOTER_BTN, "Очистить очередь", PartyClient::clearQueue));
        bDj = addButton(new PButton(x2, y2, bw, FOOTER_BTN, "Диджей +", this::openPicker));
        bListen = addButton(new PButton(x3, y2, bw, FOOTER_BTN, "Слушать", PartyClient::toggleListening));
        bExit = addButton(new PButton(x1, y3, bw * 2 + 8, FOOTER_BTN,
                "Выйти из вечеринки", PartyClient::leaveParty));
        bClose = addButton(new PButton(x3, y3, bw, FOOTER_BTN, "Закрыть", this::close));

        // Геометрия списка: заголовок -> строка -> подсказка/прогресс -> список -> кнопки.
        listX = x0 + 8;
        listW = w - 16;
        listY = y0 + 56;
        listBottomY = y1 - 16;
        listH = Math.max(0, listBottomY - listY);

        passwordField = new TextFieldWidget(textRenderer, x0 + 20, y0 + 80,
                Math.max(80, w - 40), 16, Text.literal("Пароль"));
        passwordField.setMaxLength(64);
        passwordField.setDrawsBackground(true);

        lastIndex = PartyClient.index;
        scrollTarget = centeredScroll(lastIndex);
        scroll = scrollTarget;
        progress = 0f;
        lastBrowser = !PartyClient.member;

        shownAt = System.nanoTime();
        closeAt = 0L;
        afterClose = null;
        lastFrameNs = 0L;
        animAlpha = 1f;

        // Открытие экрана всегда обновляет список — OTHER группы могли появиться.
        try {
            PartyClientNet.sendRequestList();
        } catch (Throwable ignored) {
        }
    }

    private PButton addButton(PButton b) {
        buttons.add(b);
        return b;
    }

    // ---------------------------------------------------------------- render

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        long now = System.nanoTime();
        updateDt(now);
        refreshTheme();

        animStyle = OvertoneBridge.windowAnim();
        boolean off = "OFF".equals(animStyle);
        if (shownAt == 0L) shownAt = now;

        float in = off ? 1f : Math.min(1f, (float) (now - shownAt) / OPEN_NS);
        float out = closeAt == 0L ? 0f : Math.min(1f, (float) (now - closeAt) / CLOSE_NS);
        boolean closing = closeAt != 0L;

        if (closing && out >= 1f) {
            Runnable then = afterClose;
            afterClose = null;
            closeAt = 0L;
            if (then != null) {
                then.run();
                return;
            }
        }

        float eIn = 1f - (1f - in) * (1f - in) * (1f - in);
        float eOut = out * out * (3f - 2f * out);
        float visible = closing ? 1f - eOut : eIn;

        this.renderBackground(ctx);
        drawBackdrop(ctx, visible);

        boolean browser = !PartyClient.member;
        if (browser != lastBrowser) {
            lastBrowser = browser;
            scrollTarget = 0;
            scroll = 0;
            pickerOpen = false;
            dialog = Dialog.NONE;
        }
        updateButtonStates(browser, mouseX, mouseY);

        boolean transformed = !off && (closing || in < 1f);
        if (transformed) {
            applyTransform(ctx, in, out, closing, eIn, eOut);
            animAlpha = animAlphaFor(in, out, closing, eOut);
        } else {
            resetTransform();
            animAlpha = 1f;
        }

        drawPanel(ctx, mouseX, mouseY, browser);
        drawDialog(ctx, mouseX, mouseY, delta);

        if (transformed) {
            animAlpha = 1f;
            ctx.getMatrices().pop();
        }

        if (!transitioning() && dialog == Dialog.NONE && !pickerOpen
                && !browser && hoverAction >= 0 && hoverRow >= 0) {
            drawTooltip(ctx, mouseX, mouseY, ACTION_TOOLTIPS[hoverAction]);
        }
    }

    private void drawPanel(DrawContext ctx, int mouseX, int mouseY, boolean browser) {
        int r = radius;
        roundFill(ctx, x0, y0, w, h, r, cPanel);
        roundRing(ctx, x0, y0, w, h, r, cBorder);
        fill(ctx, x0 + r + 1, y0 + 1, x0 + w - r - 1, y0 + 2, 0x0EFFFFFF);
        roundTopFill(ctx, x0 + 1, y0 + 1, w - 2, 19, r, OvertoneBridge.withAlpha(cAccent, 0x24));

        if (browser) drawHeaderBrowser(ctx);
        else drawHeaderParty(ctx);

        if (OvertoneBridge.available() && listH >= ROW_H) {
            if (browser) drawBrowserList(ctx, mouseX, mouseY);
            else drawList(ctx, mouseX, mouseY, PartyClient.canControl(this.client));
        }
        if (!browser) drawPlayers(ctx);
        drawButtons(ctx, mouseX, mouseY);
        drawPicker(ctx, mouseX, mouseY);
    }

    /** Затемнение фона из настроек Overtone, плавно проявляется вместе с окном. */
    private void drawBackdrop(DrawContext ctx, float visible) {
        int baseAlpha = (cDim >>> 24) & 0xFF;
        int alpha = Math.round(baseAlpha * clamp01(visible));
        if (alpha <= 0) return;
        ctx.fill(0, 0, this.width, this.height, alpha << 24 | cDim & 0xFFFFFF);
    }

    private void refreshTheme() {
        OvertoneBridge.Theme t = OvertoneBridge.theme();
        if (t == null) return;
        cPanel = t.panelTop();
        cPanel2 = t.panel2();
        cBorder = t.border();
        cAccent = t.accent();
        cAccentHover = t.accentHover();
        cTrackBg = t.trackBg();
        cHover = t.hover();
        cRowCurrent = t.rowCurrent();
        cDim = t.dim();
        radius = Math.max(0, Math.min(24, t.radius()));
    }

    private void drawHeaderBrowser(DrawContext ctx) {
        int left = x0 + 10;
        int right = x0 + w - 10;
        text(ctx, "Overtone Party", left, y0 + 6, cAccent);
        String status = PartyClient.listening ? "вы не в вечеринке" : "не слушаю";
        text(ctx, status, right - textRenderer.getWidth(status), y0 + 6,
                PartyClient.listening ? C_DIM : C_WARN);

        int count = PartyClient.partyList.size();
        text(ctx, "Вечеринок на сервере: " + count, left, y0 + 24, C_TEXT);
        String hint = "Выберите вечеринку и нажмите «Присоединиться»";
        text(ctx, clip(hint, w - 20), left, y0 + 40, C_DIMMER);

        if (!OvertoneBridge.available()) {
            text(ctx, "Overtone не найден — установите мод Overtone.", left, y0 + 56, C_ERROR);
        }
    }

    private void drawHeaderParty(DrawContext ctx) {
        int left = x0 + 10;
        int right = x0 + w - 10;
        text(ctx, "Overtone Party", left, y0 + 6, cAccent);

        boolean leader = this.client != null && this.client.player != null
                && this.client.player.getUuid().equals(PartyClient.leader);
        String status;
        if (!PartyClient.listening) status = "не слушаю";
        else if (leader) status = "диджей: вы";
        else if (PartyClient.isCoDj(this.client == null || this.client.player == null ? ""
                : this.client.player.getGameProfile().getName())) status = "со-диджей";
        else status = "диджей: " + (PartyClient.leaderName.isEmpty() ? "нет" : PartyClient.leaderName);
        text(ctx, status, right - textRenderer.getWidth(status), y0 + 6,
                !PartyClient.listening ? C_WARN : C_DIM);

        if (!OvertoneBridge.available()) {
            text(ctx, "Overtone не найден — установите мод Overtone.", left, y0 + 30, C_ERROR);
            return;
        }

        PartyTrack cur = PartyClient.currentTrack();
        String title = cur == null ? "—" : partyName(cur);
        long dur = cur == null ? 0L : cur.durationMs();
        long pos = cur == null ? 0L : PartyClient.positionNow();
        String time = fmt(pos) + " / " + fmt(dur);
        int timeW = textRenderer.getWidth(time);
        text(ctx, clip("Сейчас: " + title, w - 24 - timeW - 8), left, y0 + 24, C_TEXT);
        text(ctx, time, right - timeW, y0 + 24, C_DIM);

        int barY = y0 + 40;
        int barW = w - 20;
        float target = dur > 0L ? clamp01((float) pos / (float) dur) : 0f;
        progress = smooth(progress, target, 14f);
        roundFill(ctx, left, barY, barW, 4, 2, OvertoneBridge.withAlpha(cAccent, 0x33));
        int filled = Math.round(barW * progress);
        if (filled > 0) roundFill(ctx, left, barY, Math.max(2, Math.min(barW, filled)), 4, 2, cAccent);

        String q = PartyClient.paused ? "Очередь · " + PartyClient.tracks.size() + " · пауза"
                : "Очередь · " + PartyClient.tracks.size();
        text(ctx, q, left, barY + 9, C_DIM);
    }

    private void drawPlayers(DrawContext ctx) {
        if (!PartyClient.active || PartyClient.players.isEmpty() || listH < ROW_H) return;
        String pl = "В вечеринке: " + String.join(", ", PartyClient.players);
        if (!PartyClient.coDjs.isEmpty()) pl += " · со-диджеи: " + String.join(", ", PartyClient.coDjs);
        text(ctx, clip(pl, listW), listX, listBottomY + 3, C_DIMMER);
    }

    // ---------------------------------------------------------------- browser list

    private PartyNet.PartyEntry selectedEntry() {
        if (selectedPartyId.equals(new UUID(0L, 0L))) return null;
        for (PartyNet.PartyEntry e : PartyClient.partyList) {
            if (e.id().equals(selectedPartyId)) return e;
        }
        return null;
    }

    private void drawBrowserList(DrawContext ctx, int mouseX, int mouseY) {
        List<PartyNet.PartyEntry> entries = PartyClient.partyList;
        if (selectedEntry() == null) selectedPartyId = new UUID(0L, 0L);

        int rows = entries.size();
        int maxScroll = Math.max(0, rows * PARTY_ROW_H - listH);
        scrollTarget = Math.max(0, Math.min(scrollTarget, maxScroll));
        scroll += (scrollTarget - scroll) * (1.0 - Math.exp(-dt * 10.0));
        scroll = Math.max(0, Math.min(scroll, maxScroll));
        int scrollPx = (int) Math.round(scroll);

        hoverRow = -1;
        if (rows > 0 && mxInList(mouseX, mouseY)) {
            int row = (int) ((mouseY - listY + scroll) / PARTY_ROW_H);
            if (row >= 0 && row < rows) hoverRow = row;
        }

        int listR = Math.min(radius, 8);
        roundFill(ctx, listX, listY, listW, listH, listR, OvertoneBridge.withAlpha(cPanel2, 0x9C));
        roundRing(ctx, listX, listY, listW, listH, listR, cBorder);

        if (rows == 0) {
            String msg = PartyClient.partyList.isEmpty()
                    ? "Вечеринок пока нет. Нажмите «Начать свою вечеринку»."
                    : "Список вечеринок загружается…";
            text(ctx, msg, listX + 8, listY + listH / 2 - 4, C_DIMMER);
            return;
        }

        int sx1 = mapX(listX + 1);
        int sy1 = mapY(listY + 1);
        int sx2 = mapX(listX + listW - 1);
        int sy2 = mapY(listY + listH - 1);
        ctx.enableScissor(Math.min(sx1, sx2), Math.min(sy1, sy2), Math.max(sx1, sx2), Math.max(sy1, sy2));
        for (int i = 0; i < rows; i++) {
            int ry = listY + i * PARTY_ROW_H - scrollPx;
            if (ry + PARTY_ROW_H < listY || ry > listY + listH) continue;
            PartyEntryView.draw(ctx, this, entries.get(i), i, ry);
        }
        ctx.disableScissor();

        if (maxScroll > 0) {
            int trackX = listX + listW - 4;
            int barH = Math.max(16, listH * listH / (rows * PARTY_ROW_H));
            int barY = listY + (int) ((listH - barH) * (scroll / maxScroll));
            roundFill(ctx, trackX, listY + 2, 3, listH - 4, 2, OvertoneBridge.withAlpha(cAccent, 0x22));
            roundFill(ctx, trackX, barY, 3, barH, 2, OvertoneBridge.withAlpha(cAccent, 0x88));
        }
    }

    /** Отрисовка одной строки списка вечеринок. */
    private static final class PartyEntryView {
        static void draw(DrawContext ctx, PartyScreen s, PartyNet.PartyEntry e, int i, int ry) {
            boolean selected = e.id().equals(s.selectedPartyId);
            boolean hover = i == s.hoverRow;
            int rowR = s.small(4);
            if (selected) {
                s.roundFill(ctx, s.listX + 2, ry, s.listW - 4, PARTY_ROW_H - 2, rowR,
                        OvertoneBridge.withAlpha(s.cAccent, 0x33));
                s.roundFill(ctx, s.listX + 4, ry + 5, 2, PARTY_ROW_H - 12, 1, s.cAccent);
            } else if (hover) {
                s.roundFill(ctx, s.listX + 2, ry, s.listW - 4, PARTY_ROW_H - 2, rowR, s.cHover);
            }

            String name = e.name() == null || e.name().isEmpty() ? "Без названия" : e.name();
            String head = (e.privateParty() ? "🔒 " : "") + name;
            s.text(ctx, s.clip(head, s.listW - 100), s.listX + 12, ry + 4,
                    selected ? s.cAccent : C_TEXT);

            String people = e.memberCount() + " чел.";
            s.text(ctx, people, s.listX + s.listW - 8 - s.textRenderer.getWidth(people),
                    ry + 4, selected ? s.cAccent : C_DIM);

            String sub = "диджей: " + (e.ownerName().isEmpty() ? "?" : e.ownerName())
                    + " · треков: " + e.trackCount()
                    + (e.nowPlaying().isEmpty() ? "" : " · " + e.nowPlaying());
            s.text(ctx, s.clip(sub, s.listW - 24), s.listX + 12, ry + 16, C_DIMMER);
        }
    }

    // ---------------------------------------------------------------- queue list

    private void drawList(DrawContext ctx, int mouseX, int mouseY, boolean canControl) {
        List<PartyTrack> list = PartyClient.tracks;
        int rows = list.size();
        int maxScroll = Math.max(0, rows * ROW_H - listH);
        scrollTarget = Math.max(0, Math.min(scrollTarget, maxScroll));

        if (PartyClient.index != lastIndex) {
            lastIndex = PartyClient.index;
            scrollTarget = centeredScroll(lastIndex);
            scrollTarget = Math.max(0, Math.min(scrollTarget, maxScroll));
        }

        scroll += (scrollTarget - scroll) * (1.0 - Math.exp(-dt * 10.0));
        scroll = Math.max(0, Math.min(scroll, maxScroll));
        int scrollPx = (int) Math.round(scroll);

        int groupW = 4 * ACTION_W + 3 * ACTION_GAP;
        int groupStart = listX + listW - 8 - groupW;
        for (int a = 0; a < 4; a++) actionX[a] = groupStart + a * (ACTION_W + ACTION_GAP);

        updateHover(mouseX, mouseY, canControl);

        int listR = Math.min(radius, 8);
        roundFill(ctx, listX, listY, listW, listH, listR, OvertoneBridge.withAlpha(cPanel2, 0x9C));
        roundRing(ctx, listX, listY, listW, listH, listR, cBorder);

        if (rows == 0) {
            actionsGlow = smooth(actionsGlow, 0f, 16f);
            text(ctx, "Очередь пуста. Добавьте трек кнопкой ♪+ в списке Overtone.",
                    listX + 8, listY + listH / 2 - 4, C_DIMMER);
            return;
        }

        if (rowGlow.length < rows) rowGlow = Arrays.copyOf(rowGlow, Math.max(rows, rowGlow.length * 2));
        boolean actionsOn = canControl && hoverRow >= 0;
        actionsGlow = smooth(actionsGlow, actionsOn ? 1f : 0f, 16f);
        if (actionsOn) lastActionRow = hoverRow;

        int sx1 = mapX(listX + 1);
        int sy1 = mapY(listY + 1);
        int sx2 = mapX(listX + listW - 1);
        int sy2 = mapY(listY + listH - 1);
        ctx.enableScissor(Math.min(sx1, sx2), Math.min(sy1, sy2), Math.max(sx1, sx2), Math.max(sy1, sy2));
        for (int i = 0; i < rows; i++) {
            int ry = listY + i * ROW_H - scrollPx;
            if (ry + ROW_H < listY || ry > listY + listH) continue;
            boolean now = i == PartyClient.index;
            float glow = smooth(rowGlow[i], i == hoverRow && !now ? 1f : 0f, 16f);
            rowGlow[i] = glow;
            drawRow(ctx, list.get(i), i, ry, canControl, glow);
        }

        if (canControl && actionsGlow > 0.03f && lastActionRow >= 0 && lastActionRow < rows) {
            int ry = listY + lastActionRow * ROW_H - scrollPx;
            if (ry + ROW_H > listY && ry < listY + listH) drawActions(ctx, lastActionRow, ry + 4);
        } else if (actionsGlow <= 0.03f) {
            Arrays.fill(actionGlow, 0f);
        }
        ctx.disableScissor();

        if (maxScroll > 0) {
            int trackX = listX + listW - 4;
            int barH = Math.max(16, listH * listH / (rows * ROW_H));
            int barY = listY + (int) ((listH - barH) * (scroll / maxScroll));
            roundFill(ctx, trackX, listY + 2, 3, listH - 4, 2, OvertoneBridge.withAlpha(cAccent, 0x22));
            roundFill(ctx, trackX, barY, 3, barH, 2, OvertoneBridge.withAlpha(cAccent, 0x88));
        }
    }

    private void drawRow(DrawContext ctx, PartyTrack t, int i, int ry, boolean canControl, float glow) {
        boolean now = i == PartyClient.index;
        int rowX = listX + 2;
        int rowW = listW - 4;
        int rowR = small(4);

        if (now) {
            roundFill(ctx, rowX, ry, rowW, ROW_H - 1, rowR, cRowCurrent);
            roundFill(ctx, rowX + 2, ry + 5, 2, ROW_H - 11, 1, cAccent);
        } else if (glow > 0.02f) {
            roundFill(ctx, rowX, ry, rowW, ROW_H - 1, rowR, blend(cPanel2, cHover, glow));
        }

        int textY = ry + 7;
        String num = now ? ">" : String.valueOf(i + 1);
        text(ctx, num, listX + 22 - textRenderer.getWidth(num), textY, now ? cAccent : C_DIMMER);

        int textLeft = listX + 28;
        int textRight = listX + listW - 10;
        boolean showActions = canControl && (i == hoverRow || (i == lastActionRow && actionsGlow > 0.03f));
        if (showActions) {
            textRight = actionX[0] - 6;
        } else {
            String d = fmt(t.durationMs());
            int dw = textRenderer.getWidth(d);
            text(ctx, d, listX + listW - 8 - dw, textY, C_DIMMER);
            textRight -= dw + 6;
        }
        text(ctx, clip(partyName(t), Math.max(10, textRight - textLeft)), textLeft, textY,
                now ? cAccent : C_TEXT, now);
    }

    private void drawActions(DrawContext ctx, int row, int y) {
        for (int a = 0; a < 4; a++) {
            boolean hot = hoverAction == a && hoverRow == row;
            actionGlow[a] = smooth(actionGlow[a], hot ? 1f : 0f, 18f);
            drawActionButton(ctx, a, y, row);
        }
    }

    private void drawActionButton(DrawContext ctx, int action, int y, int row) {
        int x = actionX[action];
        boolean enabled = actionEnabled(action, row);
        float glow = actionGlow[action];
        int bg = enabled ? blend(cTrackBg, cAccent, glow) : OvertoneBridge.withAlpha(cTrackBg, 0x80);
        int border = enabled && glow > 0.02f ? blend(cBorder, cAccent, glow) : cBorder;
        int glyphColor = !enabled ? C_DIMMER : (action == 3 ? C_ERROR : C_TEXT);

        roundFill(ctx, x, y, ACTION_W, 14, small(4), mulAlpha(bg, actionsGlow));
        roundRing(ctx, x, y, ACTION_W, 14, small(4), mulAlpha(border, actionsGlow));
        String glyph = ACTION_GLYPHS[action];
        int gw = textRenderer.getWidth(glyph);
        text(ctx, glyph, x + (ACTION_W - gw) / 2, y + 3, mulAlpha(glyphColor, actionsGlow));
    }

    private boolean actionEnabled(int action, int row) {
        return switch (action) {
            case 0 -> row != PartyClient.index;
            case 1 -> row > 0;
            case 2 -> row < PartyClient.tracks.size() - 1;
            case 3 -> true;
            default -> false;
        };
    }

    private void doAction(int row, int action) {
        switch (action) {
            case 0 -> PartyClient.playIndex(row);
            case 1 -> PartyClient.moveTrack(row, row - 1);
            case 2 -> PartyClient.moveTrack(row, row + 1);
            case 3 -> PartyClient.removeTrack(row);
            default -> {
            }
        }
    }

    private void updateHover(double mx, double my, boolean canControl) {
        hoverRow = -1;
        hoverAction = -1;
        if (listH < ROW_H || transitioning()) return;
        if (!mxInList(mx, my)) return;
        int row = (int) ((my - listY + scroll) / ROW_H);
        if (row < 0 || row >= PartyClient.tracks.size()) return;
        hoverRow = row;
        if (!canControl) return;
        int ry = listY + row * ROW_H - (int) Math.round(scroll);
        if (my < ry || my >= ry + ROW_H) return;
        for (int a = 0; a < 4; a++) {
            if (mx >= actionX[a] && mx < actionX[a] + ACTION_W) {
                hoverAction = a;
                return;
            }
        }
    }

    private boolean mxInList(double mx, double my) {
        return mx >= listX && mx < listX + listW && my >= listY && my < listY + listH;
    }

    // ---------------------------------------------------------------- buttons

    private void updateButtonStates(boolean browser, double mouseX, double mouseY) {
        boolean anyVisible = false;
        for (PButton b : buttons) {
            b.visible = false;
            b.enabled = false;
            b.primary = false;
        }
        if (browser) {
            bStart.visible = true;
            bStart.enabled = true;
            bStart.primary = true;
            bStart.label = "Начать свою вечеринку";
            bStart.action = this::openCreateDialog;

            bJoin.visible = true;
            bJoin.enabled = selectedEntry() != null;
            bJoin.primary = bJoin.enabled;
            bJoin.label = "Присоединиться";
            bJoin.action = this::doJoin;

            bRefresh.visible = true;
            bRefresh.enabled = true;
            bRefresh.label = "Обновить список";
            bRefresh.action = PartyClientNet::sendRequestList;

            bBrowserClose.visible = true;
            bBrowserClose.enabled = true;
            bBrowserClose.label = "Закрыть";
            bBrowserClose.action = this::close;
        } else {
            boolean leader = this.client != null && this.client.player != null
                    && this.client.player.getUuid().equals(PartyClient.leader);
            boolean canControl = PartyClient.canControl(this.client);

            bPause.visible = true;
            bPause.label = PartyClient.paused ? "Продолжить" : "Пауза";
            bPause.enabled = canControl;
            bPause.action = PartyClient::togglePause;

            bNext.visible = true;
            bNext.enabled = canControl;
            bNext.label = "Далее";
            bNext.action = PartyClient::next;

            bPrev.visible = true;
            bPrev.enabled = canControl;
            bPrev.label = "Назад";
            bPrev.action = PartyClient::prev;

            bClear.visible = true;
            bClear.enabled = canControl && !PartyClient.tracks.isEmpty();
            bClear.label = "Очистить очередь";
            bClear.action = PartyClient::clearQueue;

            boolean owner = PartyClient.isOwner(this.client);
            bDj.visible = owner;
            bDj.enabled = owner && !pickerCandidates().isEmpty();
            bDj.label = "Диджей +";
            bDj.action = this::openPicker;
            if (!owner) pickerOpen = false;

            bListen.visible = true;
            bListen.label = PartyClient.listening ? "Не слушать" : "Слушать";
            bListen.enabled = !leader;
            bListen.action = PartyClient::toggleListening;

            bExit.visible = true;
            bExit.enabled = true;
            bExit.primary = true;
            bExit.label = "Выйти из вечеринки";
            bExit.action = PartyClient::leaveParty;

            bClose.visible = true;
            bClose.enabled = true;
            bClose.label = "Закрыть";
            bClose.action = this::close;
        }
        for (PButton b : buttons) {
            if (!b.visible) continue;
            anyVisible = true;
            boolean hov = !transitioning() && dialog == Dialog.NONE && !pickerOpen
                    && b.enabled && b.contains(mouseX, mouseY);
            b.glow = smooth(b.glow, hov ? 1f : 0f, 16f);
        }
        if (!anyVisible) return;
    }

    private void drawButtons(DrawContext ctx, int mouseX, int mouseY) {
        boolean blockHover = transitioning() || pickerOpen || dialog != Dialog.NONE;
        for (PButton b : buttons) {
            if (!b.visible) continue;
            boolean hov = !blockHover && b.enabled && b.contains(mouseX, mouseY);
            b.glow = smooth(b.glow, hov ? 1f : 0f, 16f);
            int br = Math.min(radius + 1, b.h / 2);
            int bg;
            int fg;
            if (b.primary) {
                bg = b.enabled ? blend(cAccent, cAccentHover, b.glow) : cTrackBg;
                fg = b.enabled ? 0xFFFFFFFF : C_DIMMER;
                if (b.enabled) roundFill(ctx, b.x, b.y + 1, b.w, b.h, br, 0x73000000);
            } else {
                bg = blend(cTrackBg, cHover, b.glow);
                if (!b.enabled) bg = OvertoneBridge.withAlpha(cTrackBg, 0x80);
                fg = !b.enabled ? C_DIMMER : blend(C_DIM, C_TEXT, b.glow);
            }
            roundFill(ctx, b.x, b.y, b.w, b.h, br, bg);
            if (b.primary && b.enabled) roundRing(ctx, b.x, b.y, b.w, b.h, br, 0x22FFFFFF);
            String label = clip(b.label, b.w - 4);
            int tw = textRenderer.getWidth(label);
            text(ctx, label, b.x + (b.w - tw) / 2, b.y + (b.h - 9) / 2 + 1, fg);
        }
    }

    // ---------------------------------------------------------------- dialogs

    private boolean passwordActive() {
        return dialog == Dialog.JOIN || (dialog == Dialog.CREATE && createPrivate);
    }

    private void openCreateDialog() {
        dialog = Dialog.CREATE;
        createPrivate = false;
        passwordField.setText("");
        layoutDialog();
        passwordField.setFocused(true);
    }

    private void doJoin() {
        PartyNet.PartyEntry e = selectedEntry();
        if (e == null) return;
        if (e.privateParty()) {
            dialog = Dialog.JOIN;
            passwordField.setText("");
            layoutDialog();
            passwordField.setFocused(true);
        } else {
            PartyClient.joinParty(e.id(), "");
        }
    }

    private void layoutDialog() {
        dialogW = Math.min(320, w - 32);
        dialogH = dialog == Dialog.CREATE ? 138 : 116;
        dialogX = x0 + (w - dialogW) / 2;
        dialogY = y0 + (h - dialogH) / 2;
        passwordField.setX(dialogX + 12);
        passwordField.setY(dialogY + (dialog == Dialog.CREATE ? 64 : 42));
        passwordField.setWidth(dialogW - 24);
    }

    private void drawDialog(DrawContext ctx, int mouseX, int mouseY, float delta) {
        if (dialog == Dialog.NONE) return;
        layoutDialog();
        fill(ctx, 0, 0, this.width, this.height, 0x66000000);
        roundFill(ctx, dialogX, dialogY, dialogW, dialogH, radius, cPanel2);
        roundRing(ctx, dialogX, dialogY, dialogW, dialogH, radius, cBorder);

        if (dialog == Dialog.CREATE) {
            text(ctx, "Новая вечеринка", dialogX + 12, dialogY + 8, cAccent);
            drawOption(ctx, 0, "Открытая — войдёт кто угодно", mouseX, mouseY);
            drawOption(ctx, 1, "Приватная — нужен пароль", mouseX, mouseY);
            if (createPrivate) {
                text(ctx, "Пароль:", dialogX + 12, dialogY + 54, C_DIM);
                passwordField.render(ctx, mouseX, mouseY, delta);
            }
            drawDialogButton(ctx, 0, "Создать", true, mouseX, mouseY);
            drawDialogButton(ctx, 1, "Отмена", false, mouseX, mouseY);
        } else {
            PartyNet.PartyEntry e = selectedEntry();
            String name = e == null ? "?" : e.name();
            text(ctx, "Приватная вечеринка", dialogX + 12, dialogY + 8, cAccent);
            text(ctx, clip(name, dialogW - 24), dialogX + 12, dialogY + 24, C_DIM);
            text(ctx, "Введите пароль:", dialogX + 12, dialogY + 40, C_DIM);
            passwordField.render(ctx, mouseX, mouseY, delta);
            drawDialogButton(ctx, 0, "Войти", true, mouseX, mouseY);
            drawDialogButton(ctx, 1, "Отмена", false, mouseX, mouseY);
        }
    }

    private void drawOption(DrawContext ctx, int option, String label, double mx, double my) {
        int y = dialogY + 22 + option * 16;
        boolean on = option == 1 ? createPrivate : !createPrivate;
        boolean hot = mx >= dialogX + 8 && mx < dialogX + dialogW - 8
                && my >= y && my < y + 15;
        roundFill(ctx, dialogX + 8, y, dialogW - 16, 15, small(4),
                on ? OvertoneBridge.withAlpha(cAccent, 0x30) : blend(cTrackBg, cHover, hot ? 1f : 0f));
        String mark = on ? "●" : "○";
        text(ctx, mark + "  " + label, dialogX + 14, y + 3, on ? cAccent : C_DIM);
    }

    private int dialogButtonX(int index) {
        int bw = (dialogW - 28) / 2;
        return index == 0 ? dialogX + 8 : dialogX + 20 + bw;
    }

    private int dialogButtonY() {
        return dialogY + dialogH - 8 - FOOTER_BTN;
    }

    private void drawDialogButton(DrawContext ctx, int index, String label, boolean primary,
                                  double mx, double my) {
        int bx = dialogButtonX(index);
        int by = dialogButtonY();
        int bw = (dialogW - 28) / 2;
        boolean hot = mx >= bx && mx < bx + bw && my >= by && my < by + FOOTER_BTN;
        int bg = primary && hot ? blend(cAccent, cAccentHover, 1f)
                : (hot ? cHover : cTrackBg);
        roundFill(ctx, bx, by, bw, FOOTER_BTN, small(4), bg);
        int tw = textRenderer.getWidth(label);
        text(ctx, label, bx + (bw - tw) / 2, by + 6, hot || primary ? C_TEXT : C_DIM);
    }

    private boolean dialogClick(double mx, double my) {
        layoutDialog();
        if (dialog == Dialog.CREATE) {
            for (int option = 0; option < 2; option++) {
                int y = dialogY + 22 + option * 16;
                if (mx >= dialogX + 8 && mx < dialogX + dialogW - 8 && my >= y && my < y + 15) {
                    createPrivate = option == 1;
                    return true;
                }
            }
            if (createPrivate && passwordField.mouseClicked(mx, my, 0)) return true;
        } else if (passwordField.mouseClicked(mx, my, 0)) {
            return true;
        }
        for (int i = 0; i < 2; i++) {
            int bx = dialogButtonX(i);
            int by = dialogButtonY();
            int bw = (dialogW - 28) / 2;
            if (mx < bx || mx >= bx + bw || my < by || my >= by + FOOTER_BTN) continue;
            if (dialog == Dialog.CREATE) {
                if (i == 0) {
                    PartyClient.createParty(createPrivate, passwordField.getText());
                }
            } else if (i == 0) {
                PartyNet.PartyEntry e = selectedEntry();
                if (e != null) PartyClient.joinParty(e.id(), passwordField.getText());
            }
            dialog = Dialog.NONE;
            return true;
        }
        // Клик вне окна закрывает диалог.
        if (mx < dialogX || mx >= dialogX + dialogW || my < dialogY || my >= dialogY + dialogH) {
            dialog = Dialog.NONE;
        }
        return true;
    }

    // ---------------------------------------------------------------- picker

    private void openPicker() {
        pickerOpen = true;
        layoutPicker();
    }

    private List<String> pickerCandidates() {
        List<String> list = new ArrayList<>();
        for (String p : PartyClient.players) {
            if (p == null || p.isEmpty() || p.equals(PartyClient.ownerName)) continue;
            list.add(p);
        }
        return list;
    }

    private void layoutPicker() {
        int rows = Math.max(1, pickerCandidates().size());
        pickerW = Math.min(280, w - 40);
        pickerH = 24 + rows * PICK_ROW_H + FOOTER_BTN + 16;
        pickerX = x0 + (w - pickerW) / 2;
        pickerY = y0 + Math.max(8, (h - pickerH) / 2);
    }

    private void drawPicker(DrawContext ctx, int mouseX, int mouseY) {
        if (!pickerOpen) return;
        layoutPicker();
        List<String> list = pickerCandidates();
        fill(ctx, x0, y0, x0 + w, y0 + h, 0x88000000);
        roundFill(ctx, pickerX, pickerY, pickerW, pickerH, radius, cPanel2);
        roundRing(ctx, pickerX, pickerY, pickerW, pickerH, radius, cBorder);
        text(ctx, "Кто ещё будет диджеем?", pickerX + 10, pickerY + 8, cAccent);

        pickerHover = -1;
        int ry = pickerY + 24;
        for (int i = 0; i < list.size(); i++) {
            String name = list.get(i);
            boolean co = PartyClient.isCoDj(name);
            boolean hot = inPickerRow(mouseX, mouseY, ry);
            if (hot) pickerHover = i;
            int bg = co ? OvertoneBridge.withAlpha(cAccent, 0x33) : blend(cTrackBg, cHover, hot ? 1f : 0f);
            roundFill(ctx, pickerX + 6, ry, pickerW - 12, PICK_ROW_H - 2, small(4), bg);
            text(ctx, clip(name, pickerW - 96), pickerX + 12, ry + 5, co ? cAccent : C_TEXT);
            String mark = co ? "диджей ✓" : "+";
            int mw = textRenderer.getWidth(mark);
            text(ctx, mark, pickerX + pickerW - 12 - mw, ry + 5, co ? cAccent : C_DIMMER);
            ry += PICK_ROW_H;
        }
        if (list.isEmpty()) text(ctx, "Больше никого нет.", pickerX + 12, ry + 5, C_DIMMER);

        int by = pickerY + pickerH - 8 - FOOTER_BTN;
        int bx = pickerX + 8;
        int bw = pickerW - 16;
        boolean hot = mouseX >= bx && mouseX < bx + bw && mouseY >= by && mouseY < by + FOOTER_BTN;
        roundFill(ctx, bx, by, bw, FOOTER_BTN, Math.min(radius + 1, FOOTER_BTN / 2),
                blend(cTrackBg, cHover, hot ? 1f : 0f));
        String label = "Готово";
        int tw = textRenderer.getWidth(label);
        text(ctx, label, bx + (bw - tw) / 2, by + (FOOTER_BTN - 9) / 2 + 1,
                blend(C_DIM, C_TEXT, hot ? 1f : 0f));
    }

    private boolean inPickerRow(double mx, double my, int ry) {
        return mx >= pickerX + 6 && mx < pickerX + pickerW - 6
                && my >= ry && my < ry + PICK_ROW_H - 2;
    }

    private boolean pickerClick(double mx, double my) {
        layoutPicker();
        List<String> list = pickerCandidates();
        int by = pickerY + pickerH - 8 - FOOTER_BTN;
        int bx = pickerX + 8;
        int bw = pickerW - 16;
        if (mx >= bx && mx < bx + bw && my >= by && my < by + FOOTER_BTN) {
            pickerOpen = false;
            return true;
        }
        int ry = pickerY + 24;
        for (String name : list) {
            if (inPickerRow(mx, my, ry)) {
                PartyClient.toggleCoDj(name);
                return true;
            }
            ry += PICK_ROW_H;
        }
        if (mx < pickerX || mx >= pickerX + pickerW || my < pickerY || my >= pickerY + pickerH) {
            pickerOpen = false;
        }
        return true;
    }

    // ---------------------------------------------------------------- input

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (transitioning() || button != 0) return false;
        if (dialog != Dialog.NONE) return dialogClick(mx, my);
        if (pickerOpen) return pickerClick(mx, my);

        for (PButton b : buttons) {
            if (b.visible && b.enabled && b.contains(mx, my)) {
                b.action.run();
                return true;
            }
        }

        boolean browser = !PartyClient.member;
        if (!mxInList(mx, my)) return false;
        if (browser) {
            int rowH = PARTY_ROW_H;
            int row = (int) ((my - listY + scroll) / rowH);
            List<PartyNet.PartyEntry> entries = PartyClient.partyList;
            if (row < 0 || row >= entries.size()) return false;
            selectedPartyId = entries.get(row).id();
            return true;
        }

        boolean canControl = PartyClient.canControl(this.client);
        updateHover(mx, my, canControl);
        if (hoverRow < 0) return false;
        if (hoverAction >= 0) {
            if (canControl && actionEnabled(hoverAction, hoverRow)) doAction(hoverRow, hoverAction);
            return true;
        }
        if (canControl && PartyClient.active) {
            PartyClient.playIndex(hoverRow);
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double amount) {
        if (transitioning() || dialog != Dialog.NONE || !mxInList(mx, my)) {
            return super.mouseScrolled(mx, my, amount);
        }
        boolean browser = !PartyClient.member;
        int rowH = browser ? PARTY_ROW_H : ROW_H;
        int count = browser ? PartyClient.partyList.size() : PartyClient.tracks.size();
        int maxScroll = Math.max(0, count * rowH - listH);
        scrollTarget -= amount * 30.0;
        scrollTarget = Math.max(0, Math.min(scrollTarget, maxScroll));
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (dialog != Dialog.NONE) {
            if (keyCode == 256) { // Escape
                dialog = Dialog.NONE;
                return true;
            }
            if (keyCode == 257 && passwordActive() && passwordField.isFocused()) { // Enter
                dialogClick(dialogButtonX(0) + 2, dialogButtonY() + 2);
                return true;
            }
            if (passwordActive()) return passwordField.keyPressed(keyCode, scanCode, modifiers);
            return false;
        }
        if (pickerOpen && keyCode == 256) {
            pickerOpen = false;
            return true;
        }
        if (keyCode == 256) {
            close();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char chr, int modifiers) {
        if (passwordActive() && passwordField.isFocused()) {
            return passwordField.charTyped(chr, modifiers);
        }
        return super.charTyped(chr, modifiers);
    }

    /** Закрытие тоже анимируется: сначала окно уезжает, потом экран снимается. */
    @Override
    public void close() {
        if (dialog != Dialog.NONE) {
            dialog = Dialog.NONE;
            return;
        }
        if (pickerOpen) {
            pickerOpen = false;
            return;
        }
        if ("OFF".equals(OvertoneBridge.windowAnim())) {
            super.close();
            return;
        }
        if (closeAt == 0L) {
            closeAt = System.nanoTime();
            afterClose = super::close;
        }
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    // ---------------------------------------------------------------- animation

    private void updateDt(long now) {
        dt = lastFrameNs == 0L ? 0.016f : Math.min(0.1f, (float) (now - lastFrameNs) / 1.0E9f);
        lastFrameNs = now;
    }

    private boolean transitioning() {
        if (closeAt != 0L) return true;
        if ("OFF".equals(animStyle)) return false;
        return shownAt == 0L || (System.nanoTime() - shownAt) < TRANSITION_NS;
    }

    private void applyTransform(DrawContext ctx, float in, float out, boolean closing, float eIn, float eOut) {
        float cx = this.width / 2f;
        float cy = this.height / 2f;
        float kx = 1f;
        float ky = 1f;
        float ty = 0f;

        switch (animStyle) {
            case "SLIDE" -> {
                float k = closing ? 1f - 0.04f * eOut : 0.96f + 0.04f * eIn;
                kx = k;
                ky = k;
                ty = closing ? eOut * this.height * 0.1f
                        : (1f - backOut(in, 1.2f)) * this.height * 0.14f;
            }
            case "POP" -> {
                float k = closing
                        ? 1f - 0.25f * eOut + 0.05f * (float) Math.sin(Math.PI * Math.min(1f, out * 2f))
                        : 0.4f + 0.6f * elasticOut(in);
                kx = k;
                ky = k;
            }
            case "UNFOLD" -> {
                if (closing) {
                    ky = Math.max(0.01f, 1f - Math.min(1f, out * 1.6f));
                    kx = 1f - 0.6f * Math.max(0f, out * 2.5f - 1.5f);
                } else {
                    float a1 = Math.min(1f, in * 2.5f);
                    float a2 = Math.max(0f, (in - 0.3f) / 0.7f);
                    kx = 0.5f + 0.5f * (1f - (1f - a1) * (1f - a1));
                    ky = Math.max(0.01f, backOut(a2, 1.1f));
                }
            }
            default -> {
                float k = closing ? 1f - 0.08f * eOut : 0.86f + 0.14f * backOut(in, 1.4f);
                kx = k;
                ky = k;
                ty = closing ? eOut * 10f : (1f - eIn) * 14f;
            }
        }
        tfKx = kx;
        tfKy = ky;
        tfTy = ty;
        ctx.getMatrices().push();
        ctx.getMatrices().translate(cx, cy + ty, 0.0);
        ctx.getMatrices().scale(kx, ky, 1f);
        ctx.getMatrices().translate(-cx, -cy, 0.0);
    }

    private void resetTransform() {
        tfKx = 1f;
        tfKy = 1f;
        tfTy = 0f;
    }

    private float animAlphaFor(float in, float out, boolean closing, float eOut) {
        return switch (animStyle) {
            case "POP" -> closing ? 1f - eOut : Math.min(1f, in * 4f);
            case "UNFOLD" -> closing ? 1f - Math.max(0f, out * 3f - 2f) : Math.min(1f, in * 3f);
            default -> closing ? 1f - eOut : Math.min(1f, in * 2.2f);
        };
    }

    private int mapX(float x) {
        float cx = this.width / 2f;
        return Math.round(cx + (x - cx) * tfKx);
    }

    private int mapY(float y) {
        float cy = this.height / 2f;
        return Math.round(cy + tfTy + (y - cy) * tfKy);
    }

    private static float backOut(float t, float s) {
        float u = t - 1f;
        return 1f + (s + 1f) * u * u * u + s * u * u;
    }

    private static float elasticOut(float t) {
        if (t <= 0f) return 0f;
        if (t >= 1f) return 1f;
        return (float) (Math.pow(2.0, -9f * t) * Math.sin(((double) (t * 6f) - 0.5) * Math.PI * 2.0 / 3.0) + 1.0);
    }

    private float smooth(float current, float target, float speed) {
        return current + (target - current) * (1f - (float) Math.exp(-dt * speed));
    }

    private double centeredScroll(int index) {
        return Math.max(0.0, index * ROW_H - (listH - ROW_H) / 2.0);
    }

    // ---------------------------------------------------------------- helpers

    private void text(DrawContext ctx, String s, int x, int y, int color) {
        ctx.drawText(this.textRenderer, s, x, y, fade(color), false);
    }

    private void text(DrawContext ctx, String s, int x, int y, int color, boolean shadow) {
        ctx.drawText(this.textRenderer, s, x, y, fade(color), shadow);
    }

    private void fill(DrawContext ctx, int x1, int y1, int x2, int y2, int color) {
        ctx.fill(x1, y1, x2, y2, fade(color));
    }

    private int fade(int color) {
        if (animAlpha >= 0.999f) return color;
        return mulAlpha(color, animAlpha);
    }

    private static int mulAlpha(int color, float k) {
        int a = Math.round(((color >>> 24) & 0xFF) * clamp01(k));
        return a << 24 | color & 0xFFFFFF;
    }

    private int small(int r) {
        return Math.min(r, Math.max(3, radius));
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    private static int blend(int from, int to, float t) {
        t = clamp01(t);
        int a = Math.round(((from >>> 24) & 0xFF) + (((to >>> 24) & 0xFF) - ((from >>> 24) & 0xFF)) * t);
        int r = Math.round(((from >> 16) & 0xFF) + (((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)) * t);
        int g = Math.round(((from >> 8) & 0xFF) + (((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)) * t);
        int b = Math.round((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * t);
        return a << 24 | r << 16 | g << 8 | b;
    }

    // ---------------------------------------------------------- rounded shapes

    private static final int MAX_R = 24;
    private static final int[][] R_INSET = new int[MAX_R + 1][];
    private static final int[][][] R_RUNS = new int[MAX_R + 1][][];

    static {
        for (int r = 1; r <= MAX_R; r++) {
            int size = r * 2 + 1;
            boolean[] in = new boolean[size * size];
            float lim = (r - 0.35f) * (r - 0.35f) + 0.001f;
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    float qx = Math.max(0f, r - Math.min(x + 0.5f, size - x - 0.5f));
                    float qy = Math.max(0f, r - Math.min(y + 0.5f, size - y - 0.5f));
                    in[y * size + x] = qx * qx + qy * qy <= lim;
                }
            }
            int[] inset = new int[r];
            int[][] runs = new int[r][];
            for (int y = 0; y < r; y++) {
                int x = 0;
                while (x < r && !in[y * size + x]) x++;
                inset[y] = x;
                int from = -1;
                int to = -1;
                for (int i = 0; i < r; i++) {
                    if (in[y * size + i] && edge(in, size, i, y)) {
                        if (from < 0) from = i;
                        to = i + 1;
                    }
                }
                runs[y] = from < 0 ? new int[0] : new int[]{from, to};
            }
            R_INSET[r] = inset;
            R_RUNS[r] = runs;
        }
    }

    private static boolean edge(boolean[] in, int size, int x, int y) {
        return x == 0 || y == 0 || x == size - 1 || y == size - 1
                || !in[y * size + x - 1] || !in[y * size + x + 1]
                || !in[(y - 1) * size + x] || !in[(y + 1) * size + x];
    }

    private void roundFill(DrawContext ctx, int x, int y, int w, int h, int r, int color) {
        if (w <= 0 || h <= 0 || (color >>> 24) == 0) return;
        r = Math.max(0, Math.min(Math.min(r, MAX_R), Math.min(w, h) / 2));
        if (r == 0) {
            fill(ctx, x, y, x + w, y + h, color);
            return;
        }
        int[] inset = R_INSET[r];
        int row = 0;
        while (row < r) {
            int in = inset[row];
            int end = row + 1;
            while (end < r && inset[end] == in) end++;
            fill(ctx, x + in, y + row, x + w - in, y + end, color);
            fill(ctx, x + in, y + h - end, x + w - in, y + h - row, color);
            row = end;
        }
        fill(ctx, x, y + r, x + w, y + h - r, color);
    }

    private void roundTopFill(DrawContext ctx, int x, int y, int w, int h, int r, int color) {
        roundFill(ctx, x, y, w, h, r, color);
        if (r > 0 && h > r) fill(ctx, x, y + r, x + w, y + h, color);
    }

    private void roundRing(DrawContext ctx, int x, int y, int w, int h, int r, int color) {
        if (w <= 0 || h <= 0 || (color >>> 24) == 0) return;
        r = Math.max(0, Math.min(Math.min(r, MAX_R), Math.min(w, h) / 2));
        if (r == 0) {
            fill(ctx, x, y, x + w, y + 1, color);
            fill(ctx, x, y + h - 1, x + w, y + h, color);
            fill(ctx, x, y + 1, x + 1, y + h - 1, color);
            fill(ctx, x + w - 1, y + 1, x + w, y + h - 1, color);
            return;
        }
        int top = R_INSET[r][0];
        fill(ctx, x + top, y, x + w - top, y + 1, color);
        fill(ctx, x + top, y + h - 1, x + w - top, y + h, color);
        for (int row = 1; row < r; row++) {
            int[] run = R_RUNS[r][row];
            if (run.length == 0) continue;
            int a = run[0];
            int b = run[1];
            fill(ctx, x + a, y + row, x + b, y + row + 1, color);
            fill(ctx, x + w - b, y + row, x + w - a, y + row + 1, color);
            fill(ctx, x + a, y + h - 1 - row, x + b, y + h - row, color);
            fill(ctx, x + w - b, y + h - 1 - row, x + w - a, y + h - row, color);
        }
        fill(ctx, x, y + r, x + 1, y + h - r, color);
        fill(ctx, x + w - 1, y + r, x + w, y + h - r, color);
    }

    // ---------------------------------------------------------------- misc

    private String clip(String s, int maxWidth) {
        if (s == null) return "";
        if (maxWidth <= 0 || textRenderer.getWidth(s) <= maxWidth) return s;
        return textRenderer.trimToWidth(s, Math.max(4, maxWidth - 6)) + "…";
    }

    private void drawTooltip(DrawContext ctx, int mx, int my, String textValue) {
        int tw = textRenderer.getWidth(textValue);
        int bx = mx + 10;
        int by = my + 10;
        if (bx + tw + 8 > this.width) bx = this.width - tw - 8;
        if (by + 16 > this.height) by = this.height - 16;
        int r = small(4);
        roundFill(ctx, bx, by, tw + 8, 15, r, OvertoneBridge.withAlpha(cPanel2, 0xF0));
        roundRing(ctx, bx, by, tw + 8, 15, r, cBorder);
        text(ctx, textValue, bx + 4, by + 4, C_TEXT);
    }

    private static String partyName(PartyTrack t) {
        String title = t.title() == null ? "" : t.title();
        String artist = t.artist() == null ? "" : t.artist();
        if (title.isEmpty()) title = t.id() == null ? "?" : t.id();
        return artist.isEmpty() ? title : title + " — " + artist;
    }

    private static String fmt(long ms) {
        long s = Math.max(0L, ms / 1000L);
        return String.format("%d:%02d", s / 60L, s % 60L);
    }

    /** Кнопка в стиле Overtone: GHOST или PRIMARY, со сглаженным hover. */
    private static final class PButton {
        final int x;
        final int y;
        final int w;
        final int h;
        Runnable action;
        String label;
        boolean enabled = true;
        boolean visible = true;
        boolean primary;
        float glow;

        PButton(int x, int y, int w, int h, String label, Runnable action) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.label = label;
            this.action = action;
        }

        boolean contains(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }
}
