package ch.urinet.archiv.wissenpaket;

import ch.urinet.archiv.wissen.domain.paket.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

final class PaketDateien {
    private static final PaketGrenzen G = PaketGrenzen.standard();
    record OrdnerPaket(byte[] zip, boolean kopfErgaenzt) {}

    static byte[] kopf(String mandant, String erzeugtVon, String erzeugtMit, String stufe, String erzeugtAm) {
        String yaml = "paket_format: 1\nmandant: " + quote(mandant) + "\nerzeugt_von: " + quote(erzeugtVon)
                + "\nerzeugt_mit: " + quote(erzeugtMit) + "\nkontext_stufe: " + quote(stufe)
                + "\nerzeugt_am: " + quote(erzeugtAm) + "\n";
        return yaml.getBytes(StandardCharsets.UTF_8);
    }

    private static String quote(String text) { return "'" + text.replace("'", "''") + "'"; }

    static byte[] zipLesen(Path datei) throws IOException {
        if (Files.isSymbolicLink(datei) || !Files.isRegularFile(datei, LinkOption.NOFOLLOW_LINKS))
            throw new WissensPaketException(WissensPaketException.Code.PFAD, "");
        try (InputStream in = Files.newInputStream(datei, LinkOption.NOFOLLOW_LINKS)) {
            byte[] daten = in.readNBytes(G.zipBytes() + 1);
            if (daten.length > G.zipBytes()) throw new WissensPaketException(WissensPaketException.Code.DATEI_ZU_GROSS, "");
            return daten;
        }
    }

    static OrdnerPaket ordner(Path wurzel, byte[] kopf) throws IOException {
        if (Files.isSymbolicLink(wurzel) || !Files.isDirectory(wurzel, LinkOption.NOFOLLOW_LINKS))
            throw new WissensPaketException(WissensPaketException.Code.PFAD, "");
        Map<String, byte[]> dateien = new TreeMap<>();
        long gesamt = 0;
        try (var lauf = Files.walk(wurzel)) {
            Iterator<Path> it = lauf.iterator();
            while (it.hasNext()) {
                Path datei = it.next();
                if (datei.equals(wurzel)) continue;
                String name = wurzel.relativize(datei).toString().replace(File.separatorChar, '/');
                if (Files.isSymbolicLink(datei)) throw new WissensPaketException(WissensPaketException.Code.SYMLINK, name);
                if (dateien.size() >= G.eintraege()) throw new WissensPaketException(WissensPaketException.Code.GRENZE, name);
                if (Files.isDirectory(datei, LinkOption.NOFOLLOW_LINKS)) {
                    dateien.put(name + "/", new byte[0]);
                    continue;
                }
                if (!Files.isRegularFile(datei, LinkOption.NOFOLLOW_LINKS))
                    throw new WissensPaketException(WissensPaketException.Code.PFAD, name);
                byte[] daten;
                try (InputStream in = Files.newInputStream(datei, LinkOption.NOFOLLOW_LINKS)) {
                    daten = in.readNBytes(G.dateiBytes() + 1);
                }
                gesamt += daten.length;
                if (daten.length > G.dateiBytes() || gesamt > G.entpacktBytes())
                    throw new WissensPaketException(WissensPaketException.Code.GRENZE, name);
                dateien.put(name, daten);
            }
        } catch (UncheckedIOException ex) {
            throw ex.getCause();
        }
        boolean kopfErgaenzt = !dateien.containsKey("paket.yaml");
        if (kopfErgaenzt) dateien.put("paket.yaml", kopf);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            for (var eintrag : dateien.entrySet()) {
                ZipEntry e = new ZipEntry(eintrag.getKey());
                e.setTimeLocal(java.time.LocalDateTime.of(1980, 1, 1, 0, 0));
                byte[] daten = eintrag.getValue();
                boolean hochKomprimierbar = daten.length > komprimierteGroesse(daten) * (long) G.verhaeltnis();
                zip.setLevel(hochKomprimierbar ? Deflater.NO_COMPRESSION : Deflater.BEST_SPEED);
                zip.putNextEntry(e);
                if (hochKomprimierbar) {
                    int prefix = (daten.length + 49) / 50;
                    zip.write(daten, 0, prefix);
                    zip.setLevel(Deflater.BEST_SPEED);
                    zip.write(daten, prefix, daten.length - prefix);
                } else {
                    zip.write(daten);
                }
                zip.closeEntry();
                if (out.size() > G.zipBytes()) throw new WissensPaketException(WissensPaketException.Code.DATEI_ZU_GROSS, "");
            }
        }
        byte[] daten = out.toByteArray();
        if (daten.length > G.zipBytes()) throw new WissensPaketException(WissensPaketException.Code.DATEI_ZU_GROSS, "");
        return new OrdnerPaket(daten, kopfErgaenzt);
    }

    private static long komprimierteGroesse(byte[] daten) {
        Deflater deflater = new Deflater(Deflater.BEST_SPEED, true);
        try {
            deflater.setInput(daten);
            deflater.finish();
            byte[] puffer = new byte[8192];
            long groesse = 0;
            while (!deflater.finished()) groesse += deflater.deflate(puffer);
            return groesse;
        } finally {
            deflater.end();
        }
    }
}
