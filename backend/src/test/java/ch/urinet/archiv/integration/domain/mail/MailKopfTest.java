package ch.urinet.archiv.integration.domain.mail;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Kopfwerte vor {@code mail_eingang} (Spec 02, Req 1.4, 1.5): ohne NUL und Steuerzeichen, mit Längengrenze. */
class MailKopfTest {

    @Test
    void messageIdOhneSteuerzeichenUndLeerraumUeberlangeAlsStabilerHash() {
        assertThat(MailKopf.messageId(" <a\u0000b\r\n c@example.invalid> ")).contains("<abc@example.invalid>");
        assertThat(MailKopf.messageId("\u0000 \t")).isEmpty();
        assertThat(MailKopf.messageId(null)).isEmpty();
        String lang = "<" + "x".repeat(4000) + "@example.invalid>";
        Optional<String> id = MailKopf.messageId(lang);
        assertThat(id).hasValueSatisfying(v -> assertThat(v).startsWith(MailKopf.PRAEFIX_HASH).hasSize(71));
        assertThat(MailKopf.messageId(lang)).isEqualTo(id);
        assertThat(MailKopf.messageId(id.get())).isEqualTo(id);
    }

    @Test
    void betreffUndAbsenderOhneNulUndGekuerzt() {
        assertThat(MailKopf.wert(Optional.of("Rechnung\u0000X"), MailKopf.MAX_BETREFF)).contains("Rechnung X");
        assertThat(MailKopf.wert(Optional.of("y".repeat(5000)), MailKopf.MAX_BETREFF))
                .hasValueSatisfying(v -> assertThat(v).hasSize(MailKopf.MAX_BETREFF));
        assertThat(MailKopf.wert(Optional.of("\u0000"), MailKopf.MAX_ABSENDER)).isEmpty();
    }
}
