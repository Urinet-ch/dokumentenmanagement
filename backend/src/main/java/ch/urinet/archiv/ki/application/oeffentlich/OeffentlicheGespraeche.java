package ch.urinet.archiv.ki.application.oeffentlich;

import ch.urinet.archiv.integration.domain.webseite.Lesezugriff;
import ch.urinet.archiv.ki.domain.chat.ChatRolle;
import ch.urinet.archiv.ki.domain.chat.VerlaufEintrag;
import ch.urinet.archiv.ki.domain.oeffentlich.FrageBereinigung;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.wissen.application.OeffentlichesWissenPort;
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
 * Heap und verschwinden beim Neustart. Ein Gespräch gehört zu Mandant, Webseite, Client-Hash und wirksamer Zugriffsstufe (ein Stufenwechsel beginnt ein neues Gespräch, damit Antworttexte einer höheren Stufe nie in den Verlauf einer niedrigeren gelangen); eine fremde, unbekannte
 * oder abgelaufene ID beginnt still ein neues Gespräch (kein Hinweis, ob es die ID gibt). Höchstens
 * {@code archiv.webseite.chat.max-gespraeche} Gespräche; bei Überlauf fällt das älteste weg.
 */
@ApplicationScoped
public class OeffentlicheGespraeche {

    /** Höchste Länge einer gemerkten Antwort in Zeichen (die Antwort selbst ist durch max-tokens begrenzt). */
    static final int ANTWORT_MAX = 4_000;

    private record Gespraech(String bindung, List<VerlaufEintrag> verlauf, Instant geaendert,
                             List<OeffentlichesWissenPort.Stand> wissensStaende,
                             List<OeffentlichesWissenPort.Seite> wissensSeiten) {
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
    public record Geoeffnet(UUID id, List<VerlaufEintrag> verlauf,
                            List<OeffentlichesWissenPort.Stand> wissensStaende,
                            List<OeffentlichesWissenPort.Seite> wissensSeiten) {
        public Geoeffnet {
            verlauf = List.copyOf(verlauf);
            wissensStaende = List.copyOf(wissensStaende);
            wissensSeiten = List.copyOf(wissensSeiten);
        }

        public Geoeffnet(UUID id, List<VerlaufEintrag> verlauf) {
            this(id, verlauf, List.of(), List.of());
        }
    }

    /** Öffnet das Gespräch {@code id} für diese Bindung oder, wenn es nicht passt, ein neues. */
    public synchronized Geoeffnet oeffnen(UUID id, MandantCode mandant, Lesezugriff zugriff) {
        return oeffnen(id, mandant, zugriff, false);
    }

    public synchronized Geoeffnet oeffnenWissen(UUID id, MandantCode mandant, Lesezugriff zugriff) {
        return oeffnen(id, mandant, zugriff, true);
    }

    private Geoeffnet oeffnen(UUID id, MandantCode mandant, Lesezugriff zugriff, boolean wissen) {
        abgelaufeneEntfernen();
        if (id != null) {
            Gespraech g = gespraeche.get(id);
            if (g != null && g.bindung().equals(bindung(mandant, zugriff, wissen))) {
                return new Geoeffnet(id, VerlaufEintrag.normalisieren(g.verlauf()),
                        g.wissensStaende(), g.wissensSeiten());
            }
        }
        return new Geoeffnet(UUID.randomUUID(), List.of());
    }

    /** Merkt Frage und Antwort einer erfolgreichen Runde; nur die letzten {@code fenster} Nachrichten bleiben. */
    public synchronized void merken(UUID id, MandantCode mandant, Lesezugriff zugriff, String frage, String antwort) {
        merken(id, mandant, zugriff, frage, antwort, false, List.of(), List.of());
    }

    public synchronized void merkenWissen(UUID id, MandantCode mandant, Lesezugriff zugriff, String frage,
                                           String antwort, List<OeffentlichesWissenPort.Stand> staende,
                                           List<OeffentlichesWissenPort.Seite> seiten) {
        merken(id, mandant, zugriff, FrageBereinigung.fuerSpeicher(frage),
                FrageBereinigung.fuerSpeicher(antwort), true, staende, seiten);
    }

    private void merken(UUID id, MandantCode mandant, Lesezugriff zugriff, String frage, String antwort,
                        boolean wissen, List<OeffentlichesWissenPort.Stand> neueStaende,
                        List<OeffentlichesWissenPort.Seite> neueSeiten) {
        abgelaufeneEntfernen();
        String bindung = bindung(mandant, zugriff, wissen);
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
        Map<UUID, OeffentlichesWissenPort.Stand> staende = new LinkedHashMap<>();
        if (alt != null && alt.bindung().equals(bindung)) {
            alt.wissensStaende().forEach(s -> staende.put(s.id(), s));
        }
        neueStaende.forEach(s -> staende.put(s.id(), s));
        Map<UUID, OeffentlichesWissenPort.Seite> seiten = new LinkedHashMap<>();
        if (alt != null && alt.bindung().equals(bindung)) {
            alt.wissensSeiten().forEach(s -> seiten.put(s.version().id(), ohneInhalt(s)));
        }
        neueSeiten.forEach(s -> seiten.put(s.version().id(), ohneInhalt(s)));
        gespraeche.remove(id);
        gespraeche.put(id, new Gespraech(bindung, List.copyOf(verlauf), uhr.instant(),
                List.copyOf(staende.values()), List.copyOf(seiten.values())));
        while (gespraeche.size() > maxGespraeche) {
            gespraeche.remove(gespraeche.keySet().iterator().next());
        }
    }

    /** Verwirft den Verlauf des Gesprächs {@code id} (z. B. nach Entzug einer Freigabe). */
    public synchronized void verwerfen(UUID id) {
        if (id != null) {
            gespraeche.remove(id);
        }
    }

    /** Zahl der aktuell im Speicher gehaltenen Gespräche (für Tests, ohne Aufräumen). */
    public synchronized int anzahl() {
        return gespraeche.size();
    }

    public synchronized void abgelaufeneAufraeumen() {
        abgelaufeneEntfernen();
    }

    private void abgelaufeneEntfernen() {
        Instant grenze = uhr.instant().minus(dauer);
        gespraeche.values().removeIf(g -> !g.geaendert().isAfter(grenze));
    }

    private static OeffentlichesWissenPort.Seite ohneInhalt(OeffentlichesWissenPort.Seite seite) {
        return new OeffentlichesWissenPort.Seite(seite.version(), seite.pfad(), seite.titel(), seite.typ(), "",
                seite.freigegebenAm(), seite.dokumente());
    }

    private static String bindung(MandantCode mandant, Lesezugriff zugriff, boolean wissen) {
        return mandant.wert() + "\n" + zugriff.webseite() + "\n" + zugriff.clientHash().orElse("-") + "\n"
                + zugriff.wirksameStufe().name() + "\n" + wissen;
    }
}
