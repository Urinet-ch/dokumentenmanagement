package ch.urinet.archiv.export.application;

import ch.urinet.archiv.sicherheit.domain.Rolle;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Recht des Jahresexports (Spec 02, Req 7.1, 7.5). */
class ExportrechtTest {

    @Test
    void nurInhaberUndTreuhaender() {
        assertThat(Exportrecht.erlaubt(Rolle.INHABER)).isTrue();
        assertThat(Exportrecht.erlaubt(Rolle.TREUHAENDER)).isTrue();
        assertThat(Exportrecht.erlaubt(Rolle.MITARBEITER)).isFalse();
        assertThat(Exportrecht.erlaubt(Rolle.GESCHAEFTSSTELLE)).isFalse();
        assertThat(Exportrecht.erlaubt(null)).isFalse();
    }
}
