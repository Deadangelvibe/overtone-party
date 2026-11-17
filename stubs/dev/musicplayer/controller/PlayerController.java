package dev.musicplayer.controller;

import dev.musicplayer.model.Track;
import dev.musicplayer.model.Platform;
import dev.musicplayer.provider.MusicProvider;
import dev.musicplayer.provider.SoundCloudProvider;

import java.util.List;

/** Compile-time stub. The real singleton lives in Overtone. */
public class PlayerController {
    public static PlayerController I;

    public final SoundCloudProvider soundcloud = null;

    public volatile Platform platform;
    public volatile Track current;
    public volatile float volume;
    public volatile boolean idle;

    public synchronized void playFrom(List<Track> list, int i) {
    }

    public void togglePause() {
    }

    public void seekTo(long ms) {
    }

    public void seekBy(long ms) {
    }

    public void next() {
    }

    public synchronized void prev() {
    }

    public void setVolume(float v) {
    }

    public void addToQueue(Track t) {
    }

    public void clearQueue() {
    }

    public List<Track> queueSnapshot() {
        return null;
    }

    public MusicProvider provider(Track t) {
        return null;
    }

    public MusicProvider provider(Platform platform) {
        return null;
    }

    public MusicProvider engine() {
        return null;
    }
}
