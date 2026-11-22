package dev.overtoneparty.integration;

import dev.musicplayer.model.Track;
import dev.overtoneparty.OvertoneParty;
import dev.overtoneparty.client.PartyClient;
import dev.overtoneparty.model.PartyTrack;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Общая отправка списка треков в очередь вечеринки. */
public final class PartyQueue {
    private PartyQueue() {
    }

    /**
     * Отфильтровывает пустые и повторные треки, отправляет их в общую очередь
     * и возвращает количество реально добавленных треков.
     */
    public static int addTracks(List<Track> tracks, String label) {
        if (tracks == null || tracks.isEmpty()) return 0;
        List<PartyTrack> partyTracks = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Track track : tracks) {
            PartyTrack pt = PartyTrack.from(track);
            if (PartyTrack.isBlank(pt)) continue;
            String key = PartyTrack.providerKey(pt.provider()) + '|' + pt.id();
            if (seen.add(key)) partyTracks.add(pt);
        }
        if (partyTracks.isEmpty()) return 0;
        PartyClient.addTracks(partyTracks);
        OvertoneParty.LOGGER.info("{}: добавлено треков в очередь — {}", label, partyTracks.size());
        return partyTracks.size();
    }
}
