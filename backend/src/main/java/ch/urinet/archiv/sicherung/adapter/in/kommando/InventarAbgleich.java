package ch.urinet.archiv.sicherung.adapter.in.kommando;

import ch.urinet.archiv.ablage.domain.Objektversion;
import ch.urinet.archiv.sicherung.application.SicherungBucketExport;
import ch.urinet.archiv.sicherung.application.SicherungBucketExport.InventarZeile;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/** Vergleicht zwei nach Schlüssel und Versionsreihenfolge gelesene Inventarströme, ohne Einträge zu sammeln. */
final class InventarAbgleich implements Predicate<Objektversion> {

    private static final Pattern SHA256 = Pattern.compile("^(?:[0-9a-f]{64})?$");

    private final BufferedReader vorher;
    private final ObjectMapper json;
    private InventarZeile zeile;
    private InventarZeile letzteZeile;
    private boolean ende;

    InventarAbgleich(Reader vorher, ObjectMapper json) {
        this.vorher = vorher instanceof BufferedReader gepuffert ? gepuffert : new BufferedReader(vorher);
        this.json = json;
        naechsteZeile();
    }

    @Override
    public boolean test(Objektversion aktuell) {
        while (zeile != null && SicherungBucketExport.vergleichen(aktuell, zeile) > 0) {
            naechsteZeile();
        }
        if (zeile == null || SicherungBucketExport.vergleichen(aktuell, zeile) < 0) {
            return true;
        }
        naechsteZeile();
        return false;
    }

    void vollstaendigPruefen() {
        while (zeile != null) {
            naechsteZeile();
        }
    }

    private void naechsteZeile() {
        if (ende) {
            zeile = null;
            return;
        }
        try {
            String text = vorher.readLine();
            if (text == null) {
                ende = true;
                zeile = null;
                return;
            }
            JsonNode baum = json.readTree(text);
            if (baum == null || !baum.isObject()
                    || !SicherungBucketExport.INVENTAR_FORMAT.equals(baum.path("format").asText())
                    || !baum.path("schluessel").isTextual() || !baum.path("versionId").isTextual()
                    || !baum.path("lastModified").isTextual() || !baum.path("loeschmarke").isBoolean()
                    || !baum.path("groesse").isIntegralNumber() || !baum.path("groesse").canConvertToLong()
                    || !baum.path("sha256").isTextual()) {
                throw new InventarDateiFehler("Ungültige Inventarzeile");
            }
            String schluessel = baum.path("schluessel").asText();
            if (!(schluessel.startsWith("archiv/") || schluessel.startsWith("eingang/")
                    || schluessel.startsWith("manifest/"))) {
                throw new InventarDateiFehler("Inventarschlüssel ausserhalb des Sicherungsumfangs");
            }
            zeile = new InventarZeile(SicherungBucketExport.INVENTAR_FORMAT, schluessel,
                    baum.path("versionId").asText(), baum.path("loeschmarke").asBoolean(),
                    baum.path("groesse").longValue(), baum.path("lastModified").asText(),
                    baum.path("sha256").asText(), textOderNull(baum, "sperrmodus"), textOderNull(baum, "retainUntil"));
            if (zeile.versionId().isBlank() || zeile.groesse() < 0 || !SHA256.matcher(zeile.sha256()).matches()
                    || !sperreGueltig(zeile)) {
                throw new InventarDateiFehler("Ungültige Inventarzeile");
            }
            Instant.parse(zeile.lastModified());
            if (letzteZeile != null && SicherungBucketExport.vergleichen(letzteZeile, zeile) >= 0) {
                throw new InventarDateiFehler("Vorheriges Inventar ist nicht streng sortiert");
            }
            letzteZeile = zeile;
        } catch (DateTimeParseException e) {
            throw new InventarDateiFehler("Ungültige Inventarzeile", e);
        } catch (JsonProcessingException e) {
            throw new InventarDateiFehler("Ungültige JSON-Zeile im Vorinventar", e);
        } catch (IOException e) {
            throw new UncheckedIOException("Vorheriges Inventar nicht lesbar", e);
        }
    }

    private static boolean sperreGueltig(InventarZeile zeile) {
        String modus = zeile.sperrmodus();
        String retainUntil = zeile.retainUntil();
        if (modus == null || retainUntil == null) {
            return modus == null && retainUntil == null;
        }
        if (!modus.equals("GOVERNANCE") && !modus.equals("COMPLIANCE")) {
            return false;
        }
        try {
            Instant.parse(retainUntil);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    private static String textOderNull(JsonNode baum, String feld) {
        JsonNode wert = baum.get(feld);
        return wert == null || wert.isNull() ? null : wert.asText();
    }
}
