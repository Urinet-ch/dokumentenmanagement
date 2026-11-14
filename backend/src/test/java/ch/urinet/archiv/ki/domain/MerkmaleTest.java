package ch.urinet.archiv.ki.domain;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class MerkmaleTest {

    private static final Merkmale.Frist OHNE_GRENZE = new Merkmale.Frist(Duration.ofDays(1), System::nanoTime);
    private static final List<Pattern> MUSTER = List.of(Pattern.compile("^\\d{4}-\\d{3}$"),
            Pattern.compile("^O-\\d{4}-\\d{3}$"));

    @Test
    void erkenntIbanMitLeerzeichenUidEmailUndMuster() {
        Merkmale m = Merkmale.erkennen("""
                Muster Bürobedarf AG, CHE-123.456.789 MWST, Info@Muster-Buero.example
                Offerte O-2026-005, zahlbar auf IBAN CH44 3199 9123 0008 8901 2
                """, Optional.empty(), null, List.of(), MUSTER, OHNE_GRENZE).merkmale();

        assertThat(m.ibans()).containsExactly("CH4431999123000889012");
        assertThat(m.uids()).containsExactly("CHE-123.456.789");
        assertThat(m.emails()).containsExactly("info@muster-buero.example");
        assertThat(m.muster()).containsExactly("^O-\\d{4}-\\d{3}$");
    }

    @Test
    void ungueltigePruefzifferIstKeineIban() {
        assertThat(Merkmale.ibanGueltig("CH4431999123000889012")).isTrue();
        assertThat(Merkmale.ibanGueltig("CH4531999123000889012")).isFalse();
        assertThat(Merkmale.erkennen("IBAN CH45 3199 9123 0008 8901 2", Optional.empty(), null, List.of(), List.of(),
                OHNE_GRENZE).merkmale().leer()).isTrue();
    }

    @Test
    void eigeneKennungenDesMandantenZaehlenNicht() {
        Merkmale m = Merkmale.erkennen("Von CHE-111.222.333, auf CH44 3199 9123 0008 8901 2",
                Optional.of("ch44 3199 9123 0008 8901 2"), "CHE-111.222.333", List.of("CH4431999123000889012"),
                List.of(), OHNE_GRENZE).merkmale();

        assertThat(m.leer()).isTrue();
    }

    @Test
    void qrIbanZaehltAuchOhneText() {
        assertThat(Merkmale.erkennen("", Optional.of("CH4431999123000889012"), null, List.of(), List.of(),
                OHNE_GRENZE).merkmale().ibans()).containsExactly("CH4431999123000889012");
    }

    @Test
    void musterUeberDerFristGiltAlsNichtZutreffendUndWirdGemeldet() {
        AtomicLong uhr = new AtomicLong();
        Merkmale.Frist frist = new Merkmale.Frist(Duration.ofMillis(1), () -> uhr.addAndGet(1_000_000));

        Merkmale.Ergebnis e = Merkmale.erkennen("Rechnung 2026-003", Optional.empty(), null, List.of(), MUSTER,
                frist);

        assertThat(e.merkmale().muster()).isEmpty();
        assertThat(e.abgebrochen()).containsExactly(0, 1);
    }

    @Test
    void trefferZaehltGemeinsameMerkmale() {
        Merkmale a = new Merkmale(java.util.Set.of("CH4431999123000889012"), java.util.Set.of("CHE-123.456.789"),
                java.util.Set.of(), java.util.Set.of());
        Merkmale b = new Merkmale(java.util.Set.of("CH4431999123000889012"), java.util.Set.of("CHE-123.456.789"),
                java.util.Set.of("a@b.example"), java.util.Set.of());

        assertThat(a.treffer(b)).isEqualTo(2);
        assertThat(a.treffer(Merkmale.KEINE)).isZero();
    }
    @Test
    void eigeneUidZaehltAuchMitZusatzOderAndererSchreibweise() {
        Merkmale m = Merkmale.erkennen("Von CHE-111.222.333 MWST", Optional.empty(), "che 111 222 333 MWST",
                List.of(), List.of(), OHNE_GRENZE).merkmale();

        assertThat(m.uids()).isEmpty();
    }

    /** Lange Läufe ohne {@code @} dürfen den E-Mail-Ausdruck nicht quadratisch machen. */
    @Test
    @org.junit.jupiter.api.Timeout(5)
    void langerTextOhneAtLaeuftLinear() {
        String text = "a.".repeat(50_000) + " b-".repeat(50_000);

        assertThat(Merkmale.erkennen(text, Optional.empty(), null, List.of(), List.of(), OHNE_GRENZE).merkmale()
                .emails()).isEmpty();
    }

    /** Nachprüfung PR #109: die eigene UID per Muster aus {@code kennungen.uid}, auch mit weiteren Ziffern. */
    @Test
    void eigeneUidMitZusatzUndWeiterenZiffernZaehltNichtFremdeBleibt() {
        Merkmale m = Merkmale.erkennen("Von CHE-123.456.789 MWST an CHE-987.654.321", Optional.empty(),
                "CHE-123.456.789 MWST, HR 2020", List.of(), List.of(), OHNE_GRENZE).merkmale();

        assertThat(m.uids()).containsExactly("CHE-987.654.321");
    }

    @Test
    void eigeneUidOhnePraefixGiltNurMitGenauNeunZiffern() {
        String text = "CHE-123.456.789 und CHE-987.654.321";

        assertThat(Merkmale.erkennen(text, Optional.empty(), "123 456 789", List.of(), List.of(), OHNE_GRENZE)
                .merkmale().uids()).containsExactly("CHE-987.654.321");
        assertThat(Merkmale.erkennen(text, Optional.empty(), "123.456.789, HR 2020", List.of(), List.of(),
                OHNE_GRENZE).merkmale().uids()).containsExactlyInAnyOrder("CHE-123.456.789", "CHE-987.654.321");
    }

    /** Das Muster der eigenen UID hat keine Rückverfolgung: viele beinahe passende Anläufe bleiben linear. */
    @Test
    @org.junit.jupiter.api.Timeout(5)
    void langeEigeneUidLaeuftLinear() {
        String eigene = "che-12.".repeat(200_000) + "CHE 123 456 789";

        assertThat(Merkmale.erkennen("CHE-123.456.789 " + "che-1 ".repeat(200_000), Optional.empty(), eigene,
                List.of(), List.of(), OHNE_GRENZE).merkmale().uids()).isEmpty();
    }
}
