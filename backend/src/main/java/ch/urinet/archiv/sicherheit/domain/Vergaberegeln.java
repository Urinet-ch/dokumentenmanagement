package ch.urinet.archiv.sicherheit.domain;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
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

    /** Aktionen aus A22, die sich der Akteur nicht gegen sich selbst richten darf (Req 4.6). */
    public enum Selbstaktion {
        SPERREN,
        DEAKTIVIEREN,
        LOESCHEN,
        ZUGANG_ZURUECKSETZEN
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

    /** Sperren, Deaktivieren, Löschen und Zugang zurücksetzen gegen sich selbst ist verboten (Req 4.6). */
    public static Pruefung pruefeSelbstzugriff(Benutzer akteur, Benutzer ziel, Selbstaktion aktion) {
        return pruefeSelbstzugriff(akteur, ziel.id(), aktion);
    }

    public static Pruefung pruefeSelbstzugriff(Benutzer akteur, String zielId, Selbstaktion aktion) {
        Objects.requireNonNull(aktion, "aktion");
        Objects.requireNonNull(zielId, "zielId");
        if (!Rechtematrix.ergebnis(akteur.rolle(), Aktion.A22).zugaenglich()) {
            return Pruefung.NICHT_ERLAUBT;
        }
        return akteur.id().equals(zielId) ? Pruefung.ESKALATION_VERBOTEN : Pruefung.ERLAUBT;
    }

    public static boolean entferntLetztenAktivenInhaber(Rolle zielRolle, boolean zielAktiv,
                                                         int anzahlAktiveInhaber) {
        if (anzahlAktiveInhaber < 0) {
            throw new IllegalArgumentException("Anzahl aktiver Inhaber ist negativ");
        }
        return zielRolle == Rolle.INHABER && zielAktiv && anzahlAktiveInhaber <= 1;
    }
}
