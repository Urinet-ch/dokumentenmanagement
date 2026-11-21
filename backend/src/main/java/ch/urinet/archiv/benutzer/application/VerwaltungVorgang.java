package ch.urinet.archiv.benutzer.application;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.mandant.application.MandantAufloesung;
import ch.urinet.archiv.mandant.application.MandantVerzeichnis;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.protokoll.application.ZugriffProtokoll;
import ch.urinet.archiv.protokoll.domain.ProtokollAktion;
import ch.urinet.archiv.protokoll.domain.ProtokollDetails;
import ch.urinet.archiv.protokoll.domain.ZugriffEintrag;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

@ApplicationScoped
public class VerwaltungVorgang {

    private static final Logger LOG = Logger.getLogger(VerwaltungVorgang.class);

    public enum Kompensation { GEKLAERT, UNKLAR }

    /**
     * Fehler der {@code arbeit}, nachdem sie im Realm schon einen Benutzer angelegt hat. Er trägt dessen {@code sub},
     * damit das Protokoll {@code UNKLAR} oder {@code FEHLGESCHLAGEN} das Ziel nennt (Spur zum verwaisten Benutzer).
     */
    public static final class ArbeitFehler extends RuntimeException {
        private final transient UUID ziel;

        public ArbeitFehler(UUID ziel, Throwable ursache) {
            super("Verwaltungsarbeit fehlgeschlagen", ursache);
            this.ziel = Objects.requireNonNull(ziel);
        }

        UUID ziel() {
            return ziel;
        }
    }

    private static final Set<ProtokollAktion> AKTIONEN = Set.of(ProtokollAktion.BENUTZER_ANLEGEN,
            ProtokollAktion.BENUTZER_AENDERN, ProtokollAktion.BENUTZER_LOESCHEN, ProtokollAktion.ROLLE_ZUWEISEN);

    private final Vorgangssperre sperre;
    private final VerwaltungGrenzen grenzen;
    private final ZugriffProtokoll protokoll;
    private final MandantAufloesung aufloesung;
    private final MandantVerzeichnis mandanten;
    private final VerwaltungTransaktionen transaktionen;

    public VerwaltungVorgang(Vorgangssperre sperre, VerwaltungGrenzen grenzen, ZugriffProtokoll protokoll,
                             MandantAufloesung aufloesung, MandantVerzeichnis mandanten,
                             VerwaltungTransaktionen transaktionen) {
        this.sperre = sperre;
        this.grenzen = grenzen;
        this.protokoll = protokoll;
        this.aufloesung = aufloesung;
        this.mandanten = mandanten;
        this.transaktionen = transaktionen;
    }

