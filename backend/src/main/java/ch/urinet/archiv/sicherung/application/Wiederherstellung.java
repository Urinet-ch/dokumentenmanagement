package ch.urinet.archiv.sicherung.application;

import ch.urinet.archiv.ablage.domain.SicherungsSperre;
import ch.urinet.archiv.ablage.domain.Sperrmodus;
import ch.urinet.archiv.ablage.domain.Sperrplan;
import ch.urinet.archiv.ablage.domain.Stufenregel;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.sicherung.application.BucketExportLeser.Objekt;
import ch.urinet.archiv.sicherung.application.BucketExportLeser.Pruefung;
import ch.urinet.archiv.sicherung.application.SicherungBucketExport.InventarZeile;
import ch.urinet.archiv.sicherung.application.WiederherstellungsZiel.Quittung;

import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/** Stellt einen konkatenierbaren Bucket-Export in einem leeren Ziel wieder her. */
public final class Wiederherstellung {

    private static final long MAX_GROESSE = 1L << 40;
    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");
    private static final List<String> PRAEFIXE = List.of("archiv/", "eingang/", "manifest/");

    private final WiederherstellungsZiel ziel;
    private final WiederherstellungsReferenz referenz;
    private final Clock uhr;

    public Wiederherstellung(WiederherstellungsZiel ziel, Clock uhr, WiederherstellungsReferenz referenz) {
        this.ziel = java.util.Objects.requireNonNull(ziel, "ziel");
        this.referenz = java.util.Objects.requireNonNull(referenz, "referenz");
        this.uhr = java.util.Objects.requireNonNull(uhr, "uhr");
    }

    public Ergebnis wiederherstellen(MandantCode mandant, List<InventarZeile> inventar, Path strom, boolean kappen) {
        Zaehler leer = Zaehler.leer();
        if (kappen && !Stufenregel.PRAEFIX_STUFE_P.equals(ziel.praefix())) {
            return new Ergebnis(2, leer);
        }
        if (!ziel.leer(mandant)) {
            return new Ergebnis(2, leer);
        }
        List<InventarZeile> sortiert;
        Map<Objektschluessel, InventarZeile> zeilen = new HashMap<>();
        try {
            inventar.forEach(Wiederherstellung::pruefeZeile);
            sortiert = inventar.stream().sorted(Comparator.comparing(InventarZeile::schluessel)
                    .thenComparing(Wiederherstellung::lastModified)
                    .thenComparing(InventarZeile::versionId)).toList();
            for (InventarZeile zeile : sortiert) {
                if (zeilen.putIfAbsent(new Objektschluessel(zeile.schluessel(), zeile.versionId()), zeile) != null) {
                    return new Ergebnis(3, leer);
                }
            }
        } catch (IllegalArgumentException | java.time.DateTimeException | NullPointerException e) {
            return new Ergebnis(3, leer);
        }

        Map<Objektschluessel, List<Frame>> index = new HashMap<>();
        try (var quelle = Files.newInputStream(strom)) {
            BucketExportLeser leser = new BucketExportLeser(quelle);
            Optional<Objekt> naechstes;
            while ((naechstes = leser.naechstes()).isPresent()) {
                Objekt objekt = naechstes.get();
                String trailerHash = objekt.trailerHashLesen();
                Objektschluessel schluessel = new Objektschluessel(objekt.schluessel(), objekt.versionId());
                index.computeIfAbsent(schluessel, ignoriert -> new ArrayList<>())
                        .add(new Frame(objekt.position(), objekt.groesse(), objekt.lastModified(), trailerHash));
            }
        } catch (IOException | SicherungExportFehler e) {
            return new Ergebnis(3, leer);
        }

        long fehlend = 0;
        long ueberzaehlig = 0;
        for (Map.Entry<Objektschluessel, InventarZeile> eintrag : zeilen.entrySet()) {
            InventarZeile zeile = eintrag.getValue();
            if (!zeile.loeschmarke() && !index.containsKey(eintrag.getKey())) {
                fehlend++;
            }
        }
        for (Map.Entry<Objektschluessel, List<Frame>> eintrag : index.entrySet()) {
            InventarZeile zeile = zeilen.get(eintrag.getKey());
            if (zeile == null || zeile.loeschmarke()) {
                ueberzaehlig += eintrag.getValue().size();
                continue;
            }
            for (Frame frame : eintrag.getValue()) {
                if (frame.groesse() != zeile.groesse()
                        || !frame.lastModified().equals(lastModified(zeile))
                        || !zeile.sha256().isEmpty() && !zeile.sha256().equals(frame.sha256())) {
                    return new Ergebnis(3, new Zaehler(0, 0, 0, 1, fehlend, ueberzaehlig));
                }
            }
        }
        if (fehlend != 0 || ueberzaehlig != 0) {
            return new Ergebnis(4, new Zaehler(0, 0, 0, 0, fehlend, ueberzaehlig));
        }

        long versionen = 0;
        long ohneSperre = 0;
        long gekappt = 0;
        Instant jetzt = uhr.instant();
        Quittung ungesperrt = null;
        long abweichend = 0;
        try (FileChannel kanal = FileChannel.open(strom, StandardOpenOption.READ)) {
            for (InventarZeile zeile : sortiert) {
                if (zeile.loeschmarke()) {
                    continue;
                }
                Frame frame = index.get(new Objektschluessel(zeile.schluessel(), zeile.versionId())).getFirst();
                kanal.position(frame.position());
                BucketExportLeser leser = BucketExportLeser.einzelnesObjekt(Channels.newInputStream(kanal));
                Objekt objekt = leser.naechstes().orElseThrow();
                if (!objekt.schluessel().equals(zeile.schluessel())
                        || !objekt.versionId().equals(zeile.versionId())
                        || objekt.groesse() != zeile.groesse()
                        || !objekt.lastModified().equals(lastModified(zeile))) {
                    return new Ergebnis(3, new Zaehler(versionen, ohneSperre, gekappt, 1, 0, 0));
                }
                Quittung quittung = ungesperrt = ziel.schreiben(objekt.schluessel(), objekt.versionId(), objekt.lastModified(),
                        objekt.inhalt(), objekt.groesse());
                Pruefung pruefung = objekt.pruefsummePruefen();
                if (!pruefung.bestaetigt() || !pruefung.sha256().equals(quittung.sha256())
                        || !zeile.sha256().isEmpty() && !pruefung.sha256().equals(zeile.sha256())) {
                    ziel.verwerfen(quittung);
                    ungesperrt = null;
                    return new Ergebnis(3, new Zaehler(versionen, ohneSperre, gekappt, 1, 0, 0));
                }
                List<Frame> weitereKopien = index.get(new Objektschluessel(zeile.schluessel(), zeile.versionId()));
                for (int i = 1; i < weitereKopien.size(); i++) {
                    Frame weitereKopie = weitereKopien.get(i);
                    kanal.position(weitereKopie.position());
                    BucketExportLeser duplikatLeser =
                            BucketExportLeser.einzelnesObjekt(Channels.newInputStream(kanal));
                    Pruefung duplikatPruefung = duplikatLeser.naechstes().orElseThrow().pruefsummePruefen();
                    if (!duplikatPruefung.bestaetigt() || !duplikatPruefung.sha256().equals(pruefung.sha256())) {
                        ziel.verwerfen(quittung);
                        ungesperrt = null;
                        return new Ergebnis(3, new Zaehler(versionen, ohneSperre, gekappt, 1, 0, 0));
                    }
                }
                Sperrplan plan = Sperrplan.berechnen(sperre(zeile), kappen, jetzt);
                if (plan.modus().isPresent()) {
                    ziel.sperren(quittung, plan.modus().orElseThrow(), plan.bis().orElseThrow());
                    if (plan.gekappt()) {
                        gekappt++;
                    }
                } else {
                    ohneSperre++;
                }
                ungesperrt = null;
                versionen++;
                if (zeile.schluessel().startsWith("archiv/") || zeile.schluessel().startsWith("eingang/")) {
                    referenz.versionErsetzt(mandant, zeile.schluessel(), zeile.versionId(), quittung.versionId());
                }
            }
        } catch (IOException | RuntimeException e) {
            abweichend = 1;
            if (ungesperrt != null) {
                try {
                    ziel.verwerfen(ungesperrt);
                } catch (RuntimeException ignoriert) {
                    // Der Zähler meldet den Abbruch; die ungesperrte Version bleibt löschbar.
                }
            }
            return new Ergebnis(3, new Zaehler(versionen, ohneSperre, gekappt, abweichend, 0, 0));
        }
        return new Ergebnis(0, new Zaehler(versionen, ohneSperre, gekappt, 0, 0, 0));
    }

