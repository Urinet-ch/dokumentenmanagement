package ch.urinet.archiv.ki.adapter.out.langchain4j;

import ch.urinet.archiv.ki.domain.chat.ChatEreignis;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.service.tool.ToolExecutor;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Hülle um den Aufruf eines Werkzeugs im Durchgang (Spec 02, Aufgabe 7.2): meldet {@link ChatEreignis.WerkzeugStart}
 * vor und {@link ChatEreignis.WerkzeugErgebnis} nach dem Aufruf, setzt die Rundengrenze durch und kennzeichnet
 * Dokumenttexte in der Antwort als Daten (Req 5.3). Nach einem Abbruch des Clients führt sie nichts mehr aus und verhindert den
 * nächsten Modellaufruf. Ab der Runde {@code runden + 1} wird nichts mehr ausgeführt: Das Modell
 * erhält {@value #GRENZE}, damit es mit dem Bisherigen antwortet. Mandant und Benutzer des Aufrufs kommen nie von
 * hier, sondern aus dem {@code WerkzeugKontextRegister} unter derselben Lauf-ID ({@code ArchivWerkzeuge}). Weder
 * Parameter noch Antworten gehören ins Log (Req 16.7).
 */
final class AgentWerkzeug implements ToolExecutor {

    static final String GRENZE = "{\"fehler\":\"Grenze der Werkzeugaufrufe erreicht, antworte mit dem Bisherigen\"}";
    private static final Logger LOG = Logger.getLogger(AgentWerkzeug.class);

    private final String name;
    private final ToolExecutor echt;
    private final AgentLauf lauf;
    private final ObjectMapper json;
    private final int runden;

    AgentWerkzeug(String name, ToolExecutor echt, AgentLauf lauf, ObjectMapper json, int runden) {
        this.name = name;
        this.echt = echt;
        this.lauf = lauf;
        this.json = json;
        this.runden = runden;
    }

    /** Der Client hat die Verbindung geschlossen oder der Durchgang ist beendet (auch durch die Gesamtfrist): keine weiteren Werkzeuge, keine weitere Runde. */
    static final class Abgebrochen extends RuntimeException {
        Abgebrochen() {
            super("abgebrochen", null, false, false);
        }
    }

    @Override
    public String execute(ToolExecutionRequest anfrage, Object memoryId) {
        if (lauf.abgebrochen() || lauf.beendet()) {
            // Die Bibliothek macht aus der Ausnahme ein Fehlerergebnis und riefe das Modell trotzdem noch einmal auf; der
            // Marker auf diesem Thread verhindert den Aufruf (EntkoppeltesModell), damit keine Runde mehr bezahlt wird
            EntkoppeltesModell.naechstenAufrufVerhindern();
            throw new Abgebrochen();
        }
        if (lauf.runde() > runden) {
            lauf.melden(WerkzeugErgebnisse.abbilden(json, name, anfrage.arguments(), GRENZE, Instant::now));
            return GRENZE;
        }
        lauf.melden(new ChatEreignis.WerkzeugStart(name));
        String roh;
        try {
            roh = echt.execute(anfrage, memoryId);
        } catch (RuntimeException e) {
            lauf.melden(new ChatEreignis.WerkzeugErgebnis(name, Map.of(), List.of(), Optional.empty()));
            throw e;
        }
        ChatEreignis.WerkzeugErgebnis ergebnis;
        try {
            ergebnis = WerkzeugErgebnisse.abbilden(json, name, anfrage.arguments(), roh, Instant::now);
        } catch (RuntimeException e) {
            LOG.warnf("Ergebnis von %s nicht abgebildet: %s", name, e.getClass().getSimpleName());
            ergebnis = new ChatEreignis.WerkzeugErgebnis(name, Map.of(), List.of(), Optional.empty());
        }
        lauf.melden(ergebnis);
        return DokumentTexte.kennzeichnen(json, roh,
                "wissen_index".equals(name) || "wissen_seite_zeigen".equals(name));
    }
}
