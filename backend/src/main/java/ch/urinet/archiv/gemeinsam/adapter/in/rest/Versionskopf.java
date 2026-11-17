package ch.urinet.archiv.gemeinsam.adapter.in.rest;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Versionskopf {
    private static final Pattern VERSION = Pattern.compile("^\"(\\d{1,9})\"$");

    private Versionskopf() {
    }

    public static int version(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new FachFehler(Fehlercode.VERSION_FEHLT);
        }
        Matcher treffer = VERSION.matcher(ifMatch.strip());
        if (!treffer.matches()) {
            throw new FachFehler(Fehlercode.VERSION_KONFLIKT);
        }
        return Integer.parseInt(treffer.group(1));
    }
}
