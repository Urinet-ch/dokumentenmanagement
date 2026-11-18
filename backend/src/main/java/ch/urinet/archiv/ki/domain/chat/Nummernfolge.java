package ch.urinet.archiv.ki.domain.chat;

import ch.urinet.archiv.ki.domain.Merkmale;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.function.LongSupplier;
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
    public static final int BELEGNUMMERN_MAX = 10_000;
    private static final int ZIFFERN_MAX = 9;
    private static final int PRUEF_INTERVALL = 1024;
    private static final Duration STANDARD_ZEITGRENZE = Duration.ofMillis(50);

    private Nummernfolge() {
    }

    public record Frist(Duration grenze, LongSupplier uhr) {
        public Frist {
            Objects.requireNonNull(grenze, "grenze");
            Objects.requireNonNull(uhr, "uhr");
            if (grenze.isNegative() || grenze.isZero()) {
                throw new IllegalArgumentException("Zeitgrenze muss grösser als 0 sein");
            }
            grenze.toNanos();
        }

        public static Frist cpu(Duration grenze) {
            Merkmale.Frist frist = Merkmale.Frist.cpu(grenze);
            return new Frist(frist.grenze(), frist.uhr());
        }
    }

    public static final class ZeitgrenzeUeberschritten extends RuntimeException {
        public ZeitgrenzeUeberschritten() {
            super(null, null, false, false);
        }
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
        return pruefen(muster, jahr, belegnummern, BEGINN, hoechstens);
    }

    public static Ergebnis pruefen(Pattern muster, Integer jahr, Collection<String> belegnummern, int beginn,
                                   int hoechstens) {
        return pruefen(muster, jahr, belegnummern, beginn, hoechstens, Frist.cpu(STANDARD_ZEITGRENZE));
    }

    public static Ergebnis pruefen(Pattern muster, Integer jahr, Collection<String> belegnummern, int beginn,
                                   int hoechstens, Frist frist) {
        if (belegnummern.size() > BELEGNUMMERN_MAX) {
            throw new IllegalArgumentException("Zu viele Belegnummern für die Folgenprüfung");
        }
        long start = frist.uhr().getAsLong();
        long ende = start + frist.grenze().toNanos();
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
            Matcher m = matcher(muster, belegnummer, frist.uhr(), ende);
            if (m == null) {
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
            if (wert < beginn) {
                continue;
            }
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
        long fehlendGesamt = (long) hoechsteNr - beginn + 1 - nummern.tailSet(beginn).size();
        List<String> fehlend = new ArrayList<>();
        for (int n = beginn; n <= hoechsteNr && fehlend.size() < hoechstens && fehlend.size() < fehlendGesamt; n++) {
            if (!nummern.contains(n)) {
                String ziffern = String.valueOf(n);
                fehlend.add(beispiel.substring(0, von) + "0".repeat(Math.max(0, breite - ziffern.length())) + ziffern
                        + beispiel.substring(bis));
            }
        }
        return new Ergebnis(nummern.size(), hoechste, fehlend, fehlendGesamt);
    }

    private static Matcher matcher(Pattern muster, String belegnummer, LongSupplier uhr, long ende) {
        if (uhr.getAsLong() > ende) {
            throw new ZeitgrenzeUeberschritten();
        }
        Matcher m = muster.matcher(new MitFrist(belegnummer, uhr, ende));
        try {
            return m.matches() ? m : null;
        } catch (FristAbgelaufen e) {
            throw new ZeitgrenzeUeberschritten();
        }
    }

    /** Hat das Muster die benannte Gruppe? */
    public static boolean hatGruppe(Pattern muster, String name) {
        return muster.namedGroups().containsKey(name);
    }

    private static final class FristAbgelaufen extends RuntimeException {
        FristAbgelaufen() {
            super(null, null, false, false);
        }
    }

    private static final class MitFrist implements CharSequence {
        private final CharSequence text;
        private final LongSupplier uhr;
        private final long ende;
        private int zugriffe;

        MitFrist(CharSequence text, LongSupplier uhr, long ende) {
            this.text = text;
            this.uhr = uhr;
            this.ende = ende;
        }

        @Override
        public int length() {
            return text.length();
        }

        @Override
        public char charAt(int index) {
            if (++zugriffe % PRUEF_INTERVALL == 0 && uhr.getAsLong() > ende) {
                throw new FristAbgelaufen();
            }
            return text.charAt(index);
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return new MitFrist(text.subSequence(start, end), uhr, ende);
        }

        @Override
        public String toString() {
            return text.toString();
        }
    }
}
