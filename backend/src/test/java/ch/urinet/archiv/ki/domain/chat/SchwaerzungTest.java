package ch.urinet.archiv.ki.domain.chat;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SchwaerzungTest {

    @Test
    void schwaerztDieVorgegebenenPersonendatenMuster() {
        Schwaerzung schwaerzung = new Schwaerzung(List.of());

        assertThat(schwaerzung.anwenden("mail fabian@example.ch, Tel. +41 79 123 45 67, IBAN CH93 0076 2011 6238 5295 7, "
                + "UID CHE-123.456.789 MWST, AHV 756.1234.5678.97, CHF 1'234.50, Datum 08.11.2026"))
                .isEqualTo("mail [E-MAIL], Tel. [TELEFON], IBAN [IBAN], UID [UID], AHV [AHV-NUMMER], "
                        + "[BETRAG], Datum [DATUM]");
    }

    @Test
    void schwaerztAuchAusgeschriebeneDeutschePersonendaten() {
        Schwaerzung schwaerzung = new Schwaerzung(List.of());

        assertThat(schwaerzung.anwenden("Geboren am 10. Januar 1990 und am 3. Mär. 1987"))
                .isEqualTo("Geboren am [DATUM] und am [DATUM]");
    }

    @Test
    void verwendetFuerBekanntePartnerInnerhalbDerDateiStabilePlatzhalter() {
        Schwaerzung schwaerzung = new Schwaerzung(List.of(List.of("Muster AG"), List.of("Anna Beispiel")));

        assertThat(schwaerzung.anwenden("Muster AG fragt. Anna Beispiel antwortet Muster AG."))
                .isEqualTo("[PARTNER-2] fragt. [PARTNER-1] antwortet [PARTNER-2].");
    }

    @Test
    void freieNamenAusserhalbDerMandantenkontakteSindNichtZuverlaessigErkennbar() {
        Schwaerzung schwaerzung = new Schwaerzung(List.of(List.of("Muster AG")));

        assertThat(schwaerzung.anwenden("Fabian Aschwanden spricht mit einer unbekannten Person."))
                .isEqualTo("Fabian Aschwanden spricht mit einer unbekannten Person.");
    }

    @Test
    void ignoriertZeichenfolgenDieNurWieEineIbanAussehen() {
        Schwaerzung schwaerzung = new Schwaerzung(List.of());

        assertThat(schwaerzung.anwenden("Konto AB12 3456 7890 1234 5678"))
                .isEqualTo("Konto AB12 3456 7890 1234 5678");
    }

    @Test
    void schwaerztIbansAuchVorNachfolgendenWoerternUndSatzzeichen() {
        Schwaerzung schwaerzung = new Schwaerzung(List.of());

        assertThat(schwaerzung.anwenden("IBAN CH93 0076 2011 6238 5295 7 bitte"))
                .isEqualTo("IBAN [IBAN] bitte");
        assertThat(schwaerzung.anwenden("CH93 0076 2011 6238 5295 7 Rechnung 12"))
                .isEqualTo("[IBAN] Rechnung 12");
        assertThat(schwaerzung.anwenden("IBAN: CH93 0076 2011 6238 5295 7 an Hans"))
                .isEqualTo("IBAN: [IBAN] an Hans");
        assertThat(schwaerzung.anwenden("CH9300762011623852957 und mehr"))
                .isEqualTo("[IBAN] und mehr");
        assertThat(schwaerzung.anwenden("DE89 3704 0044 0532 0130 00 ok"))
                .isEqualTo("[IBAN] ok");
        assertThat(schwaerzung.anwenden("ch93 0076 2011 6238 5295 7"))
                .isEqualTo("[IBAN]");
        assertThat(schwaerzung.anwenden("CH9300762011623852957. DE89370400440532013000,"))
                .isEqualTo("[IBAN]. [IBAN],");
        assertThat(schwaerzung.anwenden("CH93 0076 2011 6238 5295 7\nDE89 3704 0044 0532 0130 00"))
                .isEqualTo("[IBAN]\n[IBAN]");
        assertThat(schwaerzung.anwenden("CH93 0076 2011 6238 5295 7 DE89 3704 0044 0532 0130 00"))
                .isEqualTo("[IBAN] [IBAN]");
        assertThat(schwaerzung.anwenden("AB12 3456 7890 1234 5678 CH9300762011623852957"))
                .isEqualTo("AB12 3456 7890 1234 5678 [IBAN]");
    }

    @Test
    void kuerztFrankenNichtAufDasWaehrungskuerzelFr() {
        Schwaerzung schwaerzung = new Schwaerzung(List.of());

        assertThat(schwaerzung.anwenden("1'250 Franken und 1'250 Fr. sowie 50 CHF"))
                .isEqualTo("1'250 Franken und [BETRAG] sowie [BETRAG]");
    }

    @Test
    void verwendetFuerNamensvariantenDerselbenKontaktgruppeEinenPlatzhalter() {
        Schwaerzung schwaerzung = new Schwaerzung(List.of(List.of("Müller AG", "mueller", "Müller GmbH")));

        assertThat(schwaerzung.anwenden("Müller AG, Müller GmbH, mueller"))
                .isEqualTo("[PARTNER-1], [PARTNER-1], [PARTNER-1]");
    }
}
