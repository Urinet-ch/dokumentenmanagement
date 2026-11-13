package ch.urinet.archiv.protokoll.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Wann das Benutzerverzeichnis eine neue Zeile braucht (Req 10.10). */
class BenutzerangabenTest {

    private static final Instant ZEIT = Instant.parse("2026-11-12T08:00:00Z");

    @Test
    void ersteAktionLegtZeileAn() {
        assertThat(angaben("Anna Probe", "MITARBEITER").neueZeile(Optional.empty()))
                .contains(new Benutzerangaben.NeueZeile(Optional.of("Anna Probe"), Optional.of("MITARBEITER")));
    }

    @Test
    void gleicheAngabenErgebenKeineZeile() {
        assertThat(angaben(" Anna Probe ", "MITARBEITER").neueZeile(letzter("Anna Probe", "MITARBEITER", false)))
                .isEmpty();
    }

    @Test
    void neuerNameOderNeueRolleErgebenNeueZeile() {
        assertThat(angaben("Anna Muster", "MITARBEITER").neueZeile(letzter("Anna Probe", "MITARBEITER", false)))
                .contains(new Benutzerangaben.NeueZeile(Optional.of("Anna Muster"), Optional.of("MITARBEITER")));
        assertThat(angaben("Anna Probe", "TREUHAENDER").neueZeile(letzter("Anna Probe", "MITARBEITER", false)))
                .contains(new Benutzerangaben.NeueZeile(Optional.of("Anna Probe"), Optional.of("TREUHAENDER")));
    }

    @Test
    void unbekannterNameUebernimmtDenBisherigen() {
        Benutzerangaben ohneName = new Benutzerangaben("sub-1", Optional.of("  "), Optional.of("MITARBEITER"), false);
        assertThat(ohneName.neueZeile(letzter("Anna Probe", "MITARBEITER", false))).isEmpty();
        assertThat(ohneName.neueZeile(letzter("Anna Probe", "INHABER", false)))
                .contains(new Benutzerangaben.NeueZeile(Optional.of("Anna Probe"), Optional.of("MITARBEITER")));
    }

    @Test
    void nachAustrittBeginntMitDerNaechstenAktionEineNeueZeile() {
        assertThat(angaben("Anna Probe", "MITARBEITER").neueZeile(letzter("Anna Probe", "MITARBEITER", true)))
                .contains(new Benutzerangaben.NeueZeile(Optional.of("Anna Probe"), Optional.of("MITARBEITER")));
    }

    @Test
    void einVerweigerterVersuchNachDemAustrittOeffnetKeineZeile() {
        Benutzerangaben verweigert = new Benutzerangaben("sub-1", Optional.of("Anna Muster"), Optional.of("INHABER"),
                true);
        assertThat(verweigert.neueZeile(letzter("Anna Probe", "MITARBEITER", true))).isEmpty();
        assertThat(verweigert.neueZeile(Optional.empty()))
                .contains(new Benutzerangaben.NeueZeile(Optional.of("Anna Muster"), Optional.of("INHABER")));
        assertThat(verweigert.neueZeile(letzter("Anna Probe", "MITARBEITER", false)))
                .contains(new Benutzerangaben.NeueZeile(Optional.of("Anna Muster"), Optional.of("INHABER")));
    }

    private static Benutzerangaben angaben(String name, String rolle) {
        return new Benutzerangaben("sub-1", Optional.of(name), Optional.of(rolle), false);
    }

    private static Optional<VerzeichnisEintrag> letzter(String name, String rolle, boolean beendet) {
        return Optional.of(new VerzeichnisEintrag(1, ZEIT, "sub-1", Optional.of(name), Optional.of(rolle), ZEIT,
                beendet ? Optional.of(ZEIT.plusSeconds(60)) : Optional.empty(),
                beendet ? VerzeichnisEintrag.Anlass.AUSTRITT : VerzeichnisEintrag.Anlass.AKTION, Optional.empty()));
    }
}
