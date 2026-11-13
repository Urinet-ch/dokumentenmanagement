package ch.urinet.archiv.ki.domain;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.math.BigInteger;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Merkmale der Vorauswahl der Korrekturbeispiele (Req 7.9, design.md «KI», {@code BeispielVorauswahl} Schritt 1):
 * IBAN (Prüfziffer mod 97, auch die Kreditor-IBAN des Swiss QR Codes), UID, E-Mail-Adressen und die Belegnummer-Muster,
 * die ein Wort des Belegs treffen. Werte normalisiert (IBAN gross ohne Leerzeichen, E-Mail klein, Muster als Text des
 * regulären Ausdrucks), je Typ höchstens {@value #MAX_JE_TYP} in der Reihenfolge des Vorkommens. Die eigenen Kennungen
 * des Mandanten zählen nicht, sie stehen auf fast jedem Beleg; die eigene UID wird nur über ihre 9 Ziffern verglichen
 * (Schreibweise und Zusatz wie «MWST» egal). Alle Ausdrücke laufen in linearer Zeit zur Textlänge: Die E-Mail beginnt
 * nur am Anfang einer Folge von Zeichen des lokalen Teils und liest diese possessiv.
 */
public record Merkmale(Set<String> ibans, Set<String> uids, Set<String> emails, Set<String> muster) {

    public static final int MAX_JE_TYP = 10;
    public static final Merkmale KEINE = new Merkmale(Set.of(), Set.of(), Set.of(), Set.of());

    private static final Pattern IBAN = Pattern.compile("\\b[A-Z]{2}\\d{2}(?: ?[A-Z0-9]){11,30}");
    private static final Pattern UID = Pattern.compile("CHE-\\d{3}\\.\\d{3}\\.\\d{3}");
    private static final Pattern EMAIL = Pattern.compile(
            "(?<![A-Za-z0-9._%+-])[A-Za-z0-9._%+-]++@[A-Za-z0-9-]++(?:\\.[A-Za-z0-9-]++)*+(?<=\\.[A-Za-z]{2,63})");
    private static final Pattern WORT = Pattern.compile("[\\s,;:()]+");
    /** Länge der IBAN der häufigen Länder; sonst gilt die längste gültige. */
    private static final Map<String, Integer> IBAN_LAENGE = Map.of("CH", 21, "LI", 21, "DE", 22, "AT", 20,
            "FR", 27, "IT", 27);
    private static final int IBAN_MIN = 15;
    private static final int IBAN_MAX = 34;
    /** Wie {@code NetworkntZusatzSchemaPruefer}: die Uhr wird nur bei jedem 1024. Zeichen gelesen. */
    private static final int PRUEF_INTERVALL = 1024;

    public Merkmale {
        ibans = Set.copyOf(Objects.requireNonNull(ibans, "ibans"));
        uids = Set.copyOf(Objects.requireNonNull(uids, "uids"));
        emails = Set.copyOf(Objects.requireNonNull(emails, "emails"));
        muster = Set.copyOf(Objects.requireNonNull(muster, "muster"));
    }

    public boolean leer() {
        return ibans.isEmpty() && uids.isEmpty() && emails.isEmpty() && muster.isEmpty();
    }

    /** Anzahl übereinstimmender Werte über alle Typen (Rang der Vorauswahl). */
    public int treffer(Merkmale andere) {
        return gemeinsam(ibans, andere.ibans) + gemeinsam(uids, andere.uids) + gemeinsam(emails, andere.emails)
                + gemeinsam(muster, andere.muster);
    }

    private static int gemeinsam(Set<String> a, Set<String> b) {
        return (int) a.stream().filter(b::contains).count();
    }

    /**
     * Grenze für die Auswertung der Belegnummer-Muster ({@code archiv.dokumentart.muster-zeitgrenze}); {@code uhr}
     * liefert Nanosekunden, Standard ist die CPU-Zeit des Threads (wie die Prüfung der Zusatz-Schemas).
     */
    public record Frist(Duration grenze, LongSupplier uhr) {
        public Frist {
            Objects.requireNonNull(grenze, "grenze");
            Objects.requireNonNull(uhr, "uhr");
        }

        public static Frist cpu(Duration grenze) {
            return new Frist(grenze, cpuUhr());
        }

        private static LongSupplier cpuUhr() {
            ThreadMXBean threads = ManagementFactory.getThreadMXBean();
            if (threads.isCurrentThreadCpuTimeSupported()) {
                if (!threads.isThreadCpuTimeEnabled()) {
                    threads.setThreadCpuTimeEnabled(true);
                }
                return threads::getCurrentThreadCpuTime;
            }
            return System::nanoTime;
        }
    }

    /** Merkmale und die Nummern (Position in der Liste) der Muster, die die Frist überschritten. */
    public record Ergebnis(Merkmale merkmale, List<Integer> abgebrochen) {
        public Ergebnis {
            Objects.requireNonNull(merkmale, "merkmale");
            abgebrochen = List.copyOf(abgebrochen);
        }
    }

    /**
     * Merkmale aus dem Belegtext und der Kreditor-IBAN des Swiss QR Codes. Ein Muster, das die Frist überschreitet,
     * gilt als «trifft nicht zu» (Spec 05, Req 2.4) und steht mit seiner Nummer im Ergebnis, damit der Aufrufer es
     * ohne Inhalte protokolliert.
     *
     * @param eigeneUid   UID des Mandanten oder {@code null}
     * @param eigeneIbans IBAN des Mandanten
     */
    public static Ergebnis erkennen(String text, Optional<String> qrIban, String eigeneUid,
                                    Collection<String> eigeneIbans, List<Pattern> muster, Frist frist) {
        String t = text == null ? "" : text;
        Set<String> eigene = new LinkedHashSet<>();
        eigeneIbans.forEach(i -> eigene.add(ibanNormal(i)));
        Set<String> ibans = new LinkedHashSet<>();
        qrIban.map(Merkmale::ibanNormal).filter(Merkmale::ibanGueltig).filter(i -> !eigene.contains(i))
                .ifPresent(ibans::add);
        Matcher m = IBAN.matcher(t);
        while (m.find() && ibans.size() < MAX_JE_TYP) {
            iban(ibanNormal(m.group())).filter(i -> !eigene.contains(i)).ifPresent(ibans::add);
        }
        String uidEigen = ziffern(eigeneUid);
        Set<String> uids = new LinkedHashSet<>();
        m = UID.matcher(t);
        while (m.find() && uids.size() < MAX_JE_TYP) {
            if (!ziffern(m.group()).equals(uidEigen)) {
                uids.add(m.group());
            }
        }
        Set<String> emails = new LinkedHashSet<>();
        m = EMAIL.matcher(t);
        while (m.find() && emails.size() < MAX_JE_TYP) {
            emails.add(m.group().toLowerCase(Locale.ROOT));
        }
        Set<String> treffer = new LinkedHashSet<>();
        List<Integer> abgebrochen = new ArrayList<>();
        String[] woerter = WORT.split(t.strip());
        for (int nr = 0; nr < muster.size() && treffer.size() < MAX_JE_TYP; nr++) {
            switch (trifft(muster.get(nr), woerter, frist)) {
                case JA -> treffer.add(muster.get(nr).pattern());
                case ABGEBROCHEN -> abgebrochen.add(nr);
                case NEIN -> {
                }
            }
        }
        return new Ergebnis(new Merkmale(ibans, uids, emails, treffer), abgebrochen);
    }

    private enum Auswertung { JA, NEIN, ABGEBROCHEN }

    private static Auswertung trifft(Pattern muster, String[] woerter, Frist frist) {
        long ende = frist.uhr().getAsLong() + frist.grenze().toNanos();
        try {
            for (String wort : woerter) {
                if (frist.uhr().getAsLong() > ende) {
                    return Auswertung.ABGEBROCHEN;
                }
                if (!wort.isEmpty() && muster.matcher(new MitFrist(wort, frist.uhr(), ende)).matches()) {
                    return Auswertung.JA;
                }
            }
            return Auswertung.NEIN;
        } catch (FristAbgelaufen e) {
            return Auswertung.ABGEBROCHEN;
        }
    }

    /** Die 9 Ziffern einer UID; leer, wenn es nicht genau 9 sind. */
    private static String ziffern(String uid) {
        String z = uid == null ? "" : uid.replaceAll("\\D", "");
        return z.length() == 9 ? z : "";
    }

    /** IBAN normalisiert: gross, ohne Leerzeichen. */
    public static String ibanNormal(String iban) {
        return iban == null ? "" : iban.replace(" ", "").strip().toUpperCase(Locale.ROOT);
    }

    /** Prüfziffer nach ISO 13616 (mod 97 = 1). */
    public static boolean ibanGueltig(String iban) {
        if (iban.length() < IBAN_MIN || iban.length() > IBAN_MAX || !iban.matches("[A-Z]{2}\\d{2}[A-Z0-9]+")) {
            return false;
        }
        String umgestellt = iban.substring(4) + iban.substring(0, 4);
        StringBuilder ziffern = new StringBuilder();
        for (char c : umgestellt.toCharArray()) {
            ziffern.append(Character.isDigit(c) ? String.valueOf(c) : String.valueOf(c - 'A' + 10));
        }
        return new BigInteger(ziffern.toString()).mod(BigInteger.valueOf(97)).intValue() == 1;
    }

    /** Gültige IBAN am Anfang des Kandidaten: bekannte Länge des Landes, sonst die längste gültige. */
    private static Optional<String> iban(String kandidat) {
        Integer laenge = IBAN_LAENGE.get(kandidat.substring(0, 2));
        if (laenge != null) {
            return kandidat.length() >= laenge && ibanGueltig(kandidat.substring(0, laenge))
                    ? Optional.of(kandidat.substring(0, laenge)) : Optional.empty();
        }
        for (int l = Math.min(IBAN_MAX, kandidat.length()); l >= IBAN_MIN; l--) {
            if (ibanGueltig(kandidat.substring(0, l))) {
                return Optional.of(kandidat.substring(0, l));
            }
        }
        return Optional.empty();
    }

    private static final class FristAbgelaufen extends RuntimeException {
        FristAbgelaufen() {
            super(null, null, false, false);
        }
    }

    /** Text, der beim Lesen die Frist prüft, damit auch ein langsames Muster nach der Grenze abbricht. */
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
