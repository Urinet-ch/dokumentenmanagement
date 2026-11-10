package ch.urinet.archiv.ablage.domain;

import java.net.URI;
import java.time.Instant;

/**
 * Zeitlich begrenzte Download-Adresse für genau eine Versions-ID (Req 14.6). Die URL ist bis {@code gueltigBis} ein
 * Zugangsschlüssel: {@link #toString()} zeigt sie nicht, damit sie in keinem Log landet.
 */
public record VorsignierteUrl(URI url, Instant gueltigBis) {

    @Override
    public String toString() {
        return "VorsignierteUrl[url=***, gueltigBis=" + gueltigBis + "]";
    }
}
