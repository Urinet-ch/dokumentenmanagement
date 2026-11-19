package ch.urinet.archiv.wissen.adapter.out.okf;

import ch.urinet.archiv.ablage.application.AblagePort;
import ch.urinet.archiv.dokument.Testdokumente;
import ch.urinet.archiv.gemeinsam.Testanmeldung;
import ch.urinet.archiv.mandant.application.MandantKontext;
import ch.urinet.archiv.mandant.application.MandantProvisionierung;
import ch.urinet.archiv.mandant.application.MandantVollexportDaten;
import ch.urinet.archiv.mandant.application.MandantVollexportSchreiber;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.wissen.application.WissenOkfExportPort;
import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static io.restassured.RestAssured.given;

@QuarkusTest
class OkfExportDatenTest {
    @Inject
    WissenOkfExportPort export;
    @Inject
    MandantKontext kontext;
    @Inject
    MandantProvisionierung provisionierung;
    @Inject
    Instance<AgroalDataSource> datenbank;

    @Test
    void geschaeftsstelleErhaeltBeiEinstellungen403OhneAenderung() throws SQLException {
        MandantCode code = new MandantCode("vhf-gsk");
        if (!vorhanden(code)) {
            provisionierung.anlegen(code.wert(), null, null);
        }
        String vorher = einstellungen(code);
        String token = Testanmeldung.token("geschaeftsstelle-vhf-gsk");
        given().auth().oauth2(token).when().get("/api/v1/wissensbasis/einstellungen").then().statusCode(403);
        given().auth().oauth2(token).contentType("application/json")
                .body("{\"budgetTokens\":100,\"lintBudgetTokens\":100,\"monatsbudgetTokens\":100}")
                .when().put("/api/v1/wissensbasis/einstellungen").then().statusCode(403);
        assertThat(einstellungen(code)).isEqualTo(vorher);
    }

