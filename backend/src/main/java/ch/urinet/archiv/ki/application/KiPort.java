package ch.urinet.archiv.ki.application;

import ch.urinet.archiv.ki.domain.Rohvorschlag;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Driven Port zum Sprachmodell der Erfassung (ADR-011, Req 7.1, 7.3, 16.13; design.md «KI»): genau ein Aufruf je
 * {@link #metadaten}, ohne Wiederholung im Adapter. Adapter {@code LangChain4jKiAdapter} gegen Infomaniak AI Services,
 * Dev-Stub über {@code archiv.ki.stub}, in Tests WireMock. Der Adapter loggt nie Inhalte, nur Klassennamen (Req 16.7).
 */
public interface KiPort {

    /** Ein Aufruf; eine Ausnahme gibt es nicht, jeder Fehler ist eine {@link Antwort}. */
    Antwort metadaten(Anfrage anfrage);

    /** Antwortschema als Text, so wie es das Modell erhält; Teil der Kennung der Prompt-Fassung. */
    String antwortschema();

    /** Codes der Dokumentarten im Antwortschema, in seiner Reihenfolge. */
    List<String> artCodes();

    /**
     * @param systemPrompt fertig gerenderter System-Prompt ({@code ErfassungPrompt.system})
     * @param qr           Zeile zum Swiss QR Code ({@code ErfassungPrompt.qr})
     * @param text         gekürzter Volltext; leer auf dem Bildweg
     * @param bildJpeg     Seitenbild als JPEG auf dem Bildweg (Req 7.1), sonst leer
     * @param fehler       bei der Nachfrage der Grund der ungültigen ersten Antwort (Req 7.3)
     */
    record Anfrage(String systemPrompt, String qr, String text, Optional<byte[]> bildJpeg, Optional<String> fehler) {
        public Anfrage {
            Objects.requireNonNull(systemPrompt, "systemPrompt");
            Objects.requireNonNull(qr, "qr");
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(bildJpeg, "bildJpeg");
            Objects.requireNonNull(fehler, "fehler");
        }

        public Anfrage nachfrage(String grund) {
            return new Anfrage(systemPrompt, qr, text, bildJpeg, Optional.of(grund));
        }
    }

    /** Ergebnis eines Aufrufs; {@link Verbrauch} auch bei Fehlern, soweit das Modell Tokens meldete. */
    sealed interface Antwort permits Gueltig, Schemafehler, Ausfall, OhneZugang {
        Verbrauch verbrauch();
    }

    /** Antwort nach dem Schema. */
    record Gueltig(Rohvorschlag vorschlag, Verbrauch verbrauch) implements Antwort {
    }

    /** Antwort, die nicht dem Schema entspricht; {@code grund} nennt nur Klassennamen, nie Inhalte. */
    record Schemafehler(String grund, Verbrauch verbrauch) implements Antwort {
    }

    /**
     * Keine Antwort (Zeitüberschreitung, Verbindung, HTTP-Fehler); {@code grund} nennt nur Klassennamen. Zählt als
     * Fehlversuch des Auftrags (Req 5.4).
     */
    record Ausfall(String grund, Verbrauch verbrauch) implements Antwort {
    }

    /**
     * Kein Aufruf, weil das Token fehlt (Secret-Datei {@code infomaniak_ai_token}): Es ging kein Request hinaus, es gibt
     * keinen Verbrauch, und eine Wiederholung hilft nicht.
     */
    record OhneZugang(Verbrauch verbrauch) implements Antwort {
    }

    /** Tokens eines Aufrufs für {@code ki_verbrauch} (design.md «KI»). */
    record Verbrauch(String modell, long tokensEin, long tokensAus) {
    }
}
