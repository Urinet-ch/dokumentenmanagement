package ch.urinet.archiv.sicherung.application;

import ch.urinet.archiv.ablage.application.AblagePort;
import ch.urinet.archiv.ablage.domain.Objektversion;
import ch.urinet.archiv.ablage.domain.SicherungsSperre;
import ch.urinet.archiv.ablage.domain.Sperrfrist;
import ch.urinet.archiv.ablage.domain.Sperrpflege;
import ch.urinet.archiv.ablage.domain.Stufe;
import ch.urinet.archiv.ablage.domain.Stufenregel;
import ch.urinet.archiv.dokument.application.DokumentPrueflaufPort;
import ch.urinet.archiv.mandant.domain.Mandant;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.protokoll.application.ManifestDaten;
import ch.urinet.archiv.protokoll.application.ManifestErzeugen;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Unabhängiger Soll-/Ist-Abgleich der Sperren nach der Wiederherstellung (Req 16.27). */
@ApplicationScoped
public class Sperrpruefung {

    private static final int SEITENGROESSE = 100;
    private static final String ARCHIV = "archiv/";
    private static final String MANIFEST = "manifest/";
    private static final Duration P_HOECHSTFRIST = Stufenregel.HOECHSTFRIST_STUFE_P;

    public enum Abweichung {
        SPERRE_FEHLT,
        MODUS_ABWEICHEND,
        SPERRE_ZU_KURZ,
        OBJEKT_FEHLT,
        OBJEKT_UEBERZAEHLIG,
        LOESCHEN_MOEGLICH
    }

    private final AblagePort ablage;
    private final SperrpruefungDaten daten;
    private final Clock uhr;

    @Inject
    public Sperrpruefung(AblagePort ablage, SperrpruefungDaten daten) {
        this(ablage, daten, Clock.systemUTC());
    }

    Sperrpruefung(AblagePort ablage, SperrpruefungDaten daten, Clock uhr) {
        this.ablage = ablage;
        this.daten = daten;
        this.uhr = uhr;
    }

    public Ergebnis pruefen(Mandant mandant, int stichprobe, Path arbeitsverzeichnis) throws IOException {
        if (stichprobe < 0 || stichprobe > 50) {
            throw new IllegalArgumentException("Stichprobe muss zwischen 0 und 50 liegen");
        }
        Instant jetzt = uhr.instant();
        try (IdZwischenablage ids = new IdZwischenablage(arbeitsverzeichnis)) {
            EnumMap<Abweichung, Long> zaehler = zaehler();
            long archivKandidaten = dokumentKandidaten(mandant.code(), jetzt);
            long manifestKandidaten = manifestKandidaten(mandant, jetzt);
            Stichprobe probe = verteilen(archivKandidaten, manifestKandidaten, stichprobe);
            inventarAbgleichen(mandant.code(), ARCHIV, zaehler);
            inventarAbgleichen(mandant.code(), MANIFEST, zaehler);
            try (BufferedWriter dokumente = Files.newBufferedWriter(ids.dokumente(), StandardCharsets.UTF_8);
                 BufferedWriter manifeste = Files.newBufferedWriter(ids.manifeste(), StandardCharsets.UTF_8)) {
                dokumentePruefen(mandant.code(), jetzt, archivKandidaten, probe.archiv(), zaehler, dokumente);
                manifestePruefen(mandant, jetzt, manifestKandidaten, probe.manifest(), zaehler, manifeste);
            }
            return ids.ergebnis(zaehler);
        }
    }

    private long dokumentKandidaten(MandantCode mandant, Instant jetzt) {
        long anzahl = 0;
        Optional<UUID> nach = Optional.empty();
        for (List<DokumentPrueflaufPort.Pruefdokument> seite; !(seite = daten.dokumente(mandant, nach,
                SEITENGROESSE)).isEmpty(); ) {
            for (var dokument : seite) {
                for (var datei : dokument.dateien()) {
                    if (sollSperren(dokument, jetzt) && datei.versionId().isPresent()) {
                        anzahl = Math.addExact(anzahl, 1);
                    }
                }
            }
            nach = Optional.of(seite.getLast().id());
        }
        return anzahl;
    }

