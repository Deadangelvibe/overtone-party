package dev.musicplayer.compat;

/**
 * Compile-time stub of Overtone's drawing canvas. Only the methods our
 * integration uses are declared; they are not shipped.
 */
public abstract class Canvas {
    public abstract void fill(int x1, int y1, int x2, int y2, int argb);

    public void rect(int x, int y, int width, int height, int argb) {
    }

    public void text(String value, int x, int y, int argb, boolean shadow) {
    }

    public int textWidth(String value) {
        return 0;
    }
}
