package ch.urinet.archiv.integration.adapter.in.kommando;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.integration.application.webseite.AlteLinksImport;
import ch.urinet.archiv.mandant.application.MandantKontext;
import ch.urinet.archiv.mandant.domain.MandantCode;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.PrintStream;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/** Admin-Kommando für den CSV-Import alter Links; ohne {@code --ausfuehren} immer Trockenlauf. */
@ApplicationScoped
public class AlteLinksKommando {

    public static final String AUFRUF = """
            Aufruf: archiv alte-links importieren <csv> --mandant <code>
                   [--dry-run | --ausfuehren --wer <name> --grund <text>]""";

    private final AlteLinksImport importer;
    private final MandantKontext kontext;

    public AlteLinksKommando(AlteLinksImport importer, MandantKontext kontext) {
        this.importer = importer;
        this.kontext = kontext;
    }

    public record Aufruf(Path datei, MandantCode mandant, boolean ausfuehren,
                         Optional<AlteLinksImport.Anlass> anlass) {
    }

    /** Prüft die Argumente nach {@code alte-links}, ohne Anwendung oder Datenbank zu starten. */
    public static Optional<Aufruf> lesen(List<String> argumente) {
        if (argumente.size() < 3 || !argumente.getFirst().equals("importieren") || argumente.get(1).isBlank()) {
            return Optional.empty();
        }
        Path datei;
        try {
            datei = Path.of(argumente.get(1));
        } catch (InvalidPathException e) {
            return Optional.empty();
        }
        String mandant = null;
        String wer = null;
        String grund = null;
        boolean ausfuehren = false;
        boolean dryRun = false;
        List<String> optionen = argumente.subList(2, argumente.size());
        for (int i = 0; i < optionen.size(); i++) {
            String option = optionen.get(i);
            switch (option) {
                case "--mandant", "--wer", "--grund" -> {
                    if (i + 1 >= optionen.size()) {
                        return Optional.empty();
                    }
                    String wert = optionen.get(++i);
                    if (wert.isBlank()) {
                        return Optional.empty();
                    }
                    switch (option) {
                        case "--mandant" -> {
                            if (mandant != null) {
                                return Optional.empty();
                            }
                            mandant = wert;
                        }
                        case "--wer" -> {
                            if (wer != null) {
                                return Optional.empty();
                            }
                            wer = wert;
                        }
                        case "--grund" -> {
                            if (grund != null) {
                                return Optional.empty();
                            }
                            grund = wert;
                        }
                        default -> throw new IllegalStateException("Unbekannte Option");
                    }
                }
                case "--ausfuehren" -> {
                    if (ausfuehren) {
                        return Optional.empty();
                    }
                    ausfuehren = true;
                }
                case "--dry-run" -> {
                    if (dryRun) {
                        return Optional.empty();
                    }
                    dryRun = true;
                }
                default -> {
                    return Optional.empty();
                }
            }
        }
        if (mandant == null || ausfuehren && dryRun || (wer == null) != (grund == null)
                || ausfuehren && wer == null || !ausfuehren && wer != null) {
            return Optional.empty();
        }
        try {
            MandantCode code = new MandantCode(mandant);
            Optional<AlteLinksImport.Anlass> anlass = wer == null ? Optional.empty()
                    : Optional.of(new AlteLinksImport.Anlass(wer, grund));
            return Optional.of(new Aufruf(datei, code, ausfuehren, anlass));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public int ausfuehren(List<String> argumente, PrintStream aus, PrintStream fehler) {
        Optional<Aufruf> gelesen = lesen(argumente);
        if (gelesen.isEmpty()) {
            fehler.println(AUFRUF);
            return 2;
        }
        try {
            Aufruf aufruf = gelesen.orElseThrow();
            AlteLinksImport.Bericht bericht = kontext.mit(aufruf.mandant(),
                    () -> importer.importieren(aufruf.datei(), aufruf.ausfuehren(), aufruf.anlass().orElse(null)));
            String modus = !aufruf.ausfuehren() ? "Trockenlauf"
                    : bericht.abgelehnt() > 0 ? "Ausführung abgebrochen" : "Ausführung";
            aus.printf("Alte-Links-Import (%s): angelegt=%d, schon vorhanden=%d, abgelehnt=%d%n", modus,
                    bericht.angelegt(),
                    bericht.schonVorhanden(), bericht.abgelehnt());
            for (AlteLinksImport.ZeilenErgebnis zeile : bericht.zeilen()) {
                aus.printf("Zeile %d%s: %s%s%n", zeile.zeile(), zeile.url() == null ? "" : " " + zeile.url(),
                        zeile.status(), zeile.grund() == null ? "" : " (" + zeile.grund() + ")");
            }
            return bericht.abgelehnt() == 0 ? 0 : 1;
        } catch (FachFehler e) {
            fehler.println(e.code() + ": " + e.code().titel());
            return 1;
        } catch (RuntimeException e) {
            fehler.println("Technischer Fehler: " + e.getClass().getSimpleName());
            return 3;
        }
    }
}
