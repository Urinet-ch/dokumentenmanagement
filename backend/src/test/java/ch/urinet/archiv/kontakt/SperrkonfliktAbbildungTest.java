package ch.urinet.archiv.kontakt;

import ch.urinet.archiv.gemeinsam.Testanmeldung;
import ch.urinet.archiv.dokument.application.DokumentEreignisse;
import ch.urinet.archiv.mandant.application.MandantProvisionierung;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.wissen.application.QuelldokumentBeobachter;
import ch.urinet.archiv.wissen.application.WissenIngestStarten;
import io.agroal.api.AgroalDataSource;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Nachweis für Req 17.12 und 17.16 an den echten Endpunkten: Zusammenführen eines Kontakts ({@code POST
 * /kontakte/{id}/zusammenfuehren}) gegen das Ändern des Partners an einem seiner Dokumente ({@code PATCH
 * /dokumente/{id}}). Beide laufen als HTTP-Anfragen durch die echten Anwendungsfälle und Adapter gegen Postgres. Die
 * Pfade sperren in umgekehrter Reihenfolge (Zusammenführen: Kontakte, dann Dokumente; Ändern: Dokument, dann Kontakt
 * {@code for share}). {@link SperrReihenfolgeHaken} legt nur den Zeitpunkt fest, an dem beide ihre erste Sperre halten;
 * so tritt der Konflikt in jedem Lauf auf. Die vorgelagerte Wissenssperre wird nur für diese erzwungene
 * Gegenprobe ausgeschaltet; ihre Serialisierung wird separat geprüft. Erwartet ist nie 500: Die unterlegene Anfrage meldet 409 STATUS_KONFLIKT
 * (Verklemmung 40P01 oder abgelaufene Wartezeit 55P03), die andere gelingt, und die Daten bleiben in sich stimmig.
 * Die reine Abbildung der Ursachen-Kette prüft {@code ProblemAbbildungTest}. Nachweis zu Req 17.12 und 17.16
 * (design.md «Fehlerbehandlung»).
 */
@Tag("start-4")
@TestProfile(SperrkonfliktAbbildungTest.SperrtestProfil.class)
@QuarkusTest
class SperrkonfliktAbbildungTest {

    public static class SperrtestProfil implements QuarkusTestProfile {
        @Override
        public Set<Class<?>> getEnabledAlternatives() {
            return Set.of(SperrReihenfolgeHaken.class, EreignisseOhneVorsperre.class);
        }
    }

    @Alternative
    @Dependent
    public static class EreignisseOhneVorsperre implements DokumentEreignisse {
        private final QuelldokumentBeobachter beobachter;

        @Inject
        public EreignisseOhneVorsperre(QuelldokumentBeobachter beobachter) {
            this.beobachter = beobachter;
        }

        @Override
        public void archiviert(UUID dokumentId, String art) {
        }

        @Override
        public void vorAenderung() {
        }

        @Override
        public void geaendert(UUID dokumentId, Set<String> felder) {
            beobachter.geaendert(dokumentId);
        }
    }

    private static final MandantCode URINET = new MandantCode("urinet");
    private static final String BENUTZER = "inhaber-urinet";

    @Inject
    AgroalDataSource datenbank;

    @Inject
    MandantProvisionierung provisionierung;

    private UUID quelle;
    private UUID ziel;
    private UUID dokument;

    @AfterEach
    void aufraeumen() throws SQLException {
        SperrReihenfolgeHaken.entschaerfen();
        if (dokument == null) {
            return;
        }
        try (Connection verbindung = datenbank.getConnection()) {
            loeschen(verbindung, "delete from " + URINET.schema() + ".dokument_version where dokument_id = ?",
                    dokument);
            loeschen(verbindung, "delete from " + URINET.schema() + ".dokument where id = ?", dokument);
            loeschen(verbindung, "delete from " + URINET.schema() + ".kontakt where id = any(?)",
                    verbindung.createArrayOf("uuid", new Object[] {quelle, ziel}));
        }
    }

