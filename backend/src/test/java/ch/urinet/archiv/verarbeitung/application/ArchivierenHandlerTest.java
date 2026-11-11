package ch.urinet.archiv.verarbeitung.application;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code sperre_bis} aus der Object-Lock-Sperre (Req 6.3), ohne Quarkus. */
class ArchivierenHandlerTest {

    @Test
    void sperreBisIstDerLetzteGesperrteTagInZuerich() {
        // Mitternacht Zürich im Winter (UTC+1): gesperrt bis und mit 31.12.2036
        assertThat(ArchivierenHandler.sperreBis(Optional.of(Instant.parse("2036-12-31T23:00:00Z"))))
                .contains(LocalDate.of(2036, 12, 31));
        // Mitternacht Zürich im Sommer (UTC+2)
        assertThat(ArchivierenHandler.sperreBis(Optional.of(Instant.parse("2027-06-30T22:00:00Z"))))
                .contains(LocalDate.of(2027, 6, 30));
        assertThat(ArchivierenHandler.sperreBis(Optional.empty())).isEmpty();
    }
}