    private static Optional<SicherungsSperre> sperre(InventarZeile zeile) {
        if (zeile.sperrmodus() == null) {
            return Optional.empty();
        }
        return Optional.of(new SicherungsSperre(zeile.sperrmodus(), Instant.parse(zeile.retainUntil())));
    }

    private static Instant lastModified(InventarZeile zeile) {
        return Instant.ofEpochMilli(Instant.parse(zeile.lastModified()).toEpochMilli());
    }

    private static void pruefeZeile(InventarZeile zeile) {
        if (zeile == null || !SicherungBucketExport.INVENTAR_FORMAT.equals(zeile.format())
                || zeile.schluessel() == null || PRAEFIXE.stream().noneMatch(zeile.schluessel()::startsWith)
                || zeile.versionId() == null || zeile.versionId().isBlank()
                || zeile.groesse() < 0 || zeile.groesse() > MAX_GROESSE
                || zeile.sha256() == null || !zeile.sha256().isEmpty() && !SHA256.matcher(zeile.sha256()).matches()) {
            throw new IllegalArgumentException("Ungültiges Inventar");
        }
        lastModified(zeile);
        if (zeile.sperrmodus() == null && zeile.retainUntil() == null) {
            return;
        }
        if (zeile.sperrmodus() == null || zeile.retainUntil() == null) {
            throw new IllegalArgumentException("Ungültiges Inventar");
        }
        Sperrmodus.valueOf(zeile.sperrmodus());
        Instant.parse(zeile.retainUntil());
    }

    private record Objektschluessel(String schluessel, String versionId) {
    }

    private record Frame(long position, long groesse, Instant lastModified, String sha256) {
    }

    public record Zaehler(long versionen, long ohneSperre, long gekappt, long abweichend,
                          long fehlendInKette, long ueberzaehlig) {
        private static Zaehler leer() {
            return new Zaehler(0, 0, 0, 0, 0, 0);
        }
    }

    public record Ergebnis(int exitCode, Zaehler zaehler) {
    }
}
