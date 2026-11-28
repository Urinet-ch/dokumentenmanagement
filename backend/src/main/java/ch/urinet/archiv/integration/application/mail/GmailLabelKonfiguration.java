package ch.urinet.archiv.integration.application.mail;

import ch.urinet.archiv.integration.domain.mail.Postfach;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Set;

/** Validierte Konfiguration einer Quelle vom Typ {@code GMAIL_LABEL} (Spec 09, Requirement 7.1, 7.8). */
public record GmailLabelKonfiguration(Postfach postfach, String label, LocalDate seit, boolean ohneAnhang,
                                      int maxJeLauf) {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> FELDER = Set.of("host", "port", "benutzer", "label", "seit", "ohneAnhang",
            "maxJeLauf");

    public GmailLabelKonfiguration {
        if (label == null || label.isBlank() || label.length() > 200
                || label.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Ungültiges Gmail-Label");
        }
        if (seit == null || maxJeLauf < 1 || maxJeLauf > 10_000) {
            throw new IllegalArgumentException("Ungültige Gmail-Laufgrenze");
        }
    }

    public static GmailLabelKonfiguration lesen(String json) {
        try {
            JsonNode objekt = JSON.readTree(json);
            if (objekt == null || !objekt.isObject()) {
                throw new IllegalArgumentException("Gmail-Konfiguration ist kein Objekt");
            }
            var namen = objekt.fieldNames();
            while (namen.hasNext()) {
                if (!FELDER.contains(namen.next())) {
                    throw new IllegalArgumentException("Unbekanntes Gmail-Konfigurationsfeld");
                }
            }
            String host = text(objekt, "host", "imap.gmail.com");
            int port = ganzzahl(objekt, "port", Postfach.STANDARD_PORT);
            String benutzer = text(objekt, "benutzer", null);
            String label = text(objekt, "label", null);
            String seit = text(objekt, "seit", null);
            boolean ohneAnhang = boolesch(objekt, "ohneAnhang", false);
            int maxJeLauf = ganzzahl(objekt, "maxJeLauf", 500);
            try {
                return new GmailLabelKonfiguration(new Postfach(host, port, benutzer), label,
                        LocalDate.parse(seit), ohneAnhang, maxJeLauf);
            } catch (DateTimeParseException | NullPointerException e) {
                throw new IllegalArgumentException("Ungültiges Startdatum der Gmail-Quelle");
            }
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Gmail-Konfiguration ist kein gültiges JSON");
        }
    }

    private static String text(JsonNode objekt, String feld, String standard) {
        JsonNode wert = objekt.get(feld);
        if (wert == null) {
            return standard;
        }
        if (!wert.isTextual()) {
            throw new IllegalArgumentException("Ungültiges Gmail-Konfigurationsfeld");
        }
        return wert.textValue();
    }

    private static int ganzzahl(JsonNode objekt, String feld, int standard) {
        JsonNode wert = objekt.get(feld);
        if (wert == null) {
            return standard;
        }
        if (!wert.canConvertToInt()) {
            throw new IllegalArgumentException("Ungültiges Gmail-Konfigurationsfeld");
        }
        return wert.intValue();
    }

    private static boolean boolesch(JsonNode objekt, String feld, boolean standard) {
        JsonNode wert = objekt.get(feld);
        if (wert == null) {
            return standard;
        }
        if (!wert.isBoolean()) {
            throw new IllegalArgumentException("Ungültiges Gmail-Konfigurationsfeld");
        }
        return wert.booleanValue();
    }
}
