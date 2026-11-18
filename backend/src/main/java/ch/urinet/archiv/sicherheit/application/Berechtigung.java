package ch.urinet.archiv.sicherheit.application;

import ch.urinet.archiv.sicherheit.domain.Aktion;
import ch.urinet.archiv.sicherheit.domain.Benutzer;
import ch.urinet.archiv.sicherheit.domain.Ergebnis;
import ch.urinet.archiv.sicherheit.domain.Rechtematrix;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Setzt die Rechtematrix durch (Req 2.6, ADR-009). Reihenfolge bei Endpunkten mit {@code {id}} (design.md
 * «Sicherheit»): 1. sichtbar, sonst 404 {@code NICHT_GEFUNDEN}; 2. Recht der Aktion, sonst 403 {@code NICHT_ERLAUBT}.
 * If-Match, Status und Inhalt prüfen die Anwendungsfälle danach. Jede Verweigerung geht an den {@link VerweigerungMelder}
 * (Zugriffsprotokoll mit {@code VERWEIGERT}, Req 10.7).
 */
@ApplicationScoped
public class Berechtigung {

    public enum Wissensentwurfszugriff {
        VOLL,
        NUR_STATUS_ENTSCHEID_GRUND
    }

    private static final Set<Aktion> WISSEN_AKTIONEN = Collections.unmodifiableSet(
            EnumSet.of(Aktion.A15, Aktion.A16, Aktion.A17, Aktion.A18, Aktion.A19));

    private static final Set<Aktion> ERFASSUNG_ERLAUBT = Collections.unmodifiableSet(
            EnumSet.of(Aktion.A1, Aktion.A2, Aktion.A3, Aktion.A4, Aktion.A5, Aktion.A6, Aktion.A7, Aktion.A8,
                    Aktion.A10));

    private final Sichtbarkeit sichtbarkeit;
    private final VerweigerungMelder melder;

    public Berechtigung(Sichtbarkeit sichtbarkeit, VerweigerungMelder melder) {
        this.sichtbarkeit = sichtbarkeit;
        this.melder = melder;
    }

    /** Aktion ohne Dokumentbezug (z. B. A1, A11–A14). */
    public void aktion(Benutzer benutzer, Aktion aktion) {
        if (!Rechtematrix.ergebnis(benutzer.rolle(), aktion).zugaenglich()) {
            verweigern(benutzer, aktion, Optional.empty(), Verweigerung.Grund.NICHT_ERLAUBT);
        }
    }

    /** Wissensseitenaktion: Sichtbarkeit wird vor dem Recht geprüft, ohne Seiteninhalte ins Zugriffsprotokoll zu schreiben. */
    public void wissensseitenAktion(Benutzer benutzer, Aktion aktion, boolean sichtbar) {
        if (!WISSEN_AKTIONEN.contains(aktion)) {
            throw new IllegalArgumentException("Keine Wissensbasis-Aktion");
        }
        Ergebnis ergebnis = Rechtematrix.ergebnis(benutzer.rolle(), aktion);
        if (!sichtbar || ergebnis == Ergebnis.NICHT_SICHTBAR) {
            verweigern(benutzer, aktion, Optional.empty(), Verweigerung.Grund.NICHT_GEFUNDEN);
        }
        if (!ergebnis.zugaenglich()) {
            verweigern(benutzer, aktion, Optional.empty(), Verweigerung.Grund.NICHT_ERLAUBT);
        }
    }

    /** A18 erlaubt nur Status, Entscheid und Grund des eigenen Analysevorschlags; A17 gewährt Vollzugriff. */
    public Wissensentwurfszugriff wissensentwurfLesen(Benutzer benutzer, Optional<String> vorschlagender) {
        if (Rechtematrix.ergebnis(benutzer.rolle(), Aktion.A17).zugaenglich()) {
            return Wissensentwurfszugriff.VOLL;
        }
        if (Rechtematrix.ergebnis(benutzer.rolle(), Aktion.A18).zugaenglich()
                && vorschlagender.filter(benutzer.id()::equals).isPresent()) {
            return Wissensentwurfszugriff.NUR_STATUS_ENTSCHEID_GRUND;
        }
        throw verweigerung(benutzer, Aktion.A18, Optional.empty(), Verweigerung.Grund.NICHT_ERLAUBT);
    }

