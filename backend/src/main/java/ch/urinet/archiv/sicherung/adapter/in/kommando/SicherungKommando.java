package ch.urinet.archiv.sicherung.adapter.in.kommando;

import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.sicherung.application.SicherungBucketExport;
import ch.urinet.archiv.sicherung.application.SicherungExportFehler;
import ch.urinet.archiv.sicherung.application.SicherungExportVerwaltung;
import ch.urinet.archiv.sicherung.application.Wiederherstellung;
import ch.urinet.archiv.sicherung.application.WiederherstellungsReferenz;
import ch.urinet.archiv.sicherung.application.WiederherstellungsZiel;
import ch.urinet.archiv.sicherung.application.WiederherstellungsZielNichtGeeignet;
import ch.urinet.archiv.sicherung.application.Wiederherstellung.Ergebnis;
import ch.urinet.archiv.sicherung.application.Wiederherstellung.Zaehler;
import ch.urinet.archiv.sicherung.application.SicherungBucketExport.InventarZeile;
import ch.urinet.archiv.sicherung.domain.SicherungsArt;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/** Admin-Kommandos für Inventar, Bucket-Zuwachs und Sicherungsnachweise. */
@ApplicationScoped
public class SicherungKommando {

    public static final String AUFRUF = "Aufruf: archiv sicherung inventar --mandant <code> | "
            + "zuwachs --mandant <code> [--inventar-vorher <datei|->] | "
            + "wiederherstellen --mandant <code> --inventar <datei> --strom <datei> [--kappen] | "
            + "festhalten --art <db|keycloak|realm|schluessel|bucket|inventar> [--mandant <code>] --sha256 <hex>";
    private static final Pattern SHA256 = Pattern.compile("^[0-9a-fA-F]{64}$");

    private final SicherungBucketExport bucketExport;
    private final SicherungExportVerwaltung verwaltung;
    private final ObjectMapper json;
    private final Path arbeitsverzeichnis;
    private final Supplier<Optional<WiederherstellungsZiel>> wiederherstellungsZiel;
    private final WiederherstellungsReferenz wiederherstellungsReferenz;
    private final Clock uhr;
    private final boolean ablageStub;

    @Inject
    public SicherungKommando(SicherungBucketExport bucketExport, SicherungExportVerwaltung verwaltung,
                             ObjectMapper json, Instance<WiederherstellungsZiel> ziel,
                             WiederherstellungsReferenz referenz,
                             @ConfigProperty(name = "archiv.ablage.stub") boolean ablageStub,
                             @ConfigProperty(name = "archiv.arbeit.verzeichnis") Path arbeitsverzeichnis) {
        this(bucketExport, verwaltung, json,
                () -> ziel.isUnsatisfied() ? Optional.empty() : Optional.of(ziel.get()), Clock.systemUTC(),
                referenz, ablageStub, arbeitsverzeichnis);
    }

    public SicherungKommando(SicherungBucketExport bucketExport, SicherungExportVerwaltung verwaltung,
                             ObjectMapper json) {
        this(bucketExport, verwaltung, json, Optional::empty, Clock.systemUTC(),
                (mandant, schluessel, alt, neu) -> { }, false,
                Path.of(System.getProperty("java.io.tmpdir")));
    }

    public SicherungKommando(SicherungBucketExport bucketExport, SicherungExportVerwaltung verwaltung,
                             ObjectMapper json, Path arbeitsverzeichnis) {
        this(bucketExport, verwaltung, json, Optional::empty, Clock.systemUTC(),
                (mandant, schluessel, alt, neu) -> { }, false, arbeitsverzeichnis);
    }

    SicherungKommando(SicherungBucketExport bucketExport, SicherungExportVerwaltung verwaltung,
                       ObjectMapper json, WiederherstellungsZiel ziel, Clock uhr) {
        this(bucketExport, verwaltung, json, () -> Optional.of(ziel), uhr,
                (mandant, schluessel, alt, neu) -> { }, false, Path.of(System.getProperty("java.io.tmpdir")));
    }

    private SicherungKommando(SicherungBucketExport bucketExport, SicherungExportVerwaltung verwaltung,
                              ObjectMapper json, Supplier<Optional<WiederherstellungsZiel>> ziel, Clock uhr,
                              WiederherstellungsReferenz referenz,
                              boolean ablageStub, Path arbeitsverzeichnis) {
        this.bucketExport = bucketExport;
        this.verwaltung = verwaltung;
        this.json = json;
        wiederherstellungsZiel = ziel;
        wiederherstellungsReferenz = referenz;
        this.uhr = uhr;
        this.ablageStub = ablageStub;
        this.arbeitsverzeichnis = arbeitsverzeichnis;
    }

