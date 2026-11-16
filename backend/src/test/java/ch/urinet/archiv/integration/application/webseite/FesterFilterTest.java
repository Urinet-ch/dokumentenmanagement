package ch.urinet.archiv.integration.application.webseite;

import ch.urinet.archiv.integration.domain.webseite.Lesezugriff;
import ch.urinet.archiv.integration.domain.webseite.Zugriffsstufe;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Aufbau des festen Filters (Spec 03, Aufgabe 11.1); die Wirkung auf die Datenbank belegen die Szenarien. */
class FesterFilterTest {

    @Test
    void bedingungNenntAlleVierPraedikate() {
        assertThat(FesterFilter.bedingung("d")).isEqualTo("(d.status = 'ARCHIVIERT' and d.vertraulichkeit = 'NORMAL' "
                + "and d.zusatz ->> 'veroeffentlicht' = 'true' and d.zusatz ->> 'zugriff' = any(:stufen))");
    }

    @Test
    void aliasIstEinFesterBezeichner() {
        for (String alias : new String[] {"", "D", "d; drop table dokument", "d.x", "1d"}) {
            assertThatThrownBy(() -> FesterFilter.bedingung(alias)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void stufenKommenAusDerWirksamenStufe() {
        assertThat(FesterFilter.stufen(new Lesezugriff("w", Zugriffsstufe.VORSTAND, Zugriffsstufe.MITGLIEDER,
                Optional.empty()))).containsExactly("oeffentlich", "mitglieder");
    }
}
