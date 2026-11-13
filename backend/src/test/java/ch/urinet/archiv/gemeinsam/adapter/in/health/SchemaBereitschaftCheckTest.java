package ch.urinet.archiv.gemeinsam.adapter.in.health;

import ch.urinet.archiv.gemeinsam.application.SchemaBereitschaft;
import ch.urinet.archiv.gemeinsam.application.SchemasBereit;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.NotificationOptions;
import jakarta.enterprise.util.TypeLiteral;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionStage;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Readiness «Mandanten-Schemas migriert» (design.md «Mandant», «Start und Migration»; Review PR #113): DOWN bis zum
 * Signal, danach UP; das Signal feuert {@link SchemasBereit} genau einmal. Über HTTP ist DOWN heute nicht sichtbar, weil
 * der Server erst nach der Migration öffnet ({@code JobsVorMigrationTest}).
 */
class SchemaBereitschaftCheckTest {

    @Test
    void downBisZumSignalDanachUp() {
        List<SchemasBereit> gefeuert = new ArrayList<>();
        SchemaBereitschaft bereitschaft = new SchemaBereitschaft(new Ereignis(gefeuert));
        SchemaBereitschaftCheck check = new SchemaBereitschaftCheck(bereitschaft);

        HealthCheckResponse vorher = check.call();
        assertThat(vorher.getName()).isEqualTo(SchemaBereitschaftCheck.NAME);
        assertThat(vorher.getStatus()).isEqualTo(HealthCheckResponse.Status.DOWN);
        assertThat(gefeuert).isEmpty();

        bereitschaft.melden();
        bereitschaft.melden();
        assertThat(check.call().getStatus()).isEqualTo(HealthCheckResponse.Status.UP);
        assertThat(gefeuert).hasSize(1);
    }

    /** Hält gefeuerte Ereignisse fest; nur {@code fire} wird gebraucht. */
    private record Ereignis(List<SchemasBereit> gefeuert) implements Event<SchemasBereit> {

        @Override
        public void fire(SchemasBereit ereignis) {
            gefeuert.add(ereignis);
        }

        @Override
        public <U extends SchemasBereit> CompletionStage<U> fireAsync(U ereignis) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <U extends SchemasBereit> CompletionStage<U> fireAsync(U ereignis, NotificationOptions optionen) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Event<SchemasBereit> select(Annotation... qualifier) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <U extends SchemasBereit> Event<U> select(Class<U> typ, Annotation... qualifier) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <U extends SchemasBereit> Event<U> select(TypeLiteral<U> typ, Annotation... qualifier) {
            throw new UnsupportedOperationException();
        }
    }
}