    /** Webseiten-Erfassung ist auf die feste Positivliste A1–A8 und A10 beschränkt (Spec 03, Req 10.8). */
    public void webseitenErfassung(Benutzer benutzer, Aktion aktion, String dokumentId) {
        if (benutzer.rolle() != Rolle.GESCHAEFTSSTELLE || !webseitenErfassungErlaubt(aktion)
                || !Rechtematrix.ergebnis(benutzer.rolle(), aktion).zugaenglich()) {
            verweigern(benutzer, aktion, Optional.empty(), Verweigerung.Grund.NICHT_ERLAUBT);
        }
        if (aktion.dokumentbezogen() && dokumentId != null) {
            UUID id;
            try {
                id = UUID.fromString(dokumentId);
            } catch (IllegalArgumentException e) {
                unbekanntesDokument(benutzer, aktion);
                return;
            }
            dokumentAktion(benutzer, aktion, id);
        }
    }

    public static boolean webseitenErfassungErlaubt(Aktion aktion) {
        return ERFASSUNG_ERLAUBT.contains(aktion);
    }

    /** Aktion auf einem Dokument: 404 vor 403, damit eine Antwort nie verrät, dass es ein unsichtbares Dokument gibt. */
    public void dokumentAktion(Benutzer benutzer, Aktion aktion, UUID dokumentId) {
        Ergebnis ergebnis = Rechtematrix.ergebnis(benutzer.rolle(), aktion);
        if (ergebnis == Ergebnis.NICHT_SICHTBAR || !sichtbarkeit.sichtbar(benutzer, dokumentId)) {
            verweigern(benutzer, aktion, Optional.of(dokumentId), Verweigerung.Grund.NICHT_GEFUNDEN);
        }
        if (!ergebnis.zugaenglich()) {
            verweigern(benutzer, aktion, Optional.of(dokumentId), Verweigerung.Grund.NICHT_ERLAUBT);
        }
    }

    /** Dokument-ID, die keine gültige ID ist: gilt wie ein unsichtbares Dokument als nicht vorhanden (404). */
    public void unbekanntesDokument(Benutzer benutzer, Aktion aktion) {
        verweigern(benutzer, aktion, Optional.empty(), Verweigerung.Grund.NICHT_GEFUNDEN);
    }

    /**
     * Ohne Archiv-Rolle, ohne Benutzer-ID oder ohne Erklärung des Endpunkts (Req 2.2, fail closed): 403
     * {@code NICHT_ERLAUBT}, bevor Daten gelesen werden. {@code aktion} ist die erklärte Aktion, falls bekannt.
     */
    public void ohneZugang(Optional<String> benutzerId, Optional<Rolle> rolle, Optional<Aktion> aktion) {
        verweigern(benutzerId, rolle, aktion, Optional.empty(), Verweigerung.Grund.NICHT_ERLAUBT);
    }

    /** Ohne gültigen, aktiven Mandanten (Req 1.3): 403 {@code MANDANT_FEHLT}, bevor Daten gelesen werden. */
    public void ohneMandant(Optional<String> benutzerId) {
        verweigern(benutzerId, Optional.empty(), Optional.empty(), Optional.empty(), Verweigerung.Grund.MANDANT_FEHLT);
    }

    private void verweigern(Benutzer benutzer, Aktion aktion, Optional<UUID> dokumentId, Verweigerung.Grund grund) {
        throw verweigerung(Optional.of(benutzer.id()), Optional.of(benutzer.rolle()), Optional.of(aktion),
                dokumentId, grund);
    }

    private void verweigern(Optional<String> benutzerId, Optional<Rolle> rolle, Optional<Aktion> aktion,
                            Optional<UUID> dokumentId, Verweigerung.Grund grund) {
        throw verweigerung(benutzerId, rolle, aktion, dokumentId, grund);
    }

    private Verweigerung verweigerung(Benutzer benutzer, Aktion aktion, Optional<UUID> dokumentId,
                                      Verweigerung.Grund grund) {
        return verweigerung(Optional.of(benutzer.id()), Optional.of(benutzer.rolle()), Optional.of(aktion),
                dokumentId, grund);
    }

    private Verweigerung verweigerung(Optional<String> benutzerId, Optional<Rolle> rolle, Optional<Aktion> aktion,
                                      Optional<UUID> dokumentId, Verweigerung.Grund grund) {
        melder.verweigert(benutzerId, rolle, aktion, dokumentId, grund);
        return new Verweigerung(grund);
    }
}
