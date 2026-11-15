package ch.urinet.archiv.ki.application.chat;

import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.sicherheit.domain.Benutzer;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import ch.urinet.archiv.sicherheit.domain.Sichtregel;
import ch.urinet.archiv.ki.application.EmbeddingPort;
import ch.urinet.archiv.ki.application.KiVerbrauchPort;
import ch.urinet.archiv.ki.application.RerankerPort;
import ch.urinet.archiv.suche.application.HybridSuche;
import ch.urinet.archiv.suche.application.SuchePort;
import ch.urinet.archiv.suche.application.SucheTransaktion;
import ch.urinet.archiv.suche.application.VektorSuchePort;
import ch.urinet.archiv.suche.domain.Suchergebnis;
import ch.urinet.archiv.suche.domain.Suchtreffer;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Rückfall von {@code dokumente_suchen} auf die Volltextsuche, wenn die Hybridsuche eine Ausnahme wirft (Spec 02, Aufgabe
 * 7.1d, z. B. ein Datenbankfehler im Vektor-Teil). Ausfälle von Embedding-Modell und Reranker fängt die Hybridsuche selbst
 * ab und deckt {@code werkzeuge-hybrid.feature} ab; hier läuft der {@code catch}-Zweig des Werkzeugs. Ohne Mockito: Proxy.
 */
class DokumenteSuchenRueckfallTest {

    private static final MandantCode URINET = new MandantCode("urinet");

    /** Testfixture: die Ports sind strikt unbenutzbar, der überschreibende Suchpfad wirft wie bei einem DB-Fehler. */
    private static final class WerfendeHybridsuche extends HybridSuche {
        int aufrufe;

        WerfendeHybridsuche() {
            super(nichtVerwendet(SuchePort.class), nichtVerwendet(VektorSuchePort.class),
                    nichtVerwendet(EmbeddingPort.class), nichtVerwendet(RerankerPort.class),
                    nichtVerwendet(KiVerbrauchPort.class), new SucheTransaktion());
        }

        @Override
        public ch.urinet.archiv.suche.domain.Hybridergebnis suchen(ch.urinet.archiv.suche.domain.Suchanfrage anfrage,
                                                                   Sichtregel regel, boolean mitDateiname, int k) {
            aufrufe++;
            throw new IllegalStateException("Datenbank nicht erreichbar: Frage Unfallversicherung");
        }
    }

    private static <T> T nichtVerwendet(Class<T> typ) {
        return typ.cast(Proxy.newProxyInstance(typ.getClassLoader(), new Class<?>[]{typ},
                (proxy, methode, args) -> {
                    throw new AssertionError("Unerwarteter Aufruf: " + methode.getName());
                }));
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

        List<LogRecord> eintraege = new ArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord eintrag) {
                eintraege.add(eintrag);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        Logger wurzel = Logger.getLogger("");
        wurzel.addHandler(handler);
        DokumenteSuchen.Antwort antwort;
        try {
            antwort = suchen.suchen(kontext,
                    new DokumenteSuchen.Anfrage("Unfallversicherung", null, null, null, null, null, 10));
        } finally {
            wurzel.removeHandler(handler);
        }

        assertThat(hybrid.aufrufe).isEqualTo(1);
        assertThat(antwort.treffer()).extracting(DokumenteSuchen.Treffer::titel).containsExactly("Police");
        assertThat(antwort.hinweis()).isEqualTo("nur Volltext");
        assertThat(antwort.degradiert()).isTrue();
        assertThat(antwort.gesamt()).isEqualTo(1);
        String protokoll = eintraege.stream().map(e -> e.getMessage() + " "
                + java.util.Arrays.toString(e.getParameters())).reduce("", (a, b) -> a + "\n" + b);
        assertThat(protokoll).contains("java.lang.IllegalStateException")
                .doesNotContain("Unfallversicherung", "Datenbank nicht erreichbar");
    }

    /** Unbekannter Partner und ausgefallene Hybridsuche: beide Hinweise bleiben, damit das Modell nichts Falsches schliesst. */
    @Test
    void unbekannterPartnerUndHybridFehlerNennenBeideHinweise() {
        SuchePort volltext = (anfrage, regel, mitDateiname) -> new Suchergebnis(List.of(), 0);
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
        DokumenteSuchen suchen = new DokumenteSuchen(volltext, new WerfendeHybridsuche(),
                PartnerTestdaten.register(List.of(), List.of()), null, daten, direkt, new BigDecimal("0.6"));
        WerkzeugKontext kontext = WerkzeugKontext.fuer(URINET, new Benutzer("joel", Rolle.INHABER), null);

        DokumenteSuchen.Antwort antwort = suchen.suchen(kontext,
                new DokumenteSuchen.Anfrage("Rechnung", null, "Nixname", null, null, null, 10));

        assertThat(antwort.hinweis()).contains("Partner nicht gefunden").contains("nur Volltext");
        assertThat(antwort.degradiert()).isTrue();
    }
}
