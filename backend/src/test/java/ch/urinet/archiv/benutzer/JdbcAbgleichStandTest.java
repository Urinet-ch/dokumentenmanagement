package ch.urinet.archiv.benutzer;

import ch.urinet.archiv.benutzer.application.AbgleichStand;
import ch.urinet.archiv.mandant.application.MandantProvisionierung;
import ch.urinet.archiv.mandant.domain.MandantCode;
import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class JdbcAbgleichStandTest {

    @Inject AbgleichStand abgleich;
    @Inject MandantProvisionierung provisionierung;
    @Inject AgroalDataSource datenquelle;

    @Test
    void liestNurVerwaltungsstandDesMandantenUndNurWirksameInhabervergaben() throws Exception {
        vorbereiten("urinet");
        vorbereiten("vhf-gsk");
        UUID inhaber = UUID.randomUUID();
        UUID beantragt = UUID.randomUUID();
        UUID entfernt = UUID.randomUUID();
        UUID offen = UUID.randomUUID();
        UUID abgeschlossen = UUID.randomUUID();
        UUID jung = UUID.randomUUID();
        UUID vierAugenAntrag = UUID.randomUUID();
        verzeichnis(inhaber, "INHABER");
        verzeichnis(beantragt, null);
        verzeichnis(entfernt, "MITARBEITER");
        protokoll("BENUTZER_ANLEGEN", UUID.randomUUID(), inhaber, "ABGESCHLOSSEN", "inhaber", 700);
        protokoll("BENUTZER_ANLEGEN", UUID.randomUUID(), beantragt, "ABGESCHLOSSEN", "inhaber", 700);
        protokoll("BENUTZER_LOESCHEN", UUID.randomUUID(), entfernt, "ABGESCHLOSSEN", null, 700);
        protokoll("BENUTZER_AENDERN", offen, inhaber, "ANGEFRAGT", null, 301);
        protokoll("BENUTZER_AENDERN", abgeschlossen, inhaber, "ANGEFRAGT", null, 301);
        protokoll("BENUTZER_AENDERN", abgeschlossen, inhaber, "ABGESCHLOSSEN", null, 300);
        protokoll("BENUTZER_AENDERN", jung, inhaber, "ANGEFRAGT", null, 240);
        protokoll("BENUTZER_ANTRAG", vierAugenAntrag, beantragt, "ANGEFRAGT", "inhaber", 700);

        var urinet = abgleich.lesen(new MandantCode("urinet"));
        var vhf = abgleich.lesen(new MandantCode("vhf-gsk"));

        assertThat(urinet.verwaltet()).contains(inhaber, beantragt).doesNotContain(entfernt);
        assertThat(urinet.dokumentierteInhaber()).contains(inhaber).doesNotContain(beantragt, entfernt);
        assertThat(urinet.offeneVorgaenge().stream().map(AbgleichStand.OffenerVorgang::id))
                .contains(offen).doesNotContain(abgeschlossen, jung, vierAugenAntrag);
        assertThat(vhf.verwaltet()).doesNotContain(inhaber, beantragt, entfernt);
        assertThat(vhf.dokumentierteInhaber()).doesNotContain(inhaber);
        assertThat(vhf.offeneVorgaenge().stream().map(AbgleichStand.OffenerVorgang::id))
                .doesNotContain(offen);
    }

    @Test
    void haeltUngeklaerteWirkungUndSperreInEinerTransaktionFest() throws Exception {
        vorbereiten("urinet");
        vorbereiten("vhf-gsk");
        UUID vorgang = UUID.randomUUID();
        UUID ziel = UUID.randomUUID();
        protokoll("BENUTZER_AENDERN", vorgang, ziel, "ANGEFRAGT", null, 301);
        try (Connection verbindung = datenquelle.getConnection();
             PreparedStatement einfuegen = verbindung.prepareStatement(
                     "insert into public.verwaltung_vorgang (mandant, vorgang_id, bis, phase) "
                             + "values ('urinet', ?, clock_timestamp() - interval '5 minutes', 'ANGEFRAGT')")) {
            einfuegen.setObject(1, vorgang);
            einfuegen.executeUpdate();
        }
        try {
            assertThat(abgleich.unklar(new MandantCode("vhf-gsk"), vorgang)).isFalse();
            assertThat(abgleich.unklar(new MandantCode("urinet"), vorgang)).isTrue();
            assertThat(abgleich.unklar(new MandantCode("urinet"), vorgang)).isFalse();
            assertThat(abgleich.lesen(new MandantCode("urinet")).offeneVorgaenge().stream()
                    .map(AbgleichStand.OffenerVorgang::id)).doesNotContain(vorgang);
            try (Connection verbindung = datenquelle.getConnection();
                 PreparedStatement pruefen = verbindung.prepareStatement(
                         "select phase, (select count(*) from m_urinet.zugriff_protokoll p "
                                 + "where p.details->>'vorgangId' = ? and p.details->>'phase' = 'UNKLAR') "
                                 + "from public.verwaltung_vorgang where mandant = 'urinet' and vorgang_id = ?")) {
                pruefen.setString(1, vorgang.toString());
                pruefen.setObject(2, vorgang);
                try (ResultSet zeile = pruefen.executeQuery()) {
                    assertThat(zeile.next()).isTrue();
                    assertThat(zeile.getString(1)).isEqualTo("UNKLAR");
                    assertThat(zeile.getInt(2)).isEqualTo(1);
                }
            }
        } finally {
            try (Connection verbindung = datenquelle.getConnection();
                 PreparedStatement entfernen = verbindung.prepareStatement(
                         "delete from public.verwaltung_vorgang where mandant = 'urinet' and vorgang_id = ?")) {
                entfernen.setObject(1, vorgang);
                entfernen.executeUpdate();
            }
        }
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

    private void protokoll(String aktion, UUID vorgang, UUID ziel, String phase, String rolle, int alterSekunden)
            throws Exception {
        try (Connection verbindung = datenquelle.getConnection();
             PreparedStatement einfuegen = verbindung.prepareStatement(
                     "insert into m_urinet.zugriff_protokoll (zeit, aktion, details) "
                             + "values (clock_timestamp() - (? * interval '1 second'), ?, "
                             + "jsonb_build_object('vorgangId', ?, 'ziel', ?, 'phase', ?, 'rolleNeu', ?))")) {
            einfuegen.setInt(1, alterSekunden);
            einfuegen.setString(2, aktion);
            einfuegen.setString(3, vorgang.toString());
            einfuegen.setString(4, ziel.toString());
            einfuegen.setString(5, phase);
            einfuegen.setString(6, rolle);
            einfuegen.executeUpdate();
        }
    }
}
