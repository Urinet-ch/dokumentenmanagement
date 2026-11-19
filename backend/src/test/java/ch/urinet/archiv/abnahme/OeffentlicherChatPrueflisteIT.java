package ch.urinet.archiv.abnahme;

import ch.urinet.archiv.abnahme.OeffentlicheChatPruefliste.Frage;
import ch.urinet.archiv.abnahme.OeffentlicherChatPrueflauf.Lauf;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Späterer manueller Lauf gegen den öffentlichen Chatbot; nie in der CI. Echte Fragen und Berichte bleiben ausserhalb
 * des Repositorys. Für Entwicklung und CI wird ausschliesslich {@link OeffentlicherChatPrueflaufTest} verwendet.
 */
@EnabledIfSystemProperty(named = "archiv.oeffentlicher-chat-prueflauf", matches = "true")
class OeffentlicherChatPrueflisteIT {

    @Test
    void pruefliste() throws Exception {
        Path golden = Path.of(vorgabe("ARCHIV_OEFFENTLICHER_CHAT_GOLDEN_DIR")).toRealPath();
        assertThat(imRepository(golden)).as("Echte Fragen müssen ausserhalb des Repositorys liegen").isFalse();
        URI basis = URI.create(vorgabe("ARCHIV_OEFFENTLICHER_CHAT_URL"));
        assertThat(basis.getScheme()).as("Basis-URL nur per https").isEqualTo("https");
        String token = token(Path.of(vorgabe("ARCHIV_OEFFENTLICHER_CHAT_TOKEN_DATEI")).toRealPath());
        String clientHash = vorgabe("ARCHIV_OEFFENTLICHER_CHAT_CLIENT_HASH");
        Duration pause = Duration.ofSeconds(Long.parseLong(System.getenv()
                .getOrDefault("ARCHIV_OEFFENTLICHER_CHAT_PAUSE_SEKUNDEN", "180")));
        assertThat(pause).as("Pause muss das Rate-Limit von 20 Fragen pro Stunde einhalten")
                .isGreaterThanOrEqualTo(Duration.ofMinutes(3));
        List<Frage> fragen = OeffentlicheChatPruefliste.lesen(golden.resolve("oeffentlicher-chat-pruefliste.yaml"));

        String hinweis = System.getenv("ARCHIV_OEFFENTLICHER_CHAT_HINWEIS");

        Lauf lauf = new OeffentlicherChatPrueflauf(basis, token, clientHash, pause, Duration.ofMinutes(2), hinweis)
                .laufen(fragen);
        Map<String, Path> dateien = OeffentlicherChatPrueflauf.schreiben(lauf, golden);

        System.out.println("ÖFFENTLICHE PRÜFLISTE Repo-Teil (ohne Inhalte):");
        System.out.println(OeffentlicherChatPrueflauf.repoTeil(lauf));
        System.out.println("Vollständiger Bericht: " + dateien.get("bericht").getFileName()
                + " unter ARCHIV_OEFFENTLICHER_CHAT_GOLDEN_DIR");
        assertThat(lauf.abgebrochen()).as("Lauf abgebrochen (HTTP %d)", lauf.abbruchStatus()).isFalse();
    }

    private static String token(Path datei) throws Exception {
        assertThat(datei).isRegularFile();
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(datei))).isEqualTo("rw-------");
        String token = Files.readString(datei).strip();
        assertThat(token).isNotBlank();
        return token;
    }

    private static boolean imRepository(Path pfad) {
        for (Path aktueller = pfad; aktueller != null; aktueller = aktueller.getParent()) {
            if (Files.exists(aktueller.resolve(".git"))) {
                return true;
            }
        }
        return false;
    }

    private static String vorgabe(String name) {
        String wert = System.getenv(name);
        assertThat(wert).as("Umgebungsvariable " + name).isNotBlank();
        return wert;
    }
}
