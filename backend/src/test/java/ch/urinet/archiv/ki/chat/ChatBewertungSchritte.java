package ch.urinet.archiv.ki.chat;

import ch.urinet.archiv.gemeinsam.Testanmeldung;
import ch.urinet.archiv.gemeinsam.adapter.in.job.TaktThread;
import ch.urinet.archiv.ki.adapter.in.job.ChatAufraeumer;
import ch.urinet.archiv.sicherheit.domain.Aktion;
import ch.urinet.archiv.sicherheit.domain.Ergebnis;
import ch.urinet.archiv.sicherheit.domain.Rechtematrix;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import io.agroal.api.AgroalDataSource;
import io.cucumber.java.After;
import io.cucumber.java.Before;
import io.cucumber.java.de.Angenommen;
import io.cucumber.java.de.Dann;
import io.cucumber.java.de.Wenn;
import io.restassured.response.Response;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

public class ChatBewertungSchritte {

    private static final String SCHEMA = "m_urinet";
    private static final String BEWERTUNGS_PFAD = "/api/v1/chat/nachrichten/";
    private static final long ID_BASIS = 8_000_000_000_000_000L;

    @Inject
    Instance<AgroalDataSource> datenbank;

    @Inject
    ChatAufraeumer aufraeumer;

    private final Map<String, Antwort> antworten = new HashMap<>();
    private final List<UUID> gespraeche = new ArrayList<>();
    private final List<RateVersuch> rateVersuche = new ArrayList<>();
    private Response letzteAntwort;

    private record Antwort(long id, UUID gespraech, String mandant) {
    }

    private record RateVersuch(String benutzer, Instant zeit) {
    }

    @Before
    public void vorbereiten() {
        antworten.clear();
        gespraeche.clear();
        rateVersuche.clear();
        letzteAntwort = null;
    }

