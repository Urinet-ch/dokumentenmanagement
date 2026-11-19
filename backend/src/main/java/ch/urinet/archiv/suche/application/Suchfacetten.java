package ch.urinet.archiv.suche.application;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.konfiguration.application.KonfigurationLesen;
import ch.urinet.archiv.konfiguration.domain.Klassifikation;
import ch.urinet.archiv.konfiguration.domain.KlassifikationWert;
import ch.urinet.archiv.sicherheit.domain.Benutzer;
import ch.urinet.archiv.sicherheit.domain.Sichtregel;
import ch.urinet.archiv.suche.domain.Suchanfrage;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.util.Set;
import java.util.stream.Collectors;

/** Validiert Filter gegen die aktive Konfiguration und liefert sichtbarkeitsgeprüfte Facetten. */
@ApplicationScoped
@Transactional
public class Suchfacetten {

    private final SuchfacettenPort port;
    private final KonfigurationLesen konfiguration;

    public Suchfacetten(SuchfacettenPort port, KonfigurationLesen konfiguration) {
        this.port = port;
        this.konfiguration = konfiguration;
    }

    public void pruefen(Suchanfrage anfrage) {
        if (anfrage.klassifikationen().isEmpty()) {
            return;
        }
        var abbild = konfiguration.aktuell();
        for (var eintrag : anfrage.klassifikationen().entrySet()) {
            Klassifikation klassifikation = abbild.klassifikationen().stream()
                    .filter(k -> k.code().equals(eintrag.getKey()))
                    .findFirst().orElse(null);
            if (klassifikation == null || !klassifikation.aktiv() || !klassifikation.suchfilter()) {
                throw ungueltig();
            }
            Set<String> aktiveWerte = klassifikation.werte().stream().filter(KlassifikationWert::aktiv)
                    .map(KlassifikationWert::code).collect(Collectors.toSet());
            if (!aktiveWerte.containsAll(eintrag.getValue())) {
                throw ungueltig();
            }
        }
    }

    public java.util.List<SuchfacettenPort.Facette> suchen(Benutzer benutzer, Suchanfrage anfrage) {
        return port.facetten(anfrage, Sichtregel.fuer(benutzer), true);
    }

    private static FachFehler ungueltig() {
        return new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
    }
}
