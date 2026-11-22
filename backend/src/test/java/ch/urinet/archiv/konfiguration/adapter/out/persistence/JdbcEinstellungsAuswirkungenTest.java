package ch.urinet.archiv.konfiguration.adapter.out.persistence;

import ch.urinet.archiv.konfiguration.application.EinstellungsAuswirkungen;
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
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class JdbcEinstellungsAuswirkungenTest {
    private static final Instant GRENZE = Instant.parse("2001-01-01T00:00:00Z");
    private static final Instant ALT = Instant.parse("2000-01-01T00:00:00Z");

    @Inject EinstellungsAuswirkungen auswirkungen;
    @Inject MandantKontext kontext;
    @Inject MandantProvisionierung provisionierung;
    @Inject AgroalDataSource datenbank;

    @Test
    void zaehltNurAlteEintraegeDesAktuellenMandanten() throws Exception {
        for (String code : new String[] {"urinet", "vhf-gsk"}) {
            if (!imRegister(code)) {
                provisionierung.anlegen(code, null, null);
            }
        }
        UUID gespraech = UUID.randomUUID();
        long frage = -1;
        try (var thread = Executors.newSingleThreadExecutor()) {
            long urinetChat = in(thread, "urinet", () -> auswirkungen.chatNachrichtenVor(GRENZE));
            long urinetFragen = in(thread, "urinet", () -> auswirkungen.oeffentlicheFragenVor(GRENZE));
            long vhfChat = in(thread, "vhf-gsk", () -> auswirkungen.chatNachrichtenVor(GRENZE));
            long vhfFragen = in(thread, "vhf-gsk", () -> auswirkungen.oeffentlicheFragenVor(GRENZE));
            try (Connection verbindung = datenbank.getConnection()) {
                try (PreparedStatement insert = verbindung.prepareStatement(
                        "insert into m_urinet.chat_gespraech (id, benutzer, titel, erstellt, geaendert) "
                                + "values (?, 'synthetisch', 'Test', ?, ?)")) {
                    insert.setObject(1, gespraech);
                    insert.setTimestamp(2, Timestamp.from(ALT));
                    insert.setTimestamp(3, Timestamp.from(ALT));
                    insert.executeUpdate();
                }
                try (PreparedStatement insert = verbindung.prepareStatement(
                        "insert into m_urinet.chat_nachricht (gespraech_id, rolle, inhalt, zeit) "
                                + "values (?, 'BENUTZER', 'Test', ?), (?, 'BENUTZER', 'Test', ?)")) {
                    insert.setObject(1, gespraech);
                    insert.setTimestamp(2, Timestamp.from(ALT));
                    insert.setObject(3, gespraech);
                    insert.setTimestamp(4, Timestamp.from(GRENZE));
                    insert.executeUpdate();
                }
                try (PreparedStatement insert = verbindung.prepareStatement(
                        "insert into m_vhf_gsk.oeffentliche_frage (frage, tokens, zeit) values ('Test', 0, ?) returning id")) {
                    insert.setTimestamp(1, Timestamp.from(ALT));
                    try (ResultSet zeile = insert.executeQuery()) {
                        assertThat(zeile.next()).isTrue();
                        frage = zeile.getLong(1);
                    }
                }
            }
            assertThat(in(thread, "urinet", () -> auswirkungen.chatNachrichtenVor(GRENZE))).isEqualTo(urinetChat + 1);
            assertThat(in(thread, "urinet", () -> auswirkungen.oeffentlicheFragenVor(GRENZE))).isEqualTo(urinetFragen);
            assertThat(in(thread, "vhf-gsk", () -> auswirkungen.chatNachrichtenVor(GRENZE))).isEqualTo(vhfChat);
            assertThat(in(thread, "vhf-gsk", () -> auswirkungen.oeffentlicheFragenVor(GRENZE))).isEqualTo(vhfFragen + 1);
        } finally {
            try (Connection verbindung = datenbank.getConnection();
                 PreparedStatement chat = verbindung.prepareStatement("delete from m_urinet.chat_gespraech where id = ?");
                 PreparedStatement oeffentlich = verbindung.prepareStatement("delete from m_vhf_gsk.oeffentliche_frage where id = ?")) {
                chat.setObject(1, gespraech);
                chat.executeUpdate();
                if (frage >= 0) {
                    oeffentlich.setLong(1, frage);
                    oeffentlich.executeUpdate();
                }
            }
        }
    }

    private <T> T in(java.util.concurrent.ExecutorService thread, String mandant, Callable<T> arbeit) throws Exception {
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
