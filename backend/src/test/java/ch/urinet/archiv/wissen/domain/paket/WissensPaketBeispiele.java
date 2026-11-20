package ch.urinet.archiv.wissen.domain.paket;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Gemeinsamer synthetischer Korpus für Reader, Import und das Offline-Werkzeug aus Aufgabe 15.7.
 * readerCode ist null bei akzeptiertem ZIP; befunde nennt ausschliesslich offline prüfbare
 * Markdown-Codes, nicht die vollständige Liste archivabhängiger Befunde.
 */
public final class WissensPaketBeispiele {
    public record Beispiel(String name, byte[] zip, String readerCode, List<String> befunde) {
        public Beispiel {
            zip = zip.clone();
            befunde = List.copyOf(befunde);
        }

        @Override public byte[] zip() { return zip.clone(); }
    }

    private WissensPaketBeispiele() {}

    public static List<Beispiel> alle() {
        String kopf = ressource("paket.yaml");
        String seite = ressource("seite.md");
        List<Beispiel> beispiele = new ArrayList<>();
        for (String zeile : ressource("beispiele.tsv").split("\\R")) {
            if (zeile.isBlank() || zeile.startsWith("#")) continue;
            String[] spalten = zeile.split("\t", -1);
            if (spalten.length != 3) throw new IllegalStateException("Ungültige Beispielbeschreibung");
            String code = spalten[1].isEmpty() ? null : spalten[1];
            List<String> befunde = spalten[2].isEmpty() ? List.of() : List.of(spalten[2].split(","));
            beispiele.add(new Beispiel(spalten[0], erzeugen(spalten[0], kopf, seite), code, befunde));
        }
        return List.copyOf(beispiele);
    }

    private static byte[] erzeugen(String name, String kopf, String seite) {
        return switch (name) {
            case "gueltig" -> paket(kopf, seite);
            case "hinweise" -> zip(Map.of("paket.yaml", kopf + "unbekannt_kopf: test\n",
                    "konzepte/test.md", seite.replace("sources: []", """
                            status: stable
                            generated: {by: test, at: '2026-11-18T14:05:00Z'}
                            verified: [{by: mensch, at: '2026-11-18T14:05:00Z'}]
                            unbekannt_seite: test
                            sources: []"""),
                    "index.md", "Synthetischer Index", "log.md", "Synthetisches Log"));
            case "sha-und-urn" -> paket(kopf, seite.replace("sources: []", """
                    sources:
                      - id: q-test
                        resource: 'urn:urinet-archiv:urinet:dokument:00000000-0000-4000-8000-000000000001#page=1'
                        sha256: 'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA'
                        seite: '1'
                        auszug: 'Synthetischer Auszug'"""));
            case "zip-slip" -> zip(Map.of("paket.yaml", kopf, "../test.md", seite));
            case "symlink" -> symlink(kopf);
            case "verschluesselt" -> manipuliert(kopf, 0x02014b50, 8);
            case "split" -> manipuliert(kopf, 0x06054b50, 4);
            case "zip-bombe" -> zip(Map.of("paket.yaml", kopf, "konzepte/test.md", "x".repeat(100_000)));
            case "normalisierte-dublette" -> zip(Map.of("konzepte/café.md", seite, "konzepte/cafe\u0301.md", seite));
            case "dateigrenze" -> zip(Map.of("paket.yaml", kopf, "konzepte/test.md", "x".repeat(131_073)),
                    Deflater.NO_COMPRESSION);
            case "entpackgrenze" -> entpackgrenze(kopf, seite);
            case "unbekannter-dateityp" -> zip(Map.of("paket.yaml", kopf, "quelle.pdf", "Test"));
            case "yaml-tag" -> paket(kopf + "extra: !!str text\n", seite);
            case "yaml-anker" -> paket(kopf + "extra: &a text\n", seite);
            case "yaml-alias" -> paket(kopf + "extra: [*a]\n", seite);
            case "yaml-mehrfachdokument" -> paket(kopf + "---\nx: 1\n", seite);
            case "rohhtml" -> paket(kopf, seite.replace("# Wissensseite", "# <span>Wissensseite</span>"));
            case "externer-link" -> paket(kopf, seite.replace("# Wissensseite",
                    "# [Wissensseite](https://beispiel.invalid/)"));
            default -> throw new IllegalStateException("Unbekanntes Beispiel");
        };
    }

    private static byte[] paket(String kopf, String seite) {
        return zip(Map.of("paket.yaml", kopf, "konzepte/test.md", seite));
    }

    private static byte[] symlink(String kopf) {
        byte[] zip = zip(Map.of("paket.yaml", kopf));
        int zentral = signatur(zip, 0x02014b50);
        zip[zentral + 5] = 3;
        zip[zentral + 40] = (byte) 0xff;
        zip[zentral + 41] = (byte) 0xa1;
        return zip;
    }

    private static byte[] manipuliert(String kopf, int signatur, int offset) {
        byte[] zip = zip(Map.of("paket.yaml", kopf));
        zip[signatur(zip, signatur) + offset] |= 1;
        return zip;
    }

    private static byte[] entpackgrenze(String kopf, String seite) {
        Random zufall = new Random(1906);
        String alphabet = "abcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder block = new StringBuilder(4096);
        for (int i = 0; i < 4096; i++) block.append(alphabet.charAt(zufall.nextInt(alphabet.length())));
        String text = seite + block.toString().repeat(32);
        text = text.substring(0, 131_072);
        Map<String, String> dateien = new LinkedHashMap<>();
        dateien.put("paket.yaml", kopf);
        for (int i = 0; i < 161; i++) dateien.put("konzepte/seite-" + i + ".md", text);
        return zip(dateien);
    }

    static byte[] zip(Map<String, String> dateien) { return zip(dateien, Deflater.DEFAULT_COMPRESSION); }

    static byte[] zip(Map<String, String> dateien, int level) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            zip.setLevel(level);
            for (var datei : dateien.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
                ZipEntry eintrag = new ZipEntry(datei.getKey());
                eintrag.setTime(0);
                zip.putNextEntry(eintrag);
                zip.write(datei.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        return out.toByteArray();
    }

    static int signatur(byte[] daten, int signatur) {
        for (int i = 0; i < daten.length - 3; i++)
            if ((daten[i] & 255) == (signatur & 255) && (daten[i + 1] & 255) == ((signatur >>> 8) & 255)
                    && (daten[i + 2] & 255) == ((signatur >>> 16) & 255)
                    && (daten[i + 3] & 255) == ((signatur >>> 24) & 255)) return i;
        throw new IllegalArgumentException("ZIP-Signatur fehlt");
    }

    private static String ressource(String name) {
        try (InputStream strom = WissensPaketBeispiele.class.getResourceAsStream("/wissen-pakete/" + name)) {
            if (strom == null) throw new IllegalStateException("Beispielressource fehlt");
            return new String(strom.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
