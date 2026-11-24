package ch.urinet.archiv.ki.application.oeffentlich;

import ch.urinet.archiv.ki.application.chat.ChatAufraeumen;
import ch.urinet.archiv.ki.application.chat.ChatSpeicher;
import ch.urinet.archiv.ki.domain.chat.Aufgeraeumt;
import ch.urinet.archiv.mandant.application.MandantEinstellungen;
import ch.urinet.archiv.mandant.application.MandantKontext;
import ch.urinet.archiv.mandant.domain.MandantCode;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatAufbewahrungMandantTest {
    private static final MandantCode A = new MandantCode("urinet");
    private static final MandantCode B = new MandantCode("vhf-gsk");
    private static final Instant JETZT = Instant.parse("2026-11-23T12:00:00Z");
    private final Kontext kontext = new Kontext();
    private final List<String> aufrufe = new ArrayList<>();
    private final Map<MandantCode, MandantEinstellungen.Aufbewahrung> werte = Map.of(
            A, new MandantEinstellungen.Aufbewahrung(2, 10),
            B, new MandantEinstellungen.Aufbewahrung(null, null));
    private final MandantEinstellungen einstellungen = new MandantEinstellungen() {
        @Override
        public Werte lesen(MandantCode mandant) {
            throw new AssertionError("Unerwarteter Aufruf");
        }

        @Override
        public Aufbewahrung aufbewahrung() {
            assertThat(kontext.aktuell()).isPresent();
            return werte.get(kontext.aktuell().orElseThrow());
        }
    };

    @Test
    void internerChatVerwendetJeMandantDenWertOderDieBisherigePeriode() {
        ChatSpeicher speicher = (ChatSpeicher) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{ChatSpeicher.class}, (proxy, methode, args) -> {
                    assertThat(methode.getName()).isEqualTo("aufraeumen");
                    aufrufe.add(kontext.aktuell().orElseThrow().wert() + ":" + args[0]);
                    return new Aufgeraeumt(1, 0);
                });
        var aufraeumen = new ChatAufraeumen(kontext, speicher, einstellungen, "P1Y2D");

        aufraeumen.aufraeumen(A, JETZT);
        aufraeumen.aufraeumen(B, JETZT);

        assertThat(aufrufe).containsExactly("urinet:2026-09-23T12:00:00Z", "vhf-gsk:2025-11-21T12:00:00Z");
        assertThat(kontext.aktuell()).isEmpty();
    }

    @Test
    void oeffentlicherChatVerwendetJeMandantDenWertOderDieBisherigeEigenschaft() {
        OeffentlicheFragenSpeicher speicher = new OeffentlicheFragenSpeicher() {
            @Override
            public void speichern(String frage, List<ch.urinet.archiv.ki.domain.oeffentlich.OeffentlicheQuelle> quellen,
                                  int tokens, Instant zeit) {
                throw new AssertionError("Unerwarteter Aufruf");
            }

            @Override
            public int loeschenVor(Instant grenze) {
                aufrufe.add(kontext.aktuell().orElseThrow().wert() + ":" + grenze);
                return 1;
            }
        };
        var aufraeumen = new OeffentlicheFragenAufraeumen(kontext, speicher, einstellungen, "90",
                List.of("archiv.mandant.vhf-gsk.chat-aufbewahrung-tage"), name -> Optional.of("30"));

        aufraeumen.aufraeumen(A, JETZT);
        aufraeumen.aufraeumen(B, JETZT);

        assertThat(aufrufe).containsExactly("urinet:2026-11-13T12:00:00Z", "vhf-gsk:2026-10-24T12:00:00Z");
        assertThat(kontext.aktuell()).isEmpty();
    }

    @Test
    void ungueltigeAufbewahrungFuehrtNichtZurLoeschung() {
        MandantEinstellungen falsch = new MandantEinstellungen() {
            @Override
            public Werte lesen(MandantCode mandant) {
                throw new AssertionError("Unerwarteter Aufruf");
            }

            @Override
            public Aufbewahrung aufbewahrung() {
                return new Aufbewahrung(0, 366);
            }
        };
        var chat = new ChatAufraeumen(kontext, null, falsch, "P12M");
        var oeffentlich = new OeffentlicheFragenAufraeumen(kontext, null, falsch, "90", List.of(),
                name -> Optional.empty());

        assertThatThrownBy(() -> chat.aufraeumen(A, JETZT)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> oeffentlich.aufraeumen(B, JETZT)).isInstanceOf(IllegalStateException.class);
        assertThat(kontext.aktuell()).isEmpty();
    }

    private static final class Kontext extends MandantKontext {
        private MandantCode aktuell;

        private Kontext() {
            super(null, null, null);
        }

        @Override
        public Optional<MandantCode> aktuell() {
            return Optional.ofNullable(aktuell);
        }

        @Override
        public <T> T mit(MandantCode code, Supplier<T> arbeit) {
            assertThat(aktuell).isNull();
            aktuell = code;
            try {
                return arbeit.get();
            } finally {
                aktuell = null;
            }
        }
    }
}
