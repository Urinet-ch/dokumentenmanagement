package ch.urinet.archiv.gemeinsam.domain;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
import java.net.SocketTimeoutException;
import java.nio.channels.ClosedByInterruptException;
import java.sql.SQLException;
import java.util.Scanner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Abfragen über die Ursachen-Kette (Review PR #97): Unterbrechung erkennen, Zeitüberschreitungen nicht. Feste Meldungen
 * eigener Ausnahmen nur mit {@link Ursachen#ketteMitMeldung} (Spec 02, design.md «Umsetzung (Aufgabe 6.1)»).
 */
class UrsachenTest {

    /** Wie {@code ConnectTimeoutException} von Apache: Zeitüberschreitung, erbt aber von InterruptedIOException. */
    private static final class VerbindungTimeoutException extends InterruptedIOException {
    }

    @Test
    void unterbrechungIrgendwoInDerKette() {
        assertThat(Ursachen.unterbrechung(new InterruptedException())).isTrue();
        assertThat(Ursachen.unterbrechung(new IllegalStateException(new ClosedByInterruptException()))).isTrue();
        assertThat(Ursachen.unterbrechung(new RuntimeException(
                new UncheckedIOException(new InterruptedIOException("unterbrochen"))))).isTrue();
    }

    @Test
    void zeitueberschreitungIstKeineUnterbrechung() {
        assertThat(Ursachen.unterbrechung(new RuntimeException(new SocketTimeoutException("Read")))).isFalse();
        assertThat(Ursachen.unterbrechung(new UncheckedIOException(new VerbindungTimeoutException()))).isFalse();
        assertThat(Ursachen.unterbrechung(new IOException("anderes"))).isFalse();
    }

    @Test
    void zyklischeKetteEndet() {
        RuntimeException a = new RuntimeException("a");
        RuntimeException b = new RuntimeException("b", a);
        a.initCause(b);

        assertThat(Ursachen.unterbrechung(a)).isFalse();
        assertThat(Ursachen.enthaelt(a, IOException.class)).isFalse();
        assertThat(Ursachen.kette(a)).isEqualTo(RuntimeException.class.getName() + " <- "
                + RuntimeException.class.getName());
    }

    @Test
    void enthaeltPrueftDieGanzeKetteUndUntertypen() {
        RuntimeException fehler = new IllegalStateException(new UncheckedIOException(new SocketTimeoutException()));
        assertThat(Ursachen.enthaelt(fehler, SocketTimeoutException.class)).isTrue();
        assertThat(Ursachen.enthaelt(fehler, IOException.class)).isTrue();
        assertThat(Ursachen.enthaelt(fehler, IllegalStateException.class)).isTrue();
        assertThat(Ursachen.enthaelt(fehler, InterruptedException.class)).isFalse();
    }

    /** Wie {@code ContextNotActiveException}: fremde Unterklasse von IllegalStateException. */
    private static final class FremdeException extends IllegalStateException {
        FremdeException() {
            super("Kontext nicht aktiv");
        }
    }

    @Test
    void ketteMitMeldungNenntFesteMeldungenEigenerAusnahmen() {
        IllegalStateException fest = new IllegalStateException("Mandanten-Kontext widerspricht der Anmeldung");
        IllegalArgumentException mitUrsache = new IllegalArgumentException("Chiffrat beschädigt",
                new SQLException("Key (code)=(GEHEIM-4711)", "08006"));

        assertThat(Ursachen.ketteMitMeldung(fest)).isEqualTo(
                "java.lang.IllegalStateException[meldung=\"Mandanten-Kontext widerspricht der Anmeldung\"]");
        assertThat(Ursachen.ketteMitMeldung(mitUrsache)).isEqualTo(
                "java.lang.IllegalArgumentException[meldung=\"Chiffrat beschädigt\"] <- java.sql.SQLException[sqlstate=08006]");
        assertThat(Ursachen.kette(fest)).isEqualTo("java.lang.IllegalStateException");
    }

    @Test
    void zusammengesetzteMeldungenBleibenDraussen() {
        String adresse = String.join("@", "inhaber", "example.invalid");
        String datei = String.join(".", "Rechnung", "pdf");

        assertThat(Ursachen.ketteMitMeldung(new IllegalArgumentException("Absender nicht zugelassen: " + adresse)))
                .isEqualTo("java.lang.IllegalArgumentException");
        assertThat(Ursachen.ketteMitMeldung(new IllegalStateException(datei))).isEqualTo("java.lang.IllegalStateException");
        assertThat(Ursachen.ketteMitMeldung(new IllegalStateException(String.format("Betreff %s", "Lohn"))))
                .isEqualTo("java.lang.IllegalStateException");
    }

    @Test
    void fremdeAusnahmenNurMitKlasse() {
        Scanner geschlossen = new Scanner("x");
        geschlossen.close();
        IllegalStateException ausDemJdk = null;
        try {
            geschlossen.next();
        } catch (IllegalStateException e) {
            ausDemJdk = e;
        }

        assertThat(ausDemJdk).isNotNull().hasMessage("Scanner closed");
        assertThat(Ursachen.ketteMitMeldung(ausDemJdk)).isEqualTo("java.lang.IllegalStateException");
        assertThat(Ursachen.ketteMitMeldung(new FremdeException())).isEqualTo(FremdeException.class.getName());
        assertThat(Ursachen.ketteMitMeldung(new RuntimeException("Fest, aber fremde Klasse")))
                .isEqualTo("java.lang.RuntimeException");
    }

    @Test
    void fachFehlerMitCodeUndFesterMeldung() {
        FachFehler nurCode = new FachFehler(Fehlercode.MANDANT_FEHLT);
        FachFehler mitText = new FachFehler(Fehlercode.MANDANT_FEHLT, "Kein Mandanten-Kontext") {
        };

        assertThat(Ursachen.ketteMitMeldung(nurCode)).isEqualTo(FachFehler.class.getName() + "[code=MANDANT_FEHLT]");
        assertThat(Ursachen.ketteMitMeldung(mitText)).isEqualTo(mitText.getClass().getName()
                + "[code=MANDANT_FEHLT, meldung=\"Kein Mandanten-Kontext\"]");
    }
}
