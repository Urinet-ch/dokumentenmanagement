package ch.urinet.archiv.ki.application.oeffentlich;

import ch.urinet.archiv.ki.application.chat.WerkzeugTransaktion;
import ch.urinet.archiv.ki.domain.chat.ChatAntwort;
import ch.urinet.archiv.ki.domain.chat.ChatStatus;
import ch.urinet.archiv.ki.domain.oeffentlich.OeffentlicheAusgabe;
import ch.urinet.archiv.integration.domain.webseite.Lesezugriff;
import ch.urinet.archiv.integration.domain.webseite.Zugriffsstufe;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.wissen.application.OeffentlichesWissenPort;
import ch.urinet.archiv.wissen.application.WissenHuelleDienst;
import io.smallrye.config.SmallRyeConfigBuilder;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

class OeffentlicheWissenAusgabeTest {
    private static final MandantCode MANDANT = new MandantCode("urinet");
    private static final UUID ID = UUID.randomUUID();
    private static final OeffentlichesWissenPort.Stand STAND = new OeffentlichesWissenPort.Stand(ID, 1, 1);
    private static final ChatAntwort ANTWORT = new ChatAntwort("Belegte Antwort", List.of(), List.of(),
            Optional.empty(), ChatStatus.OK);

    @Test
    void nurGezeigteAktuelleSeitenErlaubenAntwortMitQuellen() {
        var lauf = new OeffentlichesWissenDurchgang();
        lauf.zeigen(seite());
        var ergebnis = ausgeben(lauf, true);
        var ausgaben = ergebnis.ausgaben();
        assertThat(ausgaben).hasSize(3);
        assertThat(ausgaben.getFirst()).isEqualTo(new OeffentlicheAusgabe.Text("Belegte Antwort"));
        assertThat(((OeffentlicheAusgabe.WissenQuellen) ausgaben.get(1)).quellen().getFirst().belege().getFirst().titel())
                .isEqualTo("Beleg");
        assertThat(ergebnis.protokollierteFrage()).isEqualTo("Öffentliche Wissenschat-Frage");
        assertThat(ergebnis.gespraeche().anzahl()).isEqualTo(1);

        assertThat(ausgeben(new OeffentlichesWissenDurchgang(), true).ausgaben().getFirst())
                .isEqualTo(new OeffentlicheAusgabe.Text("Dazu steht nichts in der Wissensbasis."));
    }

    @Test
    void auchNurImIndexGesehenerEntzugOderRevisionswechselVerhindertJedenModelltext() {
        var lauf = new OeffentlichesWissenDurchgang();
        lauf.zeigen(seite());
        lauf.merken(new OeffentlichesWissenPort.Stand(UUID.randomUUID(), 1, 1));
        var entzogenerStand = ausgeben(lauf, false);
        assertThat(entzogenerStand.ausgaben()).singleElement().isInstanceOf(OeffentlicheAusgabe.Fehler.class);
        assertThat(entzogenerStand.protokollierteFrage()).isNull();
        assertThat(entzogenerStand.gespraeche().anzahl()).isZero();
        lauf.merken(new OeffentlichesWissenPort.Stand(ID, 1, 2));
        assertThat(ausgeben(lauf, true).ausgaben()).singleElement().isInstanceOf(OeffentlicheAusgabe.Fehler.class);
    }

    @Test
    void entzugVorFortsetzungVerwirftDenVerlauf() {
        var gespraechId = UUID.randomUUID();
        var zugriff = new Lesezugriff("probe", Zugriffsstufe.OEFFENTLICH, Zugriffsstufe.OEFFENTLICH,
                Optional.of("a".repeat(64)));
        var lauf = new OeffentlichesWissenDurchgang(List.of(STAND), List.of());
        var ergebnis = ausgeben(lauf, false, gespraechId, g -> {
            g.merkenWissen(gespraechId, MANDANT, zugriff, "Frage", "Antwort", List.of(STAND), List.of(seite()));
            assertThat(g.anzahl()).isEqualTo(1);
        });
        assertThat(ergebnis.ausgaben()).singleElement().isInstanceOf(OeffentlicheAusgabe.Fehler.class);
        assertThat(ergebnis.gespraeche().anzahl()).isZero();
        assertThat(ergebnis.gespraeche().oeffnenWissen(gespraechId, MANDANT, zugriff).verlauf()).isEmpty();
    }

