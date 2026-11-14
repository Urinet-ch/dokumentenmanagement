package ch.urinet.archiv.ki.domain.chat;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/** Quellenregel (Spec 02, Req 4.1; design.md «Vertrag Welle 7», Quellen). */
class QuellenTest {

    private static final ChatEreignis.Datenstand STAND = new ChatEreignis.Datenstand(Instant.parse("2026-11-14T08:00:00Z"), 3);

    private static ChatEreignis.QuellDokument dok(int nr) {
        return new ChatEreignis.QuellDokument(new UUID(0, nr), "Dokument " + nr);
    }

    private static ChatEreignis.WerkzeugErgebnis ergebnis(String name, List<ChatEreignis.QuellDokument> dokumente,
                                                          Optional<ChatEreignis.Datenstand> stand) {
        return new ChatEreignis.WerkzeugErgebnis(name, Map.of(), dokumente, stand);
    }

    private static Quelle.Dokument quelle(int nr) {
        return new Quelle.Dokument(new UUID(0, nr), "Dokument " + nr);
    }

    @Test
    void ohneErgebnisseKeineQuellen() {
        assertThat(Quellen.aus(List.of())).isEmpty();
    }

    @Test
    void dokumentZeigenImmerUndSuchtrefferDannNicht() {
        List<Quelle> quellen = Quellen.aus(List.of(
                ergebnis("dokumente_suchen", List.of(dok(1), dok(2)), Optional.empty()),
                ergebnis("dokument_zeigen", List.of(dok(3)), Optional.empty())));
        assertThat(quellen).containsExactly(quelle(3));
    }

    @Test
    void suchtrefferOhneDokumentZeigenHoechstensFuenfUeberAlleSuchen() {
        List<ChatEreignis.QuellDokument> sieben = IntStream.rangeClosed(1, 4).mapToObj(QuellenTest::dok).toList();
        List<Quelle> quellen = Quellen.aus(List.of(
                ergebnis("dokumente_suchen", sieben, Optional.empty()),
                ergebnis("dokumente_suchen", List.of(dok(4), dok(5), dok(6), dok(7)), Optional.empty())));
        assertThat(quellen).containsExactly(quelle(1), quelle(2), quelle(3), quelle(4), quelle(5));
    }

    @Test
    void datenstandDerZahlenwerkzeugeImmerAuchNebenDokumentZeigen() {
        List<Quelle> quellen = Quellen.aus(List.of(
                ergebnis("kennzahl", List.of(), Optional.of(STAND)),
                ergebnis("offene_posten", List.of(), Optional.of(STAND)),
                ergebnis("fristen", List.of(), Optional.of(STAND)),
                ergebnis("vollstaendigkeit", List.of(), Optional.of(STAND)),
                ergebnis("dokument_zeigen", List.of(dok(1)), Optional.empty()),
                ergebnis("dokumente_suchen", List.of(dok(2)), Optional.empty())));
        assertThat(quellen).containsExactly(new Quelle.Stand("kennzahl", STAND), new Quelle.Stand("offene_posten", STAND),
                new Quelle.Stand("fristen", STAND), new Quelle.Stand("vollstaendigkeit", STAND), quelle(1));
    }

    @Test
    void ohneDoppelte() {
        List<Quelle> quellen = Quellen.aus(List.of(
                ergebnis("dokument_zeigen", List.of(dok(1)), Optional.empty()),
                ergebnis("dokument_zeigen", List.of(dok(1), dok(2)), Optional.empty()),
                ergebnis("kennzahl", List.of(), Optional.of(STAND)),
                ergebnis("kennzahl", List.of(), Optional.of(STAND))));
        assertThat(quellen).containsExactly(quelle(1), quelle(2), new Quelle.Stand("kennzahl", STAND));
    }

    @Test
    void doppelteTrefferZaehlenNichtZurGrenze() {
        List<Quelle> quellen = Quellen.aus(List.of(
                ergebnis("dokumente_suchen", List.of(dok(1), dok(1), dok(2), dok(2), dok(3), dok(4), dok(5), dok(6)),
                        Optional.empty())));
        assertThat(quellen).containsExactly(quelle(1), quelle(2), quelle(3), quelle(4), quelle(5));
    }

    @Test
    void zahlenwerkzeugOhneDatenstandUndFremdeWerkzeugeOhneQuelle() {
        List<Quelle> quellen = Quellen.aus(List.of(
                ergebnis("kennzahl", List.of(dok(1)), Optional.empty()),
                ergebnis("wissen_index", List.of(dok(2)), Optional.of(STAND)),
                ergebnis("dokumente_suchen", List.of(), Optional.of(STAND))));
        assertThat(quellen).isEmpty();
    }

    @Test
    void linkIstDerRelativePwaPfad() {
        UUID id = UUID.randomUUID();
        assertThat(new Quelle.Dokument(id, "x").link()).isEqualTo("/dokument/" + id);
    }
}
