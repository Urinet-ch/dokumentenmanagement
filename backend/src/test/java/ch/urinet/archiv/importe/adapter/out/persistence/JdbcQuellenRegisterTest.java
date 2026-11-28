package ch.urinet.archiv.importe.adapter.out.persistence;

import ch.urinet.archiv.importe.application.quelle.QuellenRegister;
import ch.urinet.archiv.importe.domain.quelle.LaufArt;
import ch.urinet.archiv.importe.domain.quelle.QuellLauf;
import ch.urinet.archiv.importe.domain.quelle.QuellLaufStatus;
import ch.urinet.archiv.importe.domain.quelle.Quelle;
import ch.urinet.archiv.importe.domain.quelle.QuellenTyp;
import ch.urinet.archiv.importe.domain.quelle.Zaehler;
import ch.urinet.archiv.mandant.application.MandantKontext;
import ch.urinet.archiv.mandant.application.MandantProvisionierung;
import ch.urinet.archiv.mandant.domain.MandantCode;
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
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Quellenregister und Quellläufe je Mandant (Spec 09, 10.1 bis 10.3; tech.md: Repository-Tests mit zwei Mandanten).
 * Der Seed der allgemeinen Quellen gilt nur für {@code urinet} (10.2).
 */
@QuarkusTest
class JdbcQuellenRegisterTest {

    @Inject QuellenRegister register;
    @Inject MandantKontext kontext;
    @Inject MandantProvisionierung provisionierung;
    @Inject AgroalDataSource datenbank;

    @Test
    void seedNurFuerUrinetUndRegisterJeMandant() throws Exception {
        for (String code : new String[] {"urinet", "vhf-gsk"}) {
            if (!imRegister(code)) {
                provisionierung.anlegen(code, null, null);
            }
        }
        String code = "probe-" + UUID.randomUUID().toString().substring(0, 8);
        try (ExecutorService thread = Executors.newSingleThreadExecutor()) {
            List<String> urinet = in(thread, "urinet", () -> register.liste().stream().map(Quelle::code).toList());
            assertThat(urinet).contains("generatoren", "drive", "mac-ordner", "gmail", "crm-csv", "papier");
            assertThat(in(thread, "vhf-gsk", () -> register.liste().stream().map(Quelle::code).toList()))
                    .doesNotContain("generatoren", "drive", "mac-ordner", "gmail", "crm-csv", "papier");
            Quelle gmail = in(thread, "urinet", () -> register.finde("gmail", false)).orElseThrow();
            assertThat(gmail.typ()).isEqualTo(QuellenTyp.GMAIL_LABEL);
            assertThat(gmail.geheimnisGesetzt()).isFalse();
            assertThat(gmail.konfiguration()).isEqualTo("{}");

            assertThat(in(thread, "vhf-gsk", () -> register.anlegen(UUID.randomUUID(), code, "Probe", QuellenTyp.ORDNER,
                    "{}", Optional.empty()))).isTrue();
            byte[] chiffrat = {11, 22, 33};
            in(thread, "vhf-gsk", () -> {
                register.geheimnisSetzen(code, chiffrat);
                return null;
            });
            assertThat(in(thread, "vhf-gsk", () -> register.geheimnisLaden(code)).orElseThrow())
                    .containsExactly(chiffrat);
            assertThat(in(thread, "urinet", () -> register.geheimnisLaden(code))).isEmpty();
            assertThat(in(thread, "urinet", () -> register.finde(code, false))).isEmpty();
            assertThat(in(thread, "vhf-gsk", () -> register.anlegen(UUID.randomUUID(), code, "Doppelt",
                    QuellenTyp.ORDNER, "{}", Optional.empty()))).isFalse();

            Quelle vhf = in(thread, "vhf-gsk", () -> register.finde(code, false)).orElseThrow();
            QuellLauf lauf = in(thread, "vhf-gsk", () -> register.laufAnlegen(vhf.id(), LaufArt.ECHT,
                    Optional.of("synthetisch")));
            in(thread, "vhf-gsk", () -> {
                register.verbuchen(lauf.id(), 2, 1, 1);
                return null;
            });
            assertThat(in(thread, "urinet", () -> register.lauf(lauf.id(), false))).isEmpty();
            QuellLauf gezaehlt = in(thread, "vhf-gsk", () -> register.lauf(lauf.id(), false)).orElseThrow();
            assertThat(gezaehlt.zaehler()).isEqualTo(new Zaehler(4, 2, 1, 0, 0, 1));
            assertThat(gezaehlt.zaehler().gehtAuf()).isTrue();
            assertThat(in(thread, "vhf-gsk", () -> register.finde(code, false)).orElseThrow().letzterLauf()
                    .map(QuellLauf::id)).contains(lauf.id());

            QuellLauf beendet = in(thread, "vhf-gsk", () -> register.laufBeenden(lauf.id(), QuellLaufStatus.FERTIG,
                    Optional.empty(), 3, List.of("drei übersprungen")));
            assertThat(beendet.zaehler()).isEqualTo(new Zaehler(7, 2, 1, 0, 3, 1));
            assertThat(beendet.meldungen()).containsExactly("drei übersprungen");
            assertThat(beendet.beendet()).isPresent();
            assertThat(in(thread, "vhf-gsk", () -> register.laufenderLauf(vhf.id()))).isEmpty();

            assertThat(in(thread, "vhf-gsk", () -> register.aendern(code, vhf.version() + 5, null, "x", null)))
                    .isEmpty();
            assertThat(in(thread, "vhf-gsk", () -> register.aendern(code, vhf.version(), null, "Bemerkung", null))
                    .orElseThrow().bemerkung()).contains("Bemerkung");
        } finally {
            try (Connection verbindung = datenbank.getConnection()) {
                for (String sql : List.of("update m_vhf_gsk.quelle set letzter_lauf = null where code = ?",
                        "delete from m_vhf_gsk.quelle_lauf where quelle_id in (select id from m_vhf_gsk.quelle "
                                + "where code = ?)", "delete from m_vhf_gsk.quelle where code = ?")) {
                    try (PreparedStatement befehl = verbindung.prepareStatement(sql)) {
                        befehl.setString(1, code);
                        befehl.executeUpdate();
                    }
                }
            }
        }
    }

    private <T> T in(ExecutorService thread, String mandant, Callable<T> arbeit) throws Exception {
        return thread.submit(() -> kontext.mit(new MandantCode(mandant),
                () -> QuarkusTransaction.requiringNew().call(arbeit))).get(30, TimeUnit.SECONDS);
    }

    private boolean imRegister(String code) throws Exception {
        try (Connection verbindung = datenbank.getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement("select 1 from public.mandant where code = ?")) {
            abfrage.setString(1, code);
            try (ResultSet zeile = abfrage.executeQuery()) {
                return zeile.next();
            }
        }
    }
}
