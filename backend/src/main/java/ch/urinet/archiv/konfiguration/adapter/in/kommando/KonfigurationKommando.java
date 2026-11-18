package ch.urinet.archiv.konfiguration.adapter.in.kommando;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.konfiguration.application.KonfigurationsdateiVerwaltung;
import ch.urinet.archiv.konfiguration.application.KonfigurationsdateiVerwaltung.Analyse;
import ch.urinet.archiv.konfiguration.application.KonfigurationsdateiVerwaltung.DiffEintrag;
import ch.urinet.archiv.konfiguration.application.KonfigurationsdateiVerwaltung.Ergebnis;
import ch.urinet.archiv.mandant.application.MandantKontext;
import ch.urinet.archiv.mandant.domain.MandantCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/** Betreiberkommando zum Exportieren, Prüfen, Vergleichen und Einspielen einer Mandantenkonfiguration. */
@ApplicationScoped
public class KonfigurationKommando {

    public static final String AUFRUF = """
            Aufruf: archiv mandant konfiguration exportieren <code> [<datei>] [--json]
                   archiv mandant konfiguration pruefen     <code> <datei> [--json]
                   archiv mandant konfiguration vergleichen <code> <datei> [--json]
                   archiv mandant konfiguration einspielen  <code> <datei> --wer <name> --grund <text> [--json]""";
    private static final int DATEI_MAX = 1024 * 1024;
    private static final int TECHNISCHER_FEHLER = 3;

    private final KonfigurationsdateiVerwaltung verwaltung;
    private final MandantKontext kontext;
    private final ObjectMapper json;

    public KonfigurationKommando(KonfigurationsdateiVerwaltung verwaltung, MandantKontext kontext,
                                 ObjectMapper json) {
        this.verwaltung = verwaltung;
        this.kontext = kontext;
        this.json = json;
    }

    enum Operation {
        EXPORTIEREN, PRUEFEN, VERGLEICHEN, EINSPIELEN
    }

    record Aufruf(Operation operation, MandantCode mandant, Optional<Path> datei, String wer, String grund,
                  boolean json) {
    }

    record AnalyseAusgabe(int aktuelleVersion, Integer basisVersion, boolean gueltig,
                          List<KonfigurationKommando.BefundAusgabe> befunde, List<DiffEintrag> aenderungen) {
        static AnalyseAusgabe aus(Analyse analyse) {
            return new AnalyseAusgabe(analyse.aktuelleVersion(), analyse.basisVersion(), analyse.gueltig(),
                    analyse.befunde().stream().map(BefundAusgabe::aus).toList(), analyse.aenderungen());
        }
    }

    record BefundAusgabe(String pfad, String code, String meldung) {
        static BefundAusgabe aus(ch.urinet.archiv.konfiguration.domain.Konfiguration.Befund befund) {
            return new BefundAusgabe(befund.pfad(), befund.code(), befund.meldung());
        }
    }

    static Optional<Aufruf> lesen(List<String> argumente) {
        if (argumente.size() < 2) {
            return Optional.empty();
        }
        Operation operation = switch (argumente.getFirst()) {
            case "exportieren" -> Operation.EXPORTIEREN;
            case "pruefen" -> Operation.PRUEFEN;
            case "vergleichen" -> Operation.VERGLEICHEN;
            case "einspielen" -> Operation.EINSPIELEN;
            default -> null;
        };
        if (operation == null) {
            return Optional.empty();
        }
        MandantCode mandant;
        try {
            mandant = new MandantCode(argumente.get(1));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }

        int index = 2;
        Optional<Path> datei = Optional.empty();
        if (operation == Operation.EXPORTIEREN) {
            if (index < argumente.size() && !argumente.get(index).startsWith("--")) {
                datei = pfad(argumente.get(index++));
                if (datei.isEmpty()) {
                    return Optional.empty();
                }
            }
        } else {
            if (index >= argumente.size() || argumente.get(index).startsWith("--")) {
                return Optional.empty();
            }
            datei = pfad(argumente.get(index++));
            if (datei.isEmpty()) {
                return Optional.empty();
            }
        }

        String wer = null;
        String grund = null;
        boolean maschinenlesbar = false;
        while (index < argumente.size()) {
            String option = argumente.get(index++);
            switch (option) {
                case "--json" -> {
                    if (maschinenlesbar) {
                        return Optional.empty();
                    }
                    maschinenlesbar = true;
                }
                case "--wer", "--grund" -> {
                    if (operation != Operation.EINSPIELEN || index >= argumente.size()) {
                        return Optional.empty();
                    }
                    String wert = argumente.get(index++);
                    if (wert.isBlank() || wert.startsWith("--")) {
                        return Optional.empty();
                    }
                    if (option.equals("--wer")) {
                        if (wer != null || wert.codePointCount(0, wert.length()) > 80
                                || wert.codePoints().anyMatch(Character::isISOControl)) {
                            return Optional.empty();
                        }
                        wer = wert;
                    } else {
                        if (grund != null || wert.codePointCount(0, wert.length()) > 1000
                                || wert.codePoints().anyMatch(Character::isISOControl)) {
                            return Optional.empty();
                        }
                        grund = wert;
                    }
                }
                default -> {
                    return Optional.empty();
                }
            }
        }
        if (operation == Operation.EINSPIELEN && (wer == null || grund == null)) {
            return Optional.empty();
        }
        return Optional.of(new Aufruf(operation, mandant, datei, wer, grund, maschinenlesbar));
    }

