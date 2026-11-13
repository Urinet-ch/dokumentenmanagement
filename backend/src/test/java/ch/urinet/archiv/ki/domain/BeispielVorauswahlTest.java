package ch.urinet.archiv.ki.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class BeispielVorauswahlTest {

    private static final String IBAN = "CH4431999123000889012";
    private static final String UID = "CHE-123.456.789";
    private static final BeispielSicht MITARBEITER = new BeispielSicht("joel", true, false);

    private static Beispiel beispiel(long id, long sekunde, Set<String> ibans, Set<String> uids,
                                     String vertraulichkeit, String person) {
        return new Beispiel(id, "LIEFERANTENBELEG", Map.of(Feld.TITEL, "Titel " + id),
                new Merkmale(ibans, uids, Set.of(), Set.of()), vertraulichkeit, person,
                Instant.ofEpochSecond(sekunde));
    }

    @Test
    void mehrTrefferVorNeuerHoechstensFuenf() {
        Merkmale beleg = new Merkmale(Set.of(IBAN), Set.of(UID), Set.of(), Set.of());
        Beispiel zwei = beispiel(1, 10, Set.of(IBAN), Set.of(UID), "NORMAL", null);
        List<Beispiel> einer = IntStream.rangeClosed(2, 7)
                .mapToObj(i -> beispiel(i, 100, Set.of(IBAN), Set.of(), "NORMAL", null)).toList();
        Beispiel ohne = beispiel(99, 1000, Set.of("DE89370400440532013000"), Set.of(), "NORMAL", null);
        List<Beispiel> kandidaten = new java.util.ArrayList<>(einer);
        kandidaten.add(zwei);
        kandidaten.add(ohne);

        List<Beispiel> auswahl = BeispielVorauswahl.auswaehlen(beleg, kandidaten, List.of(ohne), MITARBEITER);

        assertThat(auswahl).extracting(Beispiel::id).containsExactly(1L, 7L, 6L, 5L, 4L);
    }

    @Test
    void ohneTrefferDieNeuesten() {
        List<Beispiel> neueste = IntStream.rangeClosed(1, 7)
                .mapToObj(i -> beispiel(i, i, Set.of(IBAN), Set.of(), "NORMAL", null)).toList();

        assertThat(BeispielVorauswahl.auswaehlen(Merkmale.KEINE, List.of(), neueste, MITARBEITER))
                .extracting(Beispiel::id).containsExactly(7L, 6L, 5L, 4L, 3L);
    }

    @Test
    void nurBeispieleDieDerErfasserLesenDarf() {
        Merkmale beleg = new Merkmale(Set.of(IBAN), Set.of(), Set.of(), Set.of());
        List<Beispiel> kandidaten = List.of(
                beispiel(1, 1, Set.of(IBAN), Set.of(), "NORMAL", null),
                beispiel(2, 2, Set.of(IBAN), Set.of(), "INTERN", null),
                beispiel(3, 3, Set.of(IBAN), Set.of(), "PERSONAL", "joel"),
                beispiel(4, 4, Set.of(IBAN), Set.of(), "PERSONAL", "fabian"));

        assertThat(BeispielVorauswahl.auswaehlen(beleg, kandidaten, List.of(), MITARBEITER))
                .extracting(Beispiel::id).containsExactly(3L, 1L);
        assertThat(BeispielVorauswahl.auswaehlen(beleg, kandidaten, List.of(), new BeispielSicht("fabian", true, true)))
                .extracting(Beispiel::id).containsExactly(4L, 2L, 1L);
        assertThat(BeispielVorauswahl.auswaehlen(Merkmale.KEINE, List.of(), kandidaten,
                new BeispielSicht("unbekannt", true, false))).extracting(Beispiel::id).containsExactly(1L);
    }

    @Test
    void promptZeigtArtUndKorrigierteFelderGekuerzt() {
        Map<Feld, String> korrektur = new java.util.EnumMap<>(Feld.class);
        korrektur.put(Feld.TITEL, "Rechnung\n Büromaterial");
        korrektur.put(Feld.BELEGNUMMER, "x".repeat(250));
        korrektur.put(Feld.PARTNER, null);
        Beispiel b = new Beispiel(1, "LIEFERANTENBELEG", korrektur, Merkmale.KEINE, "NORMAL", null, Instant.EPOCH);

        List<Beispiel> liste = List.of(b, beispiel(2, 1, Set.of(), Set.of(), "NORMAL", null));
        String zeilen = "- art=LIEFERANTENBELEG; titel=Rechnung Büromaterial; partnerName=null; belegnummer="
                + "x".repeat(200) + "…\n- art=LIEFERANTENBELEG; titel=Titel 2";

        assertThat(ErfassungPrompt.beispiele(liste, true)).isEqualTo(ErfassungPrompt.EINLEITUNG_MERKMALE + "\n" + zeilen);
        assertThat(ErfassungPrompt.beispiele(liste, false)).isEqualTo(ErfassungPrompt.EINLEITUNG_NEUESTE + "\n" + zeilen);
        assertThat(ErfassungPrompt.beispiele(List.of(), true)).isEmpty();
    }

    @Test
    void tagsErscheinenAlsKommaliste() {
        Beispiel b = new Beispiel(1, "LIEFERANTENBELEG", Map.of(Feld.TAGS, "Büro, Steuern"), Merkmale.KEINE, "NORMAL",
                null, Instant.EPOCH);

        assertThat(ErfassungPrompt.beispiele(List.of(b), true)).endsWith("\n- art=LIEFERANTENBELEG; tags=Büro, Steuern");
    }
}
