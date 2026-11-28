package ch.urinet.archiv.wissen.adapter.in.kommando;

import ch.urinet.archiv.mandant.application.MandantProvisionierung;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.wissen.application.Wissensfreischaltung;
import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec 04, Req 22.1 (neuer Mandant ohne Wissen) und 22.2: {@code archiv mandant wissen <code> [ein|aus]} setzt den
 * Zustand, protokolliert mit Akteur Betreiber, ist ohne Änderung idempotent (Exit 0, kein Eintrag), gibt ohne
 * {@code ein}/{@code aus} nur den Zustand aus und endet bei unbekanntem oder inaktivem Mandanten mit 1, bei falschem
 * Aufruf mit 2, ohne etwas zu ändern. Die Wirkung im Dienst nach höchstens 60 s prüft {@code WissensfreischaltungTest}.
 */
@QuarkusTest
@Tag("req-22.1")
@Tag("req-22.2")
class WissenFreischaltungKommandoTest {

    private static final MandantCode NEU = new MandantCode("wissen-kommando");
    private static final MandantCode INAKTIV = new MandantCode("wissen-inaktiv");

    @Inject
    WissenFreischaltungKommando kommando;

    @Inject
    MandantProvisionierung provisionierung;

    @Inject
    Wissensfreischaltung freischaltung;

    @Inject
    Instance<AgroalDataSource> datenbank;

    @Test
    void schaltetEinUndAusMitProtokollUndIdempotent() throws SQLException {
        if (zahl("select count(*) from public.mandant where code = '" + NEU.wert() + "'") == 0) {
            provisionierung.anlegen(NEU.wert(), null, null);
        }
        ausfuehren(List.of("wissen", NEU.wert(), "aus"));
        long vorher = protokoll();

        assertThat(zustandInDb()).as("neuer Mandant ohne Wissen").isFalse();
        Ergebnis status = ausfuehren(List.of("wissen", NEU.wert()));
        assertThat(status.code()).isZero();
        assertThat(status.aus()).contains("nicht freigeschaltet");
        assertThat(protokoll()).isEqualTo(vorher);

        Ergebnis ein = ausfuehren(List.of("wissen", NEU.wert(), "ein"));
        assertThat(ein.code()).as(ein.fehler()).isZero();
        assertThat(ein.aus()).contains("Wissen freigeschaltet");
        assertThat(zustandInDb()).isTrue();
        assertThat(freischaltung.freigeschaltet(NEU)).as("im selben Prozess sofort").isTrue();
        assertThat(letzteAktion()).isEqualTo("WISSEN_FREIGESCHALTET");
        assertThat(protokoll()).isEqualTo(vorher + 1);

        Ergebnis nochmals = ausfuehren(List.of("wissen", NEU.wert(), "ein"));
        assertThat(nochmals.code()).isZero();
        assertThat(nochmals.aus()).contains("bereits freigeschaltet");
        assertThat(protokoll()).as("nichts protokolliert").isEqualTo(vorher + 1);

        Ergebnis aus = ausfuehren(List.of("wissen", NEU.wert(), "aus"));
        assertThat(aus.code()).isZero();
        assertThat(aus.aus()).contains("keine Daten gelöscht");
        assertThat(zustandInDb()).isFalse();
        assertThat(freischaltung.freigeschaltet(NEU)).isFalse();
        assertThat(letzteAktion()).isEqualTo("WISSEN_ABGESCHALTET");
        assertThat(protokoll()).isEqualTo(vorher + 2);
        assertThat(zahl("select count(*) from " + NEU.schema() + ".zugriff_protokoll where aktion like 'WISSEN\\_%' "
                + "and aktion in ('WISSEN_FREIGESCHALTET', 'WISSEN_ABGESCHALTET') and (benutzer <> 'betreiber' "
                + "or dokument_id is not null or rolle is not null)")).as("Akteur Betreiber, ohne Dokument").isZero();
    }

