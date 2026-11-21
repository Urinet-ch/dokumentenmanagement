package ch.urinet.archiv.ki.application.chat;

import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.protokoll.domain.ProtokollAktion;
import ch.urinet.archiv.protokoll.domain.ZugriffEintrag;
import ch.urinet.archiv.sicherheit.application.Verweigerung;
import ch.urinet.archiv.sicherheit.domain.Benutzer;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import ch.urinet.archiv.wissen.application.WissenKonfiguration;
import ch.urinet.archiv.wissen.application.WissenLesen;
import ch.urinet.archiv.wissen.application.WissenLesenRepository;
import ch.urinet.archiv.wissen.application.WissensSuche;
import ch.urinet.archiv.wissen.domain.Quelleneintrag;
import ch.urinet.archiv.wissen.domain.Seitentyp;
import ch.urinet.archiv.wissen.domain.Wissensseite;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WissenWerkzeugeTest {
    private static final UUID SEITE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID DOKUMENT = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final WerkzeugKontext KONTEXT = WerkzeugKontext.fuer(new MandantCode("urinet"),
            new Benutzer("test", Rolle.MITARBEITER), null);
    private final List<ZugriffEintrag> eintraege = new ArrayList<>();
    private final Wissensseite seite = new Wissensseite(SEITE, "konzepte/test", Seitentyp.KONZEPT, "Test", "",
            List.of(), Wissensseite.Status.VERALTET, LocalDate.of(2000, 1, 1),
            List.of(new Quelleneintrag("a", DOKUMENT, 2, 3, "", null)),
            Wissensseite.Vertraulichkeit.NORMAL, "😀aaa [^a] und [^a]\n\n[^a]: Quelle", 1, "test",
            Instant.EPOCH, "test", Instant.EPOCH, true, true, null);
    private final WissenLesen lesen = new WissenLesen(null, null, null) {
        @Override
        public Wissensseite seite(Benutzer benutzer, String pfad) {
            assertThat(benutzer).isEqualTo(KONTEXT.benutzer());
            if (pfad.startsWith("regeln/")) {
                throw new Verweigerung(Verweigerung.Grund.NICHT_GEFUNDEN);
            }
            assertThat(pfad).isEqualTo("konzepte/test");
            return seite;
        }

        @Override
        public Map<String, QuellenAnzeige> quellenAnzeige(Benutzer benutzer, Wissensseite aktuell) {
            return Map.of("a", new QuellenAnzeige("Dokument", "test.pdf", null, null, null));
        }

        @Override
        public String inhaltDarstellen(Benutzer benutzer, String markdown) {
            return markdown;
        }
    };
    private final WissenWerkzeuge werkzeuge = new WissenWerkzeuge(lesen, null,
            new WissenKonfiguration("test", 100, 1, 100, 100, 3, 100, 100),
            eintraege::add, new WerkzeugTransaktion());

    @Test
    void kuerztUnicodeOhneQuellenZuVerlierenUndProtokolliert() {
        var antwort = werkzeuge.zeigen(KONTEXT, "konzepte/test");
        assertThat(antwort.vertraulichkeit()).isEqualTo(Wissensseite.Vertraulichkeit.NORMAL);
        assertThat(antwort.inhalt()).isEqualTo("😀aa");
        assertThat(antwort.hinweise()).containsExactly("VERALTET", "ABGELAUFEN", "WIDERSPRUCH",
                "QUELLE_PRUEFEN", "GEKUERZT");
        assertThat(antwort.quellen()).singleElement().satisfies(q -> {
            assertThat(q.dokumentId()).isEqualTo(DOKUMENT);
            assertThat(q.seiten()).isEqualTo("2-3");
            assertThat(q.anzahl()).isEqualTo(2);
        });
        assertThat(eintraege).singleElement().satisfies(e -> {
            assertThat(e.aktion()).isEqualTo(ProtokollAktion.WISSEN_ANZEIGEN);
            assertThat(e.dokumentId()).contains(SEITE);
            assertThat(e.details().kanal()).isEqualTo("CHAT");
        });
    }

    @Test
    void anzahlOhneFrageWirdAbgelehnt() {
        assertThatThrownBy(() -> werkzeuge.index(KONTEXT, null, null, 2))
                .isInstanceOf(WerkzeugFehler.class).hasMessageContaining("anzahl");
    }

    private static WissenLesenRepository.IndexZeile zeile(int nr, Seitentyp typ) {
        return new WissenLesenRepository.IndexZeile(UUID.randomUUID(), typ, "konzepte/s" + nr, "S" + nr, "b",
                "VERALTET", true, Wissensseite.Vertraulichkeit.INTERN);
    }

    private WissenWerkzeuge mitIndex(WissenLesen.IndexErgebnis index, WissensSuche suche, List<Object> aufrufe) {
        var l = new WissenLesen(null, null, null) {
            @Override
            public IndexErgebnis index(Benutzer b, Seitentyp typ, String tag, String q, UUID d, int offset,
                                       int groesse) {
                aufrufe.add(typ);
                aufrufe.add(groesse);
                return index;
            }
        };
        return new WissenWerkzeuge(l, suche, null, eintraege::add, new WerkzeugTransaktion());
    }

    @Test
    void indexOhneFrageIstAuf200GedeckeltUndMeldetKuerzung() {
        List<Object> aufrufe = new ArrayList<>();
        var zeilen = java.util.stream.IntStream.range(0, 200).mapToObj(i -> zeile(i, Seitentyp.KONZEPT)).toList();
        var gekuerzt = mitIndex(new WissenLesen.IndexErgebnis(
                List.of(new WissenLesen.IndexGruppe(Seitentyp.KONZEPT, zeilen)), 250, 0, 200), null, aufrufe);
        var antwort = gekuerzt.index(KONTEXT, null, "konzept", null);
        assertThat(aufrufe).containsExactly(Seitentyp.KONZEPT, 200);
        assertThat(antwort.treffer()).hasSize(200);
        assertThat(antwort.hinweis()).contains("200").contains("eingrenzen");
        assertThat(antwort.treffer().get(0).hinweise()).containsExactly("VERALTET", "QUELLE_PRUEFEN");
        assertThat(antwort.treffer().get(0).vertraulichkeit()).isEqualTo(Wissensseite.Vertraulichkeit.INTERN);

        var vollstaendig = mitIndex(new WissenLesen.IndexErgebnis(
                List.of(new WissenLesen.IndexGruppe(Seitentyp.KONZEPT, zeilen)), 200, 0, 200), null, aufrufe);
        assertThat(vollstaendig.index(KONTEXT, null, null, null).hinweis()).isNull();
    }

    @Test
    void indexMitFrageNutztSucheMitTypfilterKappungUndDegradiert() {
        List<Object> aufrufe = new ArrayList<>();
        var suche = new WissensSuche(null, null, null, null, null) {
            @Override
            public Ergebnis suchen(Benutzer b, String frage, Seitentyp typ, String tag, UUID d, int k,
                                   boolean hybrid) {
                aufrufe.addAll(List.of(frage, String.valueOf(typ), k, hybrid));
                return new Ergebnis(List.of(zeile(1, Seitentyp.KONZEPT)), true);
            }
        };
        var w = mitIndex(null, suche, aufrufe);
        var antwort = w.index(KONTEXT, "Frist", "konzept", 50);
        assertThat(aufrufe).containsExactly("Frist", "KONZEPT", 30, true);
        assertThat(antwort.degradiert()).isTrue();
        assertThat(antwort.treffer()).singleElement().satisfies(z -> assertThat(z.pfad()).isEqualTo("konzepte/s1"));
        assertThat(antwort.hinweis()).contains("30");

        aufrufe.clear();
        assertThat(w.index(KONTEXT, "Frist", null, null).hinweis()).isNull();
        assertThat(aufrufe).containsExactly("Frist", "null", 5, true);
    }

    @Test
    void unbekannterOderUnsichtbarerPfadIstNichtGefunden() {
        assertThatThrownBy(() -> werkzeuge.zeigen(KONTEXT, "regeln/gibts-nicht"))
                .isInstanceOf(WerkzeugFehler.class).hasMessage(WerkzeugFehler.SEITE_NICHT_GEFUNDEN);
        assertThat(eintraege).isEmpty();
    }
}
