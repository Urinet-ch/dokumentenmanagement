package ch.urinet.archiv.wissen.application;

import ch.urinet.archiv.sicherheit.domain.Aktion;
import ch.urinet.archiv.sicherheit.domain.Benutzer;
import ch.urinet.archiv.sicherheit.domain.Ergebnis;
import ch.urinet.archiv.sicherheit.domain.Rechtematrix;
import ch.urinet.archiv.sicherheit.domain.Sichtregel;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class WissenSichtbarkeit {

    private final WissenSichtbarkeitRepository repository;

    public WissenSichtbarkeit(WissenSichtbarkeitRepository repository) {
        this.repository = repository;
    }

    public List<UUID> sichtbareSeiten(Benutzer benutzer) {
        return darfLesen(benutzer) ? repository.sichtbareSeiten(Sichtregel.fuer(benutzer)) : List.of();
    }

    public boolean aktuelleSeiteSichtbar(Benutzer benutzer, UUID seiteId) {
        return darfLesen(benutzer) && repository.aktuelleSeiteSichtbar(seiteId, Sichtregel.fuer(benutzer));
    }

    /** Eine Version ist nur sichtbar, wenn die Seite selbst und die Version nach Hülle und Mindeststufe sichtbar sind. */
    public boolean versionSichtbar(Benutzer benutzer, UUID seiteId, int nummer) {
        return darfLesen(benutzer) && repository.versionSichtbar(seiteId, nummer, Sichtregel.fuer(benutzer));
    }

    private static boolean darfLesen(Benutzer benutzer) {
        Ergebnis ergebnis = Rechtematrix.ergebnis(benutzer.rolle(), Aktion.A15);
        return ergebnis == Ergebnis.ERLAUBT || ergebnis == Ergebnis.NUR_LESEND;
    }
}
