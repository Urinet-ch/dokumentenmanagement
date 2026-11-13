package ch.urinet.archiv.integration.adapter.out.imap;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Text aus HTML für Mails ohne {@code text/plain} (Spec 02, Req 1.3). */
class MailTeileTest {

    @Test
    void htmlOhneMarkupSkripteUndStile() {
        assertThat(MailTeile.ohneMarkup("<html><head><style>p{}</style></head><body><p>Guten Tag</p>"
                + "<script>alert(1)</script>Rechnung&nbsp;&amp;&nbsp;Offerte<br>Gruss</body></html>"))
                .isEqualTo("Guten Tag\nRechnung & Offerte\nGruss");
    }
}
