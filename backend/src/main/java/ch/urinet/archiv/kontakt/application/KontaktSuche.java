package ch.urinet.archiv.kontakt.application;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Anfrage der Kontaktliste (Req 17.1, 17.2; design.md «Kontakte ansehen und pflegen»), schon normalisiert. Ohne
 * Suchbegriff ist {@code text} {@code null}; sonst sind {@code name} ({@code name_normalisiert}), {@code text} (klein,
 * für Vorname und Ort), {@code email}, {@code plz} und {@code crmId} je wie gespeichert normalisiert und dürfen leer
 * sein. {@code quelle} und {@code nichtImExport} filtern nur, wenn gesetzt.
 */
public record KontaktSuche(String text, String name, String email, String plz, String crmId, BigDecimal schwelle,
                           Status status, String quelle, Boolean nichtImExport, int seite, int groesse) {

    /** Filter nach Status; Standard {@code AKTIV}. */
    public enum Status {
        AKTIV, INAKTIV, ALLE
    }

    public KontaktSuche {
        Objects.requireNonNull(status, "status");
        if (text != null) {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(email, "email");
            Objects.requireNonNull(plz, "plz");
            Objects.requireNonNull(crmId, "crmId");
            Objects.requireNonNull(schwelle, "schwelle");
        }
        if (seite < 0 || groesse < 1) {
            throw new IllegalArgumentException("seite ab 0, groesse ab 1");
        }
    }

    public boolean mitText() {
        return text != null;
    }
}
