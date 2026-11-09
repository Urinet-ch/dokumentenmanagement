package ch.urinet.archiv.spike.golden;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;

/** Req 16.22: Der Golden-Lauf misst nur gegen Infomaniak (https, Host exakt api.infomaniak.com). */
public final class Hostpruefung {

    static final String HOST = "api.infomaniak.com";
    static final List<String> SCHLUESSEL = List.of("archiv.ki.basis-url", "quarkus.langchain4j.openai.base-url");

    private Hostpruefung() {
    }

    public static boolean zulaessig(String basisUrl) {
        if (basisUrl == null || basisUrl.isBlank()) {
            return false;
        }
        try {
            URI uri = new URI(basisUrl.strip());
            return "https".equals(uri.getScheme())
                    && uri.getRawUserInfo() == null
                    && uri.getHost() != null
                    && HOST.equals(uri.getHost().toLowerCase(Locale.ROOT));
        } catch (URISyntaxException e) {
            return false;
        }
    }

    /** Bricht ab, bevor ein Beleg verarbeitet wird; die Meldung nennt nur den Host. */
    public static void pruefe(String basisUrl) {
        pruefe("archiv.ki.basis-url", basisUrl);
    }

    /**
     * Prüft alle Schlüssel, aus denen eine KI-Basis-URL kommen kann, auch die tatsächlich vom Client verwendete
     * ({@code quarkus.langchain4j.openai.base-url}); fehlt ein Wert, gilt er als abgelehnt.
     */
    public static void pruefeKonfiguration(Function<String, Optional<String>> lesen) {
        for (String schluessel : SCHLUESSEL) {
            pruefe(schluessel, lesen.apply(schluessel).orElse(null));
        }
    }

    private static void pruefe(String schluessel, String basisUrl) {
        if (!zulaessig(basisUrl)) {
            String host;
            try {
                host = String.valueOf(new URI(basisUrl == null ? "" : basisUrl.strip()).getHost());
            } catch (URISyntaxException e) {
                host = "ungültige URL";
            }
            throw new IllegalStateException(schluessel + " muss per https auf " + HOST
                    + " zeigen (Req 16.22); abgelehnt: " + host);
        }
    }
}
