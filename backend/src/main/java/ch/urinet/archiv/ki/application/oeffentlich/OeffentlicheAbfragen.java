package ch.urinet.archiv.ki.application.oeffentlich;

import ch.urinet.archiv.integration.domain.webseite.Lesezugriff;
import ch.urinet.archiv.ki.application.chat.DokumentZeigen;
import ch.urinet.archiv.ki.application.chat.WerkzeugDaten;
import ch.urinet.archiv.ki.application.chat.WerkzeugFehler;
import ch.urinet.archiv.ki.application.chat.WerkzeugTransaktion;
import ch.urinet.archiv.sicherheit.domain.Sichtregel;
import ch.urinet.archiv.suche.application.HybridSuche;
import ch.urinet.archiv.suche.application.SuchePort;
import ch.urinet.archiv.suche.domain.AuszugTeil;
import ch.urinet.archiv.suche.domain.Fundstelle;
import ch.urinet.archiv.suche.domain.Hybridergebnis;
import ch.urinet.archiv.suche.domain.Hybridtreffer;
import ch.urinet.archiv.suche.domain.Suchanfrage;
import ch.urinet.archiv.suche.domain.Suchergebnis;
import ch.urinet.archiv.suche.domain.Suchtreffer;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Die beiden Werkzeuge des öffentlichen Chatbots (Spec 03, Req 6.1; design.md «OeffentlicherChatAgent»):
 * {@code dokumente_suchen} und {@code dokument_zeigen}, nur lesend, nur Dokumente, die der feste Filter bis zur Stufe des
 * {@link Lesezugriff} liefert. Zwei Schichten: Die Suche filtert in der Datenbank ({@link Sichtregel#fuerWebseite}), und jeder
 * Treffer wird danach nochmals über {@link OeffentlicheDokumente} geprüft und dort mit Titel, Reihe und Ausgabe
 * angereichert; was der Filter nicht liefert, fehlt. Antworten tragen nur Titel, Reihe, Ausgabe, Jahr, Auszug und
 * Fundstelle: nie Stufe, Status, Vertraulichkeit, Dateiname, Partner, Beträge oder Belegnummern. Titel, Reihe und Ausgabe
 * sind fremde Daten (Erfassung durch Personen, Import) und gehen einzeilig und ohne Rahmenmarker an das Modell; Auszug
 * und Inhalt kennzeichnet der Adapter als Daten ({@code DokumentTexte}). {@code dokument_zeigen} nimmt nur Dokumente aus
 * {@code dokumente_suchen} derselben Frage.
 */
@ApplicationScoped
public class OeffentlicheAbfragen {

    private static final Logger LOG = Logger.getLogger(OeffentlicheAbfragen.class);

    public static final int FRAGE_MAX = Suchanfrage.BEGRIFF_MAX;
    public static final int ANZAHL_STANDARD = 5;
    public static final int ANZAHL_MAX = 5;
    public static final int INHALT_MAX = DokumentZeigen.INHALT_MAX;
    /** Obergrenze für Titel, Reihe und Ausgabe gegenüber dem Modell. */
    static final int TITEL_MAX = 200;
    static final String NUR_VOLLTEXT = "nur Volltext";

    private static final Pattern REIHE = Pattern.compile("^[A-Za-z][A-Za-z0-9_]{1,39}$");

    /** Treffer der Suche; {@code nummer} gilt nur in dieser Frage. */
    public record Treffer(Integer nummer, String id, String titel, String reihe, String ausgabe, Integer jahr,
                          String auszug, String seiten) {
        public Treffer mitNummer(int n) {
            return new Treffer(n, id, titel, reihe, ausgabe, jahr, auszug, seiten);
        }
    }

    public record Antwort(List<Treffer> treffer, long gesamt, String hinweis, Boolean degradiert) {
        public Antwort {
            treffer = List.copyOf(treffer);
        }
    }

    /** Ein sichtbares Dokument; {@code inhalt} ist der gezeigte Anfang des Volltexts. */
    public record Ansicht(String id, String titel, String reihe, String ausgabe, Integer jahr, String inhalt,
                          Boolean inhaltGekuerzt, Integer inhaltZeichen, String inhaltHinweis) {
    }

    private final SuchePort suche;
    private final HybridSuche hybrid;
    private final OeffentlicheDokumente dokumente;
    private final WerkzeugDaten daten;
    private final WerkzeugTransaktion transaktion;
    private final double minRelevanz;

    public OeffentlicheAbfragen(SuchePort suche, HybridSuche hybrid, OeffentlicheDokumente dokumente,
                                WerkzeugDaten daten, WerkzeugTransaktion transaktion,
                                @ConfigProperty(name = "archiv.webseite.chat.min-relevanz") double minRelevanz) {
        if (!Double.isFinite(minRelevanz) || minRelevanz < 0 || minRelevanz > 1) {
            throw new IllegalArgumentException("archiv.webseite.chat.min-relevanz muss zwischen 0 und 1 liegen");
        }
        this.suche = suche;
        this.hybrid = hybrid;
        this.dokumente = dokumente;
        this.daten = daten;
        this.transaktion = transaktion;
        this.minRelevanz = minRelevanz;
    }

    /** {@code dokumente_suchen}: Parameter des Modells werden geprüft, bevor die Datenbank etwas sieht. */
    public Antwort suchen(OeffentlicherKontext kontext, String frage, String reihe, Integer anzahl) {
        String begriff = text("frage", frage, FRAGE_MAX, true);
        String art = text("reihe", reihe, 40, false);
        if (art != null) {
            if (!REIHE.matcher(art).matches()) {
                throw WerkzeugFehler.ungueltig("reihe");
            }
            art = art.toUpperCase(java.util.Locale.ROOT);
        }
        int n = anzahl == null ? ANZAHL_STANDARD : anzahl;
        if (n < 1 || n > ANZAHL_MAX) {
            throw WerkzeugFehler.ungueltig("anzahl");
        }
        Suchanfrage anfrage;
        try {
            anfrage = new Suchanfrage(begriff, art, null, null, null, null, null, null, null, null, null, null, null, 0, n);
        } catch (RuntimeException e) {
            throw WerkzeugFehler.ungueltig("frage");
        }
        Lesezugriff zugriff = kontext.zugriff();
        Sichtregel sicht = Sichtregel.fuerWebseite(new Sichtregel.Webseite(zugriff.stufen(), null, null));
        Hybridergebnis ergebnis = hybridSuchen(anfrage, sicht, n);
        List<Suchtreffer> treffer;
        Map<UUID, Fundstelle> fundstellen = new LinkedHashMap<>();
        boolean degradiert;
        long gesamt;
        if (ergebnis != null) {
            treffer = belegteTreffer(ergebnis, begriff, minRelevanz).stream().map(h -> {
                if (h.fundstelle() != null) {
                    fundstellen.put(h.treffer().id(), h.fundstelle());
                }
                return h.treffer();
            }).toList();
            degradiert = ergebnis.degradiert();
            gesamt = treffer.size();
        } else {
            Suchergebnis volltext = transaktion.neu(() -> {
                daten.nurLesend();
                return suche.suchen(anfrage, sicht, false);
            });
            // Rückfall nach einer Ausnahme: ohne Bewertung gilt dieselbe Regel wie im degradierten Hybridergebnis
            treffer = volltext.treffer().stream().filter(t -> exakteNummer(begriff, t)).toList();
            degradiert = true;
            gesamt = treffer.size();
        }
        // zweite Schicht: jeder Treffer nochmals über den festen Filter, mit Titel, Reihe und Ausgabe
        Map<UUID, OeffentlichesDokument> sichtbar = sichtbare(treffer.stream().map(Suchtreffer::id).toList(), zugriff);
        List<Treffer> ergebnisse = treffer.stream().filter(t -> sichtbar.containsKey(t.id())).map(t -> {
            OeffentlichesDokument d = sichtbar.get(t.id());
            String auszug = t.auszug().stream().map(AuszugTeil::text).collect(Collectors.joining());
            return new Treffer(null, t.id().toString(), fremd(d.titel()), fremd(d.reiheName()), fremd(d.ausgabe()),
                    d.jahr(), auszug.isEmpty() ? null : auszug, seiten(fundstellen.get(t.id()), d.seiten()));
        }).toList();
        long verworfen = treffer.size() - ergebnisse.size();
        return new Antwort(ergebnisse, Math.max(ergebnisse.size(), gesamt - verworfen),
                degradiert ? NUR_VOLLTEXT : null, degradiert ? Boolean.TRUE : null);
    }

    static List<Hybridtreffer> belegteTreffer(Hybridergebnis ergebnis, String begriff, double minRelevanz) {
        // Ohne Modellbewertung bleibt nur die exakte Dokumentnummer als deterministischer Beleg.
        return ergebnis.treffer().stream().filter(h -> h.relevanz() != null
                ? h.relevanz() >= minRelevanz
                : exakteNummer(begriff, h.treffer())).toList();
    }

    private static boolean exakteNummer(String begriff, Suchtreffer treffer) {
        return begriff.equals(treffer.belegnummer());
    }

    /** {@code dokument_zeigen}: ID nach der Zuordnung der Gesprächsnummer; nur Dokumente, die der Filter liefert. */
    public Ansicht zeigen(OeffentlicherKontext kontext, UUID id) {
        if (id == null) {
            throw new WerkzeugFehler(WerkzeugFehler.NICHT_GEFUNDEN);
        }
        Lesezugriff zugriff = kontext.zugriff();
        Optional<Ansicht> ansicht = transaktion.neu(() -> {
            daten.nurLesend();
            List<OeffentlichesDokument> d = dokumente.sichtbare(List.of(id), zugriff);
            if (d.isEmpty()) {
                return Optional.<Ansicht>empty();
            }
            // erst nach dem Filter wird der Inhalt gelesen; auch das Lesen läuft über den Filter
            String roh = dokumente.inhalt(id, zugriff, INHALT_MAX + 1).orElse(null);
            return Optional.of(ansicht(d.getFirst(), roh));
        });
        return ansicht.orElseThrow(() -> new WerkzeugFehler(WerkzeugFehler.NICHT_GEFUNDEN));
    }

    private Ansicht ansicht(OeffentlichesDokument d, String roh) {
        boolean gekuerzt = roh != null && roh.codePointCount(0, roh.length()) > INHALT_MAX;
        String text = !gekuerzt ? roh : roh.substring(0, roh.offsetByCodePoints(0, INHALT_MAX));
        if (text != null && text.isBlank()) {
            text = null;
            gekuerzt = false;
        }
        return new Ansicht(d.id().toString(), fremd(d.titel()), fremd(d.reiheName()), fremd(d.ausgabe()), d.jahr(), text,
                gekuerzt ? Boolean.TRUE : null, gekuerzt ? INHALT_MAX : null,
                gekuerzt ? DokumentZeigen.HINWEIS_GEKUERZT.formatted(INHALT_MAX) : null);
    }

    private Map<UUID, OeffentlichesDokument> sichtbare(List<UUID> ids, Lesezugriff zugriff) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        List<OeffentlichesDokument> liste = transaktion.neu(() -> {
            daten.nurLesend();
            return dokumente.sichtbare(ids, zugriff);
        });
        Map<UUID, OeffentlichesDokument> map = new LinkedHashMap<>();
        liste.forEach(d -> map.put(d.id(), d));
        return map;
    }

    private Hybridergebnis hybridSuchen(Suchanfrage anfrage, Sichtregel sicht, int anzahl) {
        try {
            return hybrid.suchen(anfrage, sicht, false, anzahl);
        } catch (RuntimeException e) {
            LOG.warnf("Öffentliche Chat-Suche nur im Volltext: Hybridsuche gescheitert (%s)", e.getClass().getName());
            return null;
        }
    }

    /** Seiten der Fundstelle nur, wenn sie im Dokument liegen (Req 5.7); sonst keine Angabe. */
    private static String seiten(Fundstelle f, Integer dokumentSeiten) {
        return f == null ? null : f.seitenText(dokumentSeiten);
    }

    /** Fremder Text für das Modell: einzeilig, ohne Rahmenmarker, gekürzt; leer bleibt {@code null}. */
    static String fremd(String wert) {
        if (wert == null) {
            return null;
        }
        String s = DokumentZeigen.einzeilig(wert, TITEL_MAX);
        return s.isEmpty() ? null : s;
    }

    private static String text(String name, String wert, int max, boolean pflicht) {
        String text = wert == null ? null : wert.strip();
        if (text == null || text.isEmpty()) {
            if (pflicht) {
                throw WerkzeugFehler.ungueltig(name);
            }
            return null;
        }
        if (text.length() > max || text.chars().anyMatch(Character::isISOControl)) {
            throw WerkzeugFehler.ungueltig(name);
        }
        return text;
    }
}
