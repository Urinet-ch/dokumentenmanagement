package ch.urinet.archiv.spike.golden;

import ch.urinet.archiv.spike.golden.Erwartung.Datensatz;
import ch.urinet.archiv.spike.golden.Erwartung.Mandant;
import ch.urinet.archiv.spike.golden.GoldenPipeline.BelegErgebnis;
import ch.urinet.archiv.spike.golden.Vergleich.Feld;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** Ablauf eines Golden-Laufs; gemeinsam für GoldenLauf (Kommandozeile) und GoldenDatensatzIT. */
@ApplicationScoped
public class GoldenLaufDienst {

    private static final Logger LOG = Logger.getLogger(GoldenLaufDienst.class);

    @Inject
    GoldenPipeline pipeline;

    @ConfigProperty(name = "archiv.ki.token-datei")
    Path tokenDatei;
    @ConfigProperty(name = "archiv.ki.chat-modell")
    String modell;
    @ConfigProperty(name = "archiv.ki.schwelle")
    double schwelle;
    @ConfigProperty(name = "golden.mandant-name")
    String mandantName;
    @ConfigProperty(name = "golden.eigene-arten")
    List<String> eigeneArten;
    @ConfigProperty(name = "golden.preis.ein-chf-pro-mio", defaultValue = "0.20")
    double preisEin;
    @ConfigProperty(name = "golden.preis.aus-chf-pro-mio", defaultValue = "0.75")
    double preisAus;

    public record Ausgang(Path ordner, List<BelegErgebnis> ergebnisse, Bericht.Kennzahlen kennzahlen) {
    }

    public static Path goldenDir() {
        String wert = System.getenv("ARCHIV_GOLDEN_DIR");
        return Path.of(wert != null ? wert : System.getProperty("user.home") + "/urinet-archiv-golden").toAbsolutePath();
    }

    public Ausgang lauf(Modus modus, int limit, boolean k1) throws IOException {
        if (k1 && modus != Modus.ABSTIMMEN) {
            throw new IllegalArgumentException("--k1 nur im Abstimmungsteil");
        }
        // Req 16.22: vor dem ersten Beleg, mit den Werten, die der Client tatsächlich verwendet
        Config config = ConfigProvider.getConfig();
        Hostpruefung.pruefeKonfiguration(schluessel -> config.getOptionalValue(schluessel, String.class));
        if (!Files.isReadable(tokenDatei)) {
            throw new IllegalStateException("Token-Datei fehlt oder ist nicht lesbar: " + tokenDatei);
        }
        Path golden = goldenDir();
        Datensatz synthetisch = ErwartungLeser.synthetisch(golden.resolve("synthetisch/erwartet.json"));
        Datensatz echt = Files.isRegularFile(golden.resolve("erwartet.json"))
                ? ErwartungLeser.echt(golden.resolve("erwartet.json"), golden.resolve("mandant-kennungen.json"), mandantName)
                : new Datensatz(new Mandant(mandantName, null, List.of()), List.of(), 0);

        List<Erwartung> auswahl = modus == Modus.REALITAET ? echt.belege() : modus.waehle(synthetisch.belege());
        Mandant mandant = modus == Modus.REALITAET ? echt.mandant() : synthetisch.mandant();
        if (limit > 0 && auswahl.size() > limit) {
            auswahl = auswahl.subList(0, limit);
        }
        List<Dokumentarten.Art> arten = Dokumentarten.aus(Dokumentarten.designMd(Path.of("")));
        Path cache = golden.resolve(".cache-golden-lauf");
        String systemPrompt = Prompt.system(mandant.name(), Dokumentarten.alsPromptListe(arten, eigeneArten, mandant.name()));
        String promptHash = Prompt.hash(systemPrompt, bildweg(k1));

        LOG.infof("Modus %s%s: %d Belege, Prompt %s", modus.verzeichnis(), k1 ? " (K1, Bild)" : "", auswahl.size(), promptHash);
        List<BelegErgebnis> ergebnisse = new ArrayList<>();
        for (Erwartung e : auswahl) {
            BelegErgebnis r = pipeline.verarbeite(e, mandant, systemPrompt, arten, cache, k1);
            ergebnisse.add(r);
            LOG.info(zeile(r));
        }
        String zeitpunkt = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm"));
        Bericht.Lauf lauf = new Bericht.Lauf(modus, k1, zeitpunkt, promptHash, modell, schwelle, echt.belege().size(),
                echt.ausgeschlossen(), preisEin, preisAus);
        Bericht.Kennzahlen k = Bericht.berechne(ergebnisse, schwelle, preisEin, preisAus);
        Path ordner = Bericht.schreibe(golden.resolve("berichte").resolve(zeitpunkt + "-" + modus.verzeichnis()
                + (k1 ? "-k1" : "")), lauf, ergebnisse, k);
        return new Ausgang(ordner, ergebnisse, k);
    }

    /** Kennung des Bildwegs für den Prompt-Hash: Standard «fotos» (Bilder als Bild), --k1 «alle». */
    static String bildweg(boolean k1) {
        return k1 ? "alle" : "fotos";
    }

    /** Konsole: nur Beleg-ID (synthetisch Dateiname, echt Kurz-ID), Feld, richtig/falsch. */
    static String zeile(BelegErgebnis r) {
        StringBuilder sb = new StringBuilder(r.erwartung().id());
        for (Feld f : Feld.values()) {
            sb.append("  ").append(f.name().toLowerCase()).append(r.richtig().get(f) ? " richtig" : " FALSCH");
        }
        if (r.fehler() != null) {
            sb.append("  [").append(r.fehler()).append("]");
        }
        return sb.toString();
    }
}
