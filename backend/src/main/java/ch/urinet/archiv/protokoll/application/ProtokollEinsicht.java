package ch.urinet.archiv.protokoll.application;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.protokoll.domain.ProtokollFilter;
import ch.urinet.archiv.protokoll.domain.ProtokollZeile;
import ch.urinet.archiv.protokoll.domain.VerzeichnisEintrag;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Einsicht ins Zugriffsprotokoll und Benutzerverzeichnis des eigenen Mandanten (A13, nur Inhaber; Req 2.19, 10.5,
 * 10.10). Den Austritt einer Person erfasst ebenfalls der Inhaber: Er beendet die Zuordnung mit einer neuen Zeile.
 */
@ApplicationScoped
public class ProtokollEinsicht {

    private final ProtokollAbfrage abfrage;
    private final BenutzerVerzeichnis verzeichnis;

    public ProtokollEinsicht(ProtokollAbfrage abfrage, BenutzerVerzeichnis verzeichnis) {
        this.abfrage = abfrage;
        this.verzeichnis = verzeichnis;
    }

    public ProtokollZeile.Seite suchen(ProtokollFilter filter) {
        return abfrage.suchen(filter);
    }

    public List<VerzeichnisEintrag> verzeichnis() {
        return verzeichnis.liste();
    }

    /**
     * Austritt einer Person: neue Zeile mit Name und Rolle der letzten, gültig ab deren Beginn bis «jetzt». Unbekannte
     * Kennung → {@code NICHT_GEFUNDEN}; schon beendet oder ein API-Token (endet mit dem Widerruf, A14) →
     * {@code STATUS_KONFLIKT}.
     */
    @Transactional
    public void austritt(String benutzer, Optional<String> erfasstVon) {
        if (benutzer == null || benutzer.isBlank() || benutzer.length() > ProtokollFilter.MAX_BENUTZER_LAENGE) {
            throw new FachFehler(Fehlercode.NICHT_GEFUNDEN);
        }
        // vor dem Lesen: zwei Austritte nacheinander, eine laufende Aktion der Person zuerst festgeschrieben
        verzeichnis.sperren(Optional.empty(), benutzer, BenutzerVerzeichnis.Sperre.ABSCHLUSS);
        VerzeichnisEintrag letzter = verzeichnis.letzter(Optional.empty(), benutzer)
                .orElseThrow(() -> new FachFehler(Fehlercode.NICHT_GEFUNDEN));
        if (letzter.beendet() || VerzeichnisEintrag.istToken(benutzer)) {
            throw new FachFehler(Fehlercode.STATUS_KONFLIKT);
        }
        verzeichnis.einfuegen(Optional.empty(), new BenutzerVerzeichnis.Neu(benutzer, letzter.name(), letzter.rolle(),
                Optional.of(letzter.gueltigVon()), true, VerzeichnisEintrag.Anlass.AUSTRITT, erfasstVon));
    }
}
