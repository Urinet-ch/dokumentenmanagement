package ch.urinet.archiv.ki.application.chat;

import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.protokoll.domain.ProtokollAktion;
import ch.urinet.archiv.protokoll.domain.ZugriffEintrag;
import ch.urinet.archiv.sicherheit.domain.Benutzer;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import ch.urinet.archiv.wissen.application.WissenKonfiguration;
import ch.urinet.archiv.wissen.application.WissenLesen;
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
}
