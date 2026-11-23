package ch.urinet.archiv.wissen.adapter.out;

import ch.urinet.archiv.ki.application.WissenschatZugang;
import ch.urinet.archiv.sicherheit.application.Berechtigung;
import ch.urinet.archiv.sicherheit.domain.Aktion;
import ch.urinet.archiv.sicherheit.domain.Benutzer;
import ch.urinet.archiv.wissen.application.WissenModellBudget;
import jakarta.enterprise.context.ApplicationScoped;

/** {@link WissenschatZugang}: A15 über die Rechtematrix und das Monatsbudget der Wissensbasis (Requirement 13.6). */
@ApplicationScoped
public class WissenschatZugangAdapter implements WissenschatZugang {

    private final Berechtigung berechtigung;
    private final WissenModellBudget budget;

    public WissenschatZugangAdapter(Berechtigung berechtigung, WissenModellBudget budget) {
        this.berechtigung = berechtigung;
        this.budget = budget;
    }

    @Override
    public void rechtPruefen(Benutzer benutzer) {
        berechtigung.wissensseitenAktion(benutzer, Aktion.A15, true);
    }

    @Override
    public void budgetPruefen() {
        budget.chatPruefen();
    }
}
