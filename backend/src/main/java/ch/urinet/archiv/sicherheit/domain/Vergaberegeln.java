package ch.urinet.archiv.sicherheit.domain;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Eskalationsschutz und feste Rollenmenge der Benutzerverwaltung (Spec 06, Requirement 4). */
public final class Vergaberegeln {

    private static final Set<String> ARCHIV_ROLLEN = Arrays.stream(Rolle.values()).map(Rolle::code)
            .collect(Collectors.toUnmodifiableSet());

    public enum Pruefung {
        ERLAUBT,
        NICHT_ERLAUBT,
        KONFIGURATION_UNGUELTIG,
        ESKALATION_VERBOTEN
    }

    private Vergaberegeln() {
    }

    public static boolean rollenSatzZulaessig(Set<String> rollen) {
        return rollen != null && !rollen.isEmpty() && ARCHIV_ROLLEN.containsAll(rollen);
    }

    public static Pruefung pruefeRollenvergabe(Benutzer akteur, Benutzer ziel, String rollenCode,
                                               Set<String> mandantenRollen) {
        if (!Rechtematrix.ergebnis(akteur.rolle(), Aktion.A23).zugaenglich()) {
            return Pruefung.NICHT_ERLAUBT;
        }
        if (!rollenSatzZulaessig(mandantenRollen) || rollenCode == null) {
            return Pruefung.KONFIGURATION_UNGUELTIG;
        }
        Rolle zuVergeben = Rolle.ausRealmRollen(List.of(rollenCode)).orElse(null);
        if (zuVergeben == null) {
            return Pruefung.KONFIGURATION_UNGUELTIG;
        }
        if (!mandantenRollen.contains(zuVergeben.code())) {
            return Pruefung.ESKALATION_VERBOTEN;
        }
        if (akteur.id().equals(ziel.id()) || !akteur.rolle().darfVergeben(zuVergeben)) {
            return Pruefung.ESKALATION_VERBOTEN;
        }
        return Pruefung.ERLAUBT;
    }

    public static boolean entferntLetztenAktivenInhaber(Rolle zielRolle, boolean zielAktiv,
                                                         int anzahlAktiveInhaber) {
        if (anzahlAktiveInhaber < 0) {
            throw new IllegalArgumentException("Anzahl aktiver Inhaber ist negativ");
        }
        return zielRolle == Rolle.INHABER && zielAktiv && anzahlAktiveInhaber <= 1;
    }
}
