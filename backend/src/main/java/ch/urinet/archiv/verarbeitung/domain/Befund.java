package ch.urinet.archiv.verarbeitung.domain;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Befund der Überwachung (Req 16.5): Quelle, Gegenstand (Dokument-ID, Datum des Manifests, Name des Exports) und Status.
 * Der Schlüssel besteht nur aus diesen drei Teilen und enthält damit nur IDs und Status (Req 16.7); je Schlüssel geht
 * genau eine Mail. Wechselt ein Dokument den Status und steht wieder zu lange, ist das ein neuer Befund.
 */
public record Befund(Quelle quelle, String gegenstand, String status) {

    private static final Pattern GEGENSTAND = Pattern.compile("[A-Za-z0-9-]{1,64}");
    private static final Pattern STATUS = Pattern.compile("[A-Z_]{1,40}");

    public enum Quelle {
        DOKUMENT, MANIFEST, SICHERUNG;

        /** Präfix der Schlüssel dieser Quelle in der Tabelle {@code alarm}. */
        public String praefix() {
            return name().toLowerCase(Locale.ROOT) + ":";
        }
    }

    public Befund {
        Objects.requireNonNull(quelle, "quelle");
        if (gegenstand == null || !GEGENSTAND.matcher(gegenstand).matches()) {
            throw new IllegalArgumentException("Gegenstand eines Befunds nur aus Buchstaben, Ziffern und «-»");
        }
        if (status == null || !STATUS.matcher(status).matches()) {
            throw new IllegalArgumentException("Status eines Befunds nur aus Grossbuchstaben und «_»");
        }
    }

    public String schluessel() {
        return quelle.praefix() + gegenstand + ":" + status;
    }
}
