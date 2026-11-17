package ch.urinet.archiv.sicherheit.application;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.protokoll.application.BenutzerVerzeichnisPflege;
import ch.urinet.archiv.sicherheit.domain.ApiClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Grenzen des Ablaufdatums (Req 2.5): «heute» in Europe/Zurich, höchstens fünf Jahre, Ablauf = Beginn des Folgetags
 * in Zürich. Mit fester Uhr, damit weder Mitternacht noch Zeitumstellung das Ergebnis beeinflussen.
 */
class ApiTokenVerwaltungTest {

    private static final MandantCode MANDANT = new MandantCode("urinet");
    private static final String ID = "abcdefghij12";

    private final List<Optional<Instant>> gesetzt = new ArrayList<>();
    private boolean abgelaufen;

    private ApiTokenVerwaltung verwaltung(String jetzt) {
        return new ApiTokenVerwaltung(new Register(), new GeheimnisHasher() {
            @Override
            public String hashen(String geheimnis) {
                return "hash:" + geheimnis;
            }

            @Override
            public boolean pruefen(String geheimnis, String hash) {
                return hash.equals("hash:" + geheimnis);
            }
        }, code -> Optional.of("m_" + code.wert()), new HashSperre(1, Duration.ZERO),
                new BenutzerVerzeichnisPflege(null), Clock.fixed(Instant.parse(jetzt), ZoneOffset.UTC));
    }

    private Instant ablaufFuer(String jetzt, String tag) {
        gesetzt.clear();
        verwaltung(jetzt).gueltigBisSetzen(MANDANT, ID, LocalDate.parse(tag));
        return gesetzt.get(0).orElseThrow();
    }

    private void abgelehnt(String jetzt, String tag) {
        assertThatThrownBy(() -> ablaufFuer(jetzt, tag))
                .isInstanceOfSatisfying(FachFehler.class,
                        f -> assertThat(f.code()).isEqualTo(Fehlercode.ANFRAGE_UNGUELTIG));
    }

    @ParameterizedTest(name = "{0}: Ablauf {1} -> {2}")
    @CsvSource({
            // Winter: Beginn des Folgetags in Zürich ist 23:00 UTC desselben Tages
            "2026-01-15T12:00:00Z, 2026-01-15, 2026-01-15T23:00:00Z",
            // Sommer: 22:00 UTC
            "2026-07-15T12:00:00Z, 2026-07-15, 2026-07-15T22:00:00Z",
            // Zeitumstellung Frühling (29.3.2026): Folgetag beginnt in Sommerzeit
            "2026-03-28T12:00:00Z, 2026-03-28, 2026-03-28T23:00:00Z",
            "2026-03-29T12:00:00Z, 2026-03-29, 2026-03-29T22:00:00Z",
            // Zeitumstellung Herbst (25.10.2026): der Tag hat 25 Stunden
            "2026-10-25T12:00:00Z, 2026-10-25, 2026-10-25T23:00:00Z",
            // Schalttag
            "2028-02-29T12:00:00Z, 2028-02-29, 2028-02-29T23:00:00Z"
    })
    void ablaufIstBeginnDesFolgetagsInZuerich(String jetzt, String tag, String erwartet) {
        assertThat(ablaufFuer(jetzt, tag)).isEqualTo(Instant.parse(erwartet));
    }

    @Test
    void heuteIstZulaessigUndGesternNicht() {
        assertThat(ablaufFuer("2026-06-10T12:00:00Z", "2026-06-10")).isEqualTo(Instant.parse("2026-06-10T22:00:00Z"));
        abgelehnt("2026-06-10T12:00:00Z", "2026-06-09");
    }

    @Test
    void obergrenzeIstHeutePlusFuenfJahre() {
        assertThat(ablaufFuer("2026-06-10T12:00:00Z", "2031-06-10")).isEqualTo(Instant.parse("2031-06-10T22:00:00Z"));
        abgelehnt("2026-06-10T12:00:00Z", "2031-06-11");
    }

