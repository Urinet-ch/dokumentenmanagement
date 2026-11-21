package ch.urinet.archiv.wissenpaket;

import ch.urinet.archiv.gemeinsam.domain.Seitentext;
import ch.urinet.archiv.wissen.domain.WissensPruefung;
import ch.urinet.archiv.wissen.domain.paket.WissensPaket;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;

final class LokaleQuellen {
    static List<OfflinePruefung.Meldung> pruefen(WissensPaket paket, Path wurzel) throws IOException {
        return pruefen(paket, wurzel, LokaleQuellen::text);
    }

    static List<OfflinePruefung.Meldung> pruefen(WissensPaket paket, Path wurzel,
                                                  Function<Path, Optional<Seitentext>> extraktion) throws IOException {
        if (Files.isSymbolicLink(wurzel) || !Files.isDirectory(wurzel, LinkOption.NOFOLLOW_LINKS)) throw new IOException();
        Map<String, Path> dateien = new HashMap<>();
        try (var lauf = Files.walk(wurzel)) {
            for (Iterator<Path> it = lauf.iterator(); it.hasNext();) {
                Path datei = it.next();
                if (Files.isSymbolicLink(datei)) throw new IOException();
                if (!Files.isRegularFile(datei, LinkOption.NOFOLLOW_LINKS)) continue;
                dateien.putIfAbsent(sha(datei), datei);
            }
        } catch (UncheckedIOException ex) {
            throw ex.getCause();
        }
        Map<Path, Optional<Seitentext>> texte = new HashMap<>();
        List<OfflinePruefung.Meldung> hinweise = new ArrayList<>();
        for (var seite : paket.seiten()) for (var quelle : seite.sources()) {
            if (quelle.sha256() == null) continue;
            String pfad = seite.pfad();
            Path original = dateien.get(quelle.sha256());
            if (original == null) {
                hinweise.add(new OfflinePruefung.Meldung("QUELLE_NICHT_GEPRUEFT", pfad));
                continue;
            }
            if (quelle.auszug() == null || quelle.auszug().isBlank() || quelle.seite() == null) continue;
            Optional<Seitentext> text = texte.computeIfAbsent(original, extraktion);
            if (text.isEmpty()) {
                hinweise.add(new OfflinePruefung.Meldung("QUELLE_NICHT_GEPRUEFT", pfad));
                continue;
            }
            try {
                String[] zahlen = quelle.seite().split("-");
                int von = Integer.parseInt(zahlen[0]);
                int bis = zahlen.length == 1 ? von : Integer.parseInt(zahlen[1]);
                Seitentext t = text.orElseThrow();
                String gesucht = WissensPruefung.auszugNormalisieren(quelle.auszug());
                boolean gefunden = false;
                for (int n = von; n <= bis && n <= t.anzahl(); n++) {
                    if (WissensPruefung.auszugNormalisieren(t.seiten().get(n - 1)).contains(gesucht)) {
                        gefunden = true;
                        break;
                    }
                }
                if (!gefunden) hinweise.add(new OfflinePruefung.Meldung("AUSZUG_LOKAL_NICHT_GEFUNDEN", pfad));
            } catch (NumberFormatException ex) {
                hinweise.add(new OfflinePruefung.Meldung("QUELLE_NICHT_GEPRUEFT", pfad));
            }
        }
        return hinweise;
    }

    private static String sha(Path datei) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(datei, LinkOption.NOFOLLOW_LINKS);
                 DigestInputStream strom = new DigestInputStream(in, digest)) {
                strom.transferTo(OutputStream.nullOutputStream());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static Optional<Seitentext> text(Path datei) {
        if (!datei.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pdf")) return Optional.empty();
        Process prozess = null;
        try {
            prozess = new ProcessBuilder("pdftotext", "-layout", datei.toAbsolutePath().toString(), "-")
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            Process p = prozess;
            FutureTask<byte[]> daten = new FutureTask<>(() -> p.getInputStream().readNBytes(20 * 1024 * 1024 + 1));
            Thread.startVirtualThread(daten);
            byte[] ausgabe = daten.get(30, TimeUnit.SECONDS);
            if (ausgabe.length > 20 * 1024 * 1024 || !p.waitFor(5, TimeUnit.SECONDS) || p.exitValue() != 0)
                return Optional.empty();
            return Seitentext.von(new String(ausgabe, StandardCharsets.UTF_8));
        } catch (IOException | ExecutionException | TimeoutException ex) {
            return Optional.empty();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } finally {
            if (prozess != null && prozess.isAlive()) prozess.destroyForcibly();
        }
    }
}
