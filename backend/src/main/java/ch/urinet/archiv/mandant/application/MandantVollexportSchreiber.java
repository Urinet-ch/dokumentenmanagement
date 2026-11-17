package ch.urinet.archiv.mandant.application;

import ch.urinet.archiv.ablage.application.AblagePort;
import ch.urinet.archiv.ablage.domain.Kopie;
import ch.urinet.archiv.mandant.domain.MandantCode;

import java.io.BufferedWriter;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.DigestOutputStream;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Schreibt den Vollexport als ZIP mit begrenztem Heap und einer dateibasierten Prüfsummenliste. */
public class MandantVollexportSchreiber {

    private static final int SEITE = 200;
    private static final HexFormat HEX = HexFormat.of();
    private static final String METADATEN_JSON = "metadaten.json";
    private static final String METADATEN_CSV = "metadaten.csv";
    private static final String MANIFESTE_CSV = "manifeste.csv";
    private static final String PRUEFSUMMEN = "pruefsummen.sha256";

    private final MandantVollexportDaten daten;
    private final AblagePort ablage;

    public MandantVollexportSchreiber(MandantVollexportDaten daten, AblagePort ablage) {
        this.daten = Objects.requireNonNull(daten, "daten");
        this.ablage = Objects.requireNonNull(ablage, "ablage");
    }

    public int schreiben(MandantCode mandant, UUID exportId, Path arbeitsverzeichnis, OutputStream ziel)
            throws IOException, InterruptedException {
        Files.createDirectories(arbeitsverzeichnis);
        Path pruefsummenDatei = Files.createTempFile(arbeitsverzeichnis, "mandant-vollexport-", ".sha256");
        try {
            return schreibenMitPruefsummen(mandant, exportId, pruefsummenDatei, ziel);
        } finally {
            Files.deleteIfExists(pruefsummenDatei);
        }
    }

    private int schreibenMitPruefsummen(MandantCode mandant, UUID exportId, Path pruefsummenDatei,
                                        OutputStream ziel) throws IOException, InterruptedException {
        ZipOutputStream zip = new ZipOutputStream(new NichtSchliessend(ziel), StandardCharsets.UTF_8);
        zip.setLevel(Deflater.BEST_SPEED);
        try (BufferedWriter pruefsummen = Files.newBufferedWriter(pruefsummenDatei, StandardCharsets.UTF_8)) {
            schreibeMetadatenJson(zip, pruefsummen);
            schreibeMetadatenCsv(zip, pruefsummen);
            int dokumente = schreibeDateien(mandant, zip, pruefsummen);
            schreibeManifeste(mandant, zip, pruefsummen);
            pruefsummen.flush();
            zip.putNextEntry(new ZipEntry(PRUEFSUMMEN));
            Files.copy(pruefsummenDatei, zip);
            zip.closeEntry();
            zip.finish();
            zip.flush();
            return dokumente;
        }
    }

    private void schreibeMetadatenJson(ZipOutputStream zip, BufferedWriter pruefsummen)
            throws IOException, InterruptedException {
        zip.putNextEntry(new ZipEntry(METADATEN_JSON));
        MessageDigest digest = sha256();
        OutputStream ausgabe = new DigestOutputStream(zip, digest);
        schreiben(ausgabe, "[\n");
        boolean erstes = true;
        Optional<UUID> nach = Optional.empty();
        while (true) {
            unterbrochen();
            List<MandantVollexportDaten.Dokument> seite = daten.seite(nach, SEITE);
            for (MandantVollexportDaten.Dokument dokument : seite) {
                if (!erstes) {
                    schreiben(ausgabe, ",\n");
                }
                schreiben(ausgabe, dokument.metadatenJson());
                erstes = false;
            }
            if (seite.size() < SEITE) {
                break;
            }
            nach = Optional.of(seite.getLast().id());
        }
        schreiben(ausgabe, "\n]\n");
        zip.closeEntry();
        pruefsumme(pruefsummen, digest.digest(), METADATEN_JSON);
    }

    private void schreibeMetadatenCsv(ZipOutputStream zip, BufferedWriter pruefsummen)
            throws IOException, InterruptedException {
        zip.putNextEntry(new ZipEntry(METADATEN_CSV));
        MessageDigest digest = sha256();
        OutputStream ausgabe = new DigestOutputStream(zip, digest);
        schreiben(ausgabe, csvZeile("id", "status", "art", "metadaten_json"));
        Optional<UUID> nach = Optional.empty();
        while (true) {
            unterbrochen();
            List<MandantVollexportDaten.Dokument> seite = daten.seite(nach, SEITE);
            for (MandantVollexportDaten.Dokument dokument : seite) {
                schreiben(ausgabe, csvZeile(dokument.id().toString(), dokument.status(), dokument.art(),
                        dokument.metadatenJson()));
            }
            if (seite.size() < SEITE) {
                break;
            }
            nach = Optional.of(seite.getLast().id());
        }
        zip.closeEntry();
        pruefsumme(pruefsummen, digest.digest(), METADATEN_CSV);
    }

