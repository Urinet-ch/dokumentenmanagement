package ch.urinet.archiv.ki.application.chat;

import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.sicherheit.domain.Sichtregel;

import java.util.List;

/**
 * Lückenbericht nach den Regeltypen {@code FOLGE} (fehlende Nummern) und {@code ERWARTET} (Zeiträume ohne Dokument) aus
 * Spec 05, design.md «Regeltypen», für ein Geschäftsjahr. Driving Port für das Werkzeug {@code vollstaendigkeit}
 * (Spec 02, Req 3.6); Spec 05, Aufgabe 17.6 ersetzt die Quelle hinter diesem Port, das Werkzeug ändert sich dabei nicht.
 * Grundmenge sind die bestätigten Dokumente ({@code ARCHIVIERT}) unter der Sichtregel. Nur in einer Transaktion im
 * Mandanten.
 */
public interface Lueckenbericht {

    /** Der Bericht des Mandanten für das Geschäftsjahr; ohne Regeln des Mandanten ohne Kategorien. */
    Bericht erstellen(MandantCode mandant, int geschaeftsjahr, Sichtregel sicht);

    enum Regel {
        FOLGE,
        ERWARTET
    }

    /**
     * Eine Regel mit ihrer Art. {@code je} nennt bei {@code ERWARTET} den Zeitraum ({@code MONAT}), sonst {@code null}.
     * {@code hinweis} erklärt, weshalb bei {@code ERWARTET} keine vollen Monate geprüft werden konnten.
     * {@code belege}: Belege der Art, aus denen die Kategorie entsteht. {@code ohneZuordnung}: Belege ohne Wert im
     * Gruppierungsfeld, die keine Lücke begründen oder schliessen ({@code null}, wenn es keine gibt). {@code reihen}
     * sind höchstens {@value #REIHEN_MAX}; {@code reihenGesamt} zählt alle.
     */
    record Kategorie(Regel regel, String art, String je, String hinweis, long belege, Long ohneZuordnung,
                     long reihenGesamt, List<Reihe> reihen) {

        public static final int REIHEN_MAX = 50;

        public Kategorie {
            reihen = List.copyOf(reihen);
        }
    }

    /**
     * Eine Reihe einer Kategorie, bei Gruppierung je Person bzw. Partner ({@code gruppe}, sonst {@code null}).
     * {@code ab}: erster Zeitraum mit einem Dokument ({@code ERWARTET}, {@code JJJJ-MM}); {@code hoechste}: höchste
     * vorhandene Belegnummer ({@code FOLGE}). {@code vorhanden}: Zahl der Nummern bzw. Monate mit Dokument.
     * {@code fehlend}: die ersten fehlenden Nummern bzw. Monate ({@code JJJJ-MM}), höchstens {@value #FEHLEND_MAX};
     * {@code anzahlFehlend}: alle.
     */
    record Reihe(String gruppe, String ab, String hoechste, long vorhanden, long anzahlFehlend, List<String> fehlend) {

        public static final int FEHLEND_MAX = 100;

        public Reihe {
            fehlend = List.copyOf(fehlend);
        }
    }

    /** {@code belege}: alle bestätigten Belege der in den Regeln genannten Arten unter der Sichtregel (Datenstand). */
    record Bericht(int geschaeftsjahr, List<Kategorie> kategorien, long belege) {

        public Bericht {
            kategorien = List.copyOf(kategorien);
        }
    }
}
