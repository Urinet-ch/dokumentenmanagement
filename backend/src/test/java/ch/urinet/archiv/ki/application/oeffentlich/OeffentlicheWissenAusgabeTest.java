package ch.urinet.archiv.ki.application.oeffentlich;

import ch.urinet.archiv.ki.application.chat.WerkzeugTransaktion;
import ch.urinet.archiv.ki.domain.chat.ChatAntwort;
import ch.urinet.archiv.ki.domain.chat.ChatStatus;
import ch.urinet.archiv.ki.domain.oeffentlich.OeffentlicheAusgabe;
import ch.urinet.archiv.wissen.application.OeffentlichesWissenPort;
import ch.urinet.archiv.wissen.application.WissenHuelleDienst;
import io.smallrye.config.SmallRyeConfigBuilder;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

class OeffentlicheWissenAusgabeTest {
    private static final UUID ID = UUID.randomUUID();
    private static final OeffentlichesWissenPort.Stand STAND = new OeffentlichesWissenPort.Stand(ID, 1, 1);
    private static final ChatAntwort ANTWORT = new ChatAntwort("Belegte Antwort", List.of(), List.of(),
            Optional.empty(), ChatStatus.OK);

    @Test
    void nurGezeigteAktuelleSeitenErlaubenAntwortMitQuellen() {
        var lauf = new OeffentlichesWissenDurchgang();
        lauf.zeigen(seite());
        var ausgaben = ausgeben(lauf, true);
        assertThat(ausgaben).hasSize(3);
        assertThat(ausgaben.getFirst()).isEqualTo(new OeffentlicheAusgabe.Text("Belegte Antwort"));
        assertThat(((OeffentlicheAusgabe.WissenQuellen) ausgaben.get(1)).quellen().getFirst().belege().getFirst().titel())
                .isEqualTo("Beleg");

        assertThat(ausgeben(new OeffentlichesWissenDurchgang(), true).getFirst())
                .isEqualTo(new OeffentlicheAusgabe.Text("Dazu steht nichts in der Wissensbasis."));
    }

    @Test
    void auchNurImIndexGesehenerEntzugOderRevisionswechselVerhindertJedenModelltext() {
        var lauf = new OeffentlichesWissenDurchgang();
        lauf.zeigen(seite());
        lauf.merken(new OeffentlichesWissenPort.Stand(UUID.randomUUID(), 1, 1));
        assertThat(ausgeben(lauf, false)).singleElement().isInstanceOf(OeffentlicheAusgabe.Fehler.class);
        lauf.merken(new OeffentlichesWissenPort.Stand(ID, 1, 2));
        assertThat(ausgeben(lauf, true)).singleElement().isInstanceOf(OeffentlicheAusgabe.Fehler.class);
    }

    private static OeffentlichesWissenPort.Seite seite() {
        return new OeffentlichesWissenPort.Seite(STAND, "probe/seite", "Titel", "Konzept", "Inhalt", Instant.EPOCH,
                List.of(new OeffentlichesWissenPort.Dokument(UUID.randomUUID(), "Beleg", "R", "Reihe", "1")));
    }

    private static List<OeffentlicheAusgabe> ausgeben(OeffentlichesWissenDurchgang lauf, boolean gueltig) {
        OeffentlichesWissenPort port = new OeffentlichesWissenPort() {
            public List<Indexzeile> suchen(String begriff, int hoechstens) { return List.of(); }
            public Optional<Seite> seite(String pfad) { return Optional.empty(); }
            public boolean aktuell(Stand stand) { return gueltig; }
        };
        WissenHuelleDienst huelle = new WissenHuelleDienst(null) {
            public void sperren() { }
        };
        WerkzeugTransaktion transaktion = new WerkzeugTransaktion() {
            public <T> T neu(Supplier<T> arbeit) { return arbeit.get(); }
        };
        var dienst = new OeffentlicheFragen(null, null, null, null, null, null, port, huelle, null, transaktion,
                null, null, null, new SmallRyeConfigBuilder().build(), 500);
        List<OeffentlicheAusgabe> ausgaben = new ArrayList<>();
        try {
            dienst.wissenAusgeben(ID, lauf, ANTWORT, ausgaben::add);
        } finally {
            dienst.beenden();
        }
        return ausgaben;
    }
}