    @Test
    void obergrenzeAmSchalttag() {
        assertThat(ablaufFuer("2028-02-29T12:00:00Z", "2033-02-28")).isEqualTo(Instant.parse("2033-02-28T23:00:00Z"));
        abgelehnt("2028-02-29T12:00:00Z", "2033-03-01");
    }

    @Test
    void heuteRichtetSichNachZuerichNichtNachUtc() {
        // 23:30 UTC am 10.6. ist in Zürich schon 01:30 am 11.6.: der 10. ist vergangen, der 11. zulässig
        abgelehnt("2026-06-10T23:30:00Z", "2026-06-10");
        assertThat(ablaufFuer("2026-06-10T23:30:00Z", "2026-06-11")).isEqualTo(Instant.parse("2026-06-11T22:00:00Z"));
        // 22:30 UTC am 10.6. ist in Zürich 00:30 am 11.6. (Sommerzeit); im Winter ab 23:00 UTC
        abgelehnt("2026-06-10T22:30:00Z", "2026-06-10");
        assertThat(ablaufFuer("2026-01-10T22:30:00Z", "2026-01-10")).isEqualTo(Instant.parse("2026-01-10T23:00:00Z"));
        abgelehnt("2026-01-10T23:30:00Z", "2026-01-10");
    }

    @Test
    void entfernenSetztLeerenAblauf() {
        gesetzt.clear();
        verwaltung("2026-06-10T12:00:00Z").gueltigBisEntfernen(MANDANT, ID);
        assertThat(gesetzt).containsExactly(Optional.empty());
    }

    @Test
    void setzenOhneDatumIstPflichtfeld() {
        assertThatThrownBy(() -> verwaltung("2026-06-10T12:00:00Z").gueltigBisSetzen(MANDANT, ID, null))
                .isInstanceOfSatisfying(FachFehler.class,
                        f -> assertThat(f.code()).isEqualTo(Fehlercode.PFLICHTFELD_FEHLT));
    }

    @Test
    void abgelaufenesTokenLaesstSichWederVerlaengernNochEntfristen() {
        abgelaufen = true;
        gesetzt.clear();
        ApiTokenVerwaltung verwaltung = verwaltung("2026-06-10T12:00:00Z");

        assertThatThrownBy(() -> verwaltung.gueltigBisSetzen(MANDANT, ID, LocalDate.parse("2026-06-11")))
                .isInstanceOfSatisfying(FachFehler.class,
                        f -> assertThat(f.code()).isEqualTo(Fehlercode.API_TOKEN_ABGELAUFEN));
        assertThatThrownBy(() -> verwaltung.gueltigBisEntfernen(MANDANT, ID))
                .isInstanceOfSatisfying(FachFehler.class,
                        f -> assertThat(f.code()).isEqualTo(Fehlercode.API_TOKEN_ABGELAUFEN));
    }

    private final class Register implements ApiClientRegister {
        @Override
        public Optional<Zugang> finden(String id) {
            return Optional.empty();
        }

        @Override
        public void anlegen(ApiClient client, String hash) {
        }

        @Override
        public List<ApiClient> liste(String mandant) {
            return List.of();
        }

        @Override
        public boolean widerrufen(String mandant, String id) {
            return false;
        }

        @Override
        public boolean gueltigBisSetzen(String mandant, String id, Instant zeit) {
            return false;
        }

        @Override
        public boolean apiTokenGueltigkeitSetzen(String mandant, String id, Optional<Instant> zeit, Instant jetzt) {
            if (abgelaufen) {
                return false;
            }
            gesetzt.add(zeit);
            return true;
        }

        @Override
        public boolean apiTokenAbgelaufen(String mandant, String id, Instant jetzt) {
            return abgelaufen;
        }

        @Override
        public void zuletztSetzen(String id, Instant zeit) {
        }
    }
}
