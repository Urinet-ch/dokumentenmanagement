package ch.urinet.archiv.ki.application.oeffentlich;

import ch.urinet.archiv.integration.domain.webseite.Lesezugriff;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Driven Port: Dokumente für den öffentlichen Chatbot, jede Abfrage über den festen Filter ({@code FesterFilter}) mit
 * den Stufen des {@link Lesezugriff} (Spec 03, design.md «Fester Filter»). Was der Filter nicht liefert, gibt es nicht:
 * unbekannt, nicht veröffentlicht, zu hohe Stufe, nicht archiviert und nicht {@code NORMAL} sehen gleich aus. Aufruf in
 * einer Transaktion im Mandanten des Kontexts.
 */
public interface OeffentlicheDokumente {

    /** Die Dokumente aus {@code ids}, die der Filter liefert; die Reihenfolge ist offen. */
    List<OeffentlichesDokument> sichtbare(Collection<UUID> ids, Lesezugriff zugriff);

    /** Die ersten {@code zeichen} Zeichen des Volltexts; leer ohne sichtbares Dokument oder ohne Text. */
    Optional<String> inhalt(UUID id, Lesezugriff zugriff, int zeichen);
}
