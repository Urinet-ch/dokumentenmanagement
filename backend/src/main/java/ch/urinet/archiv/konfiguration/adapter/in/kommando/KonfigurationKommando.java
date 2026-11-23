package ch.urinet.archiv.konfiguration.adapter.in.kommando;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.BestaetigungNoetig;
import ch.urinet.archiv.gemeinsam.domain.KonfigurationUngueltig;
import ch.urinet.archiv.konfiguration.application.KonfigurationsdateiVerwaltung;
import ch.urinet.archiv.konfiguration.application.EinstellungsAenderungsPruefung;
import ch.urinet.archiv.konfiguration.application.KonfigurationsdateiVerwaltung.Analyse;
import ch.urinet.archiv.konfiguration.application.KonfigurationsdateiVerwaltung.DiffEintrag;
import ch.urinet.archiv.konfiguration.application.KonfigurationsdateiVerwaltung.Ergebnis;
import ch.urinet.archiv.mandant.application.MandantKontext;
import ch.urinet.archiv.mandant.domain.MandantCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Betreiberkommando zum Exportieren, Prüfen, Vergleichen und Einspielen einer Mandantenkonfiguration. Dateien liest es
 * höchstens 1 MiB weit (auch aus einer Pipe); der Export legt eine neue Datei mit Rechten 600 an und überschreibt nie.
 * Technische Fehler nennen nur die Fehlerklasse, nie Meldung oder Inhalt.
 */
@ApplicationScoped
public class KonfigurationKommando {

