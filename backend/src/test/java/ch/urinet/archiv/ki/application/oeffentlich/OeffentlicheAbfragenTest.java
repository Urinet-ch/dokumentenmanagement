package ch.urinet.archiv.ki.application.oeffentlich;

import ch.urinet.archiv.integration.domain.webseite.Lesezugriff;
import ch.urinet.archiv.integration.domain.webseite.Zugriffsstufe;
import ch.urinet.archiv.ki.application.chat.WerkzeugDaten;
import ch.urinet.archiv.ki.application.chat.WerkzeugTransaktion;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.sicherheit.domain.Sichtregel;
import ch.urinet.archiv.suche.application.HybridSuche;
import ch.urinet.archiv.suche.application.SuchePort;
import ch.urinet.archiv.suche.domain.Hybridergebnis;
import ch.urinet.archiv.suche.domain.Hybridtreffer;
import ch.urinet.archiv.suche.domain.Suchanfrage;
import ch.urinet.archiv.suche.domain.Suchergebnis;
import ch.urinet.archiv.suche.domain.Suchtreffer;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

class OeffentlicheAbfragenTest {

    @Test
    void mindestrelevanzVerwirftSchwacheUndUnbewerteteTreffer() {
        Hybridtreffer unterSchwelle = treffer(null, 0.149);
        Hybridtreffer anSchwelle = treffer(null, 0.15);
        Hybridtreffer ohneBewertung = treffer(null, null);
        Hybridtreffer exakteDokumentnummer = treffer("B-2071-01", null);

        List<Hybridtreffer> belegte = OeffentlicheAbfragen.belegteTreffer(
                new Hybridergebnis(List.of(unterSchwelle, anSchwelle, ohneBewertung, exakteDokumentnummer), false),
                "B-2071-01", 0.15);

        assertThat(belegte).containsExactly(anSchwelle, exakteDokumentnummer);
    }

    @Test
    void exakteDokumentnummerBleibtImDegradiertenHybridergebnisBeleg() {
        Hybridtreffer unbewertet = treffer(null, null);
        Hybridtreffer exakteDokumentnummer = treffer("B-2071-01", null);

        List<Hybridtreffer> belegte = OeffentlicheAbfragen.belegteTreffer(
                new Hybridergebnis(List.of(unbewertet, exakteDokumentnummer), true), "B-2071-01", 0.15);

        assertThat(belegte).containsExactly(exakteDokumentnummer);
    }

    @Test
    void volltextRueckfallNachAusnahmeDerHybridsucheBehaeltNurDieExakteDokumentnummer() {
        Suchtreffer unscharf = dokument(null);
        Suchtreffer exakt = dokument("B-2071-01");
        HybridSuche wirft = new HybridSuche(null, null, null, null, null, null) {
            @Override
            public Hybridergebnis suchen(Suchanfrage anfrage, Sichtregel regel, boolean mitDateiname, int k) {
                throw new IllegalStateException("synthetischer Ausfall");
            }
        };
        SuchePort volltext = (anfrage, regel, mitDateiname) -> new Suchergebnis(List.of(unscharf, exakt), 2);
        OeffentlicheDokumente alleSichtbar = new OeffentlicheDokumente() {
            @Override
            public List<OeffentlichesDokument> sichtbare(Collection<UUID> ids, Lesezugriff zugriff) {
                return ids.stream().map(id -> new OeffentlichesDokument(id, "Titel", "TEST", "Test", null, 2071, null))
                        .toList();
            }

            @Override
            public Optional<String> inhalt(UUID id, Lesezugriff zugriff, int zeichen) {
                return Optional.empty();
            }
        };
        WerkzeugDaten daten = (WerkzeugDaten) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{WerkzeugDaten.class}, (proxy, m, args) -> {
                    if ("nurLesend".equals(m.getName())) {
                        return null;
                    }
                    throw new UnsupportedOperationException(m.getName());
                });
        WerkzeugTransaktion direkt = new WerkzeugTransaktion() {
            @Override
            public <T> T neu(Supplier<T> arbeit) {
                return arbeit.get();
            }
        };
        OeffentlicheAbfragen abfragen = new OeffentlicheAbfragen(volltext, wirft, alleSichtbar, daten, direkt, 0.15);
        OeffentlicherKontext kontext = new OeffentlicherKontext(new MandantCode("urinet"), new Lesezugriff("webseite",
                Zugriffsstufe.OEFFENTLICH, Zugriffsstufe.OEFFENTLICH, Optional.empty()));

        OeffentlicheAbfragen.Antwort antwort = abfragen.suchen(kontext, "B-2071-01", null, null);

        assertThat(antwort.treffer()).extracting(OeffentlicheAbfragen.Treffer::id)
                .containsExactly(exakt.id().toString());
        assertThat(antwort.gesamt()).isEqualTo(1);
        assertThat(antwort.degradiert()).isTrue();
        assertThat(abfragen.suchen(kontext, "Seilbahn", null, null).treffer()).isEmpty();
    }

    private static Hybridtreffer treffer(String belegnummer, Double relevanz) {
        return new Hybridtreffer(dokument(belegnummer), null, relevanz);
    }

    private static Suchtreffer dokument(String belegnummer) {
        return new Suchtreffer(UUID.randomUUID(), null, null, null, Instant.EPOCH, null,
                null, List.of(), null, null, null, belegnummer, null, null, null, List.of(), null);
    }
}
