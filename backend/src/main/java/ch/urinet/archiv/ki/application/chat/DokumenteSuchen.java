package ch.urinet.archiv.ki.application.chat;

import ch.urinet.archiv.dokumentart.application.DokumentartRepository;
import ch.urinet.archiv.dokumentart.domain.Dokumentart;
import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Normalisierer;
import ch.urinet.archiv.ki.domain.Werteformat;
import ch.urinet.archiv.kontakt.application.KontaktRegister;
import ch.urinet.archiv.kontakt.domain.AbgleichErgebnis;
import ch.urinet.archiv.kontakt.domain.AbgleichRegel;
import ch.urinet.archiv.sicherheit.domain.Sichtregel;
import ch.urinet.archiv.suche.application.HybridSuche;
import ch.urinet.archiv.suche.application.SuchePort;
import ch.urinet.archiv.suche.domain.AuszugTeil;
import ch.urinet.archiv.suche.domain.Fundstelle;
import ch.urinet.archiv.suche.domain.Hybridergebnis;
import ch.urinet.archiv.suche.domain.Sortierung;
import ch.urinet.archiv.suche.domain.Suchanfrage;
import ch.urinet.archiv.suche.domain.Suchergebnis;
import ch.urinet.archiv.suche.domain.Suchtreffer;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Werkzeug {@code dokumente_suchen} (Spec 02, Req 3.1, 2.3, 2.5, 5.2; Spec 01, Req 3.13): mit {@code frage} und ohne
 * {@code neueste} die {@link HybridSuche} (Aufgabe 7.1d, {@code k = anzahl}), mit {@code neueste} und bei einem Fehler im
 * Hybrid-Pfad die Volltextsuche über den {@link SuchePort}; beide ohne Dateinamen ({@code mitDateiname=false}), auch
 * nicht im Text an den Reranker. Sichtbar nach der {@link Sichtregel} des Kontexts, ohne Status alle
 * ausser {@code VERWORFEN} (wie die Suche der PWA). Ein Partnername wird über {@link KontaktRegister#aehnlichste} mit der
 * Regel des Partner-Abgleichs aufgelöst ({@code archiv.abgleich.aehnlichkeit}, {@link AbgleichRegel}); ohne eindeutigen
 * Kontakt ist das Ergebnis leer mit Hinweis, nie eine Vermutung. Treffer nennen nie den Dateinamen.
 */
@ApplicationScoped
public class DokumenteSuchen {

    private static final Logger LOG = Logger.getLogger(DokumenteSuchen.class);

    public static final int FRAGE_MAX = Suchanfrage.BEGRIFF_MAX;
    public static final int PARTNER_MAX = 200;
    public static final int ANZAHL_STANDARD = 5;
    public static final int ANZAHL_MAX = 10;
    /** Hinweis an den Agenten, wenn die Suche nur im Volltext lief (Embedding oder Reranker ohne Ergebnis, Req 2.3). */
    static final String NUR_VOLLTEXT = "nur Volltext";
    static final String PARTNER_NICHT_GEFUNDEN = "Partner nicht gefunden";
    static final String PARTNER_NICHT_EINDEUTIG = "Partner nicht eindeutig";

    /** Parameter des Modells, ungeprüft. */
    public record Anfrage(String frage, String art, String partner, String von, String bis, Boolean neueste,
                          Integer anzahl) {
    }

    /**
     * Ein Treffer; {@code betrag} fehlt ohne Finanzangaben, {@code link} ist der Pfad der PWA. {@code bestaetigt} ist
     * {@code false} bei einem Dokument, dessen Art noch nicht bestätigt ist (nur nach Recht A7 oder für den Erfasser
     * sichtbar, Spec 01, Req 2.21). {@code seiten} ist die Fundstelle der Hybridsuche («2» oder «2-3»), fehlt bei
     * Treffern nur aus dem Volltext und ohne Seitentrenner.
     */
    public record Treffer(String id, String titel, String art, String datum, String partner, String betrag,
                          String auszug, boolean bestaetigt, String link, String seiten) {
    }

    /**
     * {@code gesamt}: alle sichtbaren Treffer, bei der Hybridsuche die Zahl der gelieferten; {@code hinweis} z. B.
     * «Partner nicht gefunden» oder «nur Volltext»; {@code degradiert} nur bei {@code true} gesetzt.
     */
    public record Antwort(List<Treffer> treffer, long gesamt, String hinweis, Boolean degradiert) {

        public Antwort(List<Treffer> treffer, long gesamt, String hinweis) {
            this(treffer, gesamt, hinweis, null);
        }

        public Antwort {
            treffer = List.copyOf(treffer);
        }
    }

    private final SuchePort suche;
    private final HybridSuche hybrid;
    private final KontaktRegister kontakte;
    private final DokumentartRepository arten;
    private final WerkzeugDaten daten;
    private final WerkzeugTransaktion transaktion;
    private final BigDecimal schwelle;

    public DokumenteSuchen(SuchePort suche, HybridSuche hybrid, KontaktRegister kontakte, DokumentartRepository arten, WerkzeugDaten daten,
                           WerkzeugTransaktion transaktion,
                           @ConfigProperty(name = "archiv.abgleich.aehnlichkeit") BigDecimal schwelle) {
        this.suche = suche;
        this.hybrid = hybrid;
        this.kontakte = kontakte;
        this.arten = arten;
        this.daten = daten;
        this.transaktion = transaktion;
        this.schwelle = schwelle;
    }

    /**
     * Prüft die Parameter vor jedem Datenbankzugriff. Auflösen von Art und Partner und das Lesen der Treffer laufen je in
     * einer eigenen, nur lesenden Transaktion; die Hybridsuche dazwischen ohne Transaktion, weil sie die Modelle ausserhalb
     * jeder Transaktion aufruft und ihre Datenbankteile selbst kurz hält.
     */
    public Antwort suchen(WerkzeugKontext kontext, Anfrage a) {
        String frage = Werkzeugparameter.text("frage", a.frage(), FRAGE_MAX, true);
        String art = Werkzeugparameter.text("art", a.art(), 40, false);
        String partner = Werkzeugparameter.text("partner", a.partner(), PARTNER_MAX, false);
        LocalDate von = Werkzeugparameter.datum("von", a.von(), false);
        LocalDate bis = Werkzeugparameter.datum("bis", a.bis(), false);
        if (von != null && bis != null && von.isAfter(bis)) {
            throw WerkzeugFehler.ungueltig("von");
        }
        int anzahl = Werkzeugparameter.zahl("anzahl", a.anzahl(), 1, ANZAHL_MAX, ANZAHL_STANDARD);
        boolean neueste = Boolean.TRUE.equals(a.neueste());
        Vorbereitet v = transaktion.neu(() -> {
            daten.nurLesend();
            return vorbereiten(frage, art, partner, von, bis, neueste ? Sortierung.DATUM : null, anzahl);
        });
        if (v.leer() != null) {
            return v.leer();
        }
        if (!neueste) {
            Hybridergebnis ergebnis = hybridSuchen(v.anfrage(), kontext.sicht(), anzahl);
            if (ergebnis != null) {
                return transaktion.neu(() -> {
                    daten.nurLesend();
                    return antwort(ergebnis);
                });
            }
        }
        boolean rueckfall = !neueste;
        return transaktion.neu(() -> {
            daten.nurLesend();
            return volltext(v.anfrage(), kontext.sicht(), rueckfall);
        });
    }

    /** Geprüfte Anfrage oder, ohne eindeutigen Partner, die leere Antwort mit Hinweis. */
    private record Vorbereitet(Suchanfrage anfrage, Antwort leer) {
    }

    private Vorbereitet vorbereiten(String frage, String art, String partner, LocalDate von, LocalDate bis,
                                    Sortierung sortierung, int anzahl) {
        String code = art == null ? null : artCode(art);
        UUID partnerId = null;
        if (partner != null) {
            String name = Normalisierer.name(partner);
            AbgleichErgebnis ergebnis = name.isEmpty() ? new AbgleichErgebnis.NeuerPartner()
                    : AbgleichRegel.aehnlich(kontakte.aehnlichste(name, AbgleichRegel.untergrenze(schwelle), 2),
                    schwelle);
            switch (ergebnis) {
                case AbgleichErgebnis.Verknuepft v -> partnerId = v.kontaktId();
                case AbgleichErgebnis.Unsicher u -> {
                    return new Vorbereitet(null, new Antwort(List.of(), 0, PARTNER_NICHT_EINDEUTIG));
                }
                case AbgleichErgebnis.NeuerPartner n -> {
                    return new Vorbereitet(null, new Antwort(List.of(), 0, PARTNER_NICHT_GEFUNDEN));
                }
            }
        }
        try {
            return new Vorbereitet(new Suchanfrage(frage, code, partnerId, von, bis, null, null, null, null, null, null,
                    null, sortierung, 0, anzahl), null);
        } catch (FachFehler e) {
            throw WerkzeugFehler.ungueltig("frage");
        }
    }

    /**
     * Hybridsuche ohne Dateinamen mit {@code k = anzahl}; {@code null} bei einem Fehler (Datenbank, Konfiguration), dann
     * gilt die Volltextsuche. Ins Log kommt nur der Klassenname, nie Frage oder Texte (Req 16.7).
     */
    private Hybridergebnis hybridSuchen(Suchanfrage anfrage, Sichtregel sicht, int anzahl) {
        try {
            return hybrid.suchen(anfrage, sicht, false, anzahl);
        } catch (RuntimeException e) {
            LOG.warnf("Chat-Suche nur im Volltext: Hybridsuche gescheitert (%s)", e.getClass().getName());
            return null;
        }
    }

    private Antwort antwort(Hybridergebnis ergebnis) {
        Set<UUID> bestaetigt = daten.bestaetigte(ergebnis.treffer().stream().map(h -> h.treffer().id()).toList());
        List<Treffer> treffer = ergebnis.treffer().stream()
                .map(h -> treffer(h.treffer(), bestaetigt.contains(h.treffer().id()), h.fundstelle())).toList();
        return new Antwort(treffer, treffer.size(), ergebnis.degradiert() ? NUR_VOLLTEXT : null,
                ergebnis.degradiert() ? Boolean.TRUE : null);
    }

    private Antwort volltext(Suchanfrage anfrage, Sichtregel sicht, boolean rueckfall) {
        Suchergebnis ergebnis = suche.suchen(anfrage, sicht, false);
        Set<UUID> bestaetigt = daten.bestaetigte(ergebnis.treffer().stream().map(Suchtreffer::id).toList());
        return new Antwort(ergebnis.treffer().stream().map(t -> treffer(t, bestaetigt.contains(t.id()), null)).toList(),
                ergebnis.gesamt(), rueckfall ? NUR_VOLLTEXT : null, rueckfall ? Boolean.TRUE : null);
    }

    /** Code einer aktiven Art (ohne Gross-/Kleinschreibung), sonst Fehler mit den aktiven Codes. */
    private String artCode(String art) {
        List<String> aktive = arten.alle().stream().filter(Dokumentart::aktiv).map(Dokumentart::code).toList();
        String code = art.toUpperCase(Locale.ROOT);
        if (!aktive.contains(code)) {
            throw new WerkzeugFehler(WerkzeugFehler.UNBEKANNTE_ART, aktive);
        }
        return code;
    }

    private static Treffer treffer(Suchtreffer t, boolean bestaetigt, Fundstelle fundstelle) {
        String auszug = t.auszug().stream().map(AuszugTeil::text).collect(Collectors.joining());
        return new Treffer(t.id().toString(), t.titel(), t.art(), Werteformat.datum(t.datum()), t.partnerName(),
                Werteformat.betrag(t.betrag(), t.waehrung()), auszug.isEmpty() ? null : auszug, bestaetigt,
                link(t.id()), seiten(fundstelle));
    }

    private static String seiten(Fundstelle f) {
        if (f == null || f.seiteVon() == null) {
            return null;
        }
        return f.seiteBis() == null || f.seiteBis().equals(f.seiteVon()) ? f.seiteVon().toString()
                : f.seiteVon() + "-" + f.seiteBis();
    }

    static String link(UUID id) {
        return "/dokument/" + id;
    }
}