    /**
     * Die erneute Prüfung unter der Vorgangssperre darf Keycloak nur lesen; sie prüft insbesondere den letzten Inhaber.
     * Die externe Arbeit und ihre Kompensation laufen ausserhalb der Datenbanktransaktion; die Arbeit darf Fehler
     * schreibender Aufrufe nicht unterdrücken.
     * Die Kompensation darf {@code GEKLAERT} nur melden, wenn der Ausgangszustand wiederhergestellt ist und keine
     * verspätete externe Wirkung mehr eintreffen kann.
     * {@code speichern} darf ausschliesslich Datenbankänderungen ausführen.
     * {@code abschluss} erstellt den Protokolleintrag aus dem Arbeitsergebnis ohne Datenbankänderung.
     */
    public <T> T ausfuehren(MandantCode mandant, ZugriffEintrag absicht, Runnable pruefen,
                            Supplier<T> arbeit, Function<RuntimeException, Kompensation> kompensieren,
                            Consumer<T> speichern, Function<T, ZugriffEintrag> abschluss) {
        if (transaktionen.aktiv()) {
            throw new IllegalStateException("Verwaltungsvorgang darf nicht in einer Transaktion beginnen");
        }
        Objects.requireNonNull(mandant);
        Objects.requireNonNull(pruefen);
        Objects.requireNonNull(arbeit);
        Objects.requireNonNull(kompensieren);
        Objects.requireNonNull(speichern);
        Objects.requireNonNull(abschluss);
        ProtokollDetails.Verwaltung details = details(absicht, ProtokollDetails.Verwaltungsphase.ANGEFRAGT);
        VerwaltungGrenzen.Art art = grenzart(absicht.aktion(), details.vorgang());
        String akteur = absicht.benutzer().orElseThrow(() -> new IllegalArgumentException("Akteur ist Pflicht"));
        String schema = mandanten.aktivesSchema(mandant)
                .orElseThrow(() -> new IllegalStateException("Mandant nicht aktiv"));
        if (!schema.equals(aufloesung.schema())) {
            throw new IllegalStateException("Vorgang widerspricht dem Mandanten-Kontext");
        }
        UUID id = details.vorgangId();
        if (!sperre.belegen(mandant, id)) {
            throw new FachFehler(Fehlercode.STATUS_KONFLIKT);
        }
        try {
            pruefen.run();
            transaktionen.neu(() -> {
                if (!sperre.anfragen(mandant, id)) {
                    throw new FachFehler(Fehlercode.STATUS_KONFLIKT);
                }
                grenzen.pruefen(akteur, art, details.ziel());
                protokoll.schreiben(absicht);
            });
        } catch (RuntimeException fehler) {
            try {
                sperre.freigeben(mandant, id);
            } catch (RuntimeException freigabeFehler) {
                LOG.errorf("Vorgang %s: Freigabe fehlgeschlagen (%s).", id,
                        freigabeFehler.getClass().getSimpleName());
                fehler.addSuppressed(freigabeFehler);
            }
            throw fehler;
        }

        boolean arbeitGeliefert = false;
        boolean abschlussGeprueft = false;
        UUID ziel = null;
        try {
            T ergebnis = arbeit.get();
            arbeitGeliefert = true;
            ZugriffEintrag eintrag = abschluss.apply(ergebnis);
            ProtokollDetails.Verwaltung ende = details(eintrag, ProtokollDetails.Verwaltungsphase.ABGESCHLOSSEN);
            if (!id.equals(ende.vorgangId()) || !absicht.aktion().equals(eintrag.aktion())
                    || !absicht.benutzer().equals(eintrag.benutzer())
                    || !absicht.rolle().equals(eintrag.rolle())
                    || details.vorgang() != ende.vorgang()
                    || details.grund() != ende.grund()
                    || (details.rolleAlt() != null && details.rolleAlt() != ende.rolleAlt())
                    || (details.rolleNeu() != null && details.rolleNeu() != ende.rolleNeu())
                    || (!details.felder().isEmpty() && !details.felder().equals(ende.felder()))
                    || (details.ziel() != null && !details.ziel().equals(ende.ziel()))
                    || (art == VerwaltungGrenzen.Art.ANLEGEN && ende.ziel() == null)) {
                throw new IllegalArgumentException("Abschluss gehört nicht zum Vorgang");
            }
            ziel = ende.ziel();
            abschlussGeprueft = true;
            transaktionen.neu(() -> {
                speichern.accept(ergebnis);
                protokoll.schreiben(eintrag);
                if (!sperre.abschliessen(mandant, id, Vorgangssperre.Abschluss.ABGESCHLOSSEN)) {
                    throw new IllegalStateException("Vorgangssperre nicht abgeschlossen");
                }
            });
            return ergebnis;
        } catch (RuntimeException fehler) {
            Kompensation ergebnis = Kompensation.UNKLAR;
            UUID geprueftesZiel = ziel;
            boolean anlageUnklar = false;
            if (!arbeitGeliefert && geprueftesZiel == null && fehler instanceof ArbeitFehler mitZiel) {
                geprueftesZiel = mitZiel.ziel();
            } else if (!arbeitGeliefert && art == VerwaltungGrenzen.Art.ANLEGEN
                    && fehler instanceof IdentitaetsDienst.AnlageException anlageFehler) {
                anlageUnklar = !anlageFehler.entfernt();
                try {
                    UUID gelesen = UUID.fromString(anlageFehler.ziel());
                    if (!gelesen.toString().equalsIgnoreCase(anlageFehler.ziel())) {
                        throw new IllegalArgumentException("Ungültiges UUID-Format");
                    }
                    geprueftesZiel = gelesen;
                } catch (IllegalArgumentException ungueltig) {
                    LOG.errorf("Vorgang %s: Zielkennung der Anlage ungültig.", id);
                    anlageUnklar = true;
                }
            }
            UUID fehlerZiel = geprueftesZiel;
            try {
                ergebnis = Objects.requireNonNull(kompensieren.apply(fehler), "Ergebnis der Kompensation fehlt");
            } catch (RuntimeException kompensationsFehler) {
                LOG.errorf("Vorgang %s: Kompensation fehlgeschlagen (%s).", id,
                        kompensationsFehler.getClass().getSimpleName());
                fehler.addSuppressed(kompensationsFehler);
            }
            if (ergebnis == Kompensation.GEKLAERT && !anlageUnklar && (!arbeitGeliefert || abschlussGeprueft)) {
                boolean abgeschlossen = false;
                try {
                    transaktionen.neu(() -> {
                        protokoll.schreiben(mitPhase(absicht, ProtokollDetails.Verwaltungsphase.FEHLGESCHLAGEN, fehlerZiel));
                        if (!sperre.abschliessen(mandant, id, Vorgangssperre.Abschluss.FEHLGESCHLAGEN)) {
                            throw new IllegalStateException("Vorgangssperre nicht abgeschlossen");
                        }
                    });
                    abgeschlossen = true;
                } catch (RuntimeException abschlussFehler) {
                    LOG.errorf("Vorgang %s: Fehlerabschluss fehlgeschlagen (%s).", id,
                            abschlussFehler.getClass().getSimpleName());
                    fehler.addSuppressed(abschlussFehler);
                }
                if (abgeschlossen) {
                    throw anlageKonfliktAbbilden(art, fehler);
                }
            }
            try {
                transaktionen.neu(() -> {
                    protokoll.schreiben(mitPhase(absicht, ProtokollDetails.Verwaltungsphase.UNKLAR, fehlerZiel));
                    if (!sperre.abschliessen(mandant, id, Vorgangssperre.Abschluss.UNKLAR)) {
                        throw new IllegalStateException("Vorgangssperre nicht abgeschlossen");
                    }
                });
            } catch (RuntimeException protokollFehler) {
                LOG.errorf("Vorgang %s: Protokollierung des unklaren Zustands fehlgeschlagen (%s).", id,
                        protokollFehler.getClass().getSimpleName());
                fehler.addSuppressed(protokollFehler);
            }
            throw fehler;
        }
    }

