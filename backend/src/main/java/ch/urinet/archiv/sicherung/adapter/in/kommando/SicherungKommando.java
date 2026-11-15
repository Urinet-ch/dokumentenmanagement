package ch.urinet.archiv.sicherung.adapter.in.kommando;

import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.sicherung.application.SicherungBucketExport;
import ch.urinet.archiv.sicherung.application.SicherungExportFehler;
import ch.urinet.archiv.sicherung.application.SicherungExportVerwaltung;
import ch.urinet.archiv.sicherung.domain.SicherungsArt;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/** Admin-Kommandos für Inventar, Bucket-Zuwachs und Sicherungsnachweise. */
@ApplicationScoped
public class SicherungKommando {

    public static final String AUFRUF = "Aufruf: archiv sicherung inventar --mandant <code> | "
            + "zuwachs --mandant <code> [--inventar-vorher <datei|->] | "
            + "festhalten --art <db|keycloak|realm|schluessel|bucket|inventar> [--mandant <code>] --sha256 <hex>";
    private static final Pattern SHA256 = Pattern.compile("^[0-9a-fA-F]{64}$");

    private final SicherungBucketExport bucketExport;
    private final SicherungExportVerwaltung verwaltung;
    private final ObjectMapper json;

    public SicherungKommando(SicherungBucketExport bucketExport, SicherungExportVerwaltung verwaltung,
                             ObjectMapper json) {
        this.bucketExport = bucketExport;
        this.verwaltung = verwaltung;
        this.json = json;
    }

    public enum Befehl {
        INVENTAR, ZUWACHS, FESTHALTEN
    }

    public record Aufruf(Befehl befehl, Optional<MandantCode> mandant, Optional<String> inventarVorher,
                         Optional<SicherungsArt> art, String sha256) {
    }

    public static Optional<Aufruf> lesen(List<String> argumente) {
        if (argumente.isEmpty()) {
            return Optional.empty();
        }
        Befehl befehl = switch (argumente.getFirst()) {
            case "inventar" -> Befehl.INVENTAR;
            case "zuwachs" -> Befehl.ZUWACHS;
            case "festhalten" -> Befehl.FESTHALTEN;
            default -> null;
        };
        if (befehl == null) {
            return Optional.empty();
        }
        Optional<MandantCode> mandant = Optional.empty();
        Optional<String> inventarVorher = Optional.empty();
        Optional<SicherungsArt> art = Optional.empty();
        String sha256 = null;
        try {
            for (int i = 1; i < argumente.size(); i++) {
                String schalter = argumente.get(i);
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
        if ((befehl == Befehl.INVENTAR || befehl == Befehl.ZUWACHS) && mandant.isEmpty()) {
            return Optional.empty();
        }
        if (befehl == Befehl.FESTHALTEN && (art.isEmpty() || sha256 == null
                || art.get().mandantPflicht() != mandant.isPresent())) {
            return Optional.empty();
        }
        return Optional.of(new Aufruf(befehl, mandant, inventarVorher, art, sha256));
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

    private void zuwachs(Aufruf aufruf, PrintStream aus) {
        Optional<String> vorher = aufruf.inventarVorher();
        if (vorher.isEmpty()) {
            bucketExport.zuwachs(aufruf.mandant().orElseThrow(), version -> true, aus);
            return;
        }
        String quelle = vorher.get();
        if (quelle.equals("-")) {
            var leser = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            var abgleich = new InventarAbgleich(leser, json);
            bucketExport.zuwachs(aufruf.mandant().orElseThrow(), abgleich, aus);
            abgleich.vollstaendigPruefen();
            return;
        }
        Path datei = Path.of(quelle);
        try (var leser = Files.newBufferedReader(datei, StandardCharsets.UTF_8)) {
            var abgleich = new InventarAbgleich(leser, json);
            bucketExport.zuwachs(aufruf.mandant().orElseThrow(), abgleich, aus);
            abgleich.vollstaendigPruefen();
        } catch (IOException e) {
            throw new UncheckedIOException("Vorheriges Inventar nicht lesbar", e);
        }
    }
}
