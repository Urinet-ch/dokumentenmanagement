package ch.urinet.archiv.benutzer.application;

import ch.urinet.archiv.benutzer.adapter.out.stub.IdentitaetsDienstStub;
import ch.urinet.archiv.benutzer.domain.BenutzerStatus;
import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.protokoll.domain.ProtokollDetails.Verwaltungsgrund;
import ch.urinet.archiv.sicherheit.application.Berechtigung;
import ch.urinet.archiv.sicherheit.domain.Benutzer;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BenutzerLoeschenTest {
    private static final MandantCode MANDANT = new MandantCode("urinet");
    private static final Benutzer AKTEUR = new Benutzer("akteur", Rolle.INHABER);
    private static final String ZIEL = "ziel";

    @Test
    void deaktivierterInhaberDarfNichtOhneAktivenInhaberGeloeschtWerden() {
        assertThatThrownBy(() -> loeschen(0).pruefen(MANDANT, AKTEUR, ZIEL))
                .isInstanceOfSatisfying(FachFehler.class,
                        fehler -> assertThat(fehler.code()).isEqualTo(Fehlercode.LETZTER_INHABER));
    }

    @Test
    void deaktivierterInhaberDarfBeiVerbleibendemAktivenInhaberGeloeschtWerden() {
        assertThatCode(() -> loeschen(1).pruefen(MANDANT, AKTEUR, ZIEL)).doesNotThrowAnyException();
        assertThatCode(() -> loeschen(2).pruefen(MANDANT, AKTEUR, ZIEL)).doesNotThrowAnyException();
    }

    private static BenutzerLoeschen loeschen(long aktiveInhaber) {
        IdentitaetsDienst identitaet = new IdentitaetsDienstStub() {
            @Override
            public synchronized Benutzer benutzerLesen(String mandant, String id) {
                return new Benutzer(id, "probe", "probe@example.invalid", "Test", "Person", false,
                        Set.of(Rolle.INHABER), Set.of(), true, false);
            }

            @Override
            public synchronized long inhaberZaehlen(String mandant) {
                return aktiveInhaber;
            }
        };
        BenutzerStatusSpeicher status = new BenutzerStatusSpeicher() {
            @Override
            public Optional<Eintrag> lesen(String benutzer) {
                return Optional.of(new Eintrag(BenutzerStatus.DEAKTIVIERT, Instant.now().minus(Duration.ofDays(31)),
                        Verwaltungsgrund.AUSTRITT, AKTEUR.id()));
            }

            @Override
            public void setzen(String benutzer, Eintrag eintrag) {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean entfernen(String benutzer) {
                throw new UnsupportedOperationException();
            }
        };
        return new BenutzerLoeschen(identitaet, status, null, null, null, new Berechtigung(null, null),
                null, null, Duration.ofDays(30), Duration.ofHours(72));
    }
}
