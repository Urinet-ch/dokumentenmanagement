package ch.urinet.archiv.ki.domain;

import ch.urinet.archiv.gemeinsam.domain.RegexZeitgrenze;

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
 * (Schreibweise und Zusatz wie «MWST» egal). Diese 9 Ziffern löst das Muster {@code CHE} mit drei Dreiergruppen
 * (Gross/Klein egal, Trenner {@code -}, {@code .} oder Leerzeichen) aus {@code kennungen.uid}, auch wenn dort weitere
 * Ziffern stehen (z. B. «CHE-123.456.789 MWST, HR 2020»); ohne Treffer zählen alle Ziffern des Werts und nur genau 9
 * ergeben eine eigene UID (Nachprüfung PR #109). Alle Ausdrücke laufen in linearer Zeit zur Textlänge: Die E-Mail
 * beginnt nur am Anfang einer Folge von Zeichen des lokalen Teils und liest diese possessiv; das UID-Muster hat nur
 * Quantoren fester Länge und optionale Einzelzeichen, also keine Rückverfolgung über mehr als 15 Zeichen.
 */
public record Merkmale(Set<String> ibans, Set<String> uids, Set<String> emails, Set<String> muster) {

    public static final int MAX_JE_TYP = 10;
    public static final Merkmale KEINE = new Merkmale(Set.of(), Set.of(), Set.of(), Set.of());

    private static final Pattern IBAN = Pattern.compile("\\b[A-Z]{2}\\d{2}(?: ?[A-Z0-9]){11,30}");
    private static final Pattern UID = Pattern.compile("CHE-\\d{3}\\.\\d{3}\\.\\d{3}");
    /** Eigene UID in {@code kennungen.uid}: Schreibweise frei, weitere Ziffern danach (z. B. Handelsregister) egal. */
    private static final Pattern UID_EIGEN = Pattern.compile("(?i)CHE[-. ]?(\\d{3})[. ]?(\\d{3})[. ]?(\\d{3})");
    private static final Pattern EMAIL = Pattern.compile(
            "(?<![A-Za-z0-9._%+-])[A-Za-z0-9._%+-]++@[A-Za-z0-9-]++(?:\\.[A-Za-z0-9-]++)*+(?<=\\.[A-Za-z]{2,63})");
    private static final Pattern WORT = Pattern.compile("[\\s,;:()]+");
    /** Länge der IBAN der häufigen Länder; sonst gilt die längste gültige. */
    private static final Map<String, Integer> IBAN_LAENGE = Map.of("CH", 21, "LI", 21, "DE", 22, "AT", 20,
            "FR", 27, "IT", 27);
    private static final int IBAN_MIN = 15;
    private static final int IBAN_MAX = 34;

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

        public long ende() {
            return uhr.getAsLong() + grenze.toNanos();
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

    public record MusterErgebnis(boolean trifftZu, boolean abgebrochen) {
    }

    /** Prüft ein ganzes Feld mit derselben Zeitgrenze, die auch für die Beispielmerkmale gilt. */
    public static MusterErgebnis musterTrifft(Pattern muster, String text, Frist frist, long ende) {
        RegexZeitgrenze.Ergebnis ergebnis = RegexZeitgrenze.pruefen(muster, text, List.of(), frist.uhr(), ende);
        return new MusterErgebnis(ergebnis.trifft(), ergebnis.abgebrochen());
    }

    /** Kompiliert ein validiertes Konfigurationsmuster erst innerhalb seiner Laufzeitgrenze. */
    public static MusterErgebnis musterTrifft(String muster, String text, Frist frist, long ende) {
        if (frist.uhr().getAsLong() > ende) {
            return new MusterErgebnis(false, true);
        }
        Pattern kompiliert = Pattern.compile(muster);
        if (frist.uhr().getAsLong() > ende) {
            return new MusterErgebnis(false, true);
        }
        return musterTrifft(kompiliert, text, frist, ende);
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
                                    Collection<String> eigeneIbans, List<String> muster, Frist frist) {
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
            // Zeitgrenze je Muster (Spec 05, Req 2.4): ein abgebrochenes Muster trifft nicht zu, die übrigen laufen weiter
            switch (trifft(muster.get(nr), woerter, frist, frist.ende())) {
                case JA -> treffer.add(muster.get(nr));
                case ABGEBROCHEN -> {
                    abgebrochen.add(nr + 1);
                }
                case NEIN -> {
                }
            }
        }
        return new Ergebnis(new Merkmale(ibans, uids, emails, treffer), abgebrochen);
    }

    private enum Auswertung { JA, NEIN, ABGEBROCHEN }

    private static Auswertung trifft(String muster, String[] woerter, Frist frist, long ende) {
        if (frist.uhr().getAsLong() > ende) {
            return Auswertung.ABGEBROCHEN;
        }
        Pattern kompiliert = Pattern.compile(muster);
        if (frist.uhr().getAsLong() > ende) {
            return Auswertung.ABGEBROCHEN;
        }
        for (String wort : woerter) {
            if (frist.uhr().getAsLong() > ende) {
                return Auswertung.ABGEBROCHEN;
            }
            if (!wort.isEmpty()) {
                RegexZeitgrenze.Ergebnis auswertung = RegexZeitgrenze.pruefen(kompiliert, wort, List.of(),
                        frist.uhr(), ende);
                if (auswertung.abgebrochen()) {
                    return Auswertung.ABGEBROCHEN;
                }
                if (auswertung.trifft()) {
                    return Auswertung.JA;
                }
            }
        }
        return Auswertung.NEIN;
    }

    /**
     * Die 9 Ziffern einer UID: aus dem ersten Treffer von {@link #UID_EIGEN}, sonst alle Ziffern des Werts, wenn es
     * genau 9 sind; sonst leer.
     */
    private static String ziffern(String uid) {
        if (uid == null) {
            return "";
        }
        Matcher m = UID_EIGEN.matcher(uid);
        if (m.find()) {
            return m.group(1) + m.group(2) + m.group(3);
        }
        String z = uid.replaceAll("\\D", "");
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

}
