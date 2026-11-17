package dev.musicplayer.ui;

import dev.musicplayer.compat.Canvas;
import dev.musicplayer.controller.PlayerController;
import dev.musicplayer.model.Track;

import java.util.List;

/**
 * Compile-time stub of Overtone's player screen. Signatures mirror the real
 * class in overtone-0.2.2; the mixin only ever sees the real one at runtime.
 */
public class PlayerScreen {
    private final PlayerController ctl = PlayerController.I;
    private int lx;
    private int lw;
    private int listTop;
    private int rows;
    private int page;
    private boolean queueMode;
    private int mouseX;
    private int mouseY;

    private List<?> items() {
        return List.of();
    }

    private Track trackAt(int idx) {
        return null;
    }

    private void rowButton(int x, int y, int w, Runnable action) {
    }

    private int viewSig() {
        return 0;
    }

    private void buildMain(String oldQuery) {
    }

    private void drawList(Canvas g) {
    }
}