    private long manifestKandidaten(Mandant mandant, Instant jetzt) {
        long anzahl = 0;
        Optional<java.time.LocalDate> nach = Optional.empty();
        for (List<SperrpruefungDaten.Pruefmanifest> seite; !(seite = daten.manifeste(mandant.code(), nach,
                SEITENGROESSE)).isEmpty(); ) {
            for (var manifest : seite) {
                if (manifestSoll(mandant, manifest, jetzt).isPresent()) {
                    anzahl = Math.addExact(anzahl, manifest.dateien().size());
                }
            }
            nach = Optional.of(seite.getLast().stand().datum());
        }
        return anzahl;
    }

    private void inventarAbgleichen(MandantCode mandant, String praefix, EnumMap<Abweichung, Long> zaehler) {
        Optional<ch.urinet.archiv.ablage.domain.Versionsseite.Marke> nach = Optional.empty();
        do {
            var seite = ablage.versionen(mandant, praefix, nach, SEITENGROESSE);
            Set<String> schluessel = new HashSet<>();
            seite.versionen().forEach(version -> schluessel.add(version.schluessel()));
            Set<SperrpruefungDaten.Version> bekannte;
            if (praefix.equals(ARCHIV)) {
                Set<DokumentPrueflaufPort.Bekannt> dokumente = daten.bekannteDokumente(mandant, schluessel);
                bekannte = dokumente.stream()
                        .map(dokument -> new SperrpruefungDaten.Version(dokument.schluessel(), dokument.versionId()))
                        .collect(java.util.stream.Collectors.toSet());
            } else {
                bekannte = daten.bekannteManifeste(mandant, seite.versionen());
            }
            for (Objektversion version : seite.versionen()) {
                if (!bekannte.contains(new SperrpruefungDaten.Version(version.schluessel(), version.versionId()))) {
                    zaehler.merge(Abweichung.OBJEKT_UEBERZAEHLIG, 1L, Long::sum);
                }
            }
            nach = seite.weiter();
        } while (nach.isPresent());
    }

    private void dokumentePruefen(MandantCode mandant, Instant jetzt, long gesamt, int stichprobe,
                                   EnumMap<Abweichung, Long> zaehler, BufferedWriter ids) throws IOException {
        long kandidat = 0;
        Optional<UUID> nach = Optional.empty();
        for (List<DokumentPrueflaufPort.Pruefdokument> seite; !(seite = daten.dokumente(mandant, nach,
                SEITENGROESSE)).isEmpty(); ) {
            for (var dokument : seite) {
                boolean betroffen = false;
                Optional<Instant> soll = Sperrfrist.retainUntil(dokument.vorgabe(), jetzt,
                        Optional.of(P_HOECHSTFRIST));
                for (var datei : dokument.dateien()) {
                    boolean kandidatAktuell = soll.isPresent() && datei.versionId().isPresent();
                    Optional<String> versionId = datei.versionId();
                    boolean vorhanden = versionId.flatMap(id ->
                            ablage.pruefen(mandant, datei.schluessel(), id)).isPresent();
                    if (!vorhanden) {
                        zaehler.merge(Abweichung.OBJEKT_FEHLT, 1L, Long::sum);
                        betroffen = true;
                    } else if (soll.isPresent()) {
                        Optional<SicherungsSperre> ist = ablage.sicherungsSperre(mandant,
                                new Objektversion(datei.schluessel(), versionId.orElseThrow(), false));
                        boolean aktiv = ist.filter(s -> Sperrpflege.entscheiden(Stufe.P,
                                Optional.of(s.retainUntil()), jetzt, Optional.of(P_HOECHSTFRIST)).art()
                                != Sperrpflege.Art.OHNE_SPERRE).isPresent();
                        if (!aktiv) {
                            zaehler.merge(Abweichung.SPERRE_FEHLT, 1L, Long::sum);
                            betroffen = true;
                        } else {
                            SicherungsSperre sperre = ist.orElseThrow();
                            if (!sperre.modus().equals("GOVERNANCE")) {
                                zaehler.merge(Abweichung.MODUS_ABWEICHEND, 1L, Long::sum);
                                betroffen = true;
                            }
                            if (sperre.retainUntil().isBefore(soll.orElseThrow())) {
                                zaehler.merge(Abweichung.SPERRE_ZU_KURZ, 1L, Long::sum);
                                betroffen = true;
                            }
                        }
                    }
                    if (kandidatAktuell) {
                        if (auswahl(kandidat++, gesamt, stichprobe)) {
                            if (vorhanden) {
                                boolean geloescht = ablage.loeschprobe(mandant, datei.schluessel(),
                                        versionId.orElseThrow());
                                if (geloescht) {
                                    zaehler.merge(Abweichung.LOESCHEN_MOEGLICH, 1L, Long::sum);
                                    betroffen = true;
                                } else if (istAktiv(mandant, datei.schluessel(), versionId.orElseThrow(), jetzt)) {
                                    // Die vorhandene aktive Sperre hat den erwarteten Löschversuch verhindert.
                                } else {
                                    throw new IllegalStateException("Löschversuch ohne aktive Sperre abgelehnt");
                                }
                            }
                        }
                    }
                }
                if (betroffen) {
                    ids.write(dokument.id().toString());
                    ids.newLine();
                }
            }
            nach = Optional.of(seite.getLast().id());
        }
    }

