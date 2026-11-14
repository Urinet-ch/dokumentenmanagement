package ch.urinet.archiv.integration.adapter.in.kommando;

import ch.urinet.archiv.ArchivMain;
import io.quarkus.dev.console.QuarkusConsole;
import io.quarkus.test.junit.main.LaunchResult;
import io.quarkus.test.junit.main.QuarkusMainLauncher;
import io.quarkus.test.junit.main.QuarkusMainTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code archiv mail einrichten} über denselben Einstieg wie der Dienst ({@code ArchivMain.main}). Befund Stufe A
 * (13.11.2026): Las das Kommando die Standardeingabe erst nach dem Start der Anwendung, kam im Wegwerf-Container nichts
 * mehr an («Passwort fehlt»). Die {@link Tastatur} dieses Tests liefert das Passwort darum nur, solange der Leser noch
 * vor {@code Quarkus.run} steht, und sonst eine leere Zeile; das Kommando gelingt also nur, wenn es vor dem Start liest.
 */
@Tag("start-2")
@QuarkusMainTest
class MailKommandoStartTest {

    private static final String[] AUFRUF = {"mail", "einrichten", "urinet", "imap.example.invalid", "993",
            "archiv@example.invalid", "a@example.invalid"};

    /**
     * {@code @QuarkusMainTest} ersetzt {@code System.in} für jeden Lauf durch {@link QuarkusConsole#REDIRECT_IN}; es nimmt
     * Zeichen nur an, solange jemand liest, und liefert nie das Ende der Eingabe. Die Tastatur tippt dort eine Zeile ein,
     * sobald ein Thread liest, und merkt sich, ob dieser Thread schon in {@code Quarkus.run} steckt
     * ({@code ApplicationLifecycleManager} auf dem Stack).
     */
    static final class Tastatur extends Thread {
        private final byte[] zeile;
        volatile Boolean vorDemStart;

        Tastatur(String zeile) {
            super("tastatur");
            setDaemon(true);
            this.zeile = (zeile + "\n").getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public void run() {
            byte[] senden = null;
            int i = 0;
            while (senden == null || i < senden.length) {
                QuarkusConsole.StateChangeInputStream ein = QuarkusConsole.REDIRECT_IN;
                Optional<Boolean> leser = leserVorDemStart();
                if (ein != null && leser.isPresent()) {
                    if (senden == null) {
                        vorDemStart = leser.get();
                        senden = vorDemStart ? zeile : "\n".getBytes(StandardCharsets.UTF_8);
                    }
                    if (ein.acceptInput(senden[i])) {
                        i++;
                        continue;
                    }
                }
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }

        /** Ob der Thread, der gerade die Standardeingabe liest, noch vor dem Start steht; leer, wenn niemand liest. */
        private static Optional<Boolean> leserVorDemStart() {
            for (StackTraceElement[] stapel : Thread.getAllStackTraces().values()) {
                if (Arrays.stream(stapel).anyMatch(f -> f.getClassName().endsWith("$StateChangeInputStream")
                        && f.getMethodName().equals("read"))) {
                    return Optional.of(Arrays.stream(stapel).noneMatch(
                            f -> f.getClassName().equals("io.quarkus.runtime.ApplicationLifecycleManager")));
                }
            }
            return Optional.empty();
        }
    }

    @AfterAll
    static void kommandoEinstellungenEntfernen() {
        ArchivMain.KOMMANDO.keySet().forEach(System::clearProperty);
    }

    @Test
    void dasPasswortWirdVorDemStartGelesen(QuarkusMainLauncher starter) throws InterruptedException {
        // Seed gibt es nur für urinet und vhf-gsk; besteht der Mandant schon, ist das Exit 1
        LaunchResult mandant = starter.launch("mandant", "anlegen", "urinet");
        assertThat(mandant.exitCode()).as(mandant.getErrorOutput()).isIn(0, 1);
        Tastatur tastatur = new Tastatur("s3hr-geheim-probe");
        tastatur.start();
        try {
            LaunchResult ergebnis = starter.launch(AUFRUF);

            assertThat(tastatur.vorDemStart).as("vor dem Start gelesen").isTrue();
            assertThat(ergebnis.exitCode()).as(ergebnis.getErrorOutput()).isZero();
            assertThat(ergebnis.getErrorOutput()).contains("eingerichtet").doesNotContain("s3hr-geheim-probe");
            assertThat(ergebnis.getOutput()).doesNotContain("s3hr-geheim-probe");
        } finally {
            tastatur.interrupt();
            tastatur.join(1_000);
            LaunchResult ab = starter.launch("mail", "abschalten", "urinet");
            assertThat(ab.exitCode()).as(ab.getErrorOutput()).isZero();
        }
    }

    @Test
    void leereZeileEndetMitExit1OhneStart(QuarkusMainLauncher starter) throws InterruptedException {
        Tastatur tastatur = new Tastatur("");
        tastatur.start();

        LaunchResult ergebnis = starter.launch(AUFRUF);
        tastatur.join(1_000);

        assertThat(ergebnis.exitCode()).as(ergebnis.getErrorOutput()).isEqualTo(1);
        assertThat(ergebnis.getErrorOutput()).contains(MailPasswortEingabe.FEHLT);
        // ohne Start kein Konsolen-Log (der Mitschnitt zählt es zu stdout, siehe TokenKommandoStartTest)
        assertThat(ergebnis.getOutput()).as("kein Start").isEmpty();
    }
}
