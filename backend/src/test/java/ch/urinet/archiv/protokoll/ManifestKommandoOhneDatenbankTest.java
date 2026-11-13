package ch.urinet.archiv.protokoll;

import ch.urinet.archiv.ArchivMain;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.main.LaunchResult;
import io.quarkus.test.junit.main.QuarkusMainLauncher;
import io.quarkus.test.junit.main.QuarkusMainTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code archiv manifest pruefen … --ohne-datenbank} (Req 15.12, docs/manifest-schluessel.md): Das Prüfwerkzeug startet
 * ohne Datenbank und liest nur die Ablage. Die Adresse der Datenbank zeigt darum auf einen Port, an dem nichts läuft; ohne
 * den Schalter scheitert derselbe Aufruf schon beim Start. Die Prüfung selbst ohne Datenbank deckt
 * {@code ManifestPruefenTest} ({@code ohneDatenbank…}).
 */
@QuarkusMainTest
@TestProfile(ManifestKommandoOhneDatenbankTest.KeineDatenbank.class)
class ManifestKommandoOhneDatenbankTest {

    public static class KeineDatenbank implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("quarkus.datasource.jdbc.url", "jdbc:postgresql://127.0.0.1:9/keine",
                    "quarkus.datasource.jdbc.acquisition-timeout", "PT2S");
        }
    }

    @AfterEach
    void kommandoEinstellungenEntfernen() {
        ArchivMain.KOMMANDO.keySet().forEach(System::clearProperty);
        ArchivMain.OHNE_DATENBANK.keySet().forEach(System::clearProperty);
    }

    @Test
    void ohneDatenbankStartetEsUndLiestNurDieAblage(QuarkusMainLauncher starter) {
        LaunchResult ergebnis = starter.launch("manifest", "pruefen", "2026-01-10", "--mandant", "probe-ohne-db",
                "--ohne-datenbank");

        // Der Start gelingt ohne Datenbank; den Bucket des nie angelegten Mandanten gibt es nicht (Mandanten anlegen
        // braucht die Datenbank), das Lesen der Ablage endet darum als technischer Fehler, nicht als Startfehler.
        assertThat(ergebnis.getOutput()).contains("Geprüft nur aus der Ablage (ohne Datenbank)");
        assertThat(ergebnis.getErrorOutput()).contains("Technischer Fehler: NoSuchBucketException")
                .doesNotContain("Start fehlgeschlagen");
        assertThat(ergebnis.exitCode()).isEqualTo(3);
    }

    @Test
    void mitKonfigurierterAberUnerreichbarerDatenbankScheitertDerStart(QuarkusMainLauncher starter) {
        LaunchResult ergebnis = starter.launch("manifest", "pruefen", "2026-01-10", "--mandant", "probe-ohne-db");

        assertThat(ergebnis.exitCode()).isEqualTo(3);
        assertThat(ergebnis.getErrorOutput()).contains("Start fehlgeschlagen");
    }

    @Test
    void ohneDatenbankBrauchtEsDenMandanten(QuarkusMainLauncher starter) {
        LaunchResult ergebnis = starter.launch("manifest", "pruefen", "2026-01-10", "--ohne-datenbank");

        assertThat(ergebnis.exitCode()).isEqualTo(2);
        assertThat(ergebnis.getErrorOutput()).contains("--ohne-datenbank");
    }
}