    private void manifestePruefen(Mandant mandant, Instant jetzt, long gesamt, int stichprobe,
                                   EnumMap<Abweichung, Long> zaehler, BufferedWriter ids) throws IOException {
        long kandidat = 0;
        Optional<java.time.LocalDate> nach = Optional.empty();
        for (List<SperrpruefungDaten.Pruefmanifest> seite; !(seite = daten.manifeste(mandant.code(), nach,
                SEITENGROESSE)).isEmpty(); ) {
            for (var manifest : seite) {
                boolean betroffen = false;
                Optional<Instant> soll = manifestSoll(mandant, manifest, jetzt);
                for (var datei : manifest.dateien()) {
                    boolean vorhanden = ablage.pruefen(mandant.code(), datei.schluessel(),
                            datei.versionId()).isPresent();
                    if (!vorhanden) {
                        zaehler.merge(Abweichung.OBJEKT_FEHLT, 1L, Long::sum);
                        betroffen = true;
                    } else if (soll.isPresent()) {
                        Optional<SicherungsSperre> ist = ablage.sicherungsSperre(mandant.code(),
                                new Objektversion(datei.schluessel(), datei.versionId(), false));
                        boolean aktiv = ist.filter(s -> Sperrpflege.entscheiden(Stufe.P,
                                Optional.of(s.retainUntil()), jetzt, Optional.of(P_HOECHSTFRIST)).art()
                                != Sperrpflege.Art.OHNE_SPERRE).isPresent();
                        if (!aktiv) {
                            zaehler.merge(Abweichung.SPERRE_FEHLT, 1L, Long::sum);
                            betroffen = true;
                        } else {
                            SicherungsSperre sperre = ist.orElseThrow();
                            if (!sperre.modus().equals("COMPLIANCE")) {
                                zaehler.merge(Abweichung.MODUS_ABWEICHEND, 1L, Long::sum);
                                betroffen = true;
                            }
                            if (sperre.retainUntil().isBefore(soll.orElseThrow())) {
                                zaehler.merge(Abweichung.SPERRE_ZU_KURZ, 1L, Long::sum);
                                betroffen = true;
                            }
                        }
                    }
                    if (soll.isPresent() && auswahl(kandidat++, gesamt, stichprobe) && vorhanden) {
                        boolean geloescht = ablage.loeschprobe(mandant.code(), datei.schluessel(), datei.versionId());
                        if (geloescht) {
                            zaehler.merge(Abweichung.LOESCHEN_MOEGLICH, 1L, Long::sum);
                            betroffen = true;
                        } else if (!istAktiv(mandant.code(), datei.schluessel(), datei.versionId(), jetzt)) {
                            throw new IllegalStateException("Löschversuch ohne aktive Sperre abgelehnt");
                        }
                    }
                }
                if (betroffen) {
                    ids.write(manifest.stand().datum().toString());
                    ids.newLine();
                }
            }
            nach = Optional.of(seite.getLast().stand().datum());
        }
    }

    private Optional<Instant> manifestSoll(Mandant mandant, SperrpruefungDaten.Pruefmanifest manifest, Instant jetzt) {
        var fristBis = ManifestErzeugen.sperre(manifest.fristen(),
                ch.urinet.archiv.protokoll.domain.ManifestFenster.tag(jetzt), manifest.stand().datum(),
                mandant.geschaeftsjahresende().monatTag());
        return Optional.of(Sperrfrist.manifest(fristBis, jetzt, Optional.of(P_HOECHSTFRIST)));
    }

