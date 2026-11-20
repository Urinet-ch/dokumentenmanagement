package ch.urinet.archiv.wissen.application;

import ch.urinet.archiv.ki.application.ChunkPort;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.verarbeitung.application.Auftrag;
import ch.urinet.archiv.verarbeitung.application.AuftragService;
import ch.urinet.archiv.verarbeitung.domain.AuftragTyp;
import ch.urinet.archiv.verarbeitung.domain.Nutzlast;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class WissensEmbeddingSpeichernTest {
    private final Port port = new Port();
    private final Auftraege auftraege = new Auftraege();
    private final WissensEmbeddingSpeichern speichern = new WissensEmbeddingSpeichern(port, auftraege);

    @Test
    void startenVerwendetBereitsLaufendeNachberechnung() {
        auftraege.laufend = OptionalLong.of(42);
        assertThat(speichern.starten()).isEqualTo(42);
        assertThat(auftraege.neu).isEmpty();
    }

    @Test
    void vollerTeilHatEinzelauftraegeUndFortsetzungUndWirdNichtDoppeltEingereiht() {
        port.seiten = IntStream.rangeClosed(1, 500)
                .mapToObj(i -> new WissensEmbeddingPort.SeitenVersion(new UUID(0, i), 3)).toList();
        var auftrag = new Auftrag(42, new MandantCode("urinet"), WissensEmbeddingSpeichern.ALLE, Nutzlast.leer(), 0);
        speichern.teilEinreihen(auftrag);
        assertThat(auftraege.neu).hasSize(501);
        assertThat(auftraege.neu.getFirst().typ()).isEqualTo(WissensEmbeddingSpeichern.EINZELN);
        assertThat(auftraege.neu.getFirst().nutzlast().uuid("seiteId")).contains(port.seiten.getFirst().id());
        assertThat(auftraege.neu.getFirst().nutzlast().zahl("version").orElseThrow()).isEqualTo(3);
        var fortsetzung = auftraege.neu.getLast();
        assertThat(fortsetzung.typ()).isEqualTo(WissensEmbeddingSpeichern.ALLE);
        assertThat(fortsetzung.nutzlast().uuid("nach")).contains(port.seiten.getLast().id());
        assertThat(fortsetzung.nutzlast().zahl("vorgaenger").orElseThrow()).isEqualTo(42);
        auftraege.nachfolger = true;
        speichern.teilEinreihen(auftrag);
        assertThat(auftraege.neu).hasSize(501);
        assertThat(port.leseaufrufe).isEqualTo(1);
    }

    @Test
    void letzterTeilVerwendetCursorUndHatKeineWeitereFortsetzung() {
        UUID nach = UUID.randomUUID();
        port.seiten = List.of(new WissensEmbeddingPort.SeitenVersion(UUID.randomUUID(), 1));
        speichern.teilEinreihen(new Auftrag(42, new MandantCode("urinet"), WissensEmbeddingSpeichern.ALLE,
                new Nutzlast(java.util.Map.of("nach", nach)), 0));
        assertThat(port.nach).contains(nach);
        assertThat(auftraege.neu).hasSize(1);
        assertThat(auftraege.neu.getFirst().typ()).isEqualTo(WissensEmbeddingSpeichern.EINZELN);
    }

    private static class Port implements WissensEmbeddingPort {
        List<SeitenVersion> seiten = List.of();
        Optional<UUID> nach = Optional.empty();
        int leseaufrufe;
        @Override public Optional<Stand> laden(UUID id) { throw new AssertionError("kein Inhalt laden"); }
        @Override public boolean ersetzen(UUID id, int version, List<ChunkPort.Eingebettet> chunks) {
            throw new AssertionError("keine Chunks schreiben");
        }
        @Override public List<SeitenVersion> nachberechnen(Optional<UUID> cursor, int groesse) {
            assertThat(groesse).isEqualTo(500);
            nach = cursor;
            leseaufrufe++;
            return seiten;
        }
    }

    private static class Auftraege extends AuftragService {
        record NeuerAuftrag(AuftragTyp typ, Nutzlast nutzlast) { }
        List<NeuerAuftrag> neu = new ArrayList<>();
        OptionalLong laufend = OptionalLong.empty();
        boolean nachfolger;
        Auftraege() { super(null, null, null, null); }
        @Override public OptionalLong offen(AuftragTyp typ, Optional<String> schluessel, boolean eigenschaft) {
            return laufend;
        }
        @Override public long einreihen(AuftragTyp typ, Nutzlast nutzlast) {
            neu.add(new NeuerAuftrag(typ, nutzlast));
            return neu.size();
        }
        @Override public void anspruchSichern(Auftrag auftrag) { }
        @Override public boolean nachfolgerVorhanden(Auftrag auftrag, String schluessel) { return nachfolger; }
    }
}