    SicherungKommando(SicherungBucketExport bucketExport, SicherungExportVerwaltung verwaltung,
                      ObjectMapper json, WiederherstellungsZiel ziel, Clock uhr, boolean ablageStub) {
        this(bucketExport, verwaltung, json, () -> Optional.of(ziel), uhr,
                (mandant, schluessel, alt, neu) -> { }, ablageStub,
                Path.of(System.getProperty("java.io.tmpdir")));
    }

    public enum Befehl {
        INVENTAR, ZUWACHS, WIEDERHERSTELLEN, FESTHALTEN
    }

    public record Aufruf(Befehl befehl, Optional<MandantCode> mandant, Optional<String> inventarVorher,
                         Optional<String> inventar, Optional<String> strom, boolean kappen,
                         Optional<SicherungsArt> art, String sha256) {
    }

    public static Optional<Aufruf> lesen(List<String> argumente) {
        if (argumente.isEmpty()) {
            return Optional.empty();
        }
        Befehl befehl = switch (argumente.getFirst()) {
            case "inventar" -> Befehl.INVENTAR;
            case "zuwachs" -> Befehl.ZUWACHS;
            case "wiederherstellen" -> Befehl.WIEDERHERSTELLEN;
            case "festhalten" -> Befehl.FESTHALTEN;
            default -> null;
        };
        if (befehl == null) {
            return Optional.empty();
        }
        Optional<MandantCode> mandant = Optional.empty();
        Optional<String> inventarVorher = Optional.empty();
        Optional<String> inventar = Optional.empty();
        Optional<String> strom = Optional.empty();
        boolean kappen = false;
        Optional<SicherungsArt> art = Optional.empty();
        String sha256 = null;
        try {
            for (int i = 1; i < argumente.size(); i++) {
                String schalter = argumente.get(i);
                if (schalter.equals("--kappen")) {
                    if (befehl != Befehl.WIEDERHERSTELLEN || kappen) {
                        return Optional.empty();
                    }
                    kappen = true;
                    continue;
                }
                if (i + 1 >= argumente.size()) {
                    return Optional.empty();
                }
                String wert = argumente.get(++i);
                switch (schalter) {
                    case "--mandant" -> {
                        if (mandant.isPresent()) {
                            return Optional.empty();
                        }
                        mandant = Optional.of(new MandantCode(wert));
                    }
                    case "--inventar-vorher" -> {
                        if (befehl != Befehl.ZUWACHS || inventarVorher.isPresent() || wert.isBlank()) {
                            return Optional.empty();
                        }
                        if (!wert.equals("-")) {
                            Path.of(wert);
                        }
                        inventarVorher = Optional.of(wert);
                    }
                    case "--inventar" -> {
                        if (befehl != Befehl.WIEDERHERSTELLEN || inventar.isPresent() || wert.isBlank()) {
                            return Optional.empty();
                        }
                        Path.of(wert);
                        inventar = Optional.of(wert);
                    }
                    case "--strom" -> {
                        if (befehl != Befehl.WIEDERHERSTELLEN || strom.isPresent() || wert.isBlank()) {
                            return Optional.empty();
                        }
                        Path.of(wert);
                        strom = Optional.of(wert);
                    }
                    case "--art" -> {
                        if (befehl != Befehl.FESTHALTEN || art.isPresent()) {
                            return Optional.empty();
                        }
                        art = SicherungsArt.aus(wert);
                        if (art.isEmpty()) {
                            return Optional.empty();
                        }
                    }
                    case "--sha256" -> {
                        if (befehl != Befehl.FESTHALTEN || sha256 != null || !SHA256.matcher(wert).matches()) {
                            return Optional.empty();
                        }
                        sha256 = wert.toLowerCase(Locale.ROOT);
                    }
                    default -> {
                        return Optional.empty();
                    }
                }
            }
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        if ((befehl == Befehl.INVENTAR || befehl == Befehl.ZUWACHS
                || befehl == Befehl.WIEDERHERSTELLEN) && mandant.isEmpty()) {
            return Optional.empty();
        }
        if (befehl == Befehl.WIEDERHERSTELLEN && (inventar.isEmpty() || strom.isEmpty())
                || befehl != Befehl.WIEDERHERSTELLEN && (inventar.isPresent() || strom.isPresent() || kappen)) {
            return Optional.empty();
        }
        if (befehl == Befehl.FESTHALTEN && (art.isEmpty() || sha256 == null
                || art.get().mandantPflicht() != mandant.isPresent())) {
            return Optional.empty();
        }
        return Optional.of(new Aufruf(befehl, mandant, inventarVorher, inventar, strom, kappen, art, sha256));
    }

    public int ausfuehren(List<String> argumente, PrintStream aus, PrintStream fehler) {
        Optional<Aufruf> gelesen = lesen(argumente);
        if (gelesen.isEmpty()) {
            fehler.println(AUFRUF);
            return 2;
        }
        Aufruf aufruf = gelesen.get();
        if (aufruf.mandant().isPresent() && !verwaltung.mandantAktiv(aufruf.mandant().get())) {
            fehler.println("Unbekannter oder inaktiver Mandant.");
            return 2;
        }
        try {
            switch (aufruf.befehl()) {
                case INVENTAR -> bucketExport.inventar(aufruf.mandant().orElseThrow(), zeile -> {
                    try {
                        aus.println(json.writeValueAsString(zeile));
                    } catch (JsonProcessingException e) {
                        throw new IllegalStateException("Inventarzeile nicht serialisiert", e);
                    }
                });
                case ZUWACHS -> zuwachs(aufruf, aus);
                case WIEDERHERSTELLEN -> {
                    return wiederherstellen(aufruf, aus, fehler);
                }
                case FESTHALTEN -> {
                    verwaltung.festhalten(aufruf.art().orElseThrow(), aufruf.mandant(), aufruf.sha256());
                    aus.println("Sicherungsexport festgehalten.");
                }
            }
            if (aus.checkError()) {
                throw new IllegalStateException("Ausgabestrom nicht schreibbar");
            }
            return 0;
        } catch (SicherungExportFehler e) {
            fehler.println("Ungültiger oder inaktiver Mandant.");
            return 2;
        } catch (InventarDateiFehler e) {
            fehler.println("Das Vorinventar ist ungültig.");
            return 2;
        } catch (RuntimeException e) {
            fehler.println("Technischer Fehler: " + e.getClass().getSimpleName());
            return 3;
        }
    }

    private int wiederherstellen(Aufruf aufruf, PrintStream aus, PrintStream fehler) {
        Ergebnis ergebnis;
        if (ablageStub) {
            ergebnis = new Ergebnis(2, new Zaehler(0, 0, 0, 0, 0, 0));
            fehler.printf("Wiederherstellung im Ablage-Stub nicht möglich (Mandant %s).%n",
                    aufruf.mandant().orElseThrow().wert());
        } else {
            try {
                List<InventarZeile> zeilen = inventarLesen(Path.of(aufruf.inventar().orElseThrow()));
                Optional<WiederherstellungsZiel> ziel = wiederherstellungsZiel.get();
                if (ziel.isEmpty()) {
                    ergebnis = new Ergebnis(3, new Zaehler(0, 0, 0, 0, 0, 0));
                    fehler.println("Wiederherstellungsziel ist nicht angebunden.");
                } else {
                    ergebnis = new Wiederherstellung(ziel.get(), uhr, wiederherstellungsReferenz).wiederherstellen(
                            aufruf.mandant().orElseThrow(), zeilen, Path.of(aufruf.strom().orElseThrow()),
                            aufruf.kappen());
                    if (ergebnis.exitCode() != 0) {
                        fehler.println(schlechteWiederherstellung(ergebnis.exitCode()));
                    }
                }
            } catch (WiederherstellungsZielNichtGeeignet e) {
                ergebnis = new Ergebnis(2, new Zaehler(0, 0, 0, 0, 0, 0));
                fehler.println(e.getMessage());
            } catch (IOException | IllegalArgumentException | SicherungExportFehler e) {
                ergebnis = new Ergebnis(3, new Zaehler(0, 0, 0, 0, 0, 0));
                fehler.println("Wiederherstellung fehlgeschlagen.");
            } catch (RuntimeException e) {
                ergebnis = new Ergebnis(3, new Zaehler(0, 0, 0, 0, 0, 0));
                fehler.println("Wiederherstellung fehlgeschlagen: " + e.getClass().getSimpleName());
            }
        }
        try {
            aus.println(json.writeValueAsString(ergebnis.zaehler()));
            if (aus.checkError()) {
                fehler.println("Wiederherstellungsstatus nicht schreibbar.");
                return 3;
            }
        } catch (JsonProcessingException e) {
            fehler.println("Wiederherstellungsstatus nicht serialisiert.");
            return 3;
        }
        return ergebnis.exitCode();
    }

    private static String schlechteWiederherstellung(int code) {
        return switch (code) {
            case 2 -> "Wiederherstellung abgebrochen: Ziel nicht leer oder Kappung nicht zulässig.";
            case 3 -> "Wiederherstellung wegen eines Format-, Prüf- oder Schreibfehlers abgebrochen.";
            case 4 -> "Wiederherstellung abgebrochen: Exportkette unvollständig oder überzählig.";
            default -> "Wiederherstellung fehlgeschlagen.";
        };
    }

    private List<InventarZeile> inventarLesen(Path datei) throws IOException {
        List<InventarZeile> zeilen = new ArrayList<>();
        try (BufferedReader leser = Files.newBufferedReader(datei, StandardCharsets.UTF_8)) {
            for (String zeile; (zeile = leser.readLine()) != null; ) {
                if (zeile.isBlank()) {
                    throw new IOException("Ungültige Inventarzeile");
                }
                zeilen.add(json.readValue(zeile, InventarZeile.class));
            }
        }
        return zeilen;
    }

    /** Bricht beim ersten Schreibfehler ab; PrintStream meldet ihn sonst nur über checkError() am Ende. */
    private static OutputStream pruefend(PrintStream aus) {
        return new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                aus.write(b);
                pruefen();
            }

            @Override
            public void write(byte[] daten, int ab, int laenge) throws IOException {
                aus.write(daten, ab, laenge);
                pruefen();
            }

            @Override
            public void flush() throws IOException {
                aus.flush();
                pruefen();
            }

            private void pruefen() throws IOException {
                if (aus.checkError()) {
                    throw new IOException("Ausgabestrom nicht schreibbar");
                }
            }
        };
    }

    private void zuwachs(Aufruf aufruf, PrintStream stdout) {
        Optional<String> vorher = aufruf.inventarVorher();
        if (vorher.isEmpty()) {
            bucketExport.zuwachs(aufruf.mandant().orElseThrow(), version -> true, pruefend(stdout));
            return;
        }
        String quelle = vorher.get();
        if (quelle.equals("-")) {
            try (var zwischenablage = new VorinventarZwischenablage(arbeitsverzeichnis)) {
                Files.copy(System.in, zwischenablage.pfad(), StandardCopyOption.REPLACE_EXISTING);
                zuwachsMitVorinventar(aufruf.mandant().orElseThrow(), zwischenablage.pfad(), stdout);
            } catch (IOException e) {
                throw new UncheckedIOException("Vorheriges Inventar nicht lesbar", e);
            }
            return;
        }
        zuwachsMitVorinventar(aufruf.mandant().orElseThrow(), Path.of(quelle), stdout);
    }

    private void zuwachsMitVorinventar(MandantCode mandant, Path datei, PrintStream stdout) {
        try (var leser = Files.newBufferedReader(datei, StandardCharsets.UTF_8)) {
            new InventarAbgleich(leser, json).vollstaendigPruefen();
        } catch (IOException e) {
            throw new UncheckedIOException("Vorheriges Inventar nicht lesbar", e);
        }
        try (var leser = Files.newBufferedReader(datei, StandardCharsets.UTF_8)) {
            var abgleich = new InventarAbgleich(leser, json);
            bucketExport.zuwachs(mandant, abgleich, pruefend(stdout));
            abgleich.vollstaendigPruefen();
        } catch (IOException e) {
            throw new UncheckedIOException("Vorheriges Inventar nicht lesbar", e);
        }
    }

    private static final class VorinventarZwischenablage implements AutoCloseable {
        private final Path pfad;

        private VorinventarZwischenablage(Path arbeitsverzeichnis) throws IOException {
            Files.createDirectories(arbeitsverzeichnis);
            pfad = Files.createTempFile(arbeitsverzeichnis, "archiv-vorinventar-", ".jsonl");
        }

        private Path pfad() {
            return pfad;
        }

        @Override
        public void close() throws IOException {
            Files.deleteIfExists(pfad);
        }
    }
}