    @After
    public void aufraeumen() throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection()) {
            for (RateVersuch versuch : rateVersuche) {
                try (PreparedStatement befehl = verbindung.prepareStatement(
                        "delete from " + SCHEMA + ".chat_bewertung_versuch where benutzer = ? and zeit = ?")) {
                    befehl.setString(1, versuch.benutzer());
                    befehl.setTimestamp(2, Timestamp.from(versuch.zeit()));
                    befehl.executeUpdate();
                }
            }
            for (UUID gespraech : gespraeche) {
                try (PreparedStatement befehl = verbindung.prepareStatement(
                        "delete from " + SCHEMA + ".chat_gespraech where id = ?")) {
                    befehl.setObject(1, gespraech);
                    befehl.executeUpdate();
                }
            }
        }
    }

    @Angenommen("für «{word}» existiert im Mandanten «{word}» die Chat-Antwort «{word}»")
    public void antwortAnlegen(String benutzer, String mandant, String bezeichnung) throws SQLException {
        String schema = schema(mandant);
        UUID gespraech = UUID.randomUUID();
        Instant jetzt = Instant.now();
        String sub = Testanmeldung.sub(benutzer);
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement einfuegen = verbindung.prepareStatement("insert into " + schema
                     + ".chat_gespraech (id, benutzer, titel, erstellt, geaendert) values (?, ?, ?, ?, ?)")) {
            einfuegen.setObject(1, gespraech);
            einfuegen.setString(2, sub);
            einfuegen.setString(3, "Synthetisches Testgespräch");
            einfuegen.setTimestamp(4, Timestamp.from(jetzt));
            einfuegen.setTimestamp(5, Timestamp.from(jetzt));
            einfuegen.executeUpdate();
        }
        gespraeche.add(gespraech);
        long id = ID_BASIS + Math.floorMod(UUID.randomUUID().getLeastSignificantBits(), 1_000_000_000_000L);
        try (Connection verbindung = datenbank.get().getConnection()) {
            try (PreparedStatement frage = verbindung.prepareStatement("insert into " + schema
                    + ".chat_nachricht (gespraech_id, rolle, inhalt, zeit) values (?, 'BENUTZER', ?, ?)")) {
                frage.setObject(1, gespraech);
                frage.setString(2, "Synthetische Testfrage");
                frage.setTimestamp(3, Timestamp.from(jetzt));
                frage.executeUpdate();
            }
            try (PreparedStatement antwort = verbindung.prepareStatement("insert into " + schema
                    + ".chat_nachricht (id, gespraech_id, rolle, inhalt, status, quellen, zeit) "
                    + "values (?, ?, 'ASSISTENT', ?, 'OK', '[]'::jsonb, ?) returning id")) {
                antwort.setLong(1, id);
                antwort.setObject(2, gespraech);
                antwort.setString(3, "Synthetische Testantwort");
                antwort.setTimestamp(4, Timestamp.from(jetzt));
                try (ResultSet zeile = antwort.executeQuery()) {
                    zeile.next();
                    id = zeile.getLong(1);
                }
            }
        }
        antworten.put(bezeichnung, new Antwort(id, gespraech, mandant));
    }

    @Wenn("«{word}» bewertet «{word}» mit dem Körper «{}»")
    public void bewerten(String benutzer, String antwort, String body) {
        letzteAntwort = anfrage(benutzer, antworten.get(antwort).id(), "PUT", body);
    }

    @Wenn("«{word}» ruft die Bewertung von «{word}» ab")
    public void lesen(String benutzer, String antwort) {
        letzteAntwort = anfrage(benutzer, antworten.get(antwort).id(), "GET", null);
    }

    @Wenn("«{word}» löscht die Bewertung von «{word}»")
    public void loeschen(String benutzer, String antwort) {
        letzteAntwort = anfrage(benutzer, antworten.get(antwort).id(), "DELETE", null);
    }

    @Wenn("«{word}» bewertet «{word}» in einem anderen Mandanten")
    public void fremderMandant(String benutzer, String antwort) {
        letzteAntwort = anfrage(benutzer, antworten.get(antwort).id(), "GET", null);
    }

    @Angenommen("«{word}» hat bereits {int} Bewertungsversuche innerhalb der letzten Stunde")
    public void bewertungsversuche(String benutzer, int anzahl) throws SQLException {
        String sub = Testanmeldung.sub(benutzer);
        Instant zeit = Instant.now().minusSeconds(30);
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement einfuegen = verbindung.prepareStatement(
                     "insert into " + SCHEMA + ".chat_bewertung_versuch (benutzer, zeit) values (?, ?)")) {
            for (int i = 0; i < anzahl; i++) {
                einfuegen.setString(1, sub);
                einfuegen.setTimestamp(2, Timestamp.from(zeit));
                einfuegen.executeUpdate();
                rateVersuche.add(new RateVersuch(sub, zeit));
            }
        }
    }

    @Wenn("die Chat-Antwort «{word}» älter als 12 Monate wird")
    public void antwortWirdAlt(String bezeichnung) throws SQLException {
        Antwort antwort = antworten.get(bezeichnung);
        Instant alt = Instant.now().minusSeconds(400L * 24 * 60 * 60);
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement befehl = verbindung.prepareStatement("update " + schema(antwort.mandant())
                     + ".chat_nachricht set zeit = ? where gespraech_id = ?")) {
            befehl.setTimestamp(1, Timestamp.from(alt));
            befehl.setObject(2, antwort.gespraech());
            befehl.executeUpdate();
        }
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement befehl = verbindung.prepareStatement("update " + schema(antwort.mandant())
                     + ".chat_gespraech set geaendert = ? where id = ?")) {
            befehl.setTimestamp(1, Timestamp.from(alt));
            befehl.setObject(2, antwort.gespraech());
            befehl.executeUpdate();
        }
        TaktThread.ausfuehren("chat-bewertungen-test", aufraeumer::alleAufraeumen);
    }

    @Dann("ist die Bewertungsanfrage mit Status {int} beantwortet")
    public void status(int status) {
        assertThat(letzteAntwort.statusCode()).isEqualTo(status);
    }

    @Dann("enthält die Antwort die Bewertung mit {int} Sternen und dem Freitext «{}»")
    public void bewertungInhalt(int sterne, String freitext) {
        assertThat(letzteAntwort.jsonPath().getInt("sterne")).isEqualTo(sterne);
        assertThat(letzteAntwort.jsonPath().getString("freitext")).isEqualTo(freitext);
    }

    @Dann("enthält die Antwort den gespeicherten Freitext «{}»")
    public void gespeicherterFreitext(String freitext) {
        assertThat(letzteAntwort.jsonPath().getString("freitext")).isEqualTo(freitext);
    }

    @Dann("enthält die Antwort den Fehlercode «{word}»")
    public void fehlercode(String fehlercode) {
        assertThat(letzteAntwort.jsonPath().getString("fehlercode")).isEqualTo(fehlercode);
    }

    @Dann("ist A24 nur für den Inhaber erlaubt")
    public void a24NurInhaber() {
        assertThat(Rechtematrix.ergebnis(Rolle.INHABER, Aktion.A24)).isEqualTo(Ergebnis.ERLAUBT);
        assertThat(Rechtematrix.ergebnis(Rolle.MITARBEITER, Aktion.A24)).isEqualTo(Ergebnis.VERBOTEN);
        assertThat(Rechtematrix.ergebnis(Rolle.TREUHAENDER, Aktion.A24)).isEqualTo(Ergebnis.VERBOTEN);
        assertThat(Rechtematrix.ergebnis(Rolle.GESCHAEFTSSTELLE, Aktion.A24)).isEqualTo(Ergebnis.VERBOTEN);
    }

    @Dann("ist die Bewertung von «{word}» nicht mehr gespeichert")
    public void nichtMehrGespeichert(String bezeichnung) throws SQLException {
        Antwort antwort = antworten.get(bezeichnung);
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement("select count(*) from " + schema(antwort.mandant())
                     + ".chat_bewertung where nachricht_id = ?")) {
            abfrage.setLong(1, antwort.id());
            try (ResultSet zeile = abfrage.executeQuery()) {
                zeile.next();
                assertThat(zeile.getLong(1)).isZero();
            }
        }
    }

    private Response anfrage(String benutzer, long nachrichtId, String methode, String body) {
        var spezifikation = given().auth().oauth2(Testanmeldung.token(benutzer));
        if (body != null) {
            spezifikation.contentType("application/json").body(body);
        }
        String pfad = BEWERTUNGS_PFAD + nachrichtId + "/bewertung";
        return switch (methode) {
            case "PUT" -> spezifikation.when().put(pfad);
            case "GET" -> spezifikation.when().get(pfad);
            case "DELETE" -> spezifikation.when().delete(pfad);
            default -> throw new IllegalArgumentException("Unbekannte Bewertungsoperation");
        };
    }

    private static String schema(String mandant) {
        return switch (mandant) {
            case "urinet" -> "m_urinet";
            case "vhf-gsk" -> "m_vhf_gsk";
            default -> throw new IllegalArgumentException("Unbekannter Testmandant");
        };
    }
}
