package ch.urinet.archiv.ki.application.chat;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.ki.domain.chat.Chatmodus;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.sicherheit.application.Berechtigung;
import ch.urinet.archiv.sicherheit.domain.Aktion;
import ch.urinet.archiv.sicherheit.domain.Benutzer;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import ch.urinet.archiv.wissen.application.WissenModellBudget;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Spec 04, Requirement 21.1 und 21.7: Frage in einem Gespräch im Modus WISSEN. Ohne A15 antwortet 403 vor dem 429 des
 * Budgets; weder Modell noch Speicher (über die Sperren hinaus) werden berührt.
 */
class ChatFragenModusTest {

    private static final Benutzer BENUTZER = new Benutzer("b", Rolle.MITARBEITER);
    private final List<String> speicherAufrufe = new ArrayList<>();
    private final List<String> ereignisse = new ArrayList<>();

    private ChatFragen fragen(boolean a15, boolean budgetErschoepft) {
        ChatSpeicher speicher = (ChatSpeicher) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {ChatSpeicher.class}, (p, m, args) -> {
                    speicherAufrufe.add(m.getName());
                    return switch (m.getName()) {
                        case "benutzerSperren" -> null;
                        case "gespraechSperren" -> Optional.of(Chatmodus.WISSEN);
                        default -> throw new AssertionError("Speicher berührt: " + m.getName());
                    };
                });
        Berechtigung berechtigung = new Berechtigung(null, null) {
            @Override
            public void wissensseitenAktion(Benutzer benutzer, Aktion aktion, boolean sichtbar) {
                ereignisse.add("A15 " + aktion);
                if (!a15) {
                    throw new IllegalStateException("403");
                }
            }
        };
        WissenModellBudget budget = new WissenModellBudget(null, null, false, false) {
            @Override
            public void chatPruefen() {
                ereignisse.add("budget");
                if (budgetErschoepft) {
                    throw new FachFehler(Fehlercode.BUDGET);
                }
            }
        };
        ChatAgentPort agent = (anfrage, ausgabe, abbruch) -> {
            throw new AssertionError("Modell darf nicht aufgerufen werden");
        };
        return new ChatFragen(agent, speicher, null, null, null, berechtigung, budget, null, 100);
    }

    @Test
    void ohneA15KommtDas403VorDem429DesBudgets() {
        ChatFragen fragen = fragen(false, true);
        assertThatThrownBy(() -> fragen.annehmen(BENUTZER, new MandantCode("urinet"), Optional.empty(), "Frage?",
                Optional.of(UUID.randomUUID()))).hasMessage("403");
        assertThat(ereignisse).containsExactly("A15 A15");
        assertThat(speicherAufrufe).containsExactly("benutzerSperren", "gespraechSperren");
    }

    @Test
    void mitA15UndErschoepftemBudgetKommtDas429OhneWeitereSpeicherzugriffe() {
        ChatFragen fragen = fragen(true, true);
        assertThatThrownBy(() -> fragen.annehmen(BENUTZER, new MandantCode("urinet"), Optional.empty(), "Frage?",
                Optional.of(UUID.randomUUID())))
                .isInstanceOfSatisfying(FachFehler.class, e -> assertThat(e.code()).isEqualTo(Fehlercode.BUDGET));
        assertThat(ereignisse).containsExactly("A15 A15", "budget");
        assertThat(speicherAufrufe).containsExactly("benutzerSperren", "gespraechSperren");
    }
}