    @Test
    void folgefrageOhneWerkzeugZeigtKeineQuellenFrueehererRunden() {
        var frueher = seite();
        var lauf = new OeffentlichesWissenDurchgang(List.of(STAND), List.of(frueher));
        var ausgaben = ausgeben(lauf, true).ausgaben();
        assertThat(ausgaben.getFirst()).isEqualTo(new OeffentlicheAusgabe.Text("Dazu steht nichts in der Wissensbasis."));
        assertThat(((OeffentlicheAusgabe.WissenQuellen) ausgaben.get(1)).quellen()).isEmpty();

        lauf.zeigen(frueher);
        assertThat(((OeffentlicheAusgabe.WissenQuellen) ausgeben(lauf, true).ausgaben().get(1)).quellen()).hasSize(1);
    }

    private static OeffentlichesWissenPort.Seite seite() {
        return new OeffentlichesWissenPort.Seite(STAND, "probe/seite", "Titel", "Konzept", "Inhalt", Instant.EPOCH,
                List.of(new OeffentlichesWissenPort.Dokument(UUID.randomUUID(), "Beleg", "R", "Reihe", "1")));
    }

    private record Ergebnis(List<OeffentlicheAusgabe> ausgaben, String protokollierteFrage,
                            OeffentlicheGespraeche gespraeche) {
    }

    private static Ergebnis ausgeben(OeffentlichesWissenDurchgang lauf, boolean gueltig) {
        return ausgeben(lauf, gueltig, UUID.randomUUID(), g -> { });
    }

    private static Ergebnis ausgeben(OeffentlichesWissenDurchgang lauf, boolean gueltig, UUID gespraechId,
                                     java.util.function.Consumer<OeffentlicheGespraeche> vorbereiten) {
        OeffentlichesWissenPort port = new OeffentlichesWissenPort() {
            public boolean hatFreigegebeneSeite() { return true; }
            public List<Indexzeile> suchen(String begriff, int hoechstens) { return List.of(); }
            public Optional<Seite> seite(String pfad) { return Optional.empty(); }
            public boolean aktuell(Stand stand) { return gueltig; }
        };
        var gesperrt = new AtomicBoolean();
        WissenHuelleDienst huelle = new WissenHuelleDienst(null) {
            public void sperren() { gesperrt.set(true); }
        };
        WerkzeugTransaktion transaktion = new WerkzeugTransaktion() {
            public <T> T neu(Supplier<T> arbeit) { return arbeit.get(); }
        };
        var gespraeche = new OeffentlicheGespraeche(
                java.time.Duration.ofMinutes(30), java.time.Duration.ofSeconds(10), 2, 6, Clock.systemUTC());
        vorbereiten.accept(gespraeche);
        var protokollierteFrage = new AtomicReference<String>();
        OeffentlicheFragenSpeicher speicher = new OeffentlicheFragenSpeicher() {
            public void speichern(String frage, List<ch.urinet.archiv.ki.domain.oeffentlich.OeffentlicheQuelle> quellen,
                                  int tokens, Instant zeit) {
                protokollierteFrage.set(frage);
            }

            public int loeschenVor(Instant grenze) { return 0; }
        };
        var dienst = new OeffentlicheFragen(null, null, null, gespraeche, speicher, null, port, huelle, null, transaktion,
                null, null, null, new SmallRyeConfigBuilder().build(), 500);
        var zugriff = new Lesezugriff("probe", Zugriffsstufe.OEFFENTLICH, Zugriffsstufe.OEFFENTLICH,
                Optional.of("a".repeat(64)));
        var grenzen = new OeffentlicheGrenzen(10, 10, 1, 1, Clock.systemUTC());
        var platz = grenzen.annehmen(MANDANT, zugriff);
        var frage = "Frage max.muster@example.invalid";
        var anfrage = new OeffentlicheAnfrage(MANDANT, zugriff, LocalDate.now(), "", frage, List.of(), lauf);
        var annahme = new OeffentlicheFragen.Annahme(gespraechId, anfrage, frage, platz);
        List<OeffentlicheAusgabe> ausgaben = new ArrayList<>();
        try {
            dienst.wissenAusgeben(annahme, ANTWORT, ausgabe -> {
                assertThat(gesperrt.get()).isTrue();
                ausgaben.add(ausgabe);
            });
        } finally {
            platz.freigeben();
            dienst.beenden();
        }
        return new Ergebnis(ausgaben, protokollierteFrage.get(), gespraeche);
    }
}
