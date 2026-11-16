package ch.urinet.archiv.ki.application.oeffentlich;

import ch.urinet.archiv.integration.domain.webseite.Lesezugriff;
import ch.urinet.archiv.ki.domain.chat.ChatRolle;
import ch.urinet.archiv.ki.domain.chat.VerlaufEintrag;
import ch.urinet.archiv.mandant.domain.MandantCode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Verlauf der öffentlichen Gespräche, nur im Speicher dieser Instanz (Spec 03, design.md «OeffentlicherChatAgent»:
 * Gesprächsspeicher 6 Nachrichten; Req 6.5: keine Gesprächsinhalte mit Personenbezug gespeichert). Es gibt keine Tabelle:
 * Fragen und Antworten liegen höchstens {@code archiv.webseite.chat.gespraech-dauer} (30 Minuten ab der letzten Frage) im
 * Heap und verschwinden beim Neustart. Ein Gespräch gehört zu Mandant, Webseite und Client-Hash; eine fremde, unbekannte
 * oder abgelaufene ID beginnt still ein neues Gespräch (kein Hinweis, ob es die ID gibt). Höchstens
 * {@code archiv.webseite.chat.max-gespraeche} Gespräche; bei Überlauf fällt das älteste weg.
 */
@ApplicationScoped
public class OeffentlicheGespraeche {

    /** Höchste Länge einer gemerkten Antwort in Zeichen (die Antwort selbst ist durch max-tokens begrenzt). */
    static final int ANTWORT_MAX = 4_000;

    private record Gespraech(String bindung, List<VerlaufEintrag> verlauf, Instant geaendert) {
    }

    private final Map<UUID, Gespraech> gespraeche = new LinkedHashMap<>();
    private final Duration dauer;
    private final int maxGespraeche;
    private final int fenster;
    private final Clock uhr;

    @Inject
    public OeffentlicheGespraeche(@ConfigProperty(name = "archiv.webseite.chat.gespraech-dauer") Duration dauer,
                                  @ConfigProperty(name = "archiv.webseite.chat.max-gespraeche") int maxGespraeche,
                                  @ConfigProperty(name = "archiv.webseite.chat.fenster") int fenster) {
        this(dauer, maxGespraeche, fenster, Clock.systemUTC());
    }

    OeffentlicheGespraeche(Duration dauer, int maxGespraeche, int fenster, Clock uhr) {
        if (dauer.isNegative() || dauer.isZero() || maxGespraeche < 1 || fenster < 2) {
            throw new IllegalArgumentException("archiv.webseite.chat.(gespraech-dauer|max-gespraeche|fenster) ungültig");
        }
        this.dauer = dauer;
        this.maxGespraeche = maxGespraeche;
        this.fenster = fenster;
        this.uhr = uhr;
    }

    /** Ergebnis von {@link #oeffnen}: die Gesprächs-ID dieser Frage und der Verlauf davor. */
    public record Geoeffnet(UUID id, List<VerlaufEintrag> verlauf) {
    }

    /** Öffnet das Gespräch {@code id} für diese Bindung oder, wenn es nicht passt, ein neues. */
    public synchronized Geoeffnet oeffnen(UUID id, MandantCode mandant, Lesezugriff zugriff) {
        abgelaufeneEntfernen();
        if (id != null) {
            Gespraech g = gespraeche.get(id);
            if (g != null && g.bindung().equals(bindung(mandant, zugriff))) {
                return new Geoeffnet(id, VerlaufEintrag.normalisieren(g.verlauf()));
            }
        }
        return new Geoeffnet(UUID.randomUUID(), List.of());
    }

    /** Merkt Frage und Antwort einer erfolgreichen Runde; nur die letzten {@code fenster} Nachrichten bleiben. */
    public synchronized void merken(UUID id, MandantCode mandant, Lesezugriff zugriff, String frage, String antwort) {
        abgelaufeneEntfernen();
        String bindung = bindung(mandant, zugriff);
        Gespraech alt = gespraeche.get(id);
        List<VerlaufEintrag> verlauf = alt != null && alt.bindung().equals(bindung) ? new ArrayList<>(alt.verlauf())
                : new ArrayList<>();
        verlauf.add(new VerlaufEintrag(ChatRolle.BENUTZER, frage));
        if (!antwort.isBlank()) {
            verlauf.add(new VerlaufEintrag(ChatRolle.ASSISTENT,
                    antwort.length() > ANTWORT_MAX ? antwort.substring(0, ANTWORT_MAX) : antwort));
        }
        if (verlauf.size() > fenster) {
            verlauf = new ArrayList<>(verlauf.subList(verlauf.size() - fenster, verlauf.size()));
        }
        gespraeche.remove(id);
        gespraeche.put(id, new Gespraech(bindung, List.copyOf(verlauf), uhr.instant()));
        while (gespraeche.size() > maxGespraeche) {
            gespraeche.remove(gespraeche.keySet().iterator().next());
        }
    }

    /** Zahl der gemerkten Gespräche (für Tests). */
    public synchronized int anzahl() {
        abgelaufeneEntfernen();
        return gespraeche.size();
    }

    private void abgelaufeneEntfernen() {
        Instant grenze = uhr.instant().minus(dauer);
        gespraeche.values().removeIf(g -> !g.geaendert().isAfter(grenze));
    }

    private static String bindung(MandantCode mandant, Lesezugriff zugriff) {
        return mandant.wert() + "\n" + zugriff.webseite() + "\n" + zugriff.clientHash().orElse("-");
    }
}
