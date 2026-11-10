package ch.urinet.archiv.sicherheit.adapter.in.kommando;

import ch.urinet.archiv.sicherheit.application.ApiClientRegister;
import ch.urinet.archiv.sicherheit.application.ApiTokenVerwaltung;
import ch.urinet.archiv.sicherheit.application.GeheimnisHasher;
import ch.urinet.archiv.sicherheit.application.HashSperre;
import ch.urinet.archiv.sicherheit.domain.ApiClient;
import ch.urinet.archiv.sicherheit.domain.ApiToken;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Exit-Codes und Ausgabe von {@code archiv token erstellen} ohne Quarkus (Req 2.5, 2.20). */
class TokenKommandoTest {

    private final List<ApiClient> angelegt = new ArrayList<>();
    private boolean speicherFehler;
    private final ByteArrayOutputStream aus = new ByteArrayOutputStream();
    private final ByteArrayOutputStream fehler = new ByteArrayOutputStream();

    private final TokenKommando kommando = new TokenKommando(new ApiTokenVerwaltung(new Register(), new Hasher(),
            code -> code.wert().equals("urinet") ? Optional.of("m_urinet") : Optional.empty(),
            new HashSperre(1, Duration.ZERO)));

    @Test
    void erstelltUndGibtDasTokenNurAufStdoutAus() {
        int code = ausfuehren("erstellen", "urinet", "Rechnungsgenerator", "generator");

        assertThat(code).isZero();
        String token = aus.toString(StandardCharsets.UTF_8).strip();
        assertThat(ApiToken.lesen(token)).isPresent();
        assertThat(fehler.toString(StandardCharsets.UTF_8)).contains(angelegt.getFirst().id())
                .doesNotContain(ApiToken.lesen(token).orElseThrow().geheimnis());
    }

    @Test
    void ungueltigerScopeOderInaktiverMandantIstExit1() {
        assertThat(ausfuehren("erstellen", "urinet", "Probe", "admin")).isEqualTo(1);
        assertThat(ausfuehren("erstellen", "probe-inaktiv", "Probe", "import")).isEqualTo(1);
        assertThat(fehler.toString(StandardCharsets.UTF_8)).contains("ANFRAGE_UNGUELTIG", "MANDANT_FEHLT");
        assertThat(aus.size()).isZero();
        assertThat(angelegt).isEmpty();
    }

    @Test
    void falscherAufrufIstExit2() {
        assertThat(ausfuehren("erstellen", "urinet", "Probe")).isEqualTo(2);
        assertThat(ausfuehren("anlegen", "urinet", "Probe", "import")).isEqualTo(2);
        assertThat(fehler.toString(StandardCharsets.UTF_8)).contains(TokenKommando.AUFRUF);
    }

    @Test
    void technischerFehlerIstExit3() {
        speicherFehler = true;

        assertThat(ausfuehren("erstellen", "urinet", "Probe", "import")).isEqualTo(3);
        assertThat(aus.size()).isZero();
    }

    private int ausfuehren(String... argumente) {
        return kommando.ausfuehren(List.of(argumente), new PrintStream(aus, true, StandardCharsets.UTF_8),
                new PrintStream(fehler, true, StandardCharsets.UTF_8));
    }

    private final class Register implements ApiClientRegister {
        @Override
        public Optional<Zugang> finden(String id) {
            return Optional.empty();
        }

        @Override
        public void anlegen(ApiClient client, String hash) {
            if (speicherFehler) {
                throw new IllegalStateException("Datenbank weg");
            }
            angelegt.add(client);
        }

        @Override
        public List<ApiClient> liste(String mandant) {
            return List.copyOf(angelegt);
        }

        @Override
        public boolean widerrufen(String mandant, String id) {
            return false;
        }

        @Override
        public void zuletztSetzen(String id, Instant zeit) {
            // nicht gebraucht
        }
    }

    private static final class Hasher implements GeheimnisHasher {
        @Override
        public String hashen(String geheimnis) {
            return "hash";
        }

        @Override
        public boolean pruefen(String geheimnis, String hash) {
            return false;
        }
    }
}
