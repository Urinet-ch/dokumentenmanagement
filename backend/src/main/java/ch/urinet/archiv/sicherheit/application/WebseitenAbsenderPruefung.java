package ch.urinet.archiv.sicherheit.application;

import ch.urinet.archiv.protokoll.domain.IpAdresse;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

/** Liest die erlaubten Absendernetze je Webseite aus der Betriebskonfiguration und schliesst bei Fehlern ab. */
@ApplicationScoped
public class WebseitenAbsenderPruefung {

    private static final Logger LOG = Logger.getLogger(WebseitenAbsenderPruefung.class);
    private static final int MAX_DATEI_GROESSE = 65_536;

    private final Path datei;
    private volatile byte[] letzteDatei;
    private volatile Map<String, List<WebseitenNetz>> netze = Map.of();

    public WebseitenAbsenderPruefung(
            @ConfigProperty(name = "archiv.webseite.absender-datei") Path datei) {
        this.datei = datei;
    }

    @PostConstruct
    void initialisieren() {
        neuLaden(true);
    }

    public boolean erlaubt(String webseite, Optional<String> realIp) {
        neuLaden(false);
        Optional<IpAdresse> ip = realIp.flatMap(IpAdresse::aus);
        return ip.isPresent() && netze.getOrDefault(webseite, List.of()).stream()
                .anyMatch(netz -> netz.enthaelt(ip.get()));
    }

    private synchronized void neuLaden(boolean erzwingen) {
        byte[] inhalt = null;
        try {
            if (!Files.exists(datei)) {
                if (erzwingen || letzteDatei != null) {
                    netze = Map.of();
                    letzteDatei = null;
                }
                return;
            }
            try (InputStream eingabe = Files.newInputStream(datei)) {
                inhalt = eingabe.readNBytes(MAX_DATEI_GROESSE + 1);
            }
            if (inhalt.length > MAX_DATEI_GROESSE) {
                throw new IOException("Absenderkonfiguration überschreitet die Grössengrenze");
            }
            if (!erzwingen && Arrays.equals(inhalt, letzteDatei)) {
                return;
            }
            letzteDatei = inhalt;
            Properties eigenschaften = new Properties();
            try (InputStreamReader reader = new InputStreamReader(new ByteArrayInputStream(inhalt),
                    StandardCharsets.UTF_8)) {
                eigenschaften.load(reader);
            }
            Map<String, List<WebseitenNetz>> geladen = new HashMap<>();
            for (String webseite : eigenschaften.stringPropertyNames()) {
                String wert = eigenschaften.getProperty(webseite);
                if (webseite.isBlank() || wert == null || wert.isBlank()) {
                    throw new IllegalArgumentException("Eintrag unvollständig");
                }
                List<WebseitenNetz> eintrag = Arrays.stream(wert.split(",", -1)).map(String::strip)
                        .map(WebseitenNetz::lesen).toList();
                geladen.put(webseite, eintrag);
            }
            netze = Map.copyOf(geladen);
        } catch (IOException | IllegalArgumentException e) {
            netze = Map.of();
            letzteDatei = inhalt;
            LOG.errorf("Absenderkonfiguration für Webseiten-Schlüssel nicht lesbar: ursache=%s",
                    e.getClass().getSimpleName());
        }
    }
}
