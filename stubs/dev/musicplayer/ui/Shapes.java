package dev.musicplayer.ui;

import dev.musicplayer.compat.Canvas;

/**
 * Compile-time stub of Overtone's rounded-rectangle painter.
 *
 * <p>Used by the mixin so the «в вечеринку» button gets exactly the same
 * corner radius as the rest of the Overtone UI.
 */
public final class Shapes {
    private Shapes() {
    }

    public static void fill(Canvas g, int x, int y, int w, int h, int r, int argb) {
        throw new AssertionError();
    }

    public static void ring(Canvas g, int x, int y, int w, int h, int r, int argb) {
        throw new AssertionError();
    }
}
