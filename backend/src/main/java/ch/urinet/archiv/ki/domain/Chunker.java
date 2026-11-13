package ch.urinet.archiv.ki.domain;

import ch.urinet.archiv.gemeinsam.domain.Seitentext;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Zerlegt ein Dokument in Abschnitte für die Embeddings (Spec 02, Req 2.1; design.md «Embeddings und Hybridsuche»):
 * Absätze (an Leerzeilen getrennt) zusammenfassen bis höchstens {@link #MAX_ZEICHEN} Zeichen Inhalt, jeder weitere
 * Abschnitt beginnt mit bis zu {@link #UEBERLAPPUNG} Zeichen vom Ende des vorigen (an einer Wortgrenze). Jeder Abschnitt
 * trägt die Kopfzeile «Titel – Art – Partner» (leere Teile entfallen); sie zählt nicht zu den {@link #MAX_ZEICHEN}.
 * Ein Absatz über {@link #MAX_ABSATZ} Zeichen wird an Leerraum geteilt. Leerraum innerhalb eines Absatzes wird zu einem
 * Leerzeichen.
 * <p>
 * Seitenbereich (Spec 03, Req 5.7): aus den Seitentrennern über {@link Seitentext#von(String, Integer)}; ohne Trenner,
 * bei {@code pdfa_fehlt} oder wenn die Zahl der Seiten nicht {@code dokument.seiten} entspricht, ohne Seitenbereich. Ein
 * Absatz gehört immer zu genau einer Seite (Absätze werden an Seitengrenzen getrennt).
 */
public final class Chunker {

    public static final int MAX_ZEICHEN = 3000;
    public static final int UEBERLAPPUNG = 400;
    static final String TRENNER = "\n\n";
    /** Längster Absatz: Überlappung, Trenner und Absatz passen zusammen in {@link #MAX_ZEICHEN}. */
    public static final int MAX_ABSATZ = MAX_ZEICHEN - UEBERLAPPUNG - TRENNER.length();

    private static final Pattern LEERZEILE = Pattern.compile("\\n[ \\t\\x0B\\r\\u00A0]*\\n");
    private static final Pattern LEERRAUM = Pattern.compile("[\\s\\u00A0]+");

    private Chunker() {
    }

    /** Was der Chunker vom Dokument braucht; {@code seiten} ist {@code dokument.seiten}. */
    public record Quelle(String titel, String art, String partner, String volltext, Integer seiten, boolean pdfaFehlt) {
    }

    private record Teil(String text, Integer seite) {
    }

    /** Abschnitte ab Nummer 1; ohne Text nur die Kopfzeile, ohne beides keine. */
    public static List<Chunk> zerlegen(Quelle quelle) {
        Objects.requireNonNull(quelle, "quelle");
        String kopf = kopfzeile(quelle);
        List<List<Teil>> gruppen = gruppieren(absaetze(quelle));
        if (gruppen.isEmpty()) {
            return kopf.isEmpty() ? List.of() : List.of(new Chunk(1, kopf, null, null));
        }
        List<Chunk> chunks = new ArrayList<>();
        for (List<Teil> gruppe : gruppen) {
            String inhalt = gruppe.stream().map(Teil::text).collect(Collectors.joining(TRENNER));
            Integer von = gruppe.stream().map(Teil::seite).filter(Objects::nonNull).min(Integer::compare).orElse(null);
            Integer bis = gruppe.stream().map(Teil::seite).filter(Objects::nonNull).max(Integer::compare).orElse(null);
            chunks.add(new Chunk(chunks.size() + 1, kopf.isEmpty() ? inhalt : kopf + TRENNER + inhalt, von, bis));
        }
        return List.copyOf(chunks);
    }

    static String kopfzeile(Quelle quelle) {
        return Stream.of(quelle.titel(), quelle.art(), quelle.partner())
                .filter(Objects::nonNull)
                .map(Chunker::verdichten)
                .filter(teil -> !teil.isEmpty())
                .collect(Collectors.joining(" – "));
    }

    private static List<Teil> absaetze(Quelle quelle) {
        String volltext = quelle.volltext() == null ? "" : quelle.volltext().replace("\r\n", "\n").replace('\r', '\n');
        Optional<Seitentext> seiten = quelle.pdfaFehlt() ? Optional.empty()
                : Seitentext.von(volltext, quelle.seiten());
        List<Teil> teile = new ArrayList<>();
        if (seiten.isPresent()) {
            for (int i = 0; i < seiten.get().anzahl(); i++) {
                absaetze(seiten.get().seiten().get(i), i + 1, teile);
            }
        } else {
            absaetze(volltext.replace(String.valueOf(Seitentext.TRENNER), TRENNER), null, teile);
        }
        return teile;
    }

    private static void absaetze(String text, Integer seite, List<Teil> teile) {
        for (String roh : LEERZEILE.split(text)) {
            String absatz = verdichten(roh);
            while (absatz.length() > MAX_ABSATZ) {
                int schnitt = absatz.lastIndexOf(' ', MAX_ABSATZ);
                if (schnitt <= 0) {
                    schnitt = MAX_ABSATZ;
                    if (Character.isHighSurrogate(absatz.charAt(schnitt - 1))) {
                        schnitt--;
                    }
                    teile.add(new Teil(absatz.substring(0, schnitt), seite));
                    absatz = absatz.substring(schnitt);
                } else {
                    teile.add(new Teil(absatz.substring(0, schnitt), seite));
                    absatz = absatz.substring(schnitt + 1);
                }
            }
            if (!absatz.isEmpty()) {
                teile.add(new Teil(absatz, seite));
            }
        }
    }

    private static List<List<Teil>> gruppieren(List<Teil> absaetze) {
        List<List<Teil>> gruppen = new ArrayList<>();
        List<Teil> aktuell = new ArrayList<>();
        boolean neu = false;
        for (Teil absatz : absaetze) {
            if (neu && laenge(aktuell) + TRENNER.length() + absatz.text().length() > MAX_ZEICHEN) {
                gruppen.add(List.copyOf(aktuell));
                aktuell = ueberlappung(aktuell);
                neu = false;
            }
            aktuell.add(absatz);
            neu = true;
        }
        if (neu) {
            gruppen.add(List.copyOf(aktuell));
        }
        return gruppen;
    }

    /** Ende der Gruppe mit höchstens {@link #UEBERLAPPUNG} Zeichen, ganze Wörter, Seiten der Absätze mitgeführt. */
    private static List<Teil> ueberlappung(List<Teil> gruppe) {
        List<Teil> ende = new ArrayList<>();
        int rest = UEBERLAPPUNG;
        for (int i = gruppe.size() - 1; i >= 0; i--) {
            Teil teil = gruppe.get(i);
            int trenner = ende.isEmpty() ? 0 : TRENNER.length();
            if (teil.text().length() + trenner <= rest) {
                ende.addFirst(teil);
                rest -= teil.text().length() + trenner;
                continue;
            }
            int platz = rest - trenner;
            if (platz > 0) {
                String wortende = wortende(teil.text(), platz);
                if (!wortende.isEmpty()) {
                    ende.addFirst(new Teil(wortende, teil.seite()));
                }
            }
            break;
        }
        return ende;
    }

    /** Die letzten höchstens {@code platz} Zeichen, ohne ein angeschnittenes Wort am Anfang. */
    private static String wortende(String text, int platz) {
        int start = text.length() - platz;
        String ende = text.substring(start);
        if (text.charAt(start - 1) != ' ') {
            int leer = ende.indexOf(' ');
            ende = leer < 0 ? "" : ende.substring(leer + 1);
        }
        return ende.strip();
    }

    private static int laenge(List<Teil> teile) {
        int summe = 0;
        for (Teil teil : teile) {
            summe += teil.text().length();
        }
        return teile.isEmpty() ? 0 : summe + TRENNER.length() * (teile.size() - 1);
    }

    private static String verdichten(String text) {
        return LEERRAUM.matcher(text).replaceAll(" ").strip();
    }
}
