package ch.urinet.archiv.verarbeitung.adapter.out.mail;

import ch.urinet.archiv.ablage.domain.Versionsseite;
import ch.urinet.archiv.verarbeitung.application.PrueflaufRegister.Fortsetzung;
import ch.urinet.archiv.verarbeitung.application.PrueflaufRegister.Phase;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Meldung zum abgebrochenen Prüflauf (Req 15.5): Lauf-ID, Teil, Phase, Marke und Fehler-ID, sonst nichts (Req 16.7). */
class MailInhaberBenachrichtigungTest {

    @Test
    void abbruchNenntLaufPhaseMarkeUndFehlerId() {
        UUID lauf = UUID.randomUUID();
        UUID fehler = UUID.randomUUID();
        String schluessel = "manifest/2026-11-30.json";
        Fortsetzung stand = new Fortsetzung(Phase.MANIFEST_VERSIONEN, Optional.empty(),
                Optional.of(new Versionsseite.Marke(schluessel, "v7")));

        assertThat(MailInhaberBenachrichtigung.abbruchBetreff(lauf)).contains(lauf.toString());
        assertThat(MailInhaberBenachrichtigung.abbruchText(lauf, 4, stand, fehler)).contains(lauf.toString(),
                "Teil: 4", "Phase: MANIFEST_VERSIONEN", "Nach Dokument: -", "Marke: " + schluessel + ", Version v7",
                fehler.toString());
    }
}
