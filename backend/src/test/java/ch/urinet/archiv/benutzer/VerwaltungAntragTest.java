package ch.urinet.archiv.benutzer;

import ch.urinet.archiv.benutzer.application.VerwaltungAntragSpeicher.Antrag;
import ch.urinet.archiv.benutzer.application.VerwaltungAntragSpeicher.Art;
import ch.urinet.archiv.benutzer.application.VerwaltungAntragSpeicher.Status;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VerwaltungAntragTest {

    @Test
    void antragDarfNurDieZulaessigenAngabenUndFristenHaben() {
        var id = UUID.randomUUID();
        var zeit = Instant.parse("2026-01-01T10:00:00Z");
        var antrag = new Antrag(id, Art.LOESCHEN, "ziel", null, "Synthetischer Grund", "akteur",
                zeit, zeit.plusSeconds(60), Status.OFFEN, null, null);
        assertThat(antrag.toString()).doesNotContain("ziel", "Synthetischer Grund", "akteur");
        assertThatThrownBy(() -> new Antrag(id, Art.INHABER_ROLLE, "ziel", Rolle.MITARBEITER, null, "akteur",
                zeit, zeit.plusSeconds(60), Status.OFFEN, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Antrag(id, Art.LOESCHEN, "ziel", null, "", "akteur",
                zeit, zeit.plusSeconds(60), Status.OFFEN, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Antrag(id, Art.LOESCHEN, "ziel", null, "Grund", "akteur",
                zeit, zeit, Status.OFFEN, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Antrag(id, Art.LOESCHEN, "ziel", null, "Grund", "akteur",
                zeit, zeit.plusSeconds(60), Status.OFFEN, "anderer", zeit))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Antrag(id, Art.LOESCHEN, "ziel", null, "Grund", "akteur",
                zeit, zeit.plusSeconds(60), Status.BESTAETIGT, null, zeit))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
