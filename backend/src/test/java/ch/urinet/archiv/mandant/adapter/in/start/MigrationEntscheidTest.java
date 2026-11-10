package ch.urinet.archiv.mandant.adapter.in.start;

import ch.urinet.archiv.mandant.adapter.in.start.MigrationBeimStart.Schritt;
import org.junit.jupiter.api.Test;

import static ch.urinet.archiv.mandant.adapter.in.start.MigrationBeimStart.entscheiden;
import static org.assertj.core.api.Assertions.assertThat;

/** Welche Laufart beim Start migriert oder prüft (design.md «Mandant», Req 1.7); ohne Quarkus. */
class MigrationEntscheidTest {

    @Test
    void apiMigriertUndPrueft() {
        assertThat(entscheiden("api", true, "validate", true)).isEqualTo(Schritt.MIGRIEREN);
        assertThat(entscheiden(" api , worker ", true, "validate", true)).isEqualTo(Schritt.MIGRIEREN);
        assertThat(entscheiden("api", true, "none", true)).isEqualTo(Schritt.MIGRIEREN);
    }

    @Test
    void apiOhneMigrationPrueftNurBeiValidate() {
        assertThat(entscheiden("api", false, "validate", true)).isEqualTo(Schritt.PRUEFEN);
        assertThat(entscheiden("api", false, "none", true)).isEqualTo(Schritt.NICHTS);
    }

    @Test
    void workerMigriertNieUndPrueft() {
        assertThat(entscheiden("worker", true, "validate", true)).isEqualTo(Schritt.PRUEFEN);
        assertThat(entscheiden("worker", false, "validate", true)).isEqualTo(Schritt.PRUEFEN);
        assertThat(entscheiden("worker", true, "none", true)).isEqualTo(Schritt.NICHTS);
    }

    @Test
    void kommandoUndOhneDatenquelleTunNichts() {
        assertThat(entscheiden("kommando", true, "validate", true)).isEqualTo(Schritt.NICHTS);
        assertThat(entscheiden("api,kommando", true, "validate", true)).isEqualTo(Schritt.NICHTS);
        assertThat(entscheiden("api", true, "validate", false)).isEqualTo(Schritt.NICHTS);
        assertThat(entscheiden("worker", false, "validate", false)).isEqualTo(Schritt.NICHTS);
    }
}
