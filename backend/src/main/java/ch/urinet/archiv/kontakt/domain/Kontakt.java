package ch.urinet.archiv.kontakt.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Kontakt eines Mandanten (design.md Data Models, {@code kontakt}). E-Mail, UID, IBAN und PLZ stehen normalisiert
 * ({@link KontaktAngaben}); {@code version} dient If-Match/ETag. Ein inaktiver Kontakt ({@code aktiv = false}, seit
 * {@code inaktivSeit}, bei einem Zusammenführen mit {@code zusammengefuehrtIn}) ist kein Ziel neuer Zuordnungen
 * (Req 17.12).
 */
public record Kontakt(UUID id, String crmId, KontaktTyp typ, String name, String vorname, String strasse, String plz,
                      String ort, String email, String telefon, String uid, String iban, String kategorie, String quelle,
                      boolean nichtImExport, Instant erstellt, Instant geaendert, int version, boolean aktiv,
                      Instant inaktivSeit, UUID zusammengefuehrtIn) {

    /** Quelle eines von Hand angelegten Kontakts. */
    public static final String QUELLE_MANUELL = "MANUELL";

    public Kontakt {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(typ, "typ");
        Objects.requireNonNull(name, "name");
        if (aktiv != (inaktivSeit == null) || aktiv && zusammengefuehrtIn != null) {
            throw new IllegalArgumentException("aktiv, inaktivSeit und zusammengefuehrtIn passen nicht zusammen");
        }
    }

    /** Aktiver Kontakt (Bestand vor Req 17). */
    public Kontakt(UUID id, String crmId, KontaktTyp typ, String name, String vorname, String strasse, String plz,
                   String ort, String email, String telefon, String uid, String iban, String kategorie, String quelle,
                   boolean nichtImExport, Instant erstellt, Instant geaendert, int version) {
        this(id, crmId, typ, name, vorname, strasse, plz, ort, email, telefon, uid, iban, kategorie, quelle,
                nichtImExport, erstellt, geaendert, version, true, null, null);
    }

    public KontaktAngaben angaben() {
        return new KontaktAngaben(typ, name, vorname, strasse, plz, ort, email, telefon, uid, iban, kategorie);
    }
}
