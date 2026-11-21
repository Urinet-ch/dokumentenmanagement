package ch.urinet.archiv.benutzer.domain;

import ch.urinet.archiv.gemeinsam.domain.KonfigurationUngueltig;
import ch.urinet.archiv.gemeinsam.domain.KonfigurationUngueltig.Befund;

import java.util.ArrayList;
import java.util.List;

/** Formregeln für Angaben der Benutzerverwaltung (Spec 06, Requirement 3.4). */
public final class BenutzerFormregeln {
    private BenutzerFormregeln() {
    }

    public static void pruefeAnlage(String benutzername, String email, String vorname, String nachname) {
        List<Befund> befunde = new ArrayList<>();
        if (benutzername == null || !benutzername.matches("[a-z0-9._-]{3,40}")) {
            befunde.add(new Befund("benutzername", "BENUTZERNAME_UNGUELTIG",
                    "Der Benutzername muss 3 bis 40 Zeichen aus a–z, 0–9, Punkt, Unterstrich oder Bindestrich enthalten."));
        }
        if (!laengeUndSteuerzeichen(email, 1, 254)
                || email.indexOf('@') < 0 || email.indexOf('@') != email.lastIndexOf('@')) {
            befunde.add(new Befund("email", "EMAIL_UNGUELTIG",
                    "Die E-Mail-Adresse darf höchstens 254 Zeichen und muss genau ein @ enthalten."));
        }
        pruefeName(befunde, "vorname", vorname);
        pruefeName(befunde, "nachname", nachname);
        if (!befunde.isEmpty()) {
            throw new KonfigurationUngueltig(befunde);
        }
    }

    public static void pruefeGrund(String grund) {
        if (!laengeUndSteuerzeichen(grund, 10, 500)) {
            throw new KonfigurationUngueltig(List.of(new Befund("grund", "GRUND_UNGUELTIG",
                    "Der Grund muss 10 bis 500 Zeichen ohne Steuerzeichen enthalten.")));
        }
    }

    private static void pruefeName(List<Befund> befunde, String feld, String name) {
        if (!laengeUndSteuerzeichen(name, 1, 60)) {
            befunde.add(new Befund(feld, "NAME_UNGUELTIG",
                    "Der Name muss 1 bis 60 Zeichen ohne Steuerzeichen enthalten."));
        }
    }

    private static boolean laengeUndSteuerzeichen(String wert, int minimum, int maximum) {
        if (wert == null) {
            return false;
        }
        int laenge = wert.codePointCount(0, wert.length());
        return laenge >= minimum && laenge <= maximum && wert.codePoints().noneMatch(Character::isISOControl);
    }
}
