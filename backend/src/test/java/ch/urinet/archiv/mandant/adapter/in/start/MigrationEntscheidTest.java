package ch.urinet.archiv.mandant.adapter.in.start;

import ch.urinet.archiv.mandant.adapter.in.start.MigrationBeimStart.Schritt;
import ch.urinet.archiv.mandant.application.MandantMigration;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static ch.urinet.archiv.mandant.adapter.in.start.MigrationBeimStart.ausfuehren;
import static ch.urinet.archiv.mandant.adapter.in.start.MigrationBeimStart.entscheiden;
import static ch.urinet.archiv.mandant.adapter.in.start.MigrationBeimStart.meldet;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Welche Laufart beim Start migriert oder prüft und wann sie die Bereitschaft meldet (design.md «Mandant», Req 1.7); ohne Quarkus. */
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

    @Test
    void bereitschaftErstNachErfolgreicherMigrationOderPruefung() {
        AtomicInteger gemeldet = new AtomicInteger();
        ausfuehren(Schritt.MIGRIEREN, true, () -> migration(false), gemeldet::incrementAndGet);
        ausfuehren(Schritt.PRUEFEN, true, () -> migration(false), gemeldet::incrementAndGet);
        assertThat(gemeldet).hasValue(2);
    }

    @Test
    void abgebrocheneMigrationMeldetKeineBereitschaft() {
        AtomicInteger gemeldet = new AtomicInteger();
        assertThatThrownBy(() -> ausfuehren(Schritt.MIGRIEREN, true, () -> migration(true), gemeldet::incrementAndGet))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ausfuehren(Schritt.PRUEFEN, true, () -> migration(true), gemeldet::incrementAndGet))
                .isInstanceOf(IllegalStateException.class);
        assertThat(gemeldet).hasValue(0);
    }

    @Test
    void ohneDatenquelleUndBeimKommandoKeineBereitschaft() {
        assertThat(meldet("api,worker", true)).isTrue();
        assertThat(meldet("worker", true)).isTrue();
        assertThat(meldet("api,worker", false)).isFalse();
        assertThat(meldet("kommando", true)).isFalse();
        assertThat(meldet("api,kommando", true)).isFalse();

        AtomicInteger gemeldet = new AtomicInteger();
        ausfuehren(Schritt.NICHTS, false, () -> migration(true), gemeldet::incrementAndGet);
        assertThat(gemeldet).hasValue(0);
    }

    private static MandantMigration migration(boolean abbrechen) {
        return new MandantMigration(null, null, null) {
            @Override
            public int aktiveMigrieren() {
                return ergebnis();
            }

            @Override
            public int aktivePruefen() {
                return ergebnis();
            }

            private int ergebnis() {
                if (abbrechen) {
                    throw new IllegalStateException("Schema abweichend");
                }
                return 1;
            }
        };
    }
}
