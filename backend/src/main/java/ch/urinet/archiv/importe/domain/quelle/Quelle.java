package ch.urinet.archiv.importe.domain.quelle;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Eintrag im Quellenregister (Spec 09, 10.1). Die Konfiguration ist ein JSON-Objekt als Text; das Geheimnis liegt nur
 * verschlüsselt in der Datenbank und wird nie zurückgegeben, darum hier nur, ob es gesetzt ist.
 */
public record Quelle(UUID id, String code, String name, QuellenTyp typ, String konfiguration, QuellenStatus status,
                     Optional<String> bemerkung, boolean geheimnisGesetzt, Optional<QuellLauf> letzterLauf,
                     int version, Optional<Instant> geaendert) {

    /** Code wie die übrigen Codes des Mandanten: Kleinbuchstaben, Ziffern, Bindestrich. */
    public static final Pattern CODE = Pattern.compile("^[a-z0-9][a-z0-9-]{0,39}$");
    public static final int NAME_MAX = 100;
    public static final int BEMERKUNG_MAX = 1000;
    public static final int KONFIGURATION_MAX = 4000;

    public static boolean codeGueltig(String code) {
        return code != null && CODE.matcher(code).matches();
    }

    public static boolean nameGueltig(String name) {
        return name != null && !name.isBlank() && name.length() <= NAME_MAX && name.chars().noneMatch(c -> c < 0x20);
    }

    public static boolean bemerkungGueltig(String bemerkung) {
        return bemerkung == null || bemerkung.length() <= BEMERKUNG_MAX;
    }
}
