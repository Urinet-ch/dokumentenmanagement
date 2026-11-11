package ch.urinet.archiv.importe.domain;

import ch.urinet.archiv.gemeinsam.domain.Normalisierer;
import ch.urinet.archiv.importe.domain.KontaktAbgleich.Bestaende;
import ch.urinet.archiv.importe.domain.KontaktAbgleich.Bestand;
import ch.urinet.archiv.importe.domain.KontaktAbgleich.Befund;
import ch.urinet.archiv.importe.domain.ZeilenPruefung.Ergebnis;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/** Abgleich crm_id → E-Mail → Name + PLZ mit synthetischen Kontakten (Req 13.4, 13.5). */
class KontaktAbgleichTest {

    private static final UUID A = UUID.fromString("00000000-0000-4000-8000-00000000000a");
    private static final UUID B = UUID.fromString("00000000-0000-4000-8000-00000000000b");

    private static Bestand org(UUID id, String crmId, String name, String plz, String email) {
        Map<String, String> felder = new HashMap<>();
        felder.put("typ", "ORGANISATION");
        felder.put("crm_id", crmId);
        felder.put("name", name);
        felder.put("plz", plz);
        felder.put("email", email);
        felder.values().removeIf(v -> v == null);
        return new Bestand(id, Normalisierer.name(name), felder);
    }

    private static Map<String, String> zeile(String... paare) {
        Map<String, String> werte = new HashMap<>();
        for (int i = 0; i < paare.length; i += 2) {
            werte.put(paare[i], paare[i + 1]);
        }
        return KontaktAbgleich.vergleichswerte(werte);
    }

    private static Befund pruefen(Map<String, String> zeile, Bestand... bestand) {
        return KontaktAbgleich.pruefen(zeile, new Bestaende(List.of(bestand)), List.of());
    }

    @Test
    void ohneTrefferUndOhneAehnlicheIstNeu() {
        Befund befund = pruefen(zeile("typ", "ORGANISATION", "name", "Beispiel AG", "plz", "6000"));

        assertThat(befund).isEqualTo(Befund.NEU);
    }

    @Test
    void trefferUeberCrmIdOhneAbweichungIstUnveraendert() {
        Befund befund = pruefen(zeile("crm_id", "K-1", "typ", "ORGANISATION", "name", "Beispiel AG"),
                org(A, "K-1", "Beispiel AG", "6000", "info@beispiel.test"));

        assertThat(befund.ergebnis()).isEqualTo(Ergebnis.UNVERAENDERT);
        assertThat(befund.kontaktId()).isEqualTo(A);
    }

    @Test
    void emailOhneGrossKleinschreibungUndAbweichendesFeldIstGeaendert() {
        Befund befund = pruefen(zeile("email", " INFO@Beispiel.test ", "name", "Beispiel AG", "plz", "6001"),
                org(A, null, "Beispiel AG", "6000", "info@beispiel.test"));

        assertThat(befund.ergebnis()).isEqualTo(Ergebnis.GEAENDERT);
        assertThat(befund.kontaktId()).isEqualTo(A);
    }

    @Test
    void normalisierterNameUndPlzTreffen() {
        // trifft über den normalisierten Namen; die andere Schreibweise ist eine Änderung des Felds name
        Befund befund = pruefen(zeile("typ", "ORGANISATION", "name", "  beispiel   ag ", "plz", "CH-6000"),
                org(A, null, "Beispiel AG", "6000", null));

        assertThat(befund.ergebnis()).isEqualTo(Ergebnis.GEAENDERT);
        assertThat(befund.kontaktId()).isEqualTo(A);
    }

    @Test
    void nameOhnePlzTrifftNicht() {
        Befund befund = pruefen(zeile("typ", "ORGANISATION", "name", "Beispiel AG"),
                org(A, null, "Beispiel AG", "6000", null));

        assertThat(befund).isEqualTo(Befund.NEU);
    }

