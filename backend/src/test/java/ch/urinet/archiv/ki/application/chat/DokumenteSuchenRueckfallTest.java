package ch.urinet.archiv.ki.application.chat;

import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.sicherheit.domain.Benutzer;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import ch.urinet.archiv.sicherheit.domain.Sichtregel;
import ch.urinet.archiv.suche.application.HybridSuche;
import ch.urinet.archiv.suche.application.SuchePort;
import ch.urinet.archiv.suche.domain.Suchergebnis;
import ch.urinet.archiv.suche.domain.Suchtreffer;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Rückfall von {@code dokumente_suchen} auf die Volltextsuche, wenn die Hybridsuche eine Ausnahme wirft (Spec 02, Aufgabe
 * 7.1d, z. B. ein Datenbankfehler im Vektor-Teil). Ausfälle von Embedding-Modell und Reranker fängt die Hybridsuche selbst
 * ab und deckt {@code werkzeuge-hybrid.feature} ab; hier läuft der {@code catch}-Zweig des Werkzeugs. Ohne Mockito: Proxy.
 */
class DokumenteSuchenRueckfallTest {

    private static final MandantCode URINET = new MandantCode("urinet");

    /** Hybridsuche, die wie bei einem Datenbankfehler wirft; ihre Ports braucht sie dafür nicht. */
    private static final class WerfendeHybridsuche extends HybridSuche {
        int aufrufe;

        WerfendeHybridsuche() {
            super(null, null, null, null, null, null);
        }

        @Override
        public ch.urinet.archiv.suche.domain.Hybridergebnis suchen(ch.urinet.archiv.suche.domain.Suchanfrage anfrage,
                                                                   Sichtregel regel, boolean mitDateiname, int k) {
            aufrufe++;
            throw new IllegalStateException("Datenbank nicht erreichbar: Frage Unfallversicherung");
        }
    }

    @Test
    void fehlerImHybridPfadLiefertVolltexttrefferMitHinweisUndDegradiert() {
        UUID id = UUID.randomUUID();
        Suchtreffer treffer = new Suchtreffer(id, "VERSICHERUNG", "Police", null, null, "ARCHIVIERT", "NORMAL", List.of(),
                null, null, null, null, null, null, null, List.of(), null);
        SuchePort volltext = (anfrage, regel, mitDateiname) -> {
            assertThat(mitDateiname).isFalse();
            return new Suchergebnis(List.of(treffer), 1);
        };
        WerkzeugDaten daten = (WerkzeugDaten) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{WerkzeugDaten.class}, (proxy, methode, args) -> switch (methode.getName()) {
                    case "bestaetigte" -> Set.of();
                    case "nurLesend" -> null;
                    default -> throw new UnsupportedOperationException(methode.getName());
                });
        WerkzeugTransaktion direkt = new WerkzeugTransaktion() {
            @Override
            public <T> T neu(Supplier<T> arbeit) {
                return arbeit.get();
            }
        };
        WerfendeHybridsuche hybrid = new WerfendeHybridsuche();
        DokumenteSuchen suchen = new DokumenteSuchen(volltext, hybrid, null, null, daten, direkt, new BigDecimal("0.6"));
        Benutzer joel = new Benutzer("joel", Rolle.INHABER);
        WerkzeugKontext kontext = WerkzeugKontext.fuer(URINET, joel, null);

        DokumenteSuchen.Antwort antwort = suchen.suchen(kontext,
                new DokumenteSuchen.Anfrage("Unfallversicherung", null, null, null, null, null, 10));

        assertThat(hybrid.aufrufe).isEqualTo(1);
        assertThat(antwort.treffer()).extracting(DokumenteSuchen.Treffer::titel).containsExactly("Police");
        assertThat(antwort.hinweis()).isEqualTo("nur Volltext");
        assertThat(antwort.degradiert()).isTrue();
        assertThat(antwort.gesamt()).isEqualTo(1);
    }
}
