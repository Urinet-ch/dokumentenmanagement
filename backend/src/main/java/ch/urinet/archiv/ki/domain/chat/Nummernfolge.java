package ch.urinet.archiv.ki.domain.chat;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fehlende Nummern einer Folge (Spec 05, design.md «Regeltypen» {@code FOLGE} mit {@code muster: true}; Übergang in
 * Spec 02, Aufgabe 7.1c). Das Belegnummer-Muster hat die benannte Gruppe {@code nr} und optional {@code jahr}. Gezählt
 * werden nur Belegnummern, die ganz auf das Muster passen (Altbestand mit anderer Form wird ignoriert) und, wenn das
 * Muster ein {@code jahr} hat, zum verlangten Jahr gehören. Fehlend sind die Nummern zwischen {@value #BEGINN} und der
 * höchsten vorhandenen.
 */
public final class Nummernfolge {

    public static final int BEGINN = 1;
    /** Längere Belegnummern gelten als ausserhalb des Musters (Schutz vor teuren Mustern). */
    static final int LAENGE_MAX = 64;
    private static final int ZIFFERN_MAX = 9;

    private Nummernfolge() {
    }

    /**
     * @param vorhanden  Zahl der verschiedenen Nummern, die zur Folge gehören
     * @param hoechste   höchste vorhandene Belegnummer im Original, {@code null} ohne Treffer
     * @param fehlend    die ersten fehlenden Belegnummern, höchstens {@code hoechstens}, in der Form der vorhandenen
     * @param fehlendGesamt Zahl aller fehlenden Nummern
     */
    public record Ergebnis(long vorhanden, String hoechste, List<String> fehlend, long fehlendGesamt) {

        public Ergebnis {
            fehlend = List.copyOf(fehlend);
        }
    }

    public static Ergebnis pruefen(Pattern muster, Integer jahr, Collection<String> belegnummern, int hoechstens) {
        TreeSet<Integer> nummern = new TreeSet<>();
        String beispiel = null;
        int breite = 0;
        int von = 0;
        int bis = 0;
        int minimum = Integer.MAX_VALUE;
        String hoechste = null;
        for (String belegnummer : belegnummern) {
            if (belegnummer == null || belegnummer.length() > LAENGE_MAX) {
                continue;
            }
            Matcher m = muster.matcher(belegnummer);
            if (!m.matches()) {
                continue;
            }
            String nr = m.group("nr");
            if (nr == null || nr.isEmpty() || nr.length() > ZIFFERN_MAX || !nr.chars().allMatch(Character::isDigit)) {
                continue;
            }
            if (jahr != null && hatGruppe(muster, "jahr") && !String.valueOf(jahr).equals(m.group("jahr"))) {
                continue;
            }
            int wert = Integer.parseInt(nr);
            if (nummern.isEmpty() || wert > nummern.last()) {
                hoechste = belegnummer;
            }
            nummern.add(wert);
            if (wert < minimum || wert == minimum && (nr.length() < breite
                    || nr.length() == breite && belegnummer.compareTo(beispiel) < 0)) {
                minimum = wert;
                beispiel = belegnummer;
                breite = nr.length();
                von = m.start("nr");
                bis = m.end("nr");
            }
        }
        if (nummern.isEmpty()) {
            return new Ergebnis(0, null, List.of(), 0);
        }
        int hoechsteNr = nummern.last();
        long fehlendGesamt = (long) hoechsteNr - BEGINN + 1 - nummern.size();
        List<String> fehlend = new ArrayList<>();
        for (int n = BEGINN; n <= hoechsteNr && fehlend.size() < hoechstens && fehlend.size() < fehlendGesamt; n++) {
            if (!nummern.contains(n)) {
                String ziffern = String.valueOf(n);
                fehlend.add(beispiel.substring(0, von) + "0".repeat(Math.max(0, breite - ziffern.length())) + ziffern
                        + beispiel.substring(bis));
            }
        }
        return new Ergebnis(nummern.size(), hoechste, fehlend, fehlendGesamt);
    }

    /** Hat das Muster die benannte Gruppe? */
    public static boolean hatGruppe(Pattern muster, String name) {
        return muster.namedGroups().containsKey(name);
    }
}
