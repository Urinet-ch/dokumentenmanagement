package ch.urinet.archiv.benutzer.application;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.mandant.application.MandantAbbauRegister;
import ch.urinet.archiv.mandant.domain.MandantCode;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Sperrt die Keycloak-Benutzer eines deaktivierten Mandanten und löscht sie nach der 30-Tage-Frist. */
@ApplicationScoped
public class BenutzerMandantSperren {
    private static final Duration FRIST = Duration.ofDays(30);
    private static final int MAX_BENUTZER = 10_000;

    public record Bericht(int gesperrt, int geloescht, Instant loeschbarAb) {
    }

    private final MandantAbbauRegister mandanten;
    private final IdentitaetsDienst identitaet;

    public BenutzerMandantSperren(MandantAbbauRegister mandanten, IdentitaetsDienst identitaet) {
        this.mandanten = mandanten;
        this.identitaet = identitaet;
    }

    public Bericht ausfuehren(MandantCode code) {
        return ausfuehren(code, Instant.now());
    }

    Bericht ausfuehren(MandantCode code, Instant jetzt) {
        var status = mandanten.status(code).orElseThrow(() -> new FachFehler(Fehlercode.MANDANT_FEHLT));
        if (status.mandant().aktiv() || status.deaktiviertAm().isEmpty()) {
            throw new FachFehler(Fehlercode.MANDANT_FEHLT);
        }
        Instant loeschbarAb = status.deaktiviertAm().orElseThrow().plus(FRIST);
        List<IdentitaetsDienst.Benutzer> benutzer = benutzerLesen(code);
        if (!jetzt.isBefore(loeschbarAb)) {
            benutzer.forEach(eintrag -> identitaet.loeschen(code.wert(), eintrag.id()));
            return new Bericht(0, benutzer.size(), loeschbarAb);
        }
        for (IdentitaetsDienst.Benutzer eintrag : benutzer) {
            identitaet.aktivieren(code.wert(), eintrag.id(), false);
            identitaet.sitzungenBeenden(code.wert(), eintrag.id());
        }
        return new Bericht(benutzer.size(), 0, loeschbarAb);
    }

    private List<IdentitaetsDienst.Benutzer> benutzerLesen(MandantCode code) {
        List<IdentitaetsDienst.Benutzer> ergebnis = new ArrayList<>();
        for (int seite = 0; ; seite++) {
            List<IdentitaetsDienst.Benutzer> treffer = identitaet.benutzerSuchen(code.wert(), null, seite);
            if (treffer.size() > IdentitaetsDienst.MAX_SEITENGROESSE
                    || ergebnis.size() + treffer.size() > MAX_BENUTZER) {
                throw new IllegalStateException("Mandantenabbau überschreitet die Benutzergrenze");
            }
            ergebnis.addAll(treffer);
            if (treffer.size() < IdentitaetsDienst.MAX_SEITENGROESSE) {
                return List.copyOf(ergebnis);
            }
        }
    }
}
