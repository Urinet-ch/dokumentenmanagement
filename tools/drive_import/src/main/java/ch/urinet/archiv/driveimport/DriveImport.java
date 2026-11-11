package ch.urinet.archiv.driveimport;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * Einmaliger Import des Google-Drive-Altbestands (Spec 01, Requirement 12; Aufgabe 4.8). Liest einen lokalen Ordner
 * (Google Drive für Desktop) rekursiv, ohne symbolischen Links zu folgen, und lädt jede Datei mit ihrem Ordnerpfad als
 * {@code hinweis} über {@code POST /api/v1/dokumente} hoch. Das Archiv behandelt sie wie jeden Upload: Eingangskorb,
 * nie direkt archiviert (Req 12.8). Exit-Codes: 0 alles übertragen oder schon vorhanden, 1 mindestens eine Datei
 * abgelehnt, 2 Aufruf oder Token-Datei ungültig, 3 Lauf abgebrochen (Token abgelehnt).
 */
@Command(name = "drive-import", version = "drive-import 0.1.0", sortOptions = false, synopsisHeading = "Aufruf: ",
        description = {"Lädt einen lokalen Ordner (Google Drive für Desktop) rekursiv ins UriNet Archiv.",
                "Jede Datei kommt mit ihrem Ordnerpfad als Hinweis in den Eingangskorb."},
        exitCodeListHeading = "%nExit-Codes:%n",
        exitCodeList = {"0:alles übertragen oder schon vorhanden", "1:mindestens eine Datei abgelehnt",
                "2:Aufruf oder Token-Datei ungültig", "3:Lauf abgebrochen (Token abgelehnt)"})
public final class DriveImport implements Callable<Integer> {

    public static final int OK = 0;
    public static final int ABGELEHNT = 1;
    public static final int UNGUELTIG = 2;
    public static final int ABGEBROCHEN = 3;
    static final String STANDARD_ARCHIV = "https://archiv.apps.urinet.ch";
    private static final Set<String> LOKAL = Set.of("localhost", "127.0.0.1", "[::1]");

    @Parameters(index = "0", paramLabel = "ORDNER", description = "Startordner, z. B. «~/Google Drive/UriNet»")
    Path ordner;

    @Option(names = {"-n", "--trockenlauf"}, description = "Nur zählen und auflisten, nichts übertragen")
    boolean trockenlauf;

    @Option(names = "--archiv", paramLabel = "URL", defaultValue = STANDARD_ARCHIV,
            description = "Adresse des Archivs (Standard: ${DEFAULT-VALUE}); http nur für localhost")
    String archiv;

    @Option(names = "--token-datei", paramLabel = "DATEI",
            description = "Datei mit dem API-Token (Scope «import», Rechte 600); sonst $" + TokenDatei.UMGEBUNG
                    + ", sonst ~/.config/urinet-archiv/token")
    Path tokenDatei;

    @Option(names = {"-h", "--hilfe", "--help"}, usageHelp = true, description = "Diese Hilfe anzeigen")
    boolean hilfe;

    @Option(names = {"-V", "--version"}, versionHelp = true, description = "Version anzeigen")
    boolean version;

    private final Map<String, String> umgebung;
    private final ArchivClient.Warten warten;
    private PrintWriter aus;
    private PrintWriter fehler;

    DriveImport(Map<String, String> umgebung, ArchivClient.Warten warten) {
        this.umgebung = umgebung;
        this.warten = warten;
    }

    public static void main(String[] args) {
        PrintWriter aus = new PrintWriter(System.out, true, StandardCharsets.UTF_8);
        PrintWriter fehler = new PrintWriter(System.err, true, StandardCharsets.UTF_8);
        System.exit(ausfuehren(aus, fehler, System.getenv(), args));
    }

    /** Führt das Werkzeug aus, ohne die JVM zu beenden (für Tests); liefert den Exit-Code. */
    public static int ausfuehren(PrintWriter aus, PrintWriter fehler, Map<String, String> umgebung, String... args) {
        return ausfuehren(aus, fehler, umgebung, d -> Thread.sleep(d.toMillis()), args);
    }

    static int ausfuehren(PrintWriter aus, PrintWriter fehler, Map<String, String> umgebung,
                          ArchivClient.Warten warten, String... args) {
        DriveImport werkzeug = new DriveImport(umgebung, warten);
        werkzeug.aus = aus;
        werkzeug.fehler = fehler;
        CommandLine befehl = new CommandLine(werkzeug);
        befehl.setOut(aus);
        befehl.setErr(fehler);
        befehl.setExecutionExceptionHandler((e, cmd, parse) -> {
            fehler.println("Unerwarteter Fehler (" + e.getClass().getSimpleName() + "), Lauf abgebrochen.");
            return ABGEBROCHEN;
        });
        int code = befehl.execute(args);
        aus.flush();
        fehler.flush();
        return code;
    }

