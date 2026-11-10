package ch.urinet.archiv.sicherheit.application;

import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.sicherheit.domain.Aktion;
import ch.urinet.archiv.sicherheit.domain.Benutzer;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Reihenfolge aus design.md «Sicherheit»: erst sichtbar (sonst 404), dann Recht der Aktion (sonst 403); Req 2.3, 2.6. */
class BerechtigungTest {

    private static final UUID DOKUMENT = UUID.randomUUID();
    private static final Benutzer TREUHAENDER = new Benutzer("treuhaender-urinet", Rolle.TREUHAENDER);
    private static final Benutzer MITARBEITER = new Benutzer("mitarbeiter-urinet", Rolle.MITARBEITER);

    private final List<Verweigerung.Grund> gemeldet = new ArrayList<>();

    private Berechtigung mit(boolean sichtbar) {
        return new Berechtigung((benutzer, id) -> sichtbar, (benutzer, rolle, aktion, id, grund) -> gemeldet.add(grund));
    }

    @Test
    void unsichtbarUndOhneRechtGibt404Nicht403() {
        assertThatThrownBy(() -> mit(false).dokumentAktion(TREUHAENDER, Aktion.A9, DOKUMENT))
                .isInstanceOfSatisfying(Verweigerung.class,
                        v -> assertThat(v.grund()).isEqualTo(Verweigerung.Grund.NICHT_GEFUNDEN));
        assertThat(gemeldet).containsExactly(Verweigerung.Grund.NICHT_GEFUNDEN);
    }

    @Test
    void sichtbarOhneRechtGibt403() {
        assertThatThrownBy(() -> mit(true).dokumentAktion(TREUHAENDER, Aktion.A9, DOKUMENT))
                .isInstanceOfSatisfying(Verweigerung.class,
                        v -> assertThat(v.grund()).isEqualTo(Verweigerung.Grund.NICHT_ERLAUBT));
    }

    @Test
    void zelle404GiltAuchBeiSichtbaremDokument() {
        assertThatThrownBy(() -> mit(true).dokumentAktion(MITARBEITER, Aktion.A3, DOKUMENT))
                .isInstanceOfSatisfying(Verweigerung.class,
                        v -> assertThat(v.grund()).isEqualTo(Verweigerung.Grund.NICHT_GEFUNDEN));
    }

    @Test
    void sichtbarMitRechtIstErlaubt() {
        assertThatCode(() -> mit(true).dokumentAktion(TREUHAENDER, Aktion.A4, DOKUMENT)).doesNotThrowAnyException();
        assertThat(gemeldet).isEmpty();
    }

    @Test
    void treuhaenderErfasstNicht() {
        assertThatThrownBy(() -> mit(true).aktion(TREUHAENDER, Aktion.A1))
                .isInstanceOfSatisfying(Verweigerung.class,
                        v -> assertThat(v.grund()).isEqualTo(Verweigerung.Grund.NICHT_ERLAUBT));
        assertThatCode(() -> mit(true).aktion(MITARBEITER, Aktion.A1)).doesNotThrowAnyException();
    }

    @Test
    void ohneMandantGibt403MandantFehltUndMeldet() {
        assertThatThrownBy(() -> mit(true).ohneMandant(Optional.of("inhaber-urinet")))
                .isInstanceOfSatisfying(Verweigerung.class,
                        v -> assertThat(v.code()).isEqualTo(Fehlercode.MANDANT_FEHLT));
        assertThat(gemeldet).containsExactly(Verweigerung.Grund.MANDANT_FEHLT);
    }

    @Test
    void ohneRolleGibt403NichtErlaubtMitAktion() {
        List<Optional<Aktion>> aktionen = new ArrayList<>();
        Berechtigung berechtigung = new Berechtigung((benutzer, id) -> true,
                (benutzer, rolle, aktion, id, grund) -> aktionen.add(aktion));
        assertThatThrownBy(() -> berechtigung.ohneZugang(Optional.of("x"), Optional.empty(), Optional.of(Aktion.A7)))
                .isInstanceOfSatisfying(Verweigerung.class,
                        v -> assertThat(v.code()).isEqualTo(Fehlercode.NICHT_ERLAUBT));
        assertThat(aktionen).containsExactly(Optional.of(Aktion.A7));
    }
}
