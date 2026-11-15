package ch.urinet.archiv.ablage.domain;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Wie der Browser eine vorsignierte Adresse behandeln soll (Req 14.6): {@code VORSCHAU} zeigt die Datei in der Seite
 * ({@code inline}, nur für Typen, die der Browser sicher darstellt), {@code DOWNLOAD} lädt sie herunter
 * ({@code attachment}). Der Typ stammt aus Rolle und Endung des Schlüssels, nie aus Nutzereingaben; das Objekt selbst
 * trägt oft keinen Typ (S3-Vorgabe {@code application/octet-stream}), darum überschreibt ihn die Adresse.
 */
public enum Ansicht {
    VORSCHAU, DOWNLOAD;

    private static final Map<String, String> ANZEIGBAR = Map.of(
            "pdf", "application/pdf", "jpg", "image/jpeg", "jpeg", "image/jpeg", "png", "image/png",
            "tif", "image/tiff", "tiff", "image/tiff");

    /** Typ für die Antwort; für Vorschau nur aus der Liste der sicher anzeigbaren Typen, sonst leer. */
    public static Optional<String> anzeigbarerTyp(DateiSchluessel schluessel) {
        return Optional.ofNullable(ANZEIGBAR.get(schluessel.endung().toLowerCase(Locale.ROOT)));
    }

    /** Typ nach der Endung eines Schlüssels (nur anzeigbare Typen), z. B. für das Setzen beim Hochladen. */
    public static Optional<String> typVonSchluessel(String schluessel) {
        int punkt = schluessel.lastIndexOf('.');
        return punkt < 0 ? Optional.empty()
                : Optional.ofNullable(ANZEIGBAR.get(schluessel.substring(punkt + 1).toLowerCase(Locale.ROOT)));
    }

    /** Dateiname für {@code attachment}: nur aus UUID, Rolle, Nummer und geprüfter Endung zusammengesetzt. */
    public static String dateiname(DateiSchluessel schluessel) {
        return schluessel.docId() + "-" + schluessel.name();
    }
}
