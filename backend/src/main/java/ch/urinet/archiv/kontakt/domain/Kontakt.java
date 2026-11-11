package ch.urinet.archiv.kontakt.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Kontakt eines Mandanten (design.md Data Models, {@code kontakt}). E-Mail, UID, IBAN und PLZ stehen normalisiert
 * ({@link KontaktAngaben}); {@code version} dient If-Match/ETag.
 */
public record Kontakt(UUID id, String crmId, KontaktTyp typ, String name, String vorname, String strasse, String plz,
                      String ort, String email, String telefon, String uid, String iban, String kategorie, String quelle,
                      boolean nichtImExport, Instant erstellt, Instant geaendert, int version) {

    /** Quelle eines von Hand angelegten Kontakts. */
    public static final String QUELLE_MANUELL = "MANUELL";

    public Kontakt {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(typ, "typ");
        Objects.requireNonNull(name, "name");
    }

    public KontaktAngaben angaben() {
        return new KontaktAngaben(typ, name, vorname, strasse, plz, ort, email, telefon, uid, iban, kategorie);
    }
}
