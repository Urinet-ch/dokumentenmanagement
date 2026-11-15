package ch.urinet.archiv.verarbeitung.application;

import ch.urinet.archiv.verarbeitung.application.SpeicherUeberwachung.Stufe;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Schwellenlogik der Speicher-Frühwarnung (Req 16.5): Stufen, einmalige Meldung, Zurückfallen, kein Maximum. */
class SpeicherUeberwachungTest {

    private static final long MIB = 1024 * 1024;

    private final List<String> mails = new ArrayList<>();
    private final Map<String, Double> kennzahlen = new ConcurrentHashMap<>();
    private boolean zugestellt = true;
    private final SpeicherUeberwachung ueberwachung = new SpeicherUeberwachung(List::of, (stufe, messung) -> {
        mails.add(messung.pool() + ":" + stufe);
        return zugestellt;
    }, kennzahlen::put, 85, 95);

    private static SpeicherMessung metaspace(int benutztMiB) {
        return new SpeicherMessung("Metaspace", benutztMiB * MIB, 100 * MIB);
    }

    private void lauf(SpeicherMessung... messungen) {
        ueberwachung.pruefen(List.of(messungen));
    }

    @Test
    @Tag("req-16.5")
    void unterDerSchwelleKeineMeldungAberKennzahl() {
        lauf(metaspace(84));

        assertThat(mails).isEmpty();
        assertThat(kennzahlen).containsEntry("Metaspace", 84.0);
    }

    @Test
    @Tag("req-16.5")
    void ueberschreitenMeldetJeStufeEinmal() {
        lauf(metaspace(85));
        lauf(metaspace(90));
        lauf(metaspace(94));
        assertThat(mails).containsExactly("Metaspace:WARNUNG");

        lauf(metaspace(95));
        lauf(metaspace(99));
        assertThat(mails).containsExactly("Metaspace:WARNUNG", "Metaspace:KRITISCH");
    }

    @Test
    @Tag("req-16.5")
    void direktUeberKritischMeldetNurKritisch() {
        lauf(metaspace(97));
        lauf(metaspace(90));

        assertThat(mails).containsExactly("Metaspace:KRITISCH");
    }

    @Test
    @Tag("req-16.5")
    void zurueckfallenSchaltetDieStufeFreiAberNichtDieNiedrigere() {
        lauf(metaspace(96));
        lauf(metaspace(88)); // unter kritisch, noch über warn: keine neue Warnung, Kritisch frei
        lauf(metaspace(96));
        assertThat(mails).containsExactly("Metaspace:KRITISCH", "Metaspace:KRITISCH");

        lauf(metaspace(50));
        lauf(metaspace(86));
        assertThat(mails).containsExactly("Metaspace:KRITISCH", "Metaspace:KRITISCH", "Metaspace:WARNUNG");
    }

    @Test
    @Tag("req-16.5")
    void jederBereichHatSeinenEigenenZustand() {
        lauf(metaspace(90), new SpeicherMessung("Heap", 90 * MIB, 100 * MIB));
        lauf(metaspace(90), new SpeicherMessung("Heap", 90 * MIB, 100 * MIB));

        assertThat(mails).containsExactly("Metaspace:WARNUNG", "Heap:WARNUNG");
    }

    @Test
    @Tag("req-16.5")
    void ohneMaximumKeineSchwelleUndKeineKennzahl() {
        lauf(new SpeicherMessung("Metaspace", 900 * MIB, -1));

        assertThat(mails).isEmpty();
        assertThat(kennzahlen).isEmpty();
    }

    @Test
    @Tag("req-16.5")
    void gescheiterterVersandWirdWiederholtBisErZugestelltIst() {
        zugestellt = false;
        lauf(metaspace(90));
        lauf(metaspace(90));
        zugestellt = true;
        lauf(metaspace(90));
        lauf(metaspace(90));

        assertThat(mails).hasSize(3);
    }

    @Test
    @Tag("req-16.5")
    void einFehlerBeimEinenBereichHaeltDenAnderenNichtAuf() {
        SpeicherUeberwachung kaputt = new SpeicherUeberwachung(List::of, (stufe, messung) -> {
            if (messung.pool().equals("A")) {
                throw new IllegalStateException();
            }
            mails.add(messung.pool());
            return true;
        }, (pool, prozent) -> { }, 85, 95);

        kaputt.pruefen(List.of(new SpeicherMessung("A", 90, 100), new SpeicherMessung("B", 90, 100)));

        assertThat(mails).containsExactly("B");
    }

    @Test
    @Tag("req-16.5")
    void schwellenMussGeordnetSein() {
        assertThatThrownBy(() -> SpeicherUeberwachung.pruefeEinstellungen(95, 95))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("archiv.ueberwachung.speicher");
        assertThatThrownBy(() -> SpeicherUeberwachung.pruefeEinstellungen(0, 95))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SpeicherUeberwachung.pruefeEinstellungen(85, 101))
                .isInstanceOf(IllegalArgumentException.class);
        SpeicherUeberwachung.pruefeEinstellungen(85, 100);
        assertThat(Stufe.values()).hasSize(2);
    }
}
