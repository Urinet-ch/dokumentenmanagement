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
import ch.urinet.archiv.suche.application.SuchePort;
import ch.urinet.archiv.suche.domain.AuszugTeil;
import ch.urinet.archiv.suche.domain.Sortierung;
import ch.urinet.archiv.suche.domain.Suchanfrage;
import ch.urinet.archiv.suche.domain.Suchergebnis;
import ch.urinet.archiv.suche.domain.Suchtreffer;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Werkzeug {@code dokumente_suchen} (Spec 02, Req 3.1, 5.2; Spec 01, Req 3.13): Volltextsuche über den {@link SuchePort}
 * ohne Dateinamen ({@code mitDateiname=false}), sichtbar nach der {@link Sichtregel} des Kontexts, ohne Status alle
 * ausser {@code VERWORFEN} (wie die Suche der PWA). Ein Partnername wird über {@link KontaktRegister#aehnlichste} mit der
 * Regel des Partner-Abgleichs aufgelöst ({@code archiv.abgleich.aehnlichkeit}, {@link AbgleichRegel}); ohne eindeutigen
 * Kontakt ist das Ergebnis leer mit Hinweis, nie eine Vermutung. Treffer nennen nie den Dateinamen.
 */
@ApplicationScoped
public class DokumenteSuchen {

    public static final int FRAGE_MAX = Suchanfrage.BEGRIFF_MAX;
    public static final int PARTNER_MAX = 200;
    public static final int ANZAHL_STANDARD = 5;
    public static final int ANZAHL_MAX = 10;
    static final String PARTNER_NICHT_GEFUNDEN = "Partner nicht gefunden";
    static final String PARTNER_NICHT_EINDEUTIG = "Partner nicht eindeutig";

    /** Parameter des Modells, ungeprüft. */
    public record Anfrage(String frage, String art, String partner, String von, String bis, Boolean neueste,
                          Integer anzahl) {
    }

    /**
     * Ein Treffer; {@code betrag} fehlt ohne Finanzangaben, {@code link} ist der Pfad der PWA. {@code bestaetigt} ist
     * {@code false} bei einem Dokument, dessen Art noch nicht bestätigt ist (nur nach Recht A7 oder für den Erfasser
     * sichtbar, Spec 01, Req 2.21).
     */
    public record Treffer(String id, String titel, String art, String datum, String partner, String betrag,
                          String auszug, boolean bestaetigt, String link) {
    }

    /** {@code gesamt}: alle sichtbaren Treffer; {@code hinweis} z. B. «Partner nicht gefunden». */
    public record Antwort(List<Treffer> treffer, long gesamt, String hinweis) {

        public Antwort {
            treffer = List.copyOf(treffer);
        }
    }

    private final SuchePort suche;
    private final KontaktRegister kontakte;
    private final DokumentartRepository arten;
    private final WerkzeugDaten daten;
    private final WerkzeugTransaktion transaktion;
    private final BigDecimal schwelle;

    public DokumenteSuchen(SuchePort suche, KontaktRegister kontakte, DokumentartRepository arten, WerkzeugDaten daten,
                           WerkzeugTransaktion transaktion,
                           @ConfigProperty(name = "archiv.abgleich.aehnlichkeit") BigDecimal schwelle) {
        this.suche = suche;
        this.kontakte = kontakte;
        this.arten = arten;
        this.daten = daten;
        this.transaktion = transaktion;
        this.schwelle = schwelle;
    }

    /** Prüft die Parameter vor jedem Datenbankzugriff, dann sucht es in einer eigenen, nur lesenden Transaktion. */
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
        Sortierung sortierung = Boolean.TRUE.equals(a.neueste()) ? Sortierung.DATUM : null;
        return transaktion.neu(() -> {
            daten.nurLesend();
            return suchen(kontext, frage, art, partner, von, bis, sortierung, anzahl);
        });
    }

    private Antwort suchen(WerkzeugKontext kontext, String frage, String art, String partner, LocalDate von,
                           LocalDate bis, Sortierung sortierung, int anzahl) {
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
                    return new Antwort(List.of(), 0, PARTNER_NICHT_EINDEUTIG);
                }
                case AbgleichErgebnis.NeuerPartner n -> {
                    return new Antwort(List.of(), 0, PARTNER_NICHT_GEFUNDEN);
                }
            }
        }
        Suchanfrage anfrage;
        try {
            anfrage = new Suchanfrage(frage, code, partnerId, von, bis, null, null, null, null, null, null, null,
                    sortierung, 0, anzahl);
        } catch (FachFehler e) {
            throw WerkzeugFehler.ungueltig("frage");
        }
        Suchergebnis ergebnis = quelle(anfrage, kontext.sicht());
        Set<UUID> bestaetigt = daten.bestaetigte(ergebnis.treffer().stream().map(Suchtreffer::id).toList());
        return new Antwort(ergebnis.treffer().stream().map(t -> treffer(t, bestaetigt.contains(t.id()))).toList(),
                ergebnis.gesamt(), null);
    }

    /** Quelle der Treffer; mit Aufgabe 7.1d wechselt nur sie (Hybridsuche nach Aufgabe 6.4, ebenfalls ohne Dateinamen). */
    private Suchergebnis quelle(Suchanfrage anfrage, Sichtregel sicht) {
        return suche.suchen(anfrage, sicht, false);
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

    private static Treffer treffer(Suchtreffer t, boolean bestaetigt) {
        String auszug = t.auszug().stream().map(AuszugTeil::text).collect(Collectors.joining());
        return new Treffer(t.id().toString(), t.titel(), t.art(), Werteformat.datum(t.datum()), t.partnerName(),
                Werteformat.betrag(t.betrag(), t.waehrung()), auszug.isEmpty() ? null : auszug, bestaetigt,
                link(t.id()));
    }

    static String link(UUID id) {
        return "/dokument/" + id;
    }
}
