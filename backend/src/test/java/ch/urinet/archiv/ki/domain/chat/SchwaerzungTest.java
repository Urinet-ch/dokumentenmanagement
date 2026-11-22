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
    void verwendetFuerBekanntePartnerInnerhalbDerDateiStabilePlatzhalter() {
        Schwaerzung schwaerzung = new Schwaerzung(List.of("Muster AG", "Anna Beispiel"));

        assertThat(schwaerzung.anwenden("Muster AG fragt. Anna Beispiel antwortet Muster AG."))
                .isEqualTo("[PARTNER-2] fragt. [PARTNER-1] antwortet [PARTNER-2].");
    }

    @Test
    void freieNamenAusserhalbDerMandantenkontakteSindNichtZuverlaessigErkennbar() {
        Schwaerzung schwaerzung = new Schwaerzung(List.of("Muster AG"));

        assertThat(schwaerzung.anwenden("Fabian Aschwanden spricht mit einer unbekannten Person."))
                .isEqualTo("Fabian Aschwanden spricht mit einer unbekannten Person.");
    }

    @Test
    void ignoriertZeichenfolgenDieNurWieEineIbanAussehen() {
        Schwaerzung schwaerzung = new Schwaerzung(List.of());

        assertThat(schwaerzung.anwenden("Konto AB12 3456 7890 1234 5678"))
                .isEqualTo("Konto AB12 3456 7890 1234 5678");
    }
}
