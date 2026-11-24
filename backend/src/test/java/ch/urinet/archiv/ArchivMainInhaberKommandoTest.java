package ch.urinet.archiv;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ArchivMainInhaberKommandoTest {
    @Test
    void inhaberUebernahmeWirdVorDemStartValidiert() {
        String ziel = "11111111-1111-4111-8111-111111111111";
        String betreiber = "22222222-2222-4222-8222-222222222222";
        assertThat(ArchivMain.aufrufPruefen("benutzer", "inhaber-uebernehmen", "probe",
                "--benutzer", ziel, "--wer", betreiber)).isEmpty();
        assertThat(ArchivMain.aufrufPruefen("benutzer", "inhaber-uebernehmen", "probe",
                "--benutzer", "ungueltig", "--wer", betreiber)).isPresent();
    }
}
