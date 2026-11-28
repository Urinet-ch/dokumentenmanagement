package ch.urinet.archiv.gemeinsam.domain;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Begrenzte JDK-Regex-Auswertung nach CPU-Zeit des aufrufenden Threads. */
public final class RegexZeitgrenze {

    private static final int PRUEF_INTERVALL = 1024;

    private RegexZeitgrenze() {
    }

    public static Ergebnis pruefen(Pattern muster, String text, Duration grenze, List<String> gruppen) {
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        if (threads.isCurrentThreadCpuTimeSupported()) {
            if (!threads.isThreadCpuTimeEnabled()) {
                threads.setThreadCpuTimeEnabled(true);
            }
            return pruefen(muster, text, grenze, gruppen, threads::getCurrentThreadCpuTime);
        }
        return pruefen(muster, text, grenze, gruppen, System::nanoTime);
    }

    public static Ergebnis pruefen(Pattern muster, String text, Duration grenze, List<String> gruppen,
                                   LongSupplier uhr) {
        return pruefen(muster, text, gruppen, uhr, uhr.getAsLong() + grenze.toNanos());
    }

    public static Ergebnis pruefen(Pattern muster, String text, List<String> gruppen, LongSupplier uhr, long ende) {
        try {
            if (uhr.getAsLong() > ende) {
                return new Ergebnis(false, true, Map.of());
            }
            Matcher matcher = muster.matcher(new TextMitFrist(text, uhr, ende));
            boolean trifft = matcher.matches();
            if (uhr.getAsLong() > ende) {
                return new Ergebnis(false, true, Map.of());
            }
            if (!trifft) {
                return new Ergebnis(false, false, Map.of());
            }
            Map<String, String> werte = new LinkedHashMap<>();
            for (String gruppe : gruppen) {
                if (uhr.getAsLong() > ende) {
                    return new Ergebnis(false, true, Map.of());
                }
                String wert = matcher.group(gruppe);
                if (wert != null) {
                    werte.put(gruppe, wert);
                }
            }
            return uhr.getAsLong() > ende
                    ? new Ergebnis(false, true, Map.of()) : new Ergebnis(true, false, werte);
        } catch (ZeitgrenzeUeberschritten e) {
            return new Ergebnis(false, true, Map.of());
        }
    }

    public record Ergebnis(boolean trifft, boolean abgebrochen, Map<String, String> gruppen) {
        public Ergebnis {
            gruppen = Map.copyOf(gruppen);
        }
    }

    private static final class ZeitgrenzeUeberschritten extends RuntimeException {
        private ZeitgrenzeUeberschritten() {
            super(null, null, false, false);
        }
    }

    private static final class TextMitFrist implements CharSequence {
        private final CharSequence text;
        private final LongSupplier uhr;
        private final long ende;
        private int zugriffe;

        private TextMitFrist(CharSequence text, LongSupplier uhr, long ende) {
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
                throw new ZeitgrenzeUeberschritten();
            }
            return text.charAt(index);
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return new TextMitFrist(text.subSequence(start, end), uhr, ende);
        }

        @Override
        public String toString() {
            return text.toString();
        }
    }
}
