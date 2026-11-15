package ch.urinet.archiv.ki.application.chat;

import ch.urinet.archiv.kontakt.domain.Kontakt;
import ch.urinet.archiv.kontakt.domain.PartnerKandidat;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static ch.urinet.archiv.ki.application.chat.PartnerTestdaten.SCHWELLE;
import static ch.urinet.archiv.ki.application.chat.PartnerTestdaten.kontakt;
import static ch.urinet.archiv.ki.application.chat.PartnerTestdaten.register;
import static org.assertj.core.api.Assertions.assertThat;

/** Partnerauflösung im Chat (Spec 02, Req 4/5): exakt, Teilwort, Ähnlichkeit; nie eine Vermutung. */
class PartnerAufloesungTest {

    private final Kontakt bachmann = kontakt("Confiseur Bachmann AG Tribschenstadt", true);

    private PartnerAufloesung mit(List<Kontakt> kontakte, List<PartnerKandidat> aehnlich) {
        return new PartnerAufloesung(register(kontakte, aehnlich), SCHWELLE);
    }

    @Test
    void teilnameLoestAufDenKontaktAuf() {
        assertThat(mit(List.of(bachmann), List.of()).aufloesen("bachmann", true))
                .isEqualTo(new PartnerAufloesung.Gefunden(bachmann.id()));
    }

    @Test
    void teilnameMitRechtsformUndGrossschreibungLoestAuf() {
        assertThat(mit(List.of(bachmann), List.of()).aufloesen("Bachmann AG", true))
                .isEqualTo(new PartnerAufloesung.Gefunden(bachmann.id()));
    }

    @Test
    void wortanfangGenuegtAberNichtWortmitte() {
        PartnerAufloesung a = mit(List.of(bachmann), List.of());
        assertThat(a.aufloesen("tribsch bach", true)).isEqualTo(new PartnerAufloesung.Gefunden(bachmann.id()));
        assertThat(a.aufloesen("achmann", true)).isInstanceOf(PartnerAufloesung.NichtGefunden.class);
    }

    @Test
    void exakterNameGewinntGegenLaengereNamen() {
        Kontakt kurz = kontakt("Bachmann AG", true);
        assertThat(mit(List.of(bachmann, kurz), List.of()).aufloesen("Bachmann AG", true))
                .isEqualTo(new PartnerAufloesung.Gefunden(kurz.id()));
    }

    @Test
    void zweiPartnerMitDemselbenWortSindNichtEindeutigMitNamen() {
        Kontakt zweiter = kontakt("Bachmann Transport GmbH", true);
        assertThat(mit(List.of(bachmann, zweiter), List.of()).aufloesen("bachmann", true))
                .isEqualTo(new PartnerAufloesung.NichtEindeutig(
                        List.of("Bachmann Transport GmbH", "Confiseur Bachmann AG Tribschenstadt")));
    }

    @Test
    void ohneRechtZumAnsehenDerKontakteKeineNamen() {
        Kontakt zweiter = kontakt("Bachmann Transport GmbH", true);
        assertThat(mit(List.of(bachmann, zweiter), List.of()).aufloesen("bachmann", false))
                .isEqualTo(new PartnerAufloesung.NichtEindeutig(List.of()));
    }

    @Test
    void hoechstensFuenfKandidaten() {
        List<Kontakt> viele = java.util.stream.IntStream.range(0, 9).mapToObj(i -> kontakt("Muster " + i + " AG", true))
                .toList();
        PartnerAufloesung.NichtEindeutig e = (PartnerAufloesung.NichtEindeutig) mit(viele, List.of())
                .aufloesen("muster", true);
        assertThat(e.namen()).hasSize(PartnerAufloesung.KANDIDATEN_MAX);
    }

    @Test
    void inaktiveKontakteSindWederTrefferNochKandidat() {
        Kontakt alt = kontakt("Bachmann Alt AG", false);
        assertThat(mit(List.of(bachmann, alt), List.of()).aufloesen("bachmann", true))
                .isEqualTo(new PartnerAufloesung.Gefunden(bachmann.id()));
    }

    @Test
    void ohneWortTrefferGiltDerAehnlichkeitsabgleich() {
        PartnerKandidat nah = new PartnerKandidat(bachmann.id(), new BigDecimal("0.9"));
        assertThat(mit(List.of(), List.of(nah)).aufloesen("Bachmann Konfiseur", true))
                .isEqualTo(new PartnerAufloesung.Gefunden(bachmann.id()));
        assertThat(mit(List.of(), List.of()).aufloesen("Unbekannt GmbH", true))
                .isInstanceOf(PartnerAufloesung.NichtGefunden.class);
    }

    @Test
    void leereOderNurRechtsformEingabeIstNichtGefunden() {
        assertThat(mit(List.of(bachmann), List.of()).aufloesen("AG", true))
                .isInstanceOf(PartnerAufloesung.NichtGefunden.class);
    }
}
