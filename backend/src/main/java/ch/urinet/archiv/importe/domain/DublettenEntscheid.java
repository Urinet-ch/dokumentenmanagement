package ch.urinet.archiv.importe.domain;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Auswahl des Benutzers für eine «mögliche Dublette» (Req 13.5, 13.6; design.md {@code POST /importe/{id}/uebernehmen}):
 * {@code NEU} legt einen neuen Kontakt an, {@code ZUSAMMENFUEHREN:<kontaktId>} aktualisiert einen der Kandidaten der
 * Zeile, {@code UEBERSPRINGEN} lässt die Zeile aus. Eine Dublette ohne Entscheid wird übersprungen.
 */
public record DublettenEntscheid(Art art, UUID kontaktId) {

    private static final String ZUSAMMENFUEHREN = "ZUSAMMENFUEHREN:";

    public DublettenEntscheid {
        Objects.requireNonNull(art, "art");
        if ((art == Art.ZUSAMMENFUEHREN) != (kontaktId != null)) {
            throw new IllegalArgumentException("Kontakt nur und immer beim Zusammenführen");
        }
    }

    /** Liest {@code NEU}, {@code UEBERSPRINGEN} oder {@code ZUSAMMENFUEHREN:<uuid>}; leer bei jedem anderen Text. */
    public static Optional<DublettenEntscheid> lesen(String text) {
        if (text == null) {
            return Optional.empty();
        }
        if (text.equals(Art.NEU.name())) {
            return Optional.of(new DublettenEntscheid(Art.NEU, null));
        }
        if (text.equals(Art.UEBERSPRINGEN.name())) {
            return Optional.of(new DublettenEntscheid(Art.UEBERSPRINGEN, null));
        }
        if (text.startsWith(ZUSAMMENFUEHREN)) {
            String id = text.substring(ZUSAMMENFUEHREN.length());
            try {
                UUID kontaktId = UUID.fromString(id);
                // UUID.fromString nimmt auch verkürzte Schreibweisen an; nur die kanonische Form gilt
                return kontaktId.toString().equalsIgnoreCase(id)
                        ? Optional.of(new DublettenEntscheid(Art.ZUSAMMENFUEHREN, kontaktId)) : Optional.empty();
            } catch (IllegalArgumentException e) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    /** Gilt nur für eine Zeile mit Ergebnis {@code DUBLETTE}; zusammengeführt wird nur mit einem ihrer Kandidaten. */
    public boolean passt(ImportZeile zeile) {
        return zeile.ergebnis() == ZeilenPruefung.Ergebnis.DUBLETTE
                && (art != Art.ZUSAMMENFUEHREN || zeile.kandidaten().contains(kontaktId));
    }

    public enum Art {
        NEU,
        ZUSAMMENFUEHREN,
        UEBERSPRINGEN
    }
}