    @Test
    void mehrereKontakteAufEinerStufeSindDublette() {
        Befund befund = pruefen(zeile("email", "info@beispiel.test"),
                org(A, null, "Beispiel AG", "6000", "info@beispiel.test"),
                org(B, null, "Muster GmbH", "6460", "info@beispiel.test"));

        assertThat(befund.ergebnis()).isEqualTo(Ergebnis.DUBLETTE);
        assertThat(befund.meldung()).isEqualTo("mehrere Kontakte mit gleicher E-Mail");
        assertThat(befund.kandidaten()).containsExactly(A, B);
        assertThat(befund.kontaktId()).isNull();
    }

    @Test
    void stufenAufVerschiedeneKontakteSindDublette() {
        Befund befund = pruefen(zeile("crm_id", "K-1", "email", "info@muster.test"),
                org(A, "K-1", "Beispiel AG", "6000", null),
                org(B, null, "Muster GmbH", "6460", "info@muster.test"));

        assertThat(befund.ergebnis()).isEqualTo(Ergebnis.DUBLETTE);
        assertThat(befund.meldung()).isEqualTo(KontaktAbgleich.WIDERSPRUCH);
        assertThat(befund.kandidaten()).containsExactly(A, B);
    }

    @Test
    void trefferMitAndererCrmIdIstDublette() {
        Befund befund = pruefen(zeile("crm_id", "K-2", "email", "info@beispiel.test"),
                org(A, "K-1", "Beispiel AG", "6000", "info@beispiel.test"));

        assertThat(befund.ergebnis()).isEqualTo(Ergebnis.DUBLETTE);
        assertThat(befund.meldung()).isEqualTo("Treffer über E-Mail mit anderer Kontakt-ID aus 21.CRM");
        assertThat(befund.kandidaten()).containsExactly(A);
    }

    @Test
    void aehnlicherNameOhneExaktenTrefferIstDubletteMitHoechstensFuenfKandidaten() {
        List<UUID> aehnliche = IntStream.range(0, 7).mapToObj(i -> UUID.randomUUID()).toList();

        Befund befund = KontaktAbgleich.pruefen(zeile("name", "Beispiel AG"), new Bestaende(List.of()), aehnliche);

        assertThat(befund.ergebnis()).isEqualTo(Ergebnis.DUBLETTE);
        assertThat(befund.meldung()).isEqualTo(KontaktAbgleich.AEHNLICH);
        assertThat(befund.kandidaten()).containsExactlyElementsOf(aehnliche.subList(0, KontaktAbgleich.KANDIDATEN));
    }

    @Test
    void personennameAusVornameUndName() {
        assertThat(KontaktAbgleich.schluessel(zeile("vorname", "Erika", "name", "Muster")).name())
                .isEqualTo(Normalisierer.personName("Erika", "Muster"));
        assertThat(KontaktAbgleich.schluessel(zeile("typ", "ORGANISATION", "vorname", "x", "name", "Muster")).name())
                .isEqualTo(Normalisierer.name("Muster"));
    }

    @Test
    void fingerabdrueckeJeGefuellterStufeUndKontakt() {
        var voll = KontaktAbgleich.schluessel(zeile("crm_id", "K-1", "email", "a@b.test", "name", "X", "plz", "6000"));
        var nurName = KontaktAbgleich.schluessel(zeile("name", "X"));

        assertThat(KontaktAbgleich.fingerabdruecke(voll, A)).hasSize(4).doesNotHaveDuplicates();
        assertThat(KontaktAbgleich.fingerabdruecke(nurName, null)).isEmpty();
        assertThat(KontaktAbgleich.fingerabdruecke(KontaktAbgleich.schluessel(zeile("email", "A@B.test")), null))
                .containsExactly(KontaktAbgleich.fingerabdruecke(
                        KontaktAbgleich.schluessel(zeile("email", "a@b.test")), null));
    }
}