    public static boolean gueltig(List<String> argumente) {
        return lesen(argumente).isPresent();
    }

    public int ausfuehren(List<String> argumente, PrintStream aus, PrintStream fehler) {
        Optional<Aufruf> gelesen = lesen(argumente);
        if (gelesen.isEmpty()) {
            fehler.println(AUFRUF);
            return 2;
        }
        Aufruf aufruf = gelesen.get();
        try {
            return switch (aufruf.operation()) {
                case EXPORTIEREN -> exportieren(aufruf, aus);
                case PRUEFEN -> analysieren(aufruf, aus, true);
                case VERGLEICHEN -> analysieren(aufruf, aus, false);
                case EINSPIELEN -> einspielen(aufruf, aus);
            };
        } catch (FachFehler e) {
            fehler.printf("%s: %s%n", e.code(), e.getMessage());
            return 1;
        } catch (IOException e) {
            fehler.printf("Datei nicht lesbar oder nicht schreibbar: %s%n", e.getMessage());
            return TECHNISCHER_FEHLER;
        } catch (RuntimeException e) {
            fehler.printf("Technischer Fehler: %s%n", e.getMessage());
            return TECHNISCHER_FEHLER;
        }
    }

    private int exportieren(Aufruf aufruf, PrintStream aus) throws IOException {
        String datei = kontext.mit(aufruf.mandant(), verwaltung::exportieren);
        if (aufruf.datei().isPresent()) {
            Files.writeString(aufruf.datei().get(), datei, StandardCharsets.UTF_8);
            aus.printf("Konfiguration exportiert: %s%n", aufruf.datei().get());
        } else {
            aus.println(datei);
        }
        return 0;
    }

    private int analysieren(Aufruf aufruf, PrintStream aus, boolean pruefen) throws IOException {
        String datei = dateiLesen(aufruf.datei().orElseThrow());
        Analyse analyse = kontext.mit(aufruf.mandant(),
                () -> pruefen ? verwaltung.pruefen(datei) : verwaltung.vergleichen(datei));
        if (aufruf.json()) {
            aus.println(json.writeValueAsString(AnalyseAusgabe.aus(analyse)));
        } else {
            aus.printf("Konfiguration %s: %d Befund(e), %d Änderung(en), aktuelle Version %d%n",
                    analyse.gueltig() ? "gültig" : "ungültig", analyse.befunde().size(),
                    analyse.aenderungen().size(), analyse.aktuelleVersion());
            analyse.befunde().forEach(befund -> aus.printf("%s: %s: %s%n",
                    befund.pfad(), befund.code(), befund.meldung()));
            analyse.aenderungen().forEach(aenderung -> aenderungAusgeben(aus, aenderung));
        }
        return analyse.gueltig() ? 0 : 1;
    }

    private int einspielen(Aufruf aufruf, PrintStream aus) throws IOException {
        String datei = dateiLesen(aufruf.datei().orElseThrow());
        Ergebnis ergebnis = kontext.mit(aufruf.mandant(), () -> verwaltung.einspielen(datei,
                "betreiber:" + aufruf.wer(), aufruf.grund()));
        if (aufruf.json()) {
            aus.println(json.writeValueAsString(ergebnis));
        } else {
            aus.printf("Konfiguration eingespielt: Version %d, %d Änderung(en)%n",
                    ergebnis.version(), ergebnis.aenderungen().size());
            ergebnis.aenderungen().forEach(aenderung -> aenderungAusgeben(aus, aenderung));
        }
        return 0;
    }

    private static String dateiLesen(Path pfad) throws IOException {
        if (Files.size(pfad) > DATEI_MAX) {
            throw new IOException("Die Datei überschreitet 1 MiB.");
        }
        return Files.readString(pfad, StandardCharsets.UTF_8);
    }

    private static void aenderungAusgeben(PrintStream aus, DiffEintrag aenderung) {
        String werte = aenderung.werteVerdeckt()
                ? "geändert" : aenderung.alt() + " → " + aenderung.neu();
        aus.printf("%s: %s (%d betroffene Dokumente)%n", aenderung.pfad(), werte,
                aenderung.betroffeneDokumente());
    }

    private static Optional<Path> pfad(String wert) {
        try {
            return wert.isBlank() ? Optional.empty() : Optional.of(Path.of(wert));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }
}