    private static RuntimeException anlageKonfliktAbbilden(VerwaltungGrenzen.Art art, RuntimeException fehler) {
        return art == VerwaltungGrenzen.Art.ANLEGEN && fehler instanceof IdentitaetsDienst.KonfliktException
                ? new FachFehler(Fehlercode.BENUTZER_EXISTIERT) : fehler;
    }

    private static ProtokollDetails.Verwaltung details(ZugriffEintrag eintrag,
                                                        ProtokollDetails.Verwaltungsphase phase) {
        Objects.requireNonNull(eintrag);
        ProtokollDetails.Verwaltung details = eintrag.details().verwaltung();
        if (!AKTIONEN.contains(eintrag.aktion()) || eintrag.dokumentId().isPresent()
                || eintrag.rolle().isEmpty() || details == null || details.phase() != phase
                || (eintrag.aktion() != ProtokollAktion.BENUTZER_ANLEGEN && details.ziel() == null)) {
            throw new IllegalArgumentException("Ungültiges Verwaltungsprotokoll");
        }
        return details;
    }

    private static VerwaltungGrenzen.Art grenzart(ProtokollAktion aktion,
                                                    ProtokollDetails.Verwaltungsvorgang vorgang) {
        Objects.requireNonNull(vorgang);
        return switch (aktion) {
            case BENUTZER_ANLEGEN -> {
                if (vorgang != ProtokollDetails.Verwaltungsvorgang.ANLEGEN) {
                    throw new IllegalArgumentException("Ungültiger Anlagevorgang");
                }
                yield VerwaltungGrenzen.Art.ANLEGEN;
            }
            case BENUTZER_AENDERN -> switch (vorgang) {
                case EINLADUNG_ERNEUT, ZUGANG_ZURUECKSETZEN, REAKTIVIEREN -> VerwaltungGrenzen.Art.EMAIL;
                case SPERREN, ENTSPERREN, DEAKTIVIEREN, EMAIL_AENDERN, GOOGLE_LOESEN ->
                        VerwaltungGrenzen.Art.BENUTZER;
                default -> throw new IllegalArgumentException("Ungültiger Änderungsvorgang");
            };
            case BENUTZER_LOESCHEN -> {
                if (vorgang != ProtokollDetails.Verwaltungsvorgang.LOESCHEN) {
                    throw new IllegalArgumentException("Ungültiger Löschvorgang");
                }
                yield VerwaltungGrenzen.Art.BENUTZER;
            }
            case ROLLE_ZUWEISEN -> {
                if (vorgang != ProtokollDetails.Verwaltungsvorgang.ROLLE_ZUWEISEN) {
                    throw new IllegalArgumentException("Ungültiger Rollenvorgang");
                }
                yield VerwaltungGrenzen.Art.BENUTZER;
            }
            default -> throw new IllegalArgumentException("Ungültige Verwaltungsaktion");
        };
    }

    private static ZugriffEintrag mitPhase(ZugriffEintrag eintrag, ProtokollDetails.Verwaltungsphase phase,
                                           UUID ziel) {
        ProtokollDetails.Verwaltung bisher = eintrag.details().verwaltung();
        var details = new ProtokollDetails.Verwaltung(bisher.vorgangId(), phase,
                bisher.ziel() != null ? bisher.ziel() : ziel, bisher.vorgang(),
                bisher.rolleAlt(), bisher.rolleNeu(), bisher.felder(), bisher.grund());
        return new ZugriffEintrag(eintrag.benutzer(), eintrag.rolle(), eintrag.aktion(), eintrag.dokumentId(),
                eintrag.ip(), ProtokollDetails.verwaltung(details), eintrag.bearbeiter());
    }
}
