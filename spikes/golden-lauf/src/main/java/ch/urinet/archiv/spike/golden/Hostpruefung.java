package ch.urinet.archiv.spike.golden;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/** Req 16.22: Der Golden-Lauf misst nur gegen Infomaniak (https, Host exakt api.infomaniak.com). */
public final class Hostpruefung {

    static final String HOST = "api.infomaniak.com";

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
        if (!zulaessig(basisUrl)) {
            String host;
            try {
                host = String.valueOf(new URI(basisUrl == null ? "" : basisUrl.strip()).getHost());
            } catch (URISyntaxException e) {
                host = "ungültige URL";
            }
            throw new IllegalStateException("archiv.ki.basis-url muss per https auf " + HOST
                    + " zeigen (Req 16.22); abgelehnt: " + host);
        }
    }
}
