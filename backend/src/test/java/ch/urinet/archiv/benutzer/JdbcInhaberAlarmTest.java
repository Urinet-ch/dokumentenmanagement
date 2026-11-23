package ch.urinet.archiv.benutzer;

import ch.urinet.archiv.gemeinsam.adapter.in.job.TaktThread;
import ch.urinet.archiv.mandant.application.MandantKontext;
import ch.urinet.archiv.mandant.application.MandantProvisionierung;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.protokoll.application.ZugriffProtokoll;
import ch.urinet.archiv.protokoll.domain.ProtokollAktion;
import ch.urinet.archiv.protokoll.domain.ProtokollDetails;
import ch.urinet.archiv.protokoll.domain.ZugriffEintrag;
import ch.urinet.archiv.verarbeitung.application.AbgleichAlarmQuelle;
import ch.urinet.archiv.verarbeitung.domain.Befund;
import io.agroal.api.AgroalDataSource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@QuarkusTest
class JdbcInhaberAlarmTest {

    @Inject MandantProvisionierung provisionierung;
    @Inject MandantKontext kontext;
    @Inject ZugriffProtokoll protokoll;
    @Inject AbgleichAlarmQuelle alarme;
    @Inject AgroalDataSource datenquelle;

    @Test
    void inhabervergabeWirdTransaktionalUndNurImBetroffenenMandantenVorgemerkt() throws Exception {
        vorbereiten("urinet");
        vorbereiten("vhf-gsk");
        MandantCode urinet = new MandantCode("urinet");
        MandantCode vhf = new MandantCode("vhf-gsk");
        UUID rolle = UUID.randomUUID();
        UUID anlage = UUID.randomUUID();
        UUID antrag = UUID.randomUUID();
        UUID zurueckgerollt = UUID.randomUUID();
        UUID ziel = UUID.randomUUID();
        verzeichnis(ziel, "INHABER");

        imMandanten(urinet, () -> QuarkusTransaction.requiringNew().run(() -> {
            protokoll.schreiben(eintrag(ProtokollAktion.ROLLE_ZUWEISEN, rolle, ziel));
            protokoll.schreiben(eintrag(ProtokollAktion.BENUTZER_ANLEGEN, anlage, ziel));
            protokoll.schreiben(eintrag(ProtokollAktion.BENUTZER_ANTRAG, antrag, ziel));
        }));
        assertThatThrownBy(() -> imMandanten(urinet, () -> QuarkusTransaction.requiringNew().run(() -> {
            protokoll.schreiben(eintrag(ProtokollAktion.ROLLE_ZUWEISEN, zurueckgerollt, ziel));
            throw new IllegalStateException("synthetischer Rollback");
        }))).isInstanceOf(IllegalStateException.class);

        assertThat(alarme.offene(urinet)).extracting(Befund::schluessel).contains(
                "verwaltungsalarm:" + rolle + ":INHABER_VERGEBEN",
                "verwaltungsalarm:" + anlage + ":INHABER_VERGEBEN")
                .doesNotContain("verwaltungsalarm:" + antrag + ":INHABER_VERGEBEN",
                        "verwaltungsalarm:" + zurueckgerollt + ":INHABER_VERGEBEN");
        assertThat(alarme.offene(vhf)).extracting(Befund::schluessel).doesNotContain(
                "verwaltungsalarm:" + rolle + ":INHABER_VERGEBEN",
                "verwaltungsalarm:" + anlage + ":INHABER_VERGEBEN");
    }

    @Test
    void anlageMitAusstehenderInhabervergabeErzeugtNochKeinenAlarm() throws Exception {
        vorbereiten("urinet");
        MandantCode urinet = new MandantCode("urinet");
        UUID vorgang = UUID.randomUUID();
        UUID ziel = UUID.randomUUID();
        verzeichnis(ziel, null);

        imMandanten(urinet, () -> QuarkusTransaction.requiringNew().run(
                () -> protokoll.schreiben(eintrag(ProtokollAktion.BENUTZER_ANLEGEN, vorgang, ziel))));

        assertThat(alarme.offene(urinet)).extracting(Befund::schluessel)
                .doesNotContain("verwaltungsalarm:" + vorgang + ":INHABER_VERGEBEN");
    }

    private static ZugriffEintrag eintrag(ProtokollAktion aktion, UUID vorgang, UUID ziel) {
        var details = new ProtokollDetails.Verwaltung(vorgang, ProtokollDetails.Verwaltungsphase.ABGESCHLOSSEN,
                ziel, ProtokollDetails.Verwaltungsvorgang.ROLLE_ZUWEISEN, null,
                ProtokollDetails.Verwaltungsrolle.INHABER, List.of());
        return new ZugriffEintrag(Optional.empty(), Optional.empty(), aktion, Optional.empty(), Optional.empty(),
                ProtokollDetails.verwaltung(details));
    }

    private void imMandanten(MandantCode mandant, Runnable arbeit) {
        TaktThread.ausfuehren("inhaber-alarm-test", () -> kontext.mit(mandant, arbeit));
    }

    private void vorbereiten(String code) throws Exception {
        try (Connection verbindung = datenquelle.getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement(
                     "select 1 from public.mandant where code = ?")) {
            abfrage.setString(1, code);
            try (ResultSet zeile = abfrage.executeQuery()) {
                if (!zeile.next()) {
                    provisionierung.anlegen(code, null, null);
                }
            }
        }
    }

    private void verzeichnis(UUID id, String rolle) throws Exception {
        try (Connection verbindung = datenquelle.getConnection();
             PreparedStatement einfuegen = verbindung.prepareStatement(
                     "insert into m_urinet.benutzer_verzeichnis (benutzer, rolle, gueltig_von, anlass) "
                             + "values (?, ?, clock_timestamp(), 'ANLAGE')")) {
            einfuegen.setString(1, id.toString());
            einfuegen.setString(2, rolle);
            einfuegen.executeUpdate();
        }
    }
}
