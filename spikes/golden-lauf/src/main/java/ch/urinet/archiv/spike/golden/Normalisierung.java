package ch.urinet.archiv.spike.golden;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** Normalisierung nach design.md, Abschnitt «KI» (Gate-Vergleich Req 0.7). */
public final class Normalisierung {

    private static final Set<String> RECHTSFORMEN = Set.of("ag", "gmbh", "sa", "sarl", "sagl", "kg", "klg", "verein",
            "genossenschaft", "stiftung", "ltd", "inc");

    private Normalisierung() {
    }

    public static String name(String name) {
        if (name == null) {
            return "";
        }
        String s = Normalizer.normalize(name, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
                .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss")
                .replace("&", " und ");
        s = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        s = s.replaceAll("[\\p{P}\\p{S}]", " ");
        return Arrays.stream(s.trim().split("\\s+"))
                .filter(w -> !w.isEmpty() && !RECHTSFORMEN.contains(w))
                .collect(Collectors.joining(" "));
    }

    public static String iban(String iban) {
        return iban == null ? "" : iban.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
    }
}
