package ch.urinet.archiv.stapel;

import ch.urinet.archiv.gemeinsam.Testanmeldung;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.sicherheit.ProblemDetails;
import ch.urinet.archiv.stapel.domain.TrennblattCode;
import io.agroal.api.AgroalDataSource;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.After;
import io.cucumber.java.de.Angenommen;
import io.cucumber.java.de.Dann;
import io.cucumber.java.de.Wenn;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Schritte zum Trennblatt-Generator (Spec 09, Requirement 6). Das PDF liest {@link TrennblattPdfLeser} wie die
 * spätere Stapelanalyse (150 dpi, ZXing). «U+XXXX» in einem Titel steht für genau dieses Zeichen. Alle Werte sind
 * synthetisch.
 */
public class TrennblattSchritte {

    private static final String PFAD = "/api/v1/trennblaetter";
    private static final Pattern ZEICHEN = Pattern.compile("U\\+([0-9A-F]{4})");
    private static final Pattern SEITEN = Pattern.compile("^(\\d+)-(\\d+)$");

    @Inject
    Instance<AgroalDataSource> datenbank;

    private Response antwort;
    private String titel;
    private List<TrennblattPdfLeser.Seite> seiten;
    private final Map<Integer, String> aufschriften = new HashMap<>();
    private final Map<String, List<String>> inaktiv = new HashMap<>();

