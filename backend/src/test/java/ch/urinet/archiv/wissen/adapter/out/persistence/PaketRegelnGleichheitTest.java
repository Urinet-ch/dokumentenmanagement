package ch.urinet.archiv.wissen.adapter.out.persistence;

import ch.urinet.archiv.wissen.WissenImportSchritte;
import ch.urinet.archiv.wissen.domain.paket.WissensPaketBeispiele;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

@QuarkusTest
class PaketRegelnGleichheitTest {
    @Inject WissenImportSchritte schritte;

    @BeforeEach
    void vorbereiten() throws Exception {
        schritte.vorbereiten();
    }

    @AfterEach
    void aufraeumen() throws Exception {
        schritte.aufraeumen();
    }

    static Stream<Arguments> beispiele() throws Exception {
        return WissensPaketBeispiele.alle().stream().map(b -> Arguments.of(b.name(), b));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("beispiele")
    void gleicheRegeln(String name, WissensPaketBeispiele.Beispiel beispiel) {
        schritte.regelgleichheit(beispiel);
    }
}