    private String einstellungen(MandantCode code) throws SQLException {
        try (Connection c = datenbank.get().getConnection();
             PreparedStatement sql = c.prepareStatement("select row_to_json(e)::text from " + code.schema()
                     + ".wissens_einstellung e where id = 1");
             ResultSet rs = sql.executeQuery()) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    @Test
    void exportLiestNurDasSchemaDesAuftrags(@TempDir Path temp) throws Exception {
        MandantCode erster = new MandantCode("urinet");
        MandantCode zweiter = new MandantCode("vhf-gsk");
        for (MandantCode code : List.of(erster, zweiter)) {
            if (!vorhanden(code)) {
                provisionierung.anlegen(code.wert(), null, null);
            }
        }
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        String pfad1 = "konzepte/probe-" + id1;
        String pfad2 = "konzepte/probe-" + id2;
        try {
            seite(erster, id1, pfad1);
            seite(zweiter, id2, pfad2);
            byte[] zip1 = exportieren(erster);
            byte[] zip2 = exportieren(zweiter);
            assertThat(dateinamen(zip1)).contains(pfad1 + ".md").doesNotContain(pfad2 + ".md");
            assertThat(dateinamen(zip2)).contains(pfad2 + ".md").doesNotContain(pfad1 + ".md");
            byte[] paket = dateiBytes(vollexportieren(erster, temp), "wissensbasis.zip");
            assertThat(dateinamen(paket)).contains(pfad1 + ".md").doesNotContain(pfad2 + ".md");
        } finally {
            loeschen(erster, id1);
            loeschen(zweiter, id2);
        }
    }

    @Test
    void veralteteHuelleErhoehtKontextstufeUndPersonaldokumentBrichtAb() throws Exception {
        MandantCode code = new MandantCode("urinet");
        if (!vorhanden(code)) {
            provisionierung.anlegen(code.wert(), null, null);
        }
        UUID id = UUID.randomUUID();
        UUID dokument = Testdokumente.anlegen(datenbank.get(),
                new Testdokumente.Angaben(code.wert(), null, "NORMAL", true, "probe", null, "ARCHIVIERT"));
        try {
            seite(code, id, "konzepte/probe-" + id);
            try (Connection c = datenbank.get().getConnection()) {
                try (PreparedStatement sql = c.prepareStatement("insert into " + code.schema()
                        + ".dokument_datei (dokument_id, nr, rolle, sha256) values (?, 1, 'ORIGINAL', ?)")) {
                    sql.setObject(1, dokument);
                    sql.setString(2, UUID.randomUUID().toString().replace("-", "").repeat(2));
                    sql.executeUpdate();
                }
                try (PreparedStatement sql = c.prepareStatement("insert into " + code.schema()
                        + ".wissensseite_quelle (seite_id, schluessel, dokument_id, seite_von, auszug) "
                        + "values (?, 'q1', ?, 1, 'Probe')")) {
                    sql.setObject(1, id);
                    sql.setObject(2, dokument);
                    sql.executeUpdate();
                }
            }
            Map<?, ?> paket = new Yaml().load(datei(exportieren(code), "paket.yaml"));
            assertThat(paket.get("kontext_stufe")).isEqualTo("INTERN");
            try (Connection c = datenbank.get().getConnection();
                 PreparedStatement sql = c.prepareStatement("update " + code.schema()
                         + ".dokument set vertraulichkeit = 'PERSONAL' where id = ?")) {
                sql.setObject(1, dokument);
                sql.executeUpdate();
            }
            assertThatThrownBy(() -> exportieren(code))
                    .hasRootCauseInstanceOf(IllegalStateException.class)
                    .hasRootCauseMessage("Dokument der Wissenshülle ist nicht exportierbar");
        } finally {
            loeschen(code, id);
            Testdokumente.loeschen(datenbank.get(), code.wert(), List.of(dokument));
        }
    }

    private byte[] exportieren(MandantCode code) throws Exception {
        return CompletableFuture.supplyAsync(() -> kontext.mit(code, () -> {
            ByteArrayOutputStream ziel = new ByteArrayOutputStream();
            try {
                export.schreiben(code, ziel);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            return ziel.toByteArray();
        })).get(1, TimeUnit.MINUTES);
    }

    private byte[] vollexportieren(MandantCode code, Path temp) throws Exception {
        MandantVollexportDaten daten = new MandantVollexportDaten() {
            public List<Dokument> seite(Optional<UUID> nach, int anzahl) {
                return List.of();
            }

            public List<Manifestdatei> manifeste() {
                return List.of();
            }
        };
        AblagePort ablage = (AblagePort) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{AblagePort.class}, (proxy, methode, args) -> {
                    throw new AssertionError("Unerwarteter Ablageaufruf: " + methode.getName());
                });
        return CompletableFuture.supplyAsync(() -> kontext.mit(code, () -> {
            ByteArrayOutputStream ziel = new ByteArrayOutputStream();
            try {
                new MandantVollexportSchreiber(daten, ablage, export).schreiben(code, UUID.randomUUID(), temp, ziel);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            return ziel.toByteArray();
        })).get(1, TimeUnit.MINUTES);
    }

    private List<String> dateinamen(byte[] daten) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(daten))) {
            java.util.ArrayList<String> namen = new java.util.ArrayList<>();
            for (var eintrag = zip.getNextEntry(); eintrag != null; eintrag = zip.getNextEntry()) {
                namen.add(eintrag.getName());
            }
            return namen;
        }
    }

    private String datei(byte[] daten, String name) throws IOException {
        return new String(dateiBytes(daten, name), StandardCharsets.UTF_8);
    }

    private byte[] dateiBytes(byte[] daten, String name) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(daten))) {
            for (var eintrag = zip.getNextEntry(); eintrag != null; eintrag = zip.getNextEntry()) {
                if (eintrag.getName().equals(name)) {
                    return zip.readAllBytes();
                }
            }
        }
        throw new IllegalStateException("Datei im Testpaket fehlt");
    }

    private boolean vorhanden(MandantCode code) throws SQLException {
        try (Connection c = datenbank.get().getConnection();
             PreparedStatement sql = c.prepareStatement("select 1 from public.mandant where code = ?")) {
            sql.setString(1, code.wert());
            try (ResultSet rs = sql.executeQuery()) {
                return rs.next();
            }
        }
    }

    private void seite(MandantCode code, UUID id, String pfad) throws SQLException {
        try (Connection c = datenbank.get().getConnection();
             PreparedStatement sql = c.prepareStatement("insert into " + code.schema() + ".wissensseite "
                     + "(id,pfad,typ,titel,beschreibung,vertraulichkeit,inhalt,version,erzeugt_von,erzeugt_am,"
                     + "freigegeben_von,freigegeben_am) values (?,?,'Konzept','Probe','Probe','NORMAL',"
                     + "'Probe',1,'probe',now(),'probe',now())")) {
            sql.setObject(1, id);
            sql.setString(2, pfad);
            sql.executeUpdate();
        }
    }

    private void loeschen(MandantCode code, UUID id) throws SQLException {
        try (Connection c = datenbank.get().getConnection();
             PreparedStatement sql = c.prepareStatement("delete from " + code.schema() + ".wissensseite where id=?")) {
            sql.setObject(1, id);
            sql.executeUpdate();
        }
    }
}