    @Test
    void parallelesZusammenfuehrenUndAendernLiefert409Statt500() throws Exception {
        datenAnlegen();
        String token = Testanmeldung.token(BENUTZER);
        String ifMatchKontakt = "\"" + zahl("kontakt", quelle, "version") + "\"";
        String ifMatchDokument = "\"" + zahl("dokument", dokument, "version") + "\"";
        SperrReihenfolgeHaken.scharfStellen();

        CompletableFuture<Response> zusammenfuehren = CompletableFuture.supplyAsync(() -> given().auth().oauth2(token)
                .header("If-Match", ifMatchKontakt).contentType(ContentType.JSON)
                .body(Map.of("ziel", ziel.toString(), "grund", "Doppelt erfasst (Sperrprobe)"))
                .when().post("/api/v1/kontakte/" + quelle + "/zusammenfuehren"));
        CompletableFuture<Response> aendern = CompletableFuture.supplyAsync(() -> given().auth().oauth2(token)
                .header("If-Match", ifMatchDokument).contentType(ContentType.JSON)
                .body(Map.of("partnerId", ziel.toString()))
                .when().patch("/api/v1/dokumente/" + dokument));
        Response merge = zusammenfuehren.get(30, TimeUnit.SECONDS);
        Response aenderung = aendern.get(30, TimeUnit.SECONDS);

        assertThat(merge.statusCode()).as("Zusammenführen: %s", merge.asString()).isIn(200, 409);
        assertThat(aenderung.statusCode()).as("Ändern: %s", aenderung.asString()).isIn(200, 409);
        Response unterlegen = merge.statusCode() == 409 ? merge : aenderung;
        assertThat(unterlegen.statusCode()).as("mindestens eine Anfrage verliert den Sperrkonflikt").isEqualTo(409);
        assertThat(unterlegen.path("fehlercode").toString()).isEqualTo("STATUS_KONFLIKT");

        // keine inkonsistente Zeile: das Dokument zeigt auf einen aktiven Kontakt, eine abgelehnte Anfrage ändert nichts
        try (Connection verbindung = datenbank.getConnection(); PreparedStatement abfrage =
                verbindung.prepareStatement("select d.partner_id, k.aktiv, (select aktiv from " + URINET.schema()
                        + ".kontakt where id = ?) as quelle_aktiv from " + URINET.schema()
                        + ".dokument d join " + URINET.schema() + ".kontakt k on k.id = d.partner_id where d.id = ?")) {
            abfrage.setObject(1, quelle);
            abfrage.setObject(2, dokument);
            try (ResultSet zeile = abfrage.executeQuery()) {
                assertThat(zeile.next()).isTrue();
                assertThat(zeile.getBoolean("aktiv")).as("Partner des Dokuments aktiv").isTrue();
                assertThat(zeile.getObject("partner_id", UUID.class)).as("Partner").isIn(quelle, ziel);
                assertThat(zeile.getBoolean("quelle_aktiv")).as("Quelle aktiv").isEqualTo(merge.statusCode() == 409);
                if (merge.statusCode() == 200) {
                    assertThat(zeile.getObject("partner_id", UUID.class)).isEqualTo(ziel);
                }
            }
        }
    }

    private long zahl(String tabelle, UUID id, String spalte) throws SQLException {
        try (Connection verbindung = datenbank.getConnection(); PreparedStatement abfrage = verbindung.prepareStatement(
                "select " + spalte + " from " + URINET.schema() + "." + tabelle + " where id = ?")) {
            abfrage.setObject(1, id);
            try (ResultSet zeile = abfrage.executeQuery()) {
                assertThat(zeile.next()).isTrue();
                return zeile.getLong(1);
            }
        }
    }

    private void datenAnlegen() throws SQLException {
        if (!mandantVorhanden()) {
            provisionierung.anlegen(URINET.wert(), null, null);
        }
        quelle = UUID.randomUUID();
        ziel = UUID.randomUUID();
        dokument = UUID.randomUUID();
        try (Connection verbindung = datenbank.getConnection()) {
            try (PreparedStatement einfuegen = verbindung.prepareStatement("insert into " + URINET.schema()
                    + ".kontakt (id, typ, name, name_normalisiert, quelle) values (?, 'ORGANISATION', ?, ?, 'TEST')")) {
                kontaktEinfuegen(einfuegen, quelle, "Sperrprobe Quelle");
                kontaktEinfuegen(einfuegen, ziel, "Sperrprobe Ziel");
            }
            try (PreparedStatement einfuegen = verbindung.prepareStatement("insert into " + URINET.schema()
                    + ".dokument (id, quelle, erstellt_von, status, partner_id, partner_name) "
                    + "values (?, 'UPLOAD', 'synthetisch', 'EINGANGSKORB', ?, 'Sperrprobe Quelle')")) {
                einfuegen.setObject(1, dokument);
                einfuegen.setObject(2, quelle);
                einfuegen.executeUpdate();
            }
        }
    }

    private boolean mandantVorhanden() throws SQLException {
        try (Connection verbindung = datenbank.getConnection(); PreparedStatement abfrage =
                verbindung.prepareStatement("select 1 from public.mandant where code = ?")) {
            abfrage.setString(1, URINET.wert());
            try (ResultSet zeile = abfrage.executeQuery()) {
                return zeile.next();
            }
        }
    }

    private static void kontaktEinfuegen(PreparedStatement einfuegen, UUID id, String name) throws SQLException {
        einfuegen.setObject(1, id);
        einfuegen.setString(2, name);
        einfuegen.setString(3, name.toLowerCase(java.util.Locale.ROOT));
        einfuegen.executeUpdate();
    }

    private static void loeschen(Connection verbindung, String sql, Object wert) throws SQLException {
        try (PreparedStatement update = verbindung.prepareStatement(sql)) {
            update.setObject(1, wert);
            update.executeUpdate();
        }
    }

}
