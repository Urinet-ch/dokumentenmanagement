package ch.urinet.archiv.wissenpaket;

import ch.urinet.archiv.wissen.domain.paket.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import picocli.CommandLine;
import picocli.CommandLine.*;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Callable;

@Command(name = "wissen_paket", mixinStandardHelpOptions = true, subcommands = {
        WissenPaketCli.Validate.class, WissenPaketCli.Bauen.class })
public final class WissenPaketCli implements Callable<Integer> {
    @Spec CommandLine.Model.CommandSpec spec;
    static final String REGEL = "Cloud-Modelle: nur NORMAL ohne Personendaten, auf Verantwortung des Inhabers; "
            + "INTERN, PERSONAL und daraus Abgeleitetes nie. Lokal: keine Personendaten und kein PERSONAL.";

    public static void main(String[] args) {
        System.exit(command(new PrintWriter(System.out, true), new PrintWriter(System.err, true)).execute(args));
    }

    static CommandLine command(PrintWriter out, PrintWriter err) {
        CommandLine cli = new CommandLine(new WissenPaketCli()).setOut(out).setErr(err);
        cli.setParameterExceptionHandler((ex, args) -> {
            ausgeben(out, Arrays.asList(args).contains("--json"),
                    List.of(new OfflinePruefung.Meldung("ARGUMENT_UNGUELTIG", "")), List.of());
            return 1;
        });
        return cli;
    }

    @Override public Integer call() {
        ausgeben(spec.commandLine().getOut(), false,
                List.of(new OfflinePruefung.Meldung("ARGUMENT_UNGUELTIG", "")), List.of());
        return 1;
    }

    abstract static class Befehl implements Callable<Integer> {
        @Spec CommandLine.Model.CommandSpec spec;
        @Parameters(index = "0", paramLabel = "EINGABE") Path eingabe;
        @Option(names = "--quellen") Path quellen;
        @Option(names = "--json") boolean json;

        abstract byte[] daten() throws IOException;
        void schreiben(byte[] zip) throws IOException {}

        @Override public Integer call() {
            try {
                byte[] zip = daten();
                WissensPaket paket = new WissensPaketLeser().lesen(zip);
                OfflinePruefung.Bericht bericht = OfflinePruefung.pruefen(paket);
                List<OfflinePruefung.Meldung> hinweise = new ArrayList<>(bericht.hinweise());
                if (quellen != null) hinweise.addAll(LokaleQuellen.pruefen(paket, quellen));
                if (bericht.gueltig()) schreiben(zip);
                ausgeben(spec.commandLine().getOut(), json, bericht.befunde(), hinweise);
                return bericht.gueltig() ? 0 : 1;
            } catch (WissensPaketException ex) {
                ausgeben(spec.commandLine().getOut(), json, List.of(new OfflinePruefung.Meldung(ex.code().name(), ex.pfad())), List.of());
            } catch (IOException | RuntimeException ex) {
                ausgeben(spec.commandLine().getOut(), json, List.of(new OfflinePruefung.Meldung("EINGABE_AUSGABE_FEHLER", "")), List.of());
            }
            return 1;
        }
    }

    @Command(name = "validate", mixinStandardHelpOptions = true)
    static final class Validate extends Befehl {
        @Override byte[] daten() throws IOException {
            return Files.isDirectory(eingabe, LinkOption.NOFOLLOW_LINKS)
                    ? PaketDateien.ordner(eingabe, null) : PaketDateien.zipLesen(eingabe);
        }
    }

    @Command(name = "bauen", mixinStandardHelpOptions = true)
    static final class Bauen extends Befehl {
        @Option(names = {"-o", "--ausgabe"}, required = true) Path ausgabe;
        @Option(names = "--mandant", required = true) String mandant;
        @Option(names = "--erzeugt-von", required = true) String erzeugtVon;
        @Option(names = "--erzeugt-mit", required = true) String erzeugtMit;
        @Option(names = "--kontext-stufe", required = true) String stufe;
        @Option(names = "--erzeugt-am", defaultValue = "1970-01-01T00:00:00Z") String erzeugtAm;
        @Option(names = "--ueberschreiben") boolean ueberschreiben;

        @Override byte[] daten() throws IOException {
            Path absolut = ausgabe.toAbsolutePath().normalize();
            Path ziel = absolut.getParent().toRealPath().resolve(absolut.getFileName());
            if (ziel.startsWith(eingabe.toRealPath()) || quellen != null && ziel.startsWith(quellen.toRealPath()))
                throw new IOException();
            if (Files.exists(ausgabe, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isRegularFile(ausgabe, LinkOption.NOFOLLOW_LINKS) || gemeinsam(eingabe, ausgabe)
                        || quellen != null && gemeinsam(quellen, ausgabe)) throw new IOException();
            }
            Instant.parse(erzeugtAm);
            String kopf = "paket_format: 1\nmandant: " + quote(mandant) + "\nerzeugt_von: " + quote(erzeugtVon)
                    + "\nerzeugt_mit: " + quote(erzeugtMit) + "\nkontext_stufe: " + quote(stufe)
                    + "\nerzeugt_am: " + quote(erzeugtAm) + "\n";
            return PaketDateien.ordner(eingabe, kopf.getBytes(StandardCharsets.UTF_8));
        }

        @Override void schreiben(byte[] zip) throws IOException {
            if (Files.isSymbolicLink(ausgabe)) throw new IOException();
            if (ueberschreiben) {
                try (var out = Files.newOutputStream(ausgabe, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) { out.write(zip); }
            } else {
                try (var out = Files.newOutputStream(ausgabe, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                        LinkOption.NOFOLLOW_LINKS)) { out.write(zip); }
            }
        }
        private static String quote(String text) { return "'" + text.replace("'", "''") + "'"; }

        private static boolean gemeinsam(Path ordner, Path ziel) throws IOException {
            try (var lauf = Files.walk(ordner)) {
                for (Iterator<Path> it = lauf.iterator(); it.hasNext();) {
                    Path datei = it.next();
                    if (Files.isRegularFile(datei, LinkOption.NOFOLLOW_LINKS) && Files.isSameFile(datei, ziel)) return true;
                }
                return false;
            }
        }
    }

    static void ausgeben(PrintWriter out, boolean json, List<OfflinePruefung.Meldung> befunde,
                         List<OfflinePruefung.Meldung> hinweise) {
        if (json) {
            try {
                out.println(new ObjectMapper().writeValueAsString(Map.of("befunde", befunde, "hinweise", hinweise,
                        "prozesshinweis", REGEL)));
            } catch (IOException ex) { throw new IllegalStateException(ex); }
        } else {
            befunde.forEach(b -> out.println("BEFUND " + b.code() + " " + sicher(b.pfad())));
            hinweise.forEach(h -> out.println("HINWEIS " + h.code() + " " + sicher(h.pfad())));
            out.println("PROZESSHINWEIS " + REGEL);
        }
        out.flush();
    }

    private static String sicher(String pfad) {
        return pfad.codePoints().collect(StringBuilder::new,
                (s, c) -> { if (!Character.isISOControl(c)) s.appendCodePoint(c); },
                StringBuilder::append).toString();
    }
}
