package ch.urinet.archiv.wissen.application;

import ch.urinet.archiv.ki.application.KiPort;
import ch.urinet.archiv.wissen.domain.Quelleneintrag;
import ch.urinet.archiv.wissen.domain.Seitentyp;
import ch.urinet.archiv.wissen.domain.WissensLint;
import ch.urinet.archiv.wissen.domain.WissensStufe;
import ch.urinet.archiv.wissen.domain.Wissensseite;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WissenLintAuswertungTest {
    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test
    void gemischtesPaarErzeugtTextNurFuerDieHoehereSeite() {
        var a = seite(A, "a", "qa");
        var b = seite(B, "b", "qb");
        var paar = new WissensLint.Paar(new WissensLint.PaarId(A, B), true, List.of(), null);
        var kontext = new WissenLintSpeichern.PaarKontext(a, b, WissensStufe.NORMAL, WissensStufe.INTERN,
                WissensStufe.INTERN);
        var widerspruch = new KiPort.LintWiderspruch(A.toString(), "qa", B.toString(), "qb", "Synthetischer Befund");
        var antwort = new KiPort.LintPaar(List.of(widerspruch), List.of(),
                List.of(new KiPort.LintLink(A.toString(), B.toString(), "Verwandt")));
        var ergebnis = WissenLintAuswertung.auswerten(paar, kontext, antwort);

        assertThat(ergebnis.auftraege()).extracting(WissenLintAuswertung.Seitenauftrag::seiteId).containsOnly(B);
        assertThat(ergebnis.befunde()).contains(new WissensLint.Befund(A, "WIDERSPRUCH", B),
                new WissensLint.Befund(B, "WIDERSPRUCH", A), new WissensLint.Befund(A, "LINK", B));
    }

    @Test
    void erfundeneQuelleOderFremdeSeiteMachtDieAntwortUngueltig() {
        var a = seite(A, "a", "qa");
        var b = seite(B, "b", "qb");
        var paar = new WissensLint.Paar(new WissensLint.PaarId(A, B), true, List.of(), null);
        var kontext = new WissenLintSpeichern.PaarKontext(a, b, WissensStufe.NORMAL, WissensStufe.NORMAL,
                WissensStufe.NORMAL);
        var fremd = new KiPort.LintPaar(List.of(new KiPort.LintWiderspruch(A.toString(), "erfunden", B.toString(),
                "qb", "Probe")), List.of(), List.of());
        assertThatThrownBy(() -> WissenLintAuswertung.auswerten(paar, kontext, fremd))
                .isInstanceOf(IllegalArgumentException.class);
        var unerwartet = new KiPort.LintPaar(List.of(), List.of(new KiPort.LintVeraltet(
                A.toString(), "qa", UUID.randomUUID().toString(), "Probe")), List.of());
        assertThatThrownBy(() -> WissenLintAuswertung.auswerten(paar, kontext, unerwartet))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Wissensseite seite(UUID id, String name, String schluessel) {
        return new Wissensseite(id, "konzepte/" + name, Seitentyp.KONZEPT, name, name, List.of(),
                Wissensseite.Status.AKTIV, null,
                List.of(new Quelleneintrag(schluessel, UUID.randomUUID(), 1, null, null, null)),
                Wissensseite.Vertraulichkeit.NORMAL, "Text [^" + schluessel + "]", 1, "probe", Instant.EPOCH,
                "probe", Instant.EPOCH, false, false, null);
    }
}
