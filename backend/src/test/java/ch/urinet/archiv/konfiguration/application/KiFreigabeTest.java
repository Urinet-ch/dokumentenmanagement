package ch.urinet.archiv.konfiguration.application;

import ch.urinet.archiv.gemeinsam.domain.KonfigurationUngueltig;
import ch.urinet.archiv.konfiguration.domain.Konfiguration.KiTexte;
import ch.urinet.archiv.konfiguration.domain.KonfigurationQuelle;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KiFreigabeTest {

    @Test
    void freigabeErzeugtVersionMitBetreiberUndAbnahmeverweisUndIstIdempotent() {
        TestKiTexteRepository texte = new TestKiTexteRepository();
        TestKonfigurationVerwaltung konfiguration = new TestKonfigurationVerwaltung();
        KonfigurationsdateiVerwaltung verwaltung = new KonfigurationsdateiVerwaltung(null, konfiguration,
                null, null, null, null, null, texte, null, null);

        assertThat(verwaltung.kiFreigeben("betreiber:Fabian", "auswertung-2026-11")).isEqualTo(8);
        assertThat(texte.texte.kiFreigegeben()).isTrue();
        assertThat(texte.texte.rolle()).isEqualTo("Rolle");
        assertThat(texte.texte.feldregeln()).isEqualTo("Feldregeln");
        assertThat(konfiguration.wer).isEqualTo("betreiber:Fabian");
        assertThat(konfiguration.grund).isEqualTo("auswertung-2026-11");
        assertThat(konfiguration.quelle).isEqualTo(KonfigurationQuelle.APP);

        assertThat(verwaltung.kiFreigeben("betreiber:Fabian", "auswertung-2026-11")).isEqualTo(8);
        assertThat(konfiguration.speicherungen).isEqualTo(1);
    }

    @Test
    void freigabeOhneAbnahmeverweisAendertNichts() {
        TestKiTexteRepository texte = new TestKiTexteRepository();
        TestKonfigurationVerwaltung konfiguration = new TestKonfigurationVerwaltung();
        KonfigurationsdateiVerwaltung verwaltung = new KonfigurationsdateiVerwaltung(null, konfiguration,
                null, null, null, null, null, texte, null, null);

        assertThatThrownBy(() -> verwaltung.kiFreigeben("betreiber:Fabian", " "))
                .isInstanceOf(KonfigurationUngueltig.class);

        assertThat(texte.texte.kiFreigegeben()).isFalse();
        assertThat(konfiguration.speicherungen).isZero();
    }

    private static final class TestKiTexteRepository implements KiTexteRepository {
        private KiTexte texte = new KiTexte("Rolle", "Feldregeln", false);

        @Override
        public KiTexte lesen() {
            return texte;
        }

        @Override
        public void speichern(KiTexte kiTexte) {
            texte = kiTexte;
        }
    }

    private static final class TestKonfigurationVerwaltung extends KonfigurationVerwaltung {
        private int version = 7;
        private int speicherungen;
        private String wer;
        private String grund;
        private KonfigurationQuelle quelle;

        private TestKonfigurationVerwaltung() {
            super(null, null, null, null, null, null, null, null, null, null, null);
        }

        @Override
        public int aktuelleVersion() {
            return version;
        }

        @Override
        public void aenderungStarten(int erwarteteVersion) {
            assertThat(erwarteteVersion).isEqualTo(version);
        }

        @Override
        public int aenderungSpeichern(String wer, String grund, KonfigurationQuelle quelle) {
            this.wer = wer;
            this.grund = grund;
            this.quelle = quelle;
            speicherungen++;
            return ++version;
        }
    }
}