    @Angenommen("die Art «{word}» ist im Mandanten «{word}» inaktiv")
    public void artInaktiv(String art, String mandant) throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement update = verbindung.prepareStatement("update " + new MandantCode(mandant).schema()
                     + ".dokumentart set aktiv = false where code = ? and aktiv")) {
            update.setString(1, art);
            assertThat(update.executeUpdate()).as("aktive Art " + art).isEqualTo(1);
        }
        inaktiv.computeIfAbsent(mandant, m -> new ArrayList<>()).add(art);
    }

    @After
    public void artenWiederAktivieren() throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection()) {
            for (Map.Entry<String, List<String>> e : inaktiv.entrySet()) {
                try (PreparedStatement update = verbindung.prepareStatement("update "
                        + new MandantCode(e.getKey()).schema() + ".dokumentart set aktiv = true where code = any(?)")) {
                    update.setArray(1, verbindung.createArrayOf("text", e.getValue().toArray()));
                    update.executeUpdate();
                }
            }
        }
        inaktiv.clear();
    }

    @Wenn("«{word}» diese Trennblätter erzeugt:")
    public void erzeugen(String benutzer, DataTable tabelle) {
        List<Map<String, Object>> blaetter = new ArrayList<>();
        for (Map<String, String> zeile : tabelle.asMaps()) {
            Map<String, Object> blatt = new LinkedHashMap<>();
            blatt.put("typ", zeile.get("typ"));
            if (zeile.get("art") != null) {
                blatt.put("art", zeile.get("art"));
            }
            if (zeile.get("titel") != null) {
                blatt.put("titel", zeichen(zeile.get("titel")));
            }
            blatt.put("anzahl", Integer.parseInt(zeile.get("anzahl")));
            blaetter.add(blatt);
        }
        senden(benutzer, blaetter);
    }

    @Wenn("«{word}» ein Ordner-Deckblatt mit dem Titel «{}» erzeugt")
    public void deckblatt(String benutzer, String titel) {
        this.titel = zeichen(titel);
        senden(benutzer, List.of(Map.<String, Object>of("typ", "ORDNER", "titel", this.titel, "anzahl", 1)));
    }

    @Wenn("«{word}» ein Ordner-Deckblatt mit dem Titel aus {int} Zeichen «{word}» erzeugt")
    public void deckblattMitLaenge(String benutzer, int laenge, String zeichen) {
        deckblatt(benutzer, zeichen.repeat(laenge));
    }

    @Dann("liefert der Trennblatt-Generator ein PDF zum Herunterladen mit {int} Seiten im Format A4 hochkant")
    public void pdf(int anzahl) throws IOException {
        pdfAntwort();
        assertThat(antwort.header("Content-Disposition")).isEqualTo("attachment; filename=\"trennblaetter.pdf\"");
        assertThat(antwort.header("Cache-Control")).isEqualTo("no-store");
        seiten = TrennblattPdfLeser.seiten(antwort.asByteArray());
        assertThat(seiten).hasSize(anzahl).allSatisfy(seite -> {
            assertThat(seite.breitePt()).as("Breite A4").isBetween(595f, 596f);
            assertThat(seite.hoehePt()).as("Höhe A4").isBetween(841f, 842f);
        });
    }

    @Dann("tragen die Seiten diese QR-Codes und Aufschriften:")
    public void qrCodes(DataTable tabelle) {
        int erwartet = 0;
        for (Map<String, String> zeile : tabelle.asMaps()) {
            Matcher bereich = SEITEN.matcher(zeile.get("seiten"));
            assertThat(bereich.matches()).as(zeile.get("seiten")).isTrue();
            String aufschrift = zeichen(zeile.get("aufschrift"));
            for (int nr = Integer.parseInt(bereich.group(1)); nr <= Integer.parseInt(bereich.group(2)); nr++) {
                TrennblattPdfLeser.Seite seite = seiten.get(nr - 1);
                assertThat(seite.qr()).as("QR-Code Seite " + nr).isEqualTo(zeile.get("qr"));
                assertThat(seite.zeilen()).as("Aufschrift Seite " + nr).containsKey(aufschrift);
                aufschriften.put(nr, aufschrift);
                erwartet++;
            }
        }
        assertThat(erwartet).as("alle Seiten beschrieben").isEqualTo(seiten.size());
    }

    @Dann("trägt jede Seite den Hinweis «{}»")
    public void hinweis(String hinweis) {
        assertThat(seiten).allSatisfy(seite -> assertThat(seite.zeilen()).containsKey(hinweis));
    }

    @Dann("ist jeder QR-Code mindestens {int} mm breit, mit Fehlerkorrektur M oder höher")
    public void qrGroesse(int mm) {
        assertThat(seiten).allSatisfy(seite -> {
            assertThat(seite.qr()).as("lesbar").isNotNull();
            assertThat(seite.kanteMm()).isGreaterThanOrEqualTo(mm);
            assertThat(seite.korrektur()).isIn("M", "Q", "H");
        });
    }

    @Dann("steht die Aufschrift jeder Seite in mindestens {int} pt")
    public void grosseSchrift(int pt) {
        assertThat(aufschriften).hasSize(seiten.size());
        aufschriften.forEach((nr, aufschrift) -> assertThat(seiten.get(nr - 1).zeilen().get(aufschrift))
                .as("Seite " + nr).isGreaterThanOrEqualTo((float) pt));
    }

    @Dann("enthält jeder QR-Code den Titel URL-kodiert in UTF-8")
    public void titelKodiert() {
        String erwartet = "URINET-ORDNER;v=1;titel="
                + URLEncoder.encode(titel, StandardCharsets.UTF_8).replace("+", "%20");
        assertThat(seiten).allSatisfy(seite -> {
            assertThat(seite.qr()).isEqualTo(erwartet);
            assertThat(TrennblattCode.lesen(seite.qr()).orElseThrow().titel()).isEqualTo(titel);
        });
    }

    /** «200 PDF» oder «<Status> <Fehlercode>» als Problem Details. */
    @Dann("antwortet der Trennblatt-Generator mit «{}»")
    public void ergebnis(String ergebnis) {
        String[] teile = ergebnis.split(" ", 2);
        if ("PDF".equals(teile[1])) {
            assertThat(antwort.statusCode()).isEqualTo(Integer.parseInt(teile[0]));
            pdfAntwort();
        } else {
            assertThat(ProblemDetails.pruefen(antwort, Integer.parseInt(teile[0]))).isEqualTo(teile[1]);
        }
    }

    private void pdfAntwort() {
        assertThat(antwort.statusCode()).isEqualTo(200);
        assertThat(antwort.contentType()).startsWith("application/pdf");
        assertThat(new String(antwort.asByteArray(), 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
    }

    private void senden(String benutzer, List<Map<String, Object>> blaetter) {
        seiten = null;
        aufschriften.clear();
        antwort = given().auth().oauth2(Testanmeldung.token(benutzer)).contentType(ContentType.JSON)
                .accept("application/pdf, application/problem+json").body(Map.of("blaetter", blaetter))
                .when().post(PFAD);
    }

    private static String zeichen(String text) {
        return ZEICHEN.matcher(text).replaceAll(m -> Matcher.quoteReplacement(Character.toString(Integer.parseInt(m.group(1), 16))));
    }
}
