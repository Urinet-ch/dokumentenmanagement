package ch.urinet.archiv.integration.domain.webseite;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Formate der Header einer Abkündigung (Spec 03, Req 11.5; RFC 9745, RFC 8594, RFC 9110 IMF-fixdate). */
class AbkuendigungTest {

    @Test
    void headerNachDenRfcs() {
        Abkuendigung a = new Abkuendigung(new Hauptversion(1), LocalDate.of(2027, 7, 1));
        assertThat(a.sunsetHttpDatum()).isEqualTo("Thu, 01 Jul 2027 00:00:00 GMT");
        // 2027-01-01T00:00:00Z, sechs Monate vor dem Sunset
        assertThat(a.deprecation()).isEqualTo("@1798761600");
        assertThat(a.link()).isEqualTo("</public/v2>; rel=\"successor-version\"");
    }

    @Test
    void tagImmerZweistelligUndMonatsendeKorrekt() {
        assertThat(new Abkuendigung(new Hauptversion(3), LocalDate.of(2027, 3, 5)).sunsetHttpDatum())
                .isEqualTo("Fri, 05 Mar 2027 00:00:00 GMT");
        Abkuendigung monatsende = new Abkuendigung(new Hauptversion(1), LocalDate.of(2027, 8, 31));
        // 31. August minus 6 Monate = 28. Februar (kein ungültiger 31. Februar)
        assertThat(monatsende.deprecation()).isEqualTo("@1803772800");
        assertThat(monatsende.sunsetHttpDatum()).isEqualTo("Tue, 31 Aug 2027 00:00:00 GMT");
    }

    @Test
    void deprecationNieNachSunset() {
        Abkuendigung a = new Abkuendigung(new Hauptversion(1), LocalDate.of(2026, 9, 5));
        long deprecation = Long.parseLong(a.deprecation().substring(1));
        assertThat(deprecation).isLessThan(1788566400L);
    }

    @Test
    void hauptversionAusDemPfad() {
        assertThat(Hauptversion.ausPfad("/public/v1")).contains(new Hauptversion(1));
        assertThat(Hauptversion.ausPfad("/public/v12/dokumente")).contains(new Hauptversion(12));
        assertThat(Hauptversion.ausPfad("/public/v0/x")).isEmpty();
        assertThat(Hauptversion.ausPfad("/public/v1x")).isEmpty();
        assertThat(Hauptversion.ausPfad("/public/dokumente")).isEmpty();
        assertThat(Hauptversion.ausPfad("/api/v1/public/v1")).isEmpty();
        assertThat(new Hauptversion(1).nachfolgerin().pfad()).isEqualTo("/public/v2");
        assertThatThrownBy(() -> new Hauptversion(0)).isInstanceOf(IllegalArgumentException.class);
    }
}
