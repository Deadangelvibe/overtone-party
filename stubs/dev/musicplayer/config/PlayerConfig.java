package dev.musicplayer.config;

/** Compile-time stub of Overtone's player config. Only the fields we read. */
public final class PlayerConfig {
    public boolean syncAccent = true;
    public int customAccent = -1;
    public int tint = 1;
    public int windowOpacity = 100;
    public int dim = 45;
    public int cornerRadius = 4;
    public boolean accentText = true;
    public WindowAnim windowAnim = WindowAnim.ZOOM;

    public int accentRgb() {
        return 0x9AE6B4;
    }

    public static PlayerConfig get() {
        return null;
    }

    /** Mirrors {@code dev.musicplayer.config.PlayerConfig.WindowAnim}. */
    public enum WindowAnim {
        ZOOM,
        SLIDE,
        POP,
        UNFOLD,
        OFF
    }
}
