package ch.urinet.archiv.konfiguration;

import ch.urinet.archiv.konfiguration.domain.Einstellungskatalog;
import io.cucumber.java.de.Dann;

import static org.assertj.core.api.Assertions.assertThat;

public class EinstellungskatalogSchritte {
    @Dann("enthält der Einstellungskatalog nur freigegebene mandantenbezogene Schlüssel")
    public void katalogEnthaeltNurMandanteneinstellungen() {
        assertThat(Einstellungskatalog.alle()).extracting(Einstellungskatalog.Einstellung::schluessel)
                .containsExactlyInAnyOrder("geschaeftsjahrEnde", "kennungen.uid", "kennungen.ibans",
                        "branding.name", "branding.primaerfarbe", "chat.aufbewahrungMonate",
                        "webseitenChat.aufbewahrungTage", "wissen.budgetTokens", "wissen.lintBudgetTokens",
                        "wissen.monatsbudgetTokens", "wissen.lintZeitplan.wochentag", "wissen.lintZeitplan.stunde");
    }

    @Dann("gilt ohne eigenen Zahlenwert zuerst der bisherige Startwert und danach der Katalogstandard")
    public void standardUndStartwert() {
        var aufbewahrung = Einstellungskatalog.Zahl.WEBSEITEN_CHAT_AUFBEWAHRUNG;
        assertThat(aufbewahrung.wirksamerWert(null, 25, "vhf-gsk")).isEqualTo(25);
        assertThat(aufbewahrung.wirksamerWert(null, null, "vhf-gsk")).isEqualTo(30);
        assertThat(aufbewahrung.wirksamerWert(null, null, "urinet")).isEqualTo(90);
    }
}
