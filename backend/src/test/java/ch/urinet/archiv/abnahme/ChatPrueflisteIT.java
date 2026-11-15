package ch.urinet.archiv.abnahme;

import ch.urinet.archiv.abnahme.ChatPrueflauf.Lauf;
import ch.urinet.archiv.abnahme.Pruefliste.Frage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Chat-Prüfliste gegen den Produktivbestand (Spec 02, Aufgabe 9.1, Req 13.1 und 13.2). <b>Nur von Hand durch Fabian,
 * nie in der CI und in keinem Workflow:</b> Der Name endet auf {@code IT} (Surefire führt ihn nicht aus), und der Test
 * läuft nur mit {@code -Darchiv.chat-pruefliste=true}. Anleitung: {@code backend/README.md}, «Chat-Prüfliste gegen den
 * Produktivbestand».
 *
 * <p>Umgebungsvariablen (nie als Systemproperty, damit nichts in der Prozessliste steht):
 * <ul>
 *   <li>{@code ARCHIV_GOLDEN_DIR} – Verzeichnis mit {@code chat-pruefliste.yaml}; hier landet auch der Bericht</li>
 *   <li>{@code ARCHIV_PRUEFLISTE_URL} – Basis-URL des Archivs, nur {@code https}</li>
 *   <li>{@code ARCHIV_PRUEFLISTE_TOKEN} – Access-Token eines {@code inhaber} (nie geloggt, nie im Bericht)</li>
 *   <li>{@code ARCHIV_PRUEFLISTE_PAUSE_SEKUNDEN} – Pause zwischen den Fragen, Vorgabe 5</li>
 *   <li>{@code ARCHIV_PRUEFLISTE_AB} – erst ab dieser Fragen-Nr. (nach abgelaufenem Token), Vorgabe 1</li>
 * </ul>
 *
 * Der vollständige Bericht (Fragen, Antworten, Quellen) geht nur nach {@code ARCHIV_GOLDEN_DIR}. Auf der Konsole steht
 * allein der Repo-Teil: Fragen-Nr., Vorschlag richtig/falsch, Quelle ja/nein, p95 der Zeit bis zum ersten Text.
 * Fabian bestätigt jede Antwort im Bericht; erst danach zählt das Ergebnis (Nachweis: manuell [Fabian]).
 */
@EnabledIfSystemProperty(named = "archiv.chat-pruefliste", matches = "true")
class ChatPrueflisteIT {

    @Test
    void pruefliste() throws Exception {
        Path golden = Path.of(vorgabe("ARCHIV_GOLDEN_DIR")).toRealPath();
        URI basis = URI.create(vorgabe("ARCHIV_PRUEFLISTE_URL"));
        assertThat(basis.getScheme()).as("Basis-URL nur per https").isEqualTo("https");
        String token = vorgabe("ARCHIV_PRUEFLISTE_TOKEN");
        Duration pause = Duration.ofSeconds(Long.parseLong(System.getenv().getOrDefault("ARCHIV_PRUEFLISTE_PAUSE_SEKUNDEN", "5")));
        int ab = Integer.parseInt(System.getenv().getOrDefault("ARCHIV_PRUEFLISTE_AB", "1"));

        Path yaml = golden.resolve("chat-pruefliste.yaml");
        assertThat(yaml).as("chat-pruefliste.yaml unter ARCHIV_GOLDEN_DIR").isRegularFile();
        List<Frage> fragen = Pruefliste.lesen(yaml).stream().filter(f -> f.nr() >= ab).toList();

        Lauf lauf = new ChatPrueflauf(basis, token, pause, Duration.ofMinutes(2)).laufen(fragen);
        Map<String, Path> dateien = ChatPrueflauf.schreiben(lauf, golden, Clock.systemDefaultZone());

        System.out.println("PRUEFLISTE Repo-Teil (ohne Inhalte):");
        System.out.println(Files.readString(dateien.get("repoTeil")));
        System.out.println("PRUEFLISTE Bericht: " + dateien.get("bericht").getFileName() + " unter ARCHIV_GOLDEN_DIR");
        assertThat(lauf.abgebrochen()).as("Lauf abgebrochen (HTTP %d): neues Token holen, mit ARCHIV_PRUEFLISTE_AB fortsetzen",
                lauf.abbruchStatus()).isFalse();
    }

    private static String vorgabe(String name) {
        String wert = System.getenv(name);
        assertThat(wert).as("Umgebungsvariable " + name).isNotBlank();
        return wert;
    }
}
