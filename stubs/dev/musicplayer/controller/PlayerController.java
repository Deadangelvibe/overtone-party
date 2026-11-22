package dev.musicplayer.controller;

import dev.musicplayer.model.MusicEntity;
import dev.musicplayer.model.Page;
import dev.musicplayer.model.Platform;
import dev.musicplayer.model.Track;
import dev.musicplayer.provider.MusicProvider;
import dev.musicplayer.provider.SoundCloudProvider;
import dev.musicplayer.provider.YandexMusicProvider;

import java.util.ArrayList;
import java.util.List;

/** Compile-time stub. The real singleton lives in Overtone. */
public class PlayerController {
    public static PlayerController I;

    public final SoundCloudProvider soundcloud = null;
    public final YandexMusicProvider yandex = null;

    public volatile Platform platform;
    public volatile Track current;
    public volatile float volume;
    public volatile boolean idle;
    public volatile String status;
    public volatile boolean waveView;
    public volatile List<Track> results;

    public volatile Tab tab;
    public volatile List<MusicEntity> artists;
    public volatile List<MusicEntity> albums;
    public volatile List<MusicEntity> playlists;
    public volatile List<MusicEntity> stations;

    private final List<Page> trail = new ArrayList<>();

    public enum Tab {
        TRACKS, ARTISTS, ALBUMS, PLAYLISTS, STATIONS
    }

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

    public Page page() {
        return null;
    }

    public void open(MusicEntity entity) {
    }

    public void back() {
    }
}