    @Override
    public Integer call() throws InterruptedException {
        URI ziel = archivAdresse();
        if (ziel == null) {
            return UNGUELTIG;
        }
        Path wurzel;
        try {
            wurzel = ordner.toRealPath();
        } catch (IOException e) {
            fehler.println("Ordner " + ordner + " nicht gefunden oder nicht lesbar.");
            return UNGUELTIG;
        }
        if (!Files.isDirectory(wurzel)) {
            fehler.println(ordner + " ist kein Ordner.");
            return UNGUELTIG;
        }
        ArchivClient client = null;
        if (!trockenlauf) {
            try {
                Path datei = TokenDatei.pfad(tokenDatei, umgebung, System.getProperty("user.home"));
                client = new ArchivClient(ziel, TokenDatei.lesen(datei), warten);
            } catch (TokenDatei.Ungueltig e) {
                fehler.println(e.getMessage());
                return UNGUELTIG;
            }
        }
        List<Fund> funde = durchsuchen(wurzel);
        Bericht bericht = new Bericht(trockenlauf);
        aus.println((trockenlauf ? "Trockenlauf" : "Import nach " + ziel) + ": " + funde.size() + " Dateien in "
                + anzeige(wurzel, wurzel));
        String abbruch = null;
        for (Fund fund : funde) {
            String pfad = anzeige(wurzel, fund.pfad());
            if (abbruch != null) {
                bericht.uebersprungen(pfad, "Lauf abgebrochen: " + abbruch);
                aus.println("übersprungen:     " + pfad + " – Lauf abgebrochen");
                continue;
            }
            if (fund.problem() != null) {
                if (fund.lesefehler()) {
                    bericht.abgelehnt(pfad, fund.problem());
                    aus.println("abgelehnt:        " + pfad + " – " + fund.problem());
                } else {
                    bericht.uebersprungen(pfad, fund.problem());
                    aus.println("übersprungen:     " + pfad + " – " + fund.problem());
                }
                continue;
            }
            Einordnung einordnung = Einordnung.von(fund.pfad().getFileName().toString());
            switch (einordnung.weg()) {
                case GOOGLE -> {
                    bericht.googleFormat(pfad);
                    aus.println("übersprungen:     " + pfad + " – " + einordnung.grund());
                }
                case NICHT_UEBERNOMMEN -> {
                    bericht.uebersprungen(pfad, einordnung.grund());
                    aus.println("übersprungen:     " + pfad + " – " + einordnung.grund());
                }
                case SENDEN -> {
                    String hinweis = OrdnerHinweis.von(wurzel, fund.pfad());
                    if (client == null) {
                        bericht.uebertragen();
                        aus.println("würde übertragen: " + pfad + "  [" + hinweis + "]");
                    } else {
                        String name = OrdnerHinweis.sauber(fund.pfad().getFileName().toString());
                        ArchivClient.Ergebnis ergebnis = client.senden(fund.pfad(), name, hinweis);
                        switch (ergebnis.status()) {
                            case UEBERTRAGEN -> {
                                bericht.uebertragen();
                                aus.println("übertragen:       " + pfad);
                            }
                            case SCHON_VORHANDEN -> {
                                bericht.schonVorhanden();
                                aus.println("schon vorhanden:  " + pfad);
                            }
                            case ABGELEHNT -> {
                                bericht.abgelehnt(pfad, ergebnis.grund());
                                aus.println("abgelehnt:        " + pfad + " – " + ergebnis.grund());
                            }
                            case ABBRUCH -> {
                                abbruch = ergebnis.grund();
                                bericht.abgelehnt(pfad, ergebnis.grund());
                                aus.println("abgelehnt:        " + pfad + " – " + ergebnis.grund());
                                fehler.println("Lauf abgebrochen: " + ergebnis.grund());
                            }
                        }
                    }
                }
            }
        }
        bericht.ausgeben(aus);
        if (abbruch != null) {
            return ABGEBROCHEN;
        }
        return bericht.abgelehnt() > 0 ? ABGELEHNT : OK;
    }

    /** Gefundene Datei; {@code problem} gesetzt, wenn sie nicht gesendet werden kann. */
    private record Fund(Path pfad, String problem, boolean lesefehler) {
    }

    private List<Fund> durchsuchen(Path wurzel) {
        List<Fund> funde = new ArrayList<>();
        try {
            Files.walkFileTree(wurzel, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path datei, BasicFileAttributes attribute) {
                    if (attribute.isSymbolicLink()) {
                        funde.add(new Fund(datei, "symbolischer Link, nicht verfolgt", false));
                    } else if (!attribute.isRegularFile()) {
                        funde.add(new Fund(datei, "keine gewöhnliche Datei", false));
                    } else {
                        funde.add(new Fund(datei, null, false));
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path datei, IOException e) {
                    funde.add(new Fund(datei, "nicht lesbar, Inhalt nicht geprüft", true));
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            funde.add(new Fund(wurzel, "Ordner nicht lesbar", true));
        }
        funde.sort(Comparator.comparing(f -> f.pfad().toString()));
        return funde;
    }

    private static String anzeige(Path wurzel, Path pfad) {
        Path basis = wurzel.getParent() == null ? wurzel : wurzel.getParent();
        String text = basis.relativize(pfad).toString().replace('\\', '/');
        return OrdnerHinweis.sauber(text.isEmpty() ? pfad.toString() : text);
    }

    private URI archivAdresse() {
        URI uri;
        try {
            uri = URI.create(archiv.strip());
        } catch (IllegalArgumentException e) {
            fehler.println("--archiv: «" + archiv + "» ist keine gültige Adresse.");
            return null;
        }
        String schema = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (host.isEmpty() || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || !(schema.equals("https") || schema.equals("http") && LOKAL.contains(host))) {
            fehler.println("--archiv: nur https://<host> erlaubt, http nur für localhost.");
            return null;
        }
        return uri;
    }
}
