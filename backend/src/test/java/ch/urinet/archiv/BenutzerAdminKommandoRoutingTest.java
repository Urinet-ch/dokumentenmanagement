package ch.urinet.archiv;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BenutzerAdminKommandoRoutingTest {

    @Test
    void archivMainErkenntNeueAdminKommandosUndLehntFalscheAufrufeVorDemStartAb() {
        assertThat(ArchivMain.aufrufPruefen("mandant", "inhaber-einladen", "vhf-gsk",
                "--benutzer", "test.user", "--email", "test@example.invalid", "--vorname", "Test",
                "--nachname", "Person", "--wer", "test-betreiber")).isEmpty();
        assertThat(ArchivMain.aufrufPruefen("benutzer", "abgleichen", "urinet", "--pruefen", "--json"))
                .isEmpty();
        assertThat(ArchivMain.aufrufPruefen("mandant", "benutzer-sperren", "vhf-gsk", "--json"))
                .isEmpty();
        assertThat(ArchivMain.aufrufPruefen("benutzer", "abgleichen", "Ungueltig")).isPresent();
        assertThat(ArchivMain.aufrufPruefen("mandant", "benutzer-sperren", "vhf-gsk", "--pruefen")).isPresent();
    }
}
