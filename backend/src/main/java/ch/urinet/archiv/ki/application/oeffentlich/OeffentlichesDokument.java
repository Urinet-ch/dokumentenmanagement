package ch.urinet.archiv.ki.application.oeffentlich;

import java.util.Objects;
import java.util.UUID;

/**
 * Ein Dokument, das der feste Filter für die Anfrage liefert: nur, was Chatbot und Quellen nennen dürfen (Titel, Reihe,
 * Ausgabe, Jahr), nie Stufe, Status, Vertraulichkeit, Dateiname oder Partner.
 *
 * @param reiheCode Code der Dokumentart, die als Reihe gilt
 * @param reiheName Name der Reihe (leer, wenn die Art nicht gefunden wird)
 * @param ausgabe   {@code zusatz.ausgabe} als Text, kann fehlen
 * @param jahr      {@code zusatz.jahr}, kann fehlen
 * @param seiten    {@code dokument.seiten}, kann fehlen (nur für die Plausibilität der Fundstelle, nie an das Modell)
 */
public record OeffentlichesDokument(UUID id, String titel, String reiheCode, String reiheName, String ausgabe,
                                    Integer jahr, Integer seiten) {

    public OeffentlichesDokument {
        Objects.requireNonNull(id, "id");
        titel = titel == null ? "" : titel;
        reiheCode = reiheCode == null ? "" : reiheCode;
        reiheName = reiheName == null ? "" : reiheName;
    }
}
