package ch.urinet.archiv.kontakt.application;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.gemeinsam.domain.Normalisierer;
import ch.urinet.archiv.kontakt.domain.Kontakt;
import ch.urinet.archiv.kontakt.domain.KontaktAngaben;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Kontakte suchen (Partnerauswahl, A6), auflisten und ansehen (A6, Req 17.1–17.3), von Hand anlegen oder ändern
 * (A12, Req 2.18) sowie als inaktiv markieren und wieder aktivieren (A12, Req 17.11, 17.15). Die Rechte prüft der
 * {@code ZugangFilter} vor dem Aufruf. Das Log nennt nur IDs, nie Kontaktwerte (Req 16.7).
 */
@ApplicationScoped
public class KontaktVerwaltung {

    /** Höchstzahl Treffer der Suche für die Partnerauswahl. */
    public static final int SUCHE_ANZAHL = 20;
    /** Höchstzahl der Kontakte «zusammengeführt aus» im Detail (design.md). */
    public static final int ZUSAMMENGEFUEHRT_AUS_ANZAHL = 50;

    private static final Logger LOG = Logger.getLogger(KontaktVerwaltung.class);

    private final KontaktRegister register;
    private final KontaktVerweise verweise;
    private final KontaktProtokoll protokoll;
    private final BigDecimal schwelle;

    public KontaktVerwaltung(KontaktRegister register, KontaktVerweise verweise, KontaktProtokoll protokoll,
                             @ConfigProperty(name = "archiv.abgleich.aehnlichkeit") BigDecimal schwelle) {
        this.register = register;
        this.verweise = verweise;
        this.protokoll = protokoll;
        this.schwelle = schwelle;
    }

    /** Detail eines Kontakts (Req 17.3): Ziel einer Zusammenführung und die höchstens 50 neuesten Quellen. */
    public record Detail(Kontakt kontakt, Optional<Kontakt> zusammengefuehrtIn, List<Kontakt> zusammengefuehrtAus) {
    }

    /**
     * Liste (Req 17.1, 17.2): {@code text} {@code null} heisst ohne Suchbegriff; sonst schon geprüft (1–200 Zeichen ohne
     * Steuerzeichen). Ohne Zugriffsprotokoll wie die Partnerauswahl.
     */
    @Transactional
    public KontaktSeite liste(String text, KontaktSuche.Status status, String quelle, Boolean nichtImExport,
                              int seite, int groesse) {
        String t = text == null ? null : text.strip();
        KontaktSuche suche = t == null || t.isEmpty()
                ? new KontaktSuche(null, null, null, null, null, null, status, quelle, nichtImExport, seite, groesse)
                : new KontaktSuche(t.toLowerCase(Locale.ROOT), Normalisierer.name(t), Normalisierer.email(t),
                Normalisierer.plz(t), t, schwelle, status, quelle, nichtImExport, seite, groesse);
        return register.liste(suche);
    }

    /** Auch inaktive Kontakte sind lesbar; unbekannt oder fremder Mandant → 404. */
    @Transactional
    public Detail detail(UUID id) {
        Kontakt kontakt = register.finden(id).orElseThrow(() -> new FachFehler(Fehlercode.NICHT_GEFUNDEN));
        Optional<Kontakt> ziel = kontakt.zusammengefuehrtIn() == null ? Optional.empty()
                : register.finden(kontakt.zusammengefuehrtIn());
        return new Detail(kontakt, ziel, register.zusammengefuehrtAus(id, ZUSAMMENGEFUEHRT_AUS_ANZAHL));
    }

    /** Zahl der Verweise (Req 17.17); unbekannt → 404. */
    @Transactional
    public KontaktVerweise.Zahlen verweise(UUID id) {
        register.finden(id).orElseThrow(() -> new FachFehler(Fehlercode.NICHT_GEFUNDEN));
        return verweise.zaehlen(id);
    }

    /**
     * Als inaktiv markieren (Req 17.11): Stammdaten und Zuordnungen bleiben. Unbekannt → 404, schon inaktiv → 409,
     * Version veraltet → 412.
     */
    @Transactional
    public Kontakt inaktivSetzen(UUID id, int version, String grund) {
        gesperrt(id, version, false);
        Kontakt danach = register.statusSetzen(id, false, null);
        protokoll.markiert(id, false, grund);
        LOG.infof("Kontakt %s inaktiv (Version %d)", id, danach.version());
        return danach;
    }

    /**
     * Wieder aktivieren (Req 17.15): Unbekannt → 404, schon aktiv oder zusammengeführt → 409, Version veraltet → 412.
     */
    @Transactional
    public Kontakt aktivSetzen(UUID id, int version) {
        gesperrt(id, version, true);
        Kontakt danach = register.statusSetzen(id, true, null);
        protokoll.markiert(id, true, null);
        LOG.infof("Kontakt %s wieder aktiv (Version %d)", id, danach.version());
        return danach;
    }

    /**
     * Sperrt den Kontakt und prüft 404, dann den Status (409: zum Aktivieren muss er inaktiv und nicht zusammengeführt
     * sein, zum Markieren aktiv), dann die Version (412).
     */
    private void gesperrt(UUID id, int version, boolean aktivieren) {
        Kontakt kontakt = register.sperren(List.of(id)).stream().findFirst()
                .orElseThrow(() -> new FachFehler(Fehlercode.NICHT_GEFUNDEN));
        boolean passt = aktivieren ? !kontakt.aktiv() && kontakt.zusammengefuehrtIn() == null : kontakt.aktiv();
        if (!passt) {
            throw new FachFehler(Fehlercode.STATUS_KONFLIKT);
        }
        if (kontakt.version() != version) {
            throw new FachFehler(Fehlercode.VERSION_KONFLIKT);
        }
    }

    public List<Kontakt> suchen(String text) {
        return register.suchen(Normalisierer.name(text), SUCHE_ANZAHL);
    }

    @Transactional
    public Kontakt anlegen(KontaktAngaben angaben) {
        KontaktAngaben bereinigt = angaben.bereinigt();
        Kontakt kontakt = register.anlegen(bereinigt, bereinigt.nameNormalisiert(), Kontakt.QUELLE_MANUELL);
        LOG.infof("Kontakt %s angelegt", kontakt.id());
        return kontakt;
    }

    /**
     * Optimistisches Locking: fremde oder unbekannte ID → 404, inaktiver Kontakt → 409 (Req 17.16), veraltete Version
     * → 412 (tech.md, If-Match). 409 vor 412, weil Neuladen bei einem inaktiven Kontakt nicht hilft.
     */
    @Transactional
    public Kontakt aendern(UUID id, int version, KontaktAngaben aenderung) {
        Kontakt bisher = register.sperren(List.of(id)).stream().findFirst()
                .orElseThrow(() -> new FachFehler(Fehlercode.NICHT_GEFUNDEN));
        if (!bisher.aktiv()) {
            throw new FachFehler(Fehlercode.STATUS_KONFLIKT);
        }
        if (bisher.version() != version) {
            throw new FachFehler(Fehlercode.VERSION_KONFLIKT);
        }
        KontaktAngaben bereinigt = bisher.angaben().mit(aenderung).bereinigt();
        Kontakt kontakt = register.aendern(id, version, bereinigt, bereinigt.nameNormalisiert())
                .orElseThrow(() -> new FachFehler(Fehlercode.VERSION_KONFLIKT));
        LOG.infof("Kontakt %s geändert (Version %d)", kontakt.id(), kontakt.version());
        return kontakt;
    }
}