    private int schreibeDateien(MandantCode mandant, ZipOutputStream zip, BufferedWriter pruefsummen)
            throws IOException, InterruptedException {
        int dokumente = 0;
        Optional<UUID> nach = Optional.empty();
        while (true) {
            unterbrochen();
            List<MandantVollexportDaten.Dokument> seite = daten.seite(nach, SEITE);
            for (MandantVollexportDaten.Dokument dokument : seite) {
                dokumente++;
                for (MandantVollexportDaten.Datei datei : dokument.dateien()) {
                    kopieren(mandant, zip, pruefsummen, datei.schluessel(), datei.versionId(), datei.sha256(),
                            datei.zipPfad());
                }
            }
            if (seite.size() < SEITE) {
                break;
            }
            nach = Optional.of(seite.getLast().id());
        }
        return dokumente;
    }

    private void schreibeManifeste(MandantCode mandant, ZipOutputStream zip, BufferedWriter pruefsummen)
            throws IOException, InterruptedException {
        List<MandantVollexportDaten.Manifestdatei> manifeste = daten.manifeste().stream()
                .sorted(java.util.Comparator.comparing(MandantVollexportDaten.Manifestdatei::datum)
                        .thenComparing(MandantVollexportDaten.Manifestdatei::schluessel))
                .toList();
        zip.putNextEntry(new ZipEntry(MANIFESTE_CSV));
        MessageDigest digest = sha256();
        OutputStream ausgabe = new DigestOutputStream(zip, digest);
        schreiben(ausgabe, csvZeile("datum", "endung", "schluessel", "version", "sha256", "zip_pfad"));
        LocalDate datum = null;
        int nummer = 0;
        for (MandantVollexportDaten.Manifestdatei manifest : manifeste) {
            unterbrochen();
            if (!manifest.datum().equals(datum)) {
                datum = manifest.datum();
                nummer = 0;
            }
            String pfad = "manifeste/" + datum + "/" + nummer++ + ".bin";
            schreiben(ausgabe, csvZeile(datum.toString(), manifest.endung(), manifest.schluessel(),
                    manifest.versionId(), manifest.sha256(), pfad));
        }
        zip.closeEntry();
        pruefsumme(pruefsummen, digest.digest(), MANIFESTE_CSV);

        datum = null;
        nummer = 0;
        for (MandantVollexportDaten.Manifestdatei manifest : manifeste) {
            unterbrochen();
            if (!manifest.datum().equals(datum)) {
                datum = manifest.datum();
                nummer = 0;
            }
            String pfad = "manifeste/" + datum + "/" + nummer++ + ".bin";
            kopieren(mandant, zip, pruefsummen, manifest.schluessel(), manifest.versionId(), manifest.sha256(), pfad);
        }
    }

    private void kopieren(MandantCode mandant, ZipOutputStream zip, BufferedWriter pruefsummen,
                          String schluessel, String versionId, String erwartet, String zipPfad) throws IOException {
        if (zipPfad.startsWith("/") || zipPfad.contains("..") || zipPfad.indexOf('\\') >= 0
                || !zipPfad.matches("[A-Za-z0-9._/-]+")) {
            throw new IllegalStateException("Ungültiger ZIP-Pfad im Vollexport");
        }
        zip.putNextEntry(new ZipEntry(zipPfad));
        Kopie kopie = ablage.sicherungsKopie(mandant, schluessel, versionId, zip);
        zip.closeEntry();
        if (!kopie.sha256().equalsIgnoreCase(erwartet)) {
            throw new IllegalStateException("Prüfsumme weicht ab bei Exportdatei " + zipPfad);
        }
        pruefsumme(pruefsummen, HEX.parseHex(kopie.sha256()), zipPfad);
    }

    static String csvZeile(String... zellen) {
        StringBuilder zeile = new StringBuilder();
        for (int i = 0; i < zellen.length; i++) {
            if (i > 0) {
                zeile.append(',');
            }
            String wert = Objects.requireNonNullElse(zellen[i], "");
            if (!wert.isEmpty() && "=+-@".indexOf(wert.charAt(0)) >= 0) {
                wert = "'" + wert;
            }
            zeile.append('"').append(wert.replace("\"", "\"\"")).append('"');
        }
        return zeile.append("\r\n").toString();
    }

    private static void schreiben(OutputStream ausgabe, String text) throws IOException {
        ausgabe.write(text.getBytes(StandardCharsets.UTF_8));
    }

    private static void pruefsumme(BufferedWriter pruefsummen, byte[] digest, String pfad) throws IOException {
        pruefsummen.write(HEX.formatHex(digest));
        pruefsummen.write("  ");
        pruefsummen.write(pfad);
        pruefsummen.newLine();
    }

    private static void unterbrochen() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Mandanten-Vollexport unterbrochen");
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final class NichtSchliessend extends FilterOutputStream {
        private NichtSchliessend(OutputStream ausgabe) {
            super(ausgabe);
        }

        @Override
        public void close() throws IOException {
            flush();
        }
    }
}
