package ch.urinet.archiv.wissen.application;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.sicherheit.application.Berechtigung;
import ch.urinet.archiv.sicherheit.domain.Benutzer;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebseitenfreigabeTest {
    private static final UUID SEITE = UUID.randomUUID();
    private static final Benutzer INHABER = new Benutzer("probe", Rolle.INHABER);
    private static final Benutzer MITARBEITER = new Benutzer("probe", Rolle.MITARBEITER);
    private static final QuellenPruefungPort.SeitenStand ETAG = new QuellenPruefungPort.SeitenStand(1, 0, true);

    @Test
    void sichtbarVorRechtUndVorVersionspruefung() {
        Speicher speicher = new Speicher();
        Webseitenfreigabe dienst = dienst(speicher, true);
        assertThatThrownBy(() -> dienst.setzen(MITARBEITER, SEITE, true, () -> ETAG))
                .isInstanceOfSatisfying(FachFehler.class,
                        fehler -> assertThat(fehler.code()).isEqualTo(Fehlercode.NICHT_ERLAUBT));
        assertThatThrownBy(() -> dienst(speicher, false).setzen(MITARBEITER, SEITE, true, () -> ETAG))
                .isInstanceOfSatisfying(FachFehler.class,
                        fehler -> assertThat(fehler.code()).isEqualTo(Fehlercode.NICHT_GEFUNDEN));
        assertThat(speicher.schreibvorgaenge).isZero();
    }

    @Test
    void alteVersionUndUngeeigneteQuellenVerhindernFreigabe() {
        Speicher speicher = new Speicher();
        Webseitenfreigabe dienst = dienst(speicher, true);
        assertThatThrownBy(() -> dienst.setzen(INHABER, SEITE, true,
                () -> new QuellenPruefungPort.SeitenStand(1, 1, true)))
                .isInstanceOfSatisfying(FachFehler.class,
                        fehler -> assertThat(fehler.code()).isEqualTo(Fehlercode.VERSION_KONFLIKT));
        speicher.stand = new WebseitenfreigabePort.Stand(1, 0, false, false, "mitglieder");
        assertThatThrownBy(() -> dienst.setzen(INHABER, SEITE, true, () -> ETAG))
                .isInstanceOfSatisfying(FachFehler.class,
                        fehler -> assertThat(fehler.code()).isEqualTo(Fehlercode.STATUS_KONFLIKT));
        assertThat(speicher.schreibvorgaenge).isZero();
    }

    @Test
    void entzugBleibtAuchBeiInzwischenUngeeigneterQuelleMoeglich() {
        Speicher speicher = new Speicher();
        speicher.stand = new WebseitenfreigabePort.Stand(1, 0, true, false, "vorstand");
        dienst(speicher, true).setzen(INHABER, SEITE, false, () -> ETAG);
        assertThat(speicher.schreibvorgaenge).isEqualTo(1);
        assertThat(speicher.freigeben).isFalse();
    }

    private static Webseitenfreigabe dienst(Speicher speicher, boolean sichtbar) {
        WissenHuelleDienst huelle = new WissenHuelleDienst(null) {
            @Override
            public void sperren() {
            }
        };
        WissenSichtbarkeit sicht = new WissenSichtbarkeit(null) {
            @Override
            public boolean aktuelleSeiteSichtbar(Benutzer benutzer, UUID seiteId) {
                return sichtbar;
            }
        };
        Berechtigung berechtigung = new Berechtigung(null, (benutzer, rolle, aktion, dokument, grund) -> { });
        return new Webseitenfreigabe(huelle, speicher, sicht, berechtigung);
    }

    private static class Speicher implements WebseitenfreigabePort {
        Stand stand = new Stand(1, 0, false, true, "oeffentlich");
        int schreibvorgaenge;
        boolean freigeben;

        @Override
        public Optional<Stand> lesen(UUID seiteId, boolean sperren) {
            return Optional.of(stand);
        }

        @Override
        public void setzen(UUID seiteId, Stand stand, boolean freigeben, String benutzer) {
            this.schreibvorgaenge++;
            this.freigeben = freigeben;
        }
    }
}