    public static final String AUFRUF = """
            Aufruf: archiv mandant konfiguration exportieren <code> [<datei>] [--json]
                   archiv mandant konfiguration pruefen     <code> <datei> [--json]
                   archiv mandant konfiguration vergleichen <code> <datei> [--json]
                   archiv mandant konfiguration einspielen  <code> <datei> --wer <name> --grund <text> [--bestaetigt <schluessel,…>] [--json]
                   archiv mandant konfiguration ki-freigeben <code> --wer <name> --abnahme <verweis> [--json]""";
    private static final int DATEI_MAX = 1024 * 1024;
    private static final int TECHNISCHER_FEHLER = 3;
    private static final Logger LOG = Logger.getLogger(KonfigurationKommando.class);

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
        EXPORTIEREN, PRUEFEN, VERGLEICHEN, EINSPIELEN, KI_FREIGEBEN
    }

    record Aufruf(Operation operation, MandantCode mandant, Optional<Path> datei, String wer, String grund,
                  String abnahme, String bestaetigt, boolean json) {
    }

    record FreigabeAusgabe(int version, boolean kiFreigegeben) {
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
            case "ki-freigeben" -> Operation.KI_FREIGEBEN;
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
        if (operation != Operation.KI_FREIGEBEN) {
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
        }

        String wer = null;
        String grund = null;
        String abnahme = null;
        String bestaetigt = null;
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
                    boolean erlaubt = option.equals("--wer")
                            ? operation == Operation.EINSPIELEN || operation == Operation.KI_FREIGEBEN
                            : operation == Operation.EINSPIELEN;
                    if (!erlaubt) {
                        return Optional.empty();
                    }
                    if (index >= argumente.size()) {
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
                case "--abnahme" -> {
                    if (operation != Operation.KI_FREIGEBEN || index >= argumente.size() || abnahme != null) {
                        return Optional.empty();
                    }
                    String wert = argumente.get(index++);
                    if (wert.isBlank() || wert.startsWith("--") || wert.codePointCount(0, wert.length()) > 1000
                            || wert.codePoints().anyMatch(Character::isISOControl)) {
                        return Optional.empty();
                    }
                    abnahme = wert;
                }
                case "--bestaetigt" -> {
                    if (operation != Operation.EINSPIELEN || index >= argumente.size() || bestaetigt != null) {
                        return Optional.empty();
                    }
                    String wert = argumente.get(index++);
                    if (wert.isBlank() || wert.startsWith("--") || wert.length() > 1024) {
                        return Optional.empty();
                    }
                    bestaetigt = wert;
                }
                default -> {
                    return Optional.empty();
                }
            }
        }
        if (operation == Operation.EINSPIELEN && (wer == null || grund == null)) {
            return Optional.empty();
        }
        if (operation == Operation.KI_FREIGEBEN && (wer == null || abnahme == null)) {
            return Optional.empty();
        }
        return Optional.of(new Aufruf(operation, mandant, datei, wer, grund, abnahme, bestaetigt, maschinenlesbar));
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
                case KI_FREIGEBEN -> kiFreigeben(aufruf, aus);
            };
        } catch (FachFehler e) {
            fehler.printf("%s: %s%n", e.code(), e.getMessage());
            List<BefundAusgabe> befunde = e instanceof KonfigurationUngueltig ungueltig
                    ? ungueltig.befunde().stream().map(b -> new BefundAusgabe(b.pfad(), b.code(), b.meldung())).toList()
                    : List.of();
            List<BestaetigungNoetig.Auswirkung> auswirkung = e instanceof BestaetigungNoetig bestaetigung
                    ? bestaetigung.auswirkung() : List.of();
            befunde.forEach(befund -> fehler.printf("%s: %s: %s%n", befund.pfad(), befund.code(), befund.meldung()));
            auswirkung.forEach(folge -> fehler.printf("%s: %s%n", folge.schluessel(), folge.text()));
            fehlerAlsJson(aufruf, aus, new FehlerAusgabe(e.code().name(), e.getMessage(), befunde, auswirkung));
            return 1;
        } catch (DateiFehler e) {
            fehler.println(e.getMessage());
            fehlerAlsJson(aufruf, aus, new FehlerAusgabe("DATEI", e.getMessage(), List.of(), List.of()));
            return TECHNISCHER_FEHLER;
        } catch (IOException e) {
            String meldung = "Datei nicht lesbar oder nicht schreibbar (" + e.getClass().getSimpleName() + ").";
            fehler.println(meldung);
            fehlerAlsJson(aufruf, aus, new FehlerAusgabe("DATEI", meldung, List.of(), List.of()));
            return TECHNISCHER_FEHLER;
        } catch (RuntimeException e) {
            LOG.errorf("Konfigurationskommando %s für Mandant %s: technischer Fehler %s", aufruf.operation(),
                    aufruf.mandant().wert(), e.getClass().getName());
            String meldung = "Technischer Fehler (" + e.getClass().getSimpleName() + "); nichts geändert, Details im Log.";
            fehler.println(meldung);
            fehlerAlsJson(aufruf, aus, new FehlerAusgabe("TECHNISCHER_FEHLER", meldung, List.of(), List.of()));
            return TECHNISCHER_FEHLER;
        }
    }

    record FehlerAusgabe(String code, String meldung, List<BefundAusgabe> befunde,
                         List<BestaetigungNoetig.Auswirkung> auswirkung) {
    }

    private void fehlerAlsJson(Aufruf aufruf, PrintStream aus, FehlerAusgabe ausgabe) {
        if (!aufruf.json()) {
            return;
        }
        try {
            aus.println(json.writeValueAsString(ausgabe));
        } catch (JsonProcessingException e) {
            LOG.errorf("Fehlerausgabe als JSON nicht möglich: %s", e.getClass().getName());
        }
    }

    /** Fehler an der Datei des Betreibers mit fester, inhaltsfreier Meldung. */
    static final class DateiFehler extends IOException {
        DateiFehler(String meldung) {
            super(meldung);
        }
    }

    private int exportieren(Aufruf aufruf, PrintStream aus) throws IOException {
        String datei = kontext.mit(aufruf.mandant(), () -> verwaltung.exportieren().datei());
        if (aufruf.datei().isPresent()) {
            dateiSchreiben(aufruf.datei().get(), datei);
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
                "betreiber:" + aufruf.wer(), aufruf.grund(), aufruf.bestaetigt(),
                EinstellungsAenderungsPruefung.Ursprung.BETREIBERKOMMANDO));
        if (aufruf.json()) {
            aus.println(json.writeValueAsString(ergebnis));
        } else {
            aus.printf("Konfiguration eingespielt: Version %d, %d Änderung(en)%n",
                    ergebnis.version(), ergebnis.aenderungen().size());
            ergebnis.aenderungen().forEach(aenderung -> aenderungAusgeben(aus, aenderung));
        }
        return 0;
    }

    private int kiFreigeben(Aufruf aufruf, PrintStream aus) throws IOException {
        int version = kontext.mit(aufruf.mandant(), () -> verwaltung.kiFreigeben("betreiber:" + aufruf.wer(),
                aufruf.abnahme()));
        if (aufruf.json()) {
            aus.println(json.writeValueAsString(new FreigabeAusgabe(version, true)));
        } else {
            aus.printf("KI für Mandant %s freigegeben: Konfigurationsversion %d%n",
                    aufruf.mandant().wert(), version);
        }
        return 0;
    }

    /** Liest höchstens {@code DATEI_MAX + 1} Bytes; {@code Files.size} ist bei Pipes 0 und taugt nicht als Grenze. */
    static String dateiLesen(Path pfad) throws IOException {
        byte[] inhalt;
        try (InputStream ein = Files.newInputStream(pfad)) {
            inhalt = ein.readNBytes(DATEI_MAX + 1);
        }
        if (inhalt.length > DATEI_MAX) {
            throw new DateiFehler("Die Datei überschreitet 1 MiB.");
        }
        try {
            return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(inhalt)).toString();
        } catch (java.nio.charset.CharacterCodingException e) {
            throw new DateiFehler("Die Datei ist kein gültiges UTF-8.");
        }
    }

    /** Legt die Datei neu an (nie überschreiben, keinem Symlink folgen), unter POSIX mit Rechten 600. */
    static void dateiSchreiben(Path pfad, String inhalt) throws IOException {
        Set<StandardOpenOption> optionen = Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        byte[] bytes = inhalt.getBytes(StandardCharsets.UTF_8);
        try (SeekableByteChannel kanal = pfad.getFileSystem().supportedFileAttributeViews().contains("posix")
                ? Files.newByteChannel(pfad, optionen,
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
                : Files.newByteChannel(pfad, optionen)) {
            ByteBuffer puffer = ByteBuffer.wrap(bytes);
            while (puffer.hasRemaining()) {
                kanal.write(puffer);
            }
        } catch (FileAlreadyExistsException e) {
            throw new DateiFehler("Die Datei existiert schon; der Export überschreibt keine Datei.");
        }
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
