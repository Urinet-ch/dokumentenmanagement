package ch.urinet.archiv.wissen.application;

import ch.urinet.archiv.sicherheit.domain.Aktion;
import ch.urinet.archiv.sicherheit.domain.Benutzer;
import ch.urinet.archiv.sicherheit.domain.Ergebnis;
import ch.urinet.archiv.sicherheit.domain.Rechtematrix;
import ch.urinet.archiv.wissen.domain.WissensStufe;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@ApplicationScoped
public class WissenSichtbarkeit {

    private final WissenSichtbarkeitRepository repository;
    private final WissensDokumentSichtbarkeit dokumentSichtbarkeit;

    public WissenSichtbarkeit(WissenSichtbarkeitRepository repository,
                              WissensDokumentSichtbarkeit dokumentSichtbarkeit) {
        this.repository = repository;
        this.dokumentSichtbarkeit = dokumentSichtbarkeit;
    }

    public List<UUID> sichtbareSeiten(Benutzer benutzer) {
        return repository.aktuelleSeiten().stream()
                .filter(huelle -> sichtbar(benutzer, huelle))
                .map(WissensHuelleZugriff::seiteId)
                .toList();
    }

    public boolean aktuelleSeiteSichtbar(Benutzer benutzer, UUID seiteId) {
        return repository.aktuelleSeite(seiteId).filter(huelle -> sichtbar(benutzer, huelle)).isPresent();
    }

    /** Eine Version ist nur sichtbar, wenn die Seite selbst und die Version nach Hülle und Mindeststufe sichtbar sind. */
    public boolean versionSichtbar(Benutzer benutzer, UUID seiteId, int nummer) {
        return aktuelleSeiteSichtbar(benutzer, seiteId)
                && repository.version(seiteId, nummer).map(WissenSichtbarkeit::ohneHuelleIntern)
                        .filter(huelle -> sichtbar(benutzer, huelle)).isPresent();
    }

    // Fehlende Hüllenzeilen lassen sich nicht von einer leeren Hülle unterscheiden; die Version gilt dann als INTERN.
    private static WissensHuelleZugriff ohneHuelleIntern(WissensHuelleZugriff huelle) {
        return huelle.dokumente().isEmpty()
                ? new WissensHuelleZugriff(huelle.seiteId(), WissensStufe.INTERN, Set.of())
                : huelle;
    }

    private boolean sichtbar(Benutzer benutzer, WissensHuelleZugriff huelle) {
        if (!lesend(Rechtematrix.ergebnis(benutzer.rolle(), Aktion.A15))
                || !mindeststufeSichtbar(benutzer, huelle.mindeststufe())) {
            return false;
        }
        return huelle.dokumente().stream().allMatch(
                dokumentId -> dokumentSichtbarkeit.sichtbar(benutzer, dokumentId));
    }

    private static boolean mindeststufeSichtbar(Benutzer benutzer, WissensStufe mindeststufe) {
        Aktion aktion = mindeststufe == WissensStufe.NORMAL ? Aktion.A2 : Aktion.A3;
        return lesend(Rechtematrix.ergebnis(benutzer.rolle(), aktion));
    }

    private static boolean lesend(Ergebnis ergebnis) {
        return ergebnis == Ergebnis.ERLAUBT || ergebnis == Ergebnis.NUR_LESEND;
    }
}
