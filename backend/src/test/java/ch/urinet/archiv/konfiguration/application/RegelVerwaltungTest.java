package ch.urinet.archiv.konfiguration.application;

import ch.urinet.archiv.konfiguration.domain.Konfiguration;
import ch.urinet.archiv.konfiguration.domain.Regel;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RegelVerwaltungTest {

    @Test
    void neueRegelMeldetBetroffeneDokumenteAuchWennSieInaktivAngelegtWird() {
        Speicher speicher = new Speicher(List.of());
        KonfigurationStand stand = new KonfigurationStand(speicher);
        RegelVerwaltung verwaltung = new RegelVerwaltung(speicher, regel -> 2, stand);
        Regel regel = eindeutig(false);

        RegelVerwaltung.Ergebnis ergebnis = verwaltung.anlegen(regel, 1, "inhaber", "Neue Regel");

        assertThat(ergebnis.betroffeneDokumente()).isEqualTo(2);
        assertThat(ergebnis.version()).isEqualTo(2);
        assertThat(ergebnis.regel()).isEqualTo(regel);
        assertThat(speicher.alle()).containsExactly(regel);
    }

    @Test
    void aktivierenMeldetBetroffeneDokumenteUndErzeugtEineNeueVersion() {
        Regel inaktiv = eindeutig(false);
        Speicher speicher = new Speicher(List.of(inaktiv));
        KonfigurationStand stand = new KonfigurationStand(speicher);
        RegelVerwaltung verwaltung = new RegelVerwaltung(speicher, regel -> 3, stand);
        RegelAenderung aktivieren = new RegelAenderung(Set.of("aktiv"), null, null, null, null, null, true);

        RegelVerwaltung.Ergebnis ergebnis = verwaltung.aendern(inaktiv.code(), aktivieren, 1, "inhaber", null);

        assertThat(ergebnis.betroffeneDokumente()).isEqualTo(3);
        assertThat(ergebnis.version()).isEqualTo(2);
        assertThat(ergebnis.regel().aktiv()).isTrue();
    }

    private static Regel eindeutig(boolean aktiv) {
        return new Regel("eindeutig", "RECHNUNG", "EINDEUTIG", Map.of("felder", List.of("titel")),
                "FEHLER", "Titel ist bereits verwendet.", aktiv);
    }

    private static class Speicher implements RegelRepository {
        private final List<Regel> regeln = new ArrayList<>();

        Speicher(List<Regel> regeln) {
            this.regeln.addAll(regeln);
        }

        @Override
        public List<Regel> alle() {
            return List.copyOf(regeln);
        }

        @Override
        public Optional<Regel> finden(String code) {
            return regeln.stream().filter(regel -> regel.code().equals(code)).findFirst();
        }

        @Override
        public void anlegen(Regel regel) {
            regeln.add(regel);
        }

        @Override
        public void speichern(Regel regel) {
            regeln.replaceAll(alt -> alt.code().equals(regel.code()) ? regel : alt);
        }

        @Override
        public void ersetzen(List<Regel> neu) {
            regeln.clear();
            regeln.addAll(neu);
        }
    }

    private static class KonfigurationStand extends KonfigurationVerwaltung {
        private final Speicher speicher;
        private int version = 1;

        KonfigurationStand(Speicher speicher) {
            super(null, null, null, null, null, null, null, null, null, null, null);
            this.speicher = speicher;
        }

        @Override
        public int aktuelleVersion() {
            return version;
        }

        @Override
        public Konfiguration aktuell() {
            Konfiguration.Art art = new Konfiguration.Art("RECHNUNG", "Rechnung", null, List.of(), List.of(),
                    Konfiguration.OHNE_ZUSATZ, "NORMAL", new Konfiguration.Aufbewahrung(null, "BELEGDATUM"),
                    false, true);
            return new Konfiguration(version, new Konfiguration.Einstellungen("12-31", null), List.of(art),
                    List.of(), speicher.alle());
        }

        @Override
        public void aenderungStarten(int erwarteteVersion) {
            if (version != erwarteteVersion) {
                throw new AssertionError("Unerwartete Konfigurationsversion");
            }
        }

        @Override
        public int aenderungSpeichern(String wer, String grund) {
            return ++version;
        }
    }
}
