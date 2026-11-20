package ch.urinet.archiv.wissen.application;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.verarbeitung.application.AuftragService;
import ch.urinet.archiv.verarbeitung.domain.Nutzlast;
import ch.urinet.archiv.wissen.domain.WissensLint;
import ch.urinet.archiv.wissen.domain.WissensStufe;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class WissenLintStarten {
    public static final String SYSTEM = "process:wissen-lint";

    private final WissenLintRepository lint;
    private final WissenLintSpeichern speichern;
    private final WissenKonfiguration konfiguration;
    private final WissenEinstellungenRepository einstellungen;
    private final AuftragService auftraege;
    private final String standardZeitplan;

    public WissenLintStarten(WissenLintRepository lint, WissenLintSpeichern speichern,
                             WissenKonfiguration konfiguration, WissenEinstellungenRepository einstellungen,
                             AuftragService auftraege,
                             @ConfigProperty(name = "archiv.wissen.lint-zeitplan", defaultValue = "0 0 4 ? * SUN")
                             String standardZeitplan) {
        this.lint = lint;
        this.speichern = speichern;
        this.konfiguration = konfiguration;
        this.einstellungen = einstellungen;
        this.auftraege = auftraege;
        this.standardZeitplan = standardZeitplan;
    }

    @Transactional
    public UUID manuell(String benutzer) {
        return starten("human:" + benutzer, null).orElseThrow(() -> new FachFehler(Fehlercode.STATUS_KONFLIKT));
    }

    @Transactional
    public Optional<UUID> geplant(Instant termin) {
        return starten(SYSTEM, termin);
    }

    @Transactional
    public String zeitplan() {
        String wert = einstellungen.lesen().lintZeitplan();
        return wert == null ? standardZeitplan : wert;
    }

    private Optional<UUID> starten(String akteur, Instant termin) {
        boolean geplant = termin != null;
        boolean laeuft = auftraege.offen(WissenLintSpeichern.TYP, Optional.empty(), true).isPresent();
        if (geplant && lint.zeitplanBereits(termin)) {
            return Optional.empty();
        }
        if (laeuft) {
            if (geplant) {
                return Optional.empty();
            }
            throw new FachFehler(Fehlercode.STATUS_KONFLIKT);
        }
        UUID id = lint.anlegen(akteur, konfiguration.modell());
        var lauf = lint.laden(id).orElseThrow();
        var budget = speichern.budgetReservieren(lauf.entwurfId(), 1, Instant.now());
        if (!budget.reserviert()) {
            if (!geplant) {
                throw new FachFehler(Fehlercode.BUDGET);
            }
            var stand = new WissenLintRepository.Fortschritt(
                    List.of(new WissensLint.Befund(null, "BUDGET_ERSCHOEPFT", null)), List.of(), 0, List.of(), 0,
                    List.of(), WissensStufe.NORMAL, null, false, List.of("Budget erschöpft"), List.of());
            lint.abschliessen(id, stand, 0, 0, true, "Budget erschöpft", false);
            return Optional.of(id);
        }
        speichern.budgetFreigeben(budget.id());
        auftraege.einreihen(WissenLintSpeichern.TYP, new Nutzlast(Map.of(WissenLintSpeichern.LINT_ID, id)));
        return Optional.of(id);
    }
}
