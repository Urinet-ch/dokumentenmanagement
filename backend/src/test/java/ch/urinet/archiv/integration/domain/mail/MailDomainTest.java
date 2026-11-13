package ch.urinet.archiv.integration.domain.mail;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Zulassungsliste, Hinweis und Postfach des Mail-Eingangs (Spec 02, Req 1.1, 1.2, 1.4). */
class MailDomainTest {

    @Test
    void zulassungslisteVergleichtOhneGrossKleinUndLaesstOhneAdresseNiemandenZu() {
        Zulassungsliste liste = Zulassungsliste.aus(List.of(" Inhaber@Example.invalid ", "buero@example.invalid"));
        assertThat(liste.adressen()).containsExactlyInAnyOrder("inhaber@example.invalid", "buero@example.invalid");
        assertThat(liste.zugelassen(Optional.of("INHABER@example.INVALID"))).isTrue();
        assertThat(liste.zugelassen(Optional.of("fremd@example.invalid"))).isFalse();
        assertThat(liste.zugelassen(Optional.empty())).isFalse();
        assertThat(Zulassungsliste.aus(List.of()).zugelassen(Optional.of("inhaber@example.invalid"))).isFalse();
    }

    @Test
    void ungueltigeAdresseNenntNurIhreNummer() {
        assertThatThrownBy(() -> Zulassungsliste.aus(List.of("a@example.invalid", "Name <b@example.invalid>")))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Ungültige Absenderadresse Nr. 2");
    }

    @Test
    void hinweisNenntAbsenderDatumUndBetreffOhneSteuerzeichenUndHoechstens1000Zeichen() {
        Instant datum = Instant.parse("2026-11-13T09:15:00Z");
        assertThat(MailHinweis.text(Optional.of("inhaber@example.invalid"), Optional.of("Rechnung\r\n\tNovember‮"),
                Optional.of(datum)))
                .isEqualTo("E-Mail; Von: inhaber@example.invalid; Datum: 13.11.2026 10:15; Betreff: Rechnung November");
        assertThat(MailHinweis.text(Optional.empty(), Optional.empty(), Optional.empty())).isEqualTo("E-Mail");
        String lang = MailHinweis.text(Optional.of("a@example.invalid"), Optional.of("x".repeat(5000)), Optional.empty());
        assertThat(lang.codePointCount(0, lang.length())).isEqualTo(MailHinweis.MAX_ZEICHEN);
        assertThat(lang).endsWith("x…");
    }

    @Test
    void postfachPrueftHostPortUndBenutzer() {
        assertThat(new Postfach("imap.example.invalid", Postfach.STANDARD_PORT, "archiv@example.invalid").port())
                .isEqualTo(993);
        assertThatThrownBy(() -> new Postfach("imap example", 993, "a")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Postfach("imap.example.invalid", 0, "a"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Postfach("imap.example.invalid", 993, "a b"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
