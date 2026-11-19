package ch.urinet.archiv.sicherheit.domain;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static ch.urinet.archiv.sicherheit.domain.Vergaberegeln.Pruefung.ERLAUBT;
import static ch.urinet.archiv.sicherheit.domain.Vergaberegeln.Pruefung.ESKALATION_VERBOTEN;
import static ch.urinet.archiv.sicherheit.domain.Vergaberegeln.Pruefung.NICHT_ERLAUBT;
import static org.assertj.core.api.Assertions.assertThat;

class EskalationTest {

    private static final Set<String> ROLLEN = Arrays.stream(Rolle.values()).map(Rolle::code).collect(Collectors.toSet());

    @Test
    void jedeRollenvergabeIstAufRangfolgeSatzUndZielBegrenzt() {
        for (Rolle akteurRolle : Rolle.values()) {
            for (Rolle zielRolle : Rolle.values()) {
                for (Rolle zuVergeben : Rolle.values()) {
                    Benutzer akteur = new Benutzer("akteur", akteurRolle);
                    Benutzer ziel = new Benutzer("ziel", zielRolle);
                    Vergaberegeln.Pruefung erwartet = erwartetePruefung(akteur, ziel, zuVergeben.code());
                    assertThat(Vergaberegeln.pruefeRollenvergabe(akteur, ziel, zuVergeben.code(), ROLLEN))
                            .as("%s -> %s, vergeben %s", akteurRolle, zielRolle, zuVergeben)
                            .isEqualTo(erwartet);
                }
            }
        }
    }

    @Test
    void ablehnungsgrundUnterscheidetFehlendesRechtVonEskalation() {
        Benutzer inhaber = new Benutzer("inhaber", Rolle.INHABER);
        Benutzer ziel = new Benutzer("ziel", Rolle.MITARBEITER);

        assertThat(Vergaberegeln.pruefeRollenvergabe(inhaber, ziel, Rolle.GESCHAEFTSSTELLE.code(), Set.of("inhaber")))
                .isEqualTo(ESKALATION_VERBOTEN);
        assertThat(Vergaberegeln.pruefeRollenvergabe(
                new Benutzer("mitarbeiter", Rolle.MITARBEITER), ziel, Rolle.TREUHAENDER.code(), ROLLEN))
                .isEqualTo(NICHT_ERLAUBT);
        assertThat(Vergaberegeln.pruefeRollenvergabe(
                inhaber, new Benutzer("inhaber", Rolle.INHABER), Rolle.TREUHAENDER.code(), ROLLEN))
                .isEqualTo(ESKALATION_VERBOTEN);
    }

    @Test
    void unbekannteRollenSindKeineArchivRollen() {
        Benutzer inhaber = new Benutzer("inhaber", Rolle.INHABER);
        Benutzer ziel = new Benutzer("ziel", Rolle.MITARBEITER);
        assertThat(Vergaberegeln.pruefeRollenvergabe(inhaber, ziel, "realm-admin", ROLLEN))
                .isEqualTo(Vergaberegeln.Pruefung.KONFIGURATION_UNGUELTIG);
        assertThat(Vergaberegeln.rollenSatzZulaessig(Set.of("inhaber", "realm-admin"))).isFalse();
    }

    private static Vergaberegeln.Pruefung erwartetePruefung(Benutzer akteur, Benutzer ziel, String zuVergeben) {
        if (Rechtematrix.ergebnis(akteur.rolle(), Aktion.A23) == Ergebnis.VERBOTEN) {
            return NICHT_ERLAUBT;
        }
        Rolle rolle = Rolle.valueOf(zuVergeben.toUpperCase(java.util.Locale.ROOT));
        if (akteur.id().equals(ziel.id()) || !akteur.rolle().darfVergeben(rolle)) {
            return ESKALATION_VERBOTEN;
        }
        return ERLAUBT;
    }
}
