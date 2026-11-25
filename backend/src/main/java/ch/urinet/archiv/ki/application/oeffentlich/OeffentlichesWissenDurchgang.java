package ch.urinet.archiv.ki.application.oeffentlich;

import ch.urinet.archiv.wissen.application.OeffentlichesWissenPort;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Alle Informationen, die das Modell in dieser Frage über Wissensseiten gesehen hat. */
public final class OeffentlichesWissenDurchgang {
    private final Map<UUID, OeffentlichesWissenPort.Stand> gesehen = new LinkedHashMap<>();
    private final Map<UUID, OeffentlichesWissenPort.Seite> gezeigt = new LinkedHashMap<>();
    private final Map<UUID, OeffentlichesWissenPort.Seite> dieseRunde = new LinkedHashMap<>();
    private boolean widerspruch;

    public OeffentlichesWissenDurchgang() {
    }

    public OeffentlichesWissenDurchgang(List<OeffentlichesWissenPort.Stand> staende,
                                        List<OeffentlichesWissenPort.Seite> seiten) {
        staende.forEach(this::merken);
        seiten.forEach(seite -> {
            merken(seite.version());
            gezeigt.put(seite.version().id(), seite);
        });
    }

    public synchronized void merken(OeffentlichesWissenPort.Stand stand) {
        var bisher = gesehen.putIfAbsent(stand.id(), stand);
        if (bisher != null && !bisher.equals(stand)) {
            widerspruch = true;
        }
    }

    public synchronized void zeigen(OeffentlichesWissenPort.Seite seite) {
        merken(seite.version());
        gezeigt.put(seite.version().id(), seite);
        dieseRunde.put(seite.version().id(), seite);
    }

    public synchronized List<OeffentlichesWissenPort.Stand> gesehene() {
        return List.copyOf(gesehen.values());
    }

    public synchronized List<OeffentlichesWissenPort.Seite> gezeigte() {
        return List.copyOf(gezeigt.values());
    }

    /** Nur die Seiten, die das Modell in dieser Runde gezeigt hat (nicht die aus früheren Runden des Gesprächs). */
    public synchronized List<OeffentlichesWissenPort.Seite> gezeigteDieserRunde() {
        return List.copyOf(dieseRunde.values());
    }

    public synchronized boolean widerspruch() {
        return widerspruch;
    }
}
