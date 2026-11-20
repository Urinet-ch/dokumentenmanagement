package ch.urinet.archiv.wissen;

import ch.urinet.archiv.gemeinsam.Testanmeldung;
import ch.urinet.archiv.mandant.application.MandantKontext;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.sicherheit.ProblemDetails;
import ch.urinet.archiv.verarbeitung.application.AuftragArbeiter;
import ch.urinet.archiv.wissen.application.WissenLintStarten;
import io.agroal.api.AgroalDataSource;
import io.cucumber.java.After;
import io.cucumber.java.de.Dann;
import io.cucumber.java.de.Wenn;
import io.restassured.response.Response;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

public class WissenLintSchritte {
    @Inject Instance<AgroalDataSource> datenbankInstanz;

    private AgroalDataSource datenbank() {
        return datenbankInstanz.get();
    }
    @Inject MandantKontext kontext;
    @Inject WissenLintStarten lint;
    @Inject AuftragArbeiter arbeiter;

    private Response antwort;
    private UUID lintId;
    private String benutzer;

    @Wenn("«{word}» den Lint startet")
    public void starten(String benutzer) {
        this.benutzer = benutzer;
        antwort = given().auth().oauth2(Testanmeldung.token(benutzer))
                .when().post("/api/v1/wissensbasis/lint");
        if (antwort.statusCode() == 202) {
            lintId = UUID.fromString(antwort.jsonPath().getString("lintId"));
        }
    }

    @Dann("antwortet der Lint-Endpunkt mit Status {int}")
    public void status(int status) {
        assertThat(antwort.statusCode()).isEqualTo(status);
    }

    @Dann("antwortet der Lint-Endpunkt mit Status {int} und Fehlercode «{word}»")
    public void statusUndCode(int status, String code) {
        assertThat(ProblemDetails.pruefen(antwort, status, "felder")).isEqualTo(code);
    }

    @Dann("der Lint ist im Mandanten «urinet» eingereiht und protokolliert")
    public void eingereihtUndProtokolliert() throws SQLException {
        try (var verbindung = datenbank().getConnection();
             var auftrag = verbindung.prepareStatement("""
                     select count(*) from public.auftrag
                     where typ = 'WISSEN_LINT' and nutzlast->>'lintId' = ?
                     """);
             var protokoll = verbindung.prepareStatement("""
                     select count(*) from m_urinet.zugriff_protokoll
                     where aktion = 'WISSEN_LINT' and benutzer = ?
                     """)) {
            auftrag.setString(1, lintId.toString());
            try (var zeilen = auftrag.executeQuery()) {
                assertThat(zeilen.next()).isTrue();
                assertThat(zeilen.getInt(1)).isEqualTo(1);
            }
            protokoll.setString(1, Testanmeldung.sub(benutzer));
            try (var zeilen = protokoll.executeQuery()) {
                assertThat(zeilen.next()).isTrue();
                assertThat(zeilen.getInt(1)).isGreaterThan(0);
            }
        }
    }

    @Wenn("der geplante Lint für «urinet» fällig wird")
    public void geplant() throws Exception {
        lintId = CompletableFuture.supplyAsync(() -> kontext.mit(new MandantCode("urinet"),
                () -> lint.geplant(Instant.now()).orElseThrow())).get(30, TimeUnit.SECONDS);
    }

    @Dann("ist kein Lint-Auftrag eingereiht und «BUDGET_ERSCHOEPFT» im Lint-Log gezählt")
    public void budgetLog() throws SQLException {
        try (var verbindung = datenbank().getConnection();
             var pruefung = verbindung.prepareStatement("""
                     select l.beendet is not null,
                            w.angaben->>'BUDGET_ERSCHOEPFT',
                            (select count(*) from public.auftrag a
                             where a.typ = 'WISSEN_LINT' and a.nutzlast->>'lintId' = l.id::text)
                     from m_urinet.wissens_lint l
                     join m_urinet.wissens_log w on w.entwurf_id = l.entwurf_id and w.aktion = 'LINT'
                     where l.id = ?
                     """)) {
            pruefung.setObject(1, lintId);
            try (var zeilen = pruefung.executeQuery()) {
                assertThat(zeilen.next()).isTrue();
                assertThat(zeilen.getBoolean(1)).isTrue();
                assertThat(zeilen.getString(2)).isEqualTo("1");
                assertThat(zeilen.getInt(3)).isZero();
            }
        }
    }

    @Dann("der Lint meldet die verwaiste Seite und lässt ihre Version unverändert")
    public void verwaisteSeite() throws Exception {
        long ende = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
        while (System.nanoTime() < ende) {
            var laeufe = arbeiter.abholen();
            CompletableFuture.allOf(laeufe.toArray(CompletableFuture[]::new)).get(30, TimeUnit.SECONDS);
            try (var verbindung = datenbank().getConnection();
                 var pruefung = verbindung.prepareStatement("""
                         select l.beendet is not null,
                                l.befunde @> jsonb_build_array(jsonb_build_object(
                                    'seiteId', s.id::text, 'code', 'VERWAIST')),
                                w.angaben->>'VERWAIST', s.version
                         from m_urinet.wissens_lint l
                         join m_urinet.wissensseite s on s.pfad = 'konzepte/lintprobe'
                         left join m_urinet.wissens_log w on w.entwurf_id = l.entwurf_id and w.aktion = 'LINT'
                         where l.id = ?
                         """)) {
                pruefung.setObject(1, lintId);
                try (var zeilen = pruefung.executeQuery()) {
                    assertThat(zeilen.next()).isTrue();
                    if (zeilen.getBoolean(1)) {
                        assertThat(zeilen.getBoolean(2)).isTrue();
                        assertThat(Integer.parseInt(zeilen.getString(3))).isGreaterThanOrEqualTo(1);
                        assertThat(zeilen.getInt(4)).isEqualTo(1);
                        return;
                    }
                }
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Lint wurde nicht abgeschlossen");
    }

    @After
    public void aufraeumen() throws SQLException {
        if (lintId == null) {
            return;
        }
        TestWissenLint.loeschen(datenbank(), lintId);
        lintId = null;
    }
}
