package ch.urinet.archiv.ki.domain.chat;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Sammeln der Ereignisse und Status der Antwort. */
class ChatDurchgangTest {

    private static final UUID DOK = new UUID(0, 7);

    @Test
    void okMitTextWerkzeugenQuellenUndTokens() {
        ChatDurchgang d = new ChatDurchgang();
        List<ChatEreignis> weiter = new ArrayList<>();
        ChatEreignis.Datenstand stand = new ChatEreignis.Datenstand(Instant.EPOCH, 2);
        for (ChatEreignis e : List.of(new ChatEreignis.WerkzeugStart("dokument_zeigen"),
                new ChatEreignis.WerkzeugErgebnis("dokument_zeigen", Map.of("id", DOK.toString()),
                        List.of(new ChatEreignis.QuellDokument(DOK, "Rechnung")), Optional.of(stand)),
                new ChatEreignis.Text("Hallo "), new ChatEreignis.Text("Welt"), new ChatEreignis.Ende(10, 2),
                new ChatEreignis.Text("zu spät"))) {
            d.aufnehmen(e, weiter::add);
        }
        ChatAntwort a = d.antwort(false);
        assertThat(weiter).containsExactly(new ChatEreignis.WerkzeugStart("dokument_zeigen"),
                new ChatEreignis.Text("Hallo "), new ChatEreignis.Text("Welt"));
        assertThat(a.status()).isEqualTo(ChatStatus.OK);
        assertThat(a.inhalt()).isEqualTo("Hallo Welt");
        assertThat(a.tokens()).contains(new ChatEreignis.Ende(10, 2));
        assertThat(a.werkzeuge()).singleElement().satisfies(w -> {
            assertThat(w.name()).isEqualTo("dokument_zeigen");
            assertThat(w.dokumentIds()).containsExactly(DOK);
            assertThat(w.datenstand()).contains(stand);
        });
        assertThat(a.quellen()).containsExactly(new Quelle.Dokument(DOK, "Rechnung"));
        assertThat(d.ausfallgrund()).isEmpty();
    }

    @Test
    void ausfallNachTeilantwortIstFehlerMitTeiltext() {
        ChatDurchgang d = new ChatDurchgang();
        d.aufnehmen(new ChatEreignis.Text("Teil"), e -> { });
        d.aufnehmen(new ChatEreignis.Ausfall("ModellWeg"), e -> { });
        d.aufnehmen(new ChatEreignis.Ende(1, 1), e -> { });
        ChatAntwort a = d.antwort(false);
        assertThat(a.status()).isEqualTo(ChatStatus.FEHLER);
        assertThat(a.inhalt()).isEqualTo("Teil");
        assertThat(a.tokens()).isEmpty();
        assertThat(d.ausfallgrund()).contains("ModellWeg");
    }

    @Test
    void ohneEndeIstFehlerUndMitAbbruchAbgebrochen() {
        ChatDurchgang d = new ChatDurchgang();
        d.aufnehmen(new ChatEreignis.Text("Teil"), e -> { });
        assertThat(d.antwort(false).status()).isEqualTo(ChatStatus.FEHLER);
        assertThat(d.ausfallgrund()).contains("KeinEnde");
        assertThat(d.antwort(true).status()).isEqualTo(ChatStatus.ABGEBROCHEN);
    }

    @Test
    void ausnahmeNachEndeAendertNichts() {
        ChatDurchgang d = new ChatDurchgang();
        d.aufnehmen(new ChatEreignis.Ende(0, 0), e -> { });
        d.ausfallen("IllegalStateException");
        assertThat(d.antwort(true).status()).isEqualTo(ChatStatus.OK);
    }
}