    @Test
    void unbekannterOderInaktiverMandantUndFalscherAufrufAendernNichts() throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection(); PreparedStatement einfuegen =
                verbindung.prepareStatement("insert into public.mandant (code, name, schema_name, bucket, aktiv) "
                        + "values (?, ?, ?, ?, false) on conflict (code) do nothing")) {
            einfuegen.setString(1, INAKTIV.wert());
            einfuegen.setString(2, INAKTIV.wert());
            einfuegen.setString(3, INAKTIV.schema());
            einfuegen.setString(4, "urinet-archiv-" + INAKTIV.wert());
            einfuegen.executeUpdate();
        }
        assertThat(ausfuehren(List.of("wissen", "gibt-es-nicht", "ein")).code()).isEqualTo(1);
        assertThat(ausfuehren(List.of("wissen", "gibt-es-nicht")).code()).isEqualTo(1);
        assertThat(ausfuehren(List.of("wissen", INAKTIV.wert(), "ein")).code()).isEqualTo(1);
        assertThat(zahl("select count(*) from public.mandant where code = '" + INAKTIV.wert()
                + "' and wissen_freigeschaltet")).isZero();
        assertThat(zahl("select count(*) from public.mandant where code = 'gibt-es-nicht'")).isZero();
        for (List<String> falsch : List.of(List.of("wissen", NEU.wert(), "vielleicht"), List.of("wissen"),
                List.of("wissen", "Gross", "ein"), List.of("wissen", NEU.wert(), "ein", "aus"))) {
            Ergebnis ergebnis = ausfuehren(falsch);
            assertThat(ergebnis.code()).as(falsch.toString()).isEqualTo(2);
            assertThat(ergebnis.fehler()).contains(WissenFreischaltungKommando.AUFRUF);
        }
    }

    private record Ergebnis(int code, String aus, String fehler) {
    }

    /** Wie im Wegwerf-Container: eigener Plattform-Thread ohne Anmeldung und ohne Request-Kontext. */
    private Ergebnis ausfuehren(List<String> argumente) {
        ByteArrayOutputStream aus = new ByteArrayOutputStream();
        ByteArrayOutputStream fehler = new ByteArrayOutputStream();
        FutureTask<Integer> aufgabe = new FutureTask<>(() -> kommando.ausfuehren(argumente,
                new PrintStream(aus, true, StandardCharsets.UTF_8), new PrintStream(fehler, true, StandardCharsets.UTF_8)));
        Thread.ofPlatform().start(aufgabe);
        try {
            int code = aufgabe.get();
            return new Ergebnis(code, aus.toString(StandardCharsets.UTF_8), fehler.toString(StandardCharsets.UTF_8));
        } catch (InterruptedException | ExecutionException e) {
            throw new IllegalStateException(e);
        }
    }

    private boolean zustandInDb() throws SQLException {
        return zahl("select count(*) from public.mandant where code = '" + NEU.wert() + "' and wissen_freigeschaltet")
                == 1;
    }

    private long protokoll() throws SQLException {
        return zahl("select count(*) from " + NEU.schema() + ".zugriff_protokoll "
                + "where aktion in ('WISSEN_FREIGESCHALTET', 'WISSEN_ABGESCHALTET')");
    }

    private String letzteAktion() throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection(); PreparedStatement abfrage =
                verbindung.prepareStatement("select aktion from " + NEU.schema() + ".zugriff_protokoll "
                        + "where aktion in ('WISSEN_FREIGESCHALTET', 'WISSEN_ABGESCHALTET') order by id desc limit 1");
             ResultSet zeile = abfrage.executeQuery()) {
            assertThat(zeile.next()).isTrue();
            return zeile.getString(1);
        }
    }

    private long zahl(String sql) throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement(sql); ResultSet zeile = abfrage.executeQuery()) {
            zeile.next();
            return zeile.getLong(1);
        }
    }
}
