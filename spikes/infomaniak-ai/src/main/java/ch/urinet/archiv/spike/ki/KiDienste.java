package ch.urinet.archiv.spike.ki;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.service.Result;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.RegisterAiService;
import io.quarkiverse.langchain4j.RegisterAiService.NoChatMemoryProviderSupplier;
import jakarta.enterprise.context.ApplicationScoped;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

final class KiDienste {

    private KiDienste() {
    }

    static final String ROLLE = """
            Du bist Archivassistent für ein Schweizer KMU (Urinet Aschwanden, UID CHE-111.222.333).
            Dokumentarten:
            - AUSGANGSRECHNUNG: Rechnung, die Urinet Aschwanden ausstellt (richtung AUSGANG)
            - LIEFERANTENBELEG: Rechnung eines Lieferanten an Urinet Aschwanden (richtung EINGANG)
            - OFFERTE: Angebot, das Urinet Aschwanden ausstellt (richtung AUSGANG)
            - VERTRAG, KORRESPONDENZ
            partnerName ist immer die Gegenpartei, nie Urinet Aschwanden.
            Gib Datumswerte als ISO-Datum (JJJJ-MM-TT) zurück. Fehlt ein Wert, lass ihn leer (null).
            sicherheit enthält je Feld eine Zahl zwischen 0 und 1.
            """;

    // Ohne Memory: Standard ist ein Memory je Request-Kontext, Belege würden sich vermischen.
    @RegisterAiService(modelName = "strukturiert", chatMemoryProviderSupplier = NoChatMemoryProviderSupplier.class)
    @SystemMessage(ROLLE)
    public interface MetadatenExtraktor {
        @UserMessage("Erfasse die Metadaten dieses Belegs:\n\n{text}")
        Result<MetadatenVorschlag> extrahiere(String text);

        @UserMessage("Erfasse die Metadaten dieses Belegs:\n\n{text}")
        Result<MetadatenVorschlagIso> extrahiereIso(String text);
    }

    @RegisterAiService(modelName = "jsonmodus", chatMemoryProviderSupplier = NoChatMemoryProviderSupplier.class)
    @SystemMessage(ROLLE + "Antworte nur mit einem JSON-Objekt mit den Feldern art, datum, belegnummer, betrag.")
    public interface JsonModus {
        @UserMessage("{text}")
        String extrahiere(String text);
    }

    // Tool Calling braucht ein Chat-Memory (Build-Fehler ohne).
    @RegisterAiService(tools = ArchivWerkzeuge.class)
    @SystemMessage("Du beantwortest Fragen zum Archiv. Rechne nie selbst, verwende die Werkzeuge.")
    public interface ArchivAssistent {
        String frage(@UserMessage String frage);
    }

    @ApplicationScoped
    static class ArchivWerkzeuge {
        private final List<String> aufrufe = new ArrayList<>();

        List<String> aufrufe() {
            return aufrufe;
        }

        @Tool("Summe der Ausgangsrechnungen in CHF für einen Partner und ein Kalenderjahr")
        BigDecimal summeAusgangsrechnungen(String partner, int jahr) {
            aufrufe.add("summeAusgangsrechnungen(" + partner + ", " + jahr + ")");
            return new BigDecimal("4820.50");
        }

        @Tool("Anzahl offener Rechnungen eines Partners")
        int anzahlOffeneRechnungen(String partner) {
            aufrufe.add("anzahlOffeneRechnungen(" + partner + ")");
            return 2;
        }
    }
}