    private boolean istAktiv(MandantCode mandant, String schluessel, String versionId, Instant jetzt) {
        return ablage.sicherungsSperre(mandant, new Objektversion(schluessel, versionId, false))
                .filter(s -> s.retainUntil().isAfter(jetzt)).isPresent();
    }

    private static boolean sollSperren(DokumentPrueflaufPort.Pruefdokument dokument, Instant jetzt) {
        return Sperrfrist.retainUntil(dokument.vorgabe(), jetzt, Optional.of(P_HOECHSTFRIST)).isPresent();
    }

    private static boolean auswahl(long index, long gesamt, int anzahl) {
        if (anzahl == 0 || gesamt == 0) {
            return false;
        }
        for (long i = 0; i < anzahl; i++) {
            long position = ((2 * i + 1) * gesamt) / (2L * anzahl);
            if (index == position) {
                return true;
            }
        }
        return false;
    }

    private static Stichprobe verteilen(long archiv, long manifeste, int gewuenscht) {
        int ziel = (int) Math.min(gewuenscht, Math.addExact(archiv, manifeste));
        int archivAnzahl = (int) Math.min(archiv, (ziel + 1L) / 2);
        int manifestAnzahl = (int) Math.min(manifeste, ziel / 2);
        int offen = ziel - archivAnzahl - manifestAnzahl;
        while (offen > 0) {
            if (archivAnzahl < archiv) {
                archivAnzahl++;
                offen--;
            }
            if (offen > 0 && manifestAnzahl < manifeste) {
                manifestAnzahl++;
                offen--;
            }
        }
        return new Stichprobe(archivAnzahl, manifestAnzahl);
    }

    private static EnumMap<Abweichung, Long> zaehler() {
        EnumMap<Abweichung, Long> zaehler = new EnumMap<>(Abweichung.class);
        for (Abweichung abweichung : Abweichung.values()) {
            zaehler.put(abweichung, 0L);
        }
        return zaehler;
    }

    private record Stichprobe(int archiv, int manifest) {
    }

    private static final class IdZwischenablage implements AutoCloseable {
        private final Path dokumente;
        private final Path manifeste;
        private boolean uebergeben;

        private IdZwischenablage(Path arbeitsverzeichnis) throws IOException {
            Files.createDirectories(arbeitsverzeichnis);
            dokumente = Files.createTempFile(arbeitsverzeichnis, "sperrpruefung-dokumente-", ".tmp");
            try {
                manifeste = Files.createTempFile(arbeitsverzeichnis, "sperrpruefung-manifeste-", ".tmp");
            } catch (IOException e) {
                try {
                    Files.deleteIfExists(dokumente);
                } catch (IOException cleanup) {
                    e.addSuppressed(cleanup);
                }
                throw e;
            }
        }

        private Path dokumente() {
            return dokumente;
        }

        private Path manifeste() {
            return manifeste;
        }

        private Ergebnis ergebnis(Map<Abweichung, Long> zaehler) {
            uebergeben = true;
            return new Ergebnis(zaehler, dokumente, manifeste);
        }

        @Override
        public void close() throws IOException {
            if (uebergeben) {
                return;
            }
            IOException fehler = null;
            try {
                Files.deleteIfExists(dokumente);
            } catch (IOException e) {
                fehler = e;
            }
            try {
                Files.deleteIfExists(manifeste);
            } catch (IOException e) {
                if (fehler == null) {
                    fehler = e;
                } else {
                    fehler.addSuppressed(e);
                }
            }
            if (fehler != null) {
                throw fehler;
            }
        }
    }

    public record Ergebnis(Map<Abweichung, Long> zaehler, Path dokumentIds, Path manifestIds) implements AutoCloseable {
        public Ergebnis {
            zaehler = Map.copyOf(zaehler);
        }

        public boolean ohneAbweichung() {
            return zaehler.values().stream().allMatch(anzahl -> anzahl == 0);
        }

        @Override
        public void close() throws IOException {
            IOException fehler = null;
            try {
                Files.deleteIfExists(dokumentIds);
            } catch (IOException e) {
                fehler = e;
            }
            try {
                Files.deleteIfExists(manifestIds);
            } catch (IOException e) {
                if (fehler == null) {
                    fehler = e;
                } else {
                    fehler.addSuppressed(e);
                }
            }
            if (fehler != null) {
                throw fehler;
            }
        }
    }
}
