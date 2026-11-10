package ch.urinet.archiv.sicherheit.application;

import ch.urinet.archiv.sicherheit.domain.Aktion;
import ch.urinet.archiv.sicherheit.domain.Benutzer;
import ch.urinet.archiv.sicherheit.domain.Ergebnis;
import ch.urinet.archiv.sicherheit.domain.Rechtematrix;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Optional;
import java.util.UUID;

/**
 * Setzt die Rechtematrix durch (Req 2.6, ADR-009). Reihenfolge bei Endpunkten mit {@code {id}} (design.md
 * «Sicherheit»): 1. sichtbar, sonst 404 {@code NICHT_GEFUNDEN}; 2. Recht der Aktion, sonst 403 {@code NICHT_ERLAUBT}.
 * If-Match, Status und Inhalt prüfen die Anwendungsfälle danach. Jede Verweigerung geht an den {@link VerweigerungMelder}
 * (Zugriffsprotokoll mit {@code VERWEIGERT}, Req 10.7).
 */
@ApplicationScoped
public class Berechtigung {

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
        verweigern(Optional.of(benutzer.id()), Optional.of(benutzer.rolle()), Optional.of(aktion), dokumentId, grund);
    }

    private void verweigern(Optional<String> benutzerId, Optional<Rolle> rolle, Optional<Aktion> aktion,
                            Optional<UUID> dokumentId, Verweigerung.Grund grund) {
        melder.verweigert(benutzerId, rolle, aktion, dokumentId, grund);
        throw new Verweigerung(grund);
    }
}
