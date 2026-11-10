package ch.urinet.archiv.dokumentart;

import ch.urinet.archiv.dokumentart.domain.AufbewahrungAb;
import ch.urinet.archiv.dokumentart.domain.Aufbewahrung;
import ch.urinet.archiv.dokumentart.domain.Aufbewahrungsregel;
import io.cucumber.java.de.Dann;
import io.cucumber.java.de.Wenn;
import io.restassured.path.json.JsonPath;

import java.time.Instant;
import java.time.LocalDate;
import java.time.MonthDay;
import java.time.format.DateTimeFormatter;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/** Schritte zur Fristberechnung (Req 9.3, 9.7, 9.8, 9.9); rufen die Domain direkt auf, bis Dokumente existieren. */
public class AufbewahrungSchritte {

    private static final DateTimeFormatter DATUM = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final String LEER = "–";
    private static final String UNBEFRISTET = "unbefristet";

    private Aufbewahrung ergebnis;
    private Aufbewahrung.Neuberechnung entscheid;

    @Wenn("die Frist für die Regel «{}» mit Belegdatum «{}», Eingang «{}», Geschäftsjahr «{}», gültig bis «{}» "
            + "und Geschäftsjahresende «{}» am «{}» berechnet wird")
    public void berechnen(String regel, String belegdatum, String eingang, String geschaeftsjahr, String gueltigBis,
                          String gjEnde, String heute) {
        ergebnis = Aufbewahrung.berechne(regel(regel), datum(belegdatum),
                LEER.equals(eingang) ? null : Instant.parse(eingang),
                LEER.equals(geschaeftsjahr) ? null : Integer.valueOf(geschaeftsjahr),
                datum(gueltigBis), MonthDay.parse("--" + gjEnde), datum(heute));
    }

    @Dann("ist aufbewahren_bis «{}» und die Frist {word}")
    public void frist(String bis, String zustand) {
        assertThat(ergebnis.bis()).isEqualTo(UNBEFRISTET.equals(bis) ? null : datum(bis));
        assertThat(ergebnis.fristAbgelaufen()).isEqualTo("abgelaufen".equals(zustand));
    }

    @Wenn("eine bestehende Frist «{}» neu als «{}» berechnet wird")
    public void neuBerechnen(String bisher, String neu) {
        entscheid = Aufbewahrung.entscheiden(UNBEFRISTET.equals(bisher) ? null : datum(bisher),
                UNBEFRISTET.equals(neu) ? null : datum(neu));
    }

    @Dann("lautet der Entscheid «{word}»")
    public void entscheid(String erwartet) {
        assertThat(entscheid).isEqualTo(Aufbewahrung.Neuberechnung.valueOf(erwartet));
    }

    @Dann("bietet die API keinen Endpunkt, der ein Dokument oder seine Datei löscht")
    public void keinLoeschen() {
        Map<String, Map<String, Object>> pfade = JsonPath.from(given().queryParam("format", "json")
                .when().get("/q/openapi").then().statusCode(200).extract().asString()).getMap("paths");
        assertThat(pfade).isNotEmpty();
        pfade.forEach((pfad, methoden) -> {
            if (pfad.startsWith("/api/v1/dokumente")) {
                assertThat(methoden).as(pfad).doesNotContainKey("delete");
            }
        });
    }

    private static Aufbewahrungsregel regel(String text) {
        if (UNBEFRISTET.equals(text)) {
            return Aufbewahrungsregel.UNBEFRISTET;
        }
        String[] teile = text.split(" Jahre ab ");
        return new Aufbewahrungsregel(Integer.valueOf(teile[0]), AufbewahrungAb.valueOf(teile[1]));
    }

    private static LocalDate datum(String text) {
        return LEER.equals(text) ? null : LocalDate.parse(text, DATUM);
    }
}
