package ch.urinet.archiv.ki.domain.oeffentlich;

import java.util.Objects;
import java.util.UUID;

/**
 * Quelle einer Antwort des öffentlichen Chatbots (Spec 03, Req 6.2, 5.11): nur {@code dokumentId}, Titel, Reihe und
 * Ausgabe. Den Link auf die Dokumentseite bildet die Webseite aus der ID; Stufe, Status, Dateiname und Vertraulichkeit
 * gehören nie dazu.
 *
 * @param reiheCode Code der Reihe (Dokumentart), leer nur bei einer Art ohne Namen
 * @param reiheName Name der Reihe
 * @param ausgabe   Ausgabe-Nr. wie am Dokument gepflegt, kann fehlen
 */
public record OeffentlicheQuelle(UUID dokumentId, String titel, String reiheCode, String reiheName, String ausgabe) {

    public OeffentlicheQuelle {
        Objects.requireNonNull(dokumentId, "dokumentId");
        Objects.requireNonNull(titel, "titel");
        Objects.requireNonNull(reiheCode, "reiheCode");
        Objects.requireNonNull(reiheName, "reiheName");
    }
}
