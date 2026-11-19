package ch.urinet.archiv.wissen.adapter.in.rest;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.wissen.application.QuellenPruefungPort.SeitenStand;

import java.util.regex.Pattern;

final class WissensseitenVersionskopf {
    private static final Pattern ETAG = Pattern.compile("\"([1-9][0-9]*)-(0|[1-9][0-9]*)\"");

    private WissensseitenVersionskopf() {
    }

    static SeitenStand lesen(String header) {
        if (header == null || header.isBlank()) {
            throw new FachFehler(Fehlercode.VERSION_FEHLT);
        }
        var matcher = ETAG.matcher(header.trim());
        if (!matcher.matches()) {
            throw new FachFehler(Fehlercode.VERSION_KONFLIKT);
        }
        try {
            return new SeitenStand(Integer.parseInt(matcher.group(1)), Long.parseLong(matcher.group(2)), true);
        } catch (NumberFormatException e) {
            throw new FachFehler(Fehlercode.VERSION_KONFLIKT);
        }
    }
}
