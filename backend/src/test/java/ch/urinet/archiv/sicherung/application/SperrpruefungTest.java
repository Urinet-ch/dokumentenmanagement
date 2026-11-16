package ch.urinet.archiv.sicherung.application;

import ch.urinet.archiv.ablage.application.AblagePort;
import ch.urinet.archiv.ablage.domain.Dateipruefung;
import ch.urinet.archiv.ablage.domain.Objektversion;
import ch.urinet.archiv.ablage.domain.SicherungsSperre;
import ch.urinet.archiv.ablage.domain.Sperrvorgabe;
import ch.urinet.archiv.ablage.domain.Versionsseite;
import ch.urinet.archiv.dokument.application.DokumentPrueflaufPort;
import ch.urinet.archiv.dokument.domain.DokumentStatus;
import ch.urinet.archiv.export.application.ExportBelege;
import ch.urinet.archiv.mandant.domain.Geschaeftsjahresende;
import ch.urinet.archiv.mandant.domain.Mandant;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.protokoll.application.ManifestDaten;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static ch.urinet.archiv.sicherung.application.Sperrpruefung.Abweichung.LOESCHEN_MOEGLICH;
import static ch.urinet.archiv.sicherung.application.Sperrpruefung.Abweichung.MODUS_ABWEICHEND;
import static ch.urinet.archiv.sicherung.application.Sperrpruefung.Abweichung.OBJEKT_FEHLT;
import static ch.urinet.archiv.sicherung.application.Sperrpruefung.Abweichung.OBJEKT_UEBERZAEHLIG;
import static ch.urinet.archiv.sicherung.application.Sperrpruefung.Abweichung.SPERRE_FEHLT;
import static ch.urinet.archiv.sicherung.application.Sperrpruefung.Abweichung.SPERRE_ZU_KURZ;
import static org.assertj.core.api.Assertions.assertThat;

class SperrpruefungTest {

    private static final Instant JETZT = Instant.parse("2026-11-16T12:00:00Z");
    private static final Mandant MANDANT = new Mandant(new MandantCode("probe"), "Probe", "m_probe", "bucket",
            Geschaeftsjahresende.STANDARD, true);

    @TempDir
    Path arbeitsverzeichnis;

    @Test
    @Tag("req-16.27")
    void aktiveSperreMitSollfristIstZulaessigUndEineSekundeZuKurzNicht() throws Exception {
        String key = "archiv/2026/" + UUID.randomUUID() + "/original/1.pdf";
        LocalDate bis = LocalDate.of(2026, 11, 16);
        long sollSekunden = Instant.parse("2026-11-16T23:00:00Z").getEpochSecond() - JETZT.getEpochSecond();
        Fixture passend = fixture(List.of(dokumentBis(key, "v1", bis)), List.of(), Map.of(
                new Kennung(key, "v1"), new SicherungsSperre("GOVERNANCE", JETZT.plusSeconds(sollSekunden))));
        try (var ergebnis = passend.pruefen(0)) {
            assertThat(ergebnis.zaehler()).allSatisfy((art, anzahl) -> assertThat(anzahl).isZero());
        }

        Fixture zuKurz = fixture(List.of(dokumentBis(key, "v1", bis)), List.of(), Map.of(
                new Kennung(key, "v1"), new SicherungsSperre("GOVERNANCE",
                        JETZT.plusSeconds(sollSekunden - 1))));
        try (var ergebnis = zuKurz.pruefen(0)) {
            assertThat(ergebnis.zaehler().get(SPERRE_ZU_KURZ)).isEqualTo(1);
        }
    }

    @Test
    @Tag("req-16.27")
    void fehlendeSperreFalscherModusUndFehlendesObjektWerdenGetrenntGezaehlt() throws Exception {
        String ohneSperre = "archiv/2026/" + UUID.randomUUID() + "/original/1.pdf";
        String falscherModus = "archiv/2026/" + UUID.randomUUID() + "/original/1.pdf";
        String fehlt = "archiv/2026/" + UUID.randomUUID() + "/original/1.pdf";
        Fixture fixture = fixture(List.of(dokument(ohneSperre, "v1"), dokument(falscherModus, "v1"),
                        dokument(fehlt, "v1")), List.of(),
                Map.of(new Kennung(falscherModus, "v1"),
                        new SicherungsSperre("COMPLIANCE", JETZT.plusSeconds(86401))),
                Set.of(new Kennung(fehlt, "v1")), Set.of());

        try (var ergebnis = fixture.pruefen(0)) {
            assertThat(ergebnis.zaehler().get(SPERRE_FEHLT)).isEqualTo(1);
            assertThat(ergebnis.zaehler().get(MODUS_ABWEICHEND)).isEqualTo(1);
            assertThat(ergebnis.zaehler().get(OBJEKT_FEHLT)).isEqualTo(1);
        }
    }

    @Test
    @Tag("req-16.27")
    void ueberzaehligesObjektWirdNurGezaehlt() throws Exception {
        Fixture fixture = fixture(List.of(), List.of(), Map.of(), Set.of(),
                Set.of(new Kennung("archiv/fremd", "v1")));

        try (var ergebnis = fixture.pruefen(0)) {
            assertThat(ergebnis.zaehler().get(OBJEKT_UEBERZAEHLIG)).isEqualTo(1);
            assertThat(Files.readString(ergebnis.dokumentIds())).isEmpty();
        }
    }

    @Test
    @Tag("req-16.27")
    void stichprobeNullLoeschtNichtsUndZuGrosseStichprobePrueftAlle() throws Exception {
        String erster = "archiv/2026/" + UUID.randomUUID() + "/original/1.pdf";
        String zweiter = "archiv/2026/" + UUID.randomUUID() + "/original/1.pdf";
        Fixture keine = fixture(List.of(dokument(erster, "v1")), List.of(), Map.of());
        try (var ergebnis = keine.pruefen(0)) {
            assertThat(keine.loeschversuche).isZero();
            assertThat(ergebnis.zaehler().get(SPERRE_FEHLT)).isEqualTo(1);
        }

        Fixture alle = fixture(List.of(dokument(erster, "v1"), dokument(zweiter, "v1")), List.of(),
                Map.of(new Kennung(erster, "v1"), new SicherungsSperre("GOVERNANCE", JETZT.plusSeconds(86401))));
        alle.loeschbar.add(new Kennung(zweiter, "v1"));
        try (var ergebnis = alle.pruefen(50)) {
            assertThat(alle.loeschversuche).isEqualTo(2);
            assertThat(ergebnis.zaehler().get(LOESCHEN_MOEGLICH)).isEqualTo(1);
        }
    }

    @Test
    @Tag("req-16.27")
    void manifestSperreWirdMitComplianceUndStufePHoechstfristGeprueft() throws Exception {
        LocalDate datum = LocalDate.of(2026, 11, 15);
        String key = "manifest/" + datum + ".json";
        var datei = new ExportBelege.Manifestdatei(datum, "json", key, "m1", "hash");
        var stand = new ManifestDaten.Sperrpruefstand(datum, Optional.empty(), JETZT);
        var manifest = new SperrpruefungDaten.Pruefmanifest(stand,
                new ManifestDaten.Fristen(0, false, Optional.empty()), List.of(datei));
        Fixture fixture = fixture(List.of(), List.of(manifest), Map.of(
                new Kennung(key, "m1"), new SicherungsSperre("COMPLIANCE", JETZT.plusSeconds(86401))));

        try (var ergebnis = fixture.pruefen(1)) {
            assertThat(ergebnis.zaehler()).allSatisfy((art, anzahl) -> assertThat(anzahl).isZero());
            assertThat(fixture.loeschversuche).isEqualTo(1);
        }
    }

    @Test
    @Tag("req-16.27")
    @Tag("req-6.11")
    void gekappteSperreBleibtMinutenNachDerWiederherstellungOhneAbweichung() throws Exception {
        String key = "archiv/2026/" + UUID.randomUUID() + "/original/1.pdf";
        Instant restore = JETZT.minusSeconds(5 * 60);
        Fixture fixture = fixture(List.of(dokument(key, "v1")), List.of(), Map.of(
                new Kennung(key, "v1"), new SicherungsSperre("GOVERNANCE", restore.plusSeconds(86400))));
        try (var ergebnis = fixture.pruefen(0)) {
            assertThat(ergebnis.zaehler()).allSatisfy((art, anzahl) -> assertThat(anzahl).isZero());
        }
        LocalDate morgen = LocalDate.of(2026, 11, 16);
        String kurzKey = "archiv/2026/" + UUID.randomUUID() + "/original/2.pdf";
        Fixture kurz = fixture(List.of(dokumentBis(kurzKey, "v1", morgen)), List.of(), Map.of(
                new Kennung(kurzKey, "v1"), new SicherungsSperre("GOVERNANCE", JETZT.plusSeconds(60))));
        try (var ergebnis = kurz.pruefen(0)) {
            assertThat(ergebnis.zaehler().get(SPERRE_ZU_KURZ)).isEqualTo(1);
        }
    }

    @Test
    @Tag("req-16.27")
    @Tag("req-6.11")
    void gekapptesManifestBleibtMinutenNachDerWiederherstellungOhneAbweichung() throws Exception {
        LocalDate datum = LocalDate.of(2026, 11, 15);
        String key = "manifest/" + datum + ".json";
        var datei = new ExportBelege.Manifestdatei(datum, "json", key, "m1", "hash");
        var stand = new ManifestDaten.Sperrpruefstand(datum, Optional.empty(), JETZT);
        var manifest = new SperrpruefungDaten.Pruefmanifest(stand,
                new ManifestDaten.Fristen(0, false, Optional.empty()), List.of(datei));
        Instant restore = JETZT.minusSeconds(10 * 60);
        Fixture fixture = fixture(List.of(), List.of(manifest), Map.of(
                new Kennung(key, "m1"), new SicherungsSperre("COMPLIANCE", restore.plusSeconds(86400))));
        try (var ergebnis = fixture.pruefen(0)) {
            assertThat(ergebnis.zaehler()).allSatisfy((art, anzahl) -> assertThat(anzahl).isZero());
        }
    }

    private static DokumentPrueflaufPort.Pruefdokument dokumentBis(String key, String version, LocalDate bis) {
        var datei = new DokumentPrueflaufPort.Datei(key, Optional.of(version), Optional.of("hash"),
                "original", 1, true);
        return new DokumentPrueflaufPort.Pruefdokument(UUID.randomUUID(), DokumentStatus.ARCHIVIERT,
                Sperrvorgabe.bis(bis), Optional.of(JETZT), List.of(datei));
    }

    private Fixture fixture(List<DokumentPrueflaufPort.Pruefdokument> dokumente,
                            List<SperrpruefungDaten.Pruefmanifest> manifeste,
                            Map<Kennung, SicherungsSperre> sperren) {
        return fixture(dokumente, manifeste, sperren, Set.of(), Set.of());
    }

    private Fixture fixture(List<DokumentPrueflaufPort.Pruefdokument> dokumente,
                            List<SperrpruefungDaten.Pruefmanifest> manifeste,
                            Map<Kennung, SicherungsSperre> sperren, Set<Kennung> fehlend,
                            Set<Kennung> ueberzaehlig) {
        return new Fixture(dokumente, manifeste, sperren, fehlend, ueberzaehlig);
    }

    private static DokumentPrueflaufPort.Pruefdokument dokument(String key, String version) {
        var datei = new DokumentPrueflaufPort.Datei(key, Optional.of(version), Optional.of("hash"),
                "original", 1, true);
        return new DokumentPrueflaufPort.Pruefdokument(UUID.randomUUID(), DokumentStatus.ARCHIVIERT,
                Sperrvorgabe.unbefristet(), Optional.of(JETZT), List.of(datei));
    }

    private record Kennung(String key, String version) {
    }

    private final class Fixture {
        private final List<DokumentPrueflaufPort.Pruefdokument> dokumente;
        private final List<SperrpruefungDaten.Pruefmanifest> manifeste;
        private final Map<Kennung, SicherungsSperre> sperren;
        private final Set<Kennung> fehlend;
        private final Set<Kennung> bucketVersionen;
        private final Set<Kennung> loeschbar = new HashSet<>();
        private int loeschversuche;
        private Sperrpruefung pruefung;
        private AblagePort ablageStub;
        private SperrpruefungDaten datenStub;

        private Fixture(List<DokumentPrueflaufPort.Pruefdokument> dokumente,
                        List<SperrpruefungDaten.Pruefmanifest> manifeste,
                        Map<Kennung, SicherungsSperre> sperren, Set<Kennung> fehlend,
                        Set<Kennung> ueberzaehlig) {
            this.dokumente = dokumente;
            this.manifeste = manifeste;
            this.sperren = sperren;
            this.fehlend = new HashSet<>(fehlend);
            bucketVersionen = new HashSet<>(ueberzaehlig);
            dokumente.forEach(d -> d.dateien().forEach(f -> f.versionId()
                    .ifPresent(v -> bucketVersionen.add(new Kennung(f.schluessel(), v)))));
            manifeste.forEach(m -> m.dateien().forEach(f -> bucketVersionen.add(new Kennung(f.schluessel(),
                    f.versionId()))));
            bucketVersionen.removeAll(fehlend);
            AblagePort ablage = (AblagePort) Proxy.newProxyInstance(AblagePort.class.getClassLoader(),
                    new Class<?>[]{AblagePort.class}, (proxy, methode, args) -> switch (methode.getName()) {
                        case "versionen" -> versionen((String) args[1], (Optional<Versionsseite.Marke>) args[2],
                                (int) args[3]);
                        case "pruefen" -> {
                            Kennung id = new Kennung((String) args[1], (String) args[2]);
                            yield fehlend.contains(id) ? Optional.empty()
                                    : Optional.of(new Dateipruefung("hash", 1,
                                    Optional.ofNullable(sperren.get(id)).map(SicherungsSperre::retainUntil)));
                        }
                        case "sicherungsSperre" -> {
                            Objektversion objekt = (Objektversion) args[1];
                            yield Optional.ofNullable(sperren.get(new Kennung(objekt.schluessel(), objekt.versionId())));
                        }
                        case "loeschprobe" -> {
                            loeschversuche++;
                            Kennung id = new Kennung((String) args[1], (String) args[2]);
                            boolean gesperrt = Optional.ofNullable(sperren.get(id))
                                    .map(SicherungsSperre::retainUntil).filter(JETZT::isBefore).isPresent();
                            yield !gesperrt && loeschbar.contains(id);
                        }
                        default -> throw new UnsupportedOperationException(methode.getName());
                    });
            SperrpruefungDaten daten = new SperrpruefungDaten() {
                @Override
                public List<DokumentPrueflaufPort.Pruefdokument> dokumente(MandantCode mandant,
                                                                           Optional<UUID> nach, int anzahl) {
                    return dokumente.stream().filter(d -> nach.map(id -> d.id().compareTo(id) > 0).orElse(true))
                            .sorted(java.util.Comparator.comparing(DokumentPrueflaufPort.Pruefdokument::id))
                            .limit(anzahl).toList();
                }

                @Override
                public List<Pruefmanifest> manifeste(MandantCode mandant, Optional<LocalDate> nach, int anzahl) {
                    return manifeste.stream().filter(m -> nach.map(d -> m.stand().datum().isAfter(d)).orElse(true))
                            .sorted(java.util.Comparator.comparing(m -> m.stand().datum()))
                            .limit(anzahl).toList();
                }

                @Override
                public Set<DokumentPrueflaufPort.Bekannt> bekannteDokumente(MandantCode mandant,
                                                                            Collection<String> schluessel) {
                    Set<DokumentPrueflaufPort.Bekannt> bekannt = new HashSet<>();
                    dokumente.forEach(d -> d.dateien().stream().filter(f -> schluessel.contains(f.schluessel()))
                            .forEach(f -> f.versionId().ifPresent(v -> bekannt.add(
                                    new DokumentPrueflaufPort.Bekannt(f.schluessel(), v)))));
                    return bekannt;
                }

                @Override
                public Set<Version> bekannteManifeste(MandantCode mandant, Collection<Objektversion> versionen) {
                    Set<Kennung> gesucht = new HashSet<>();
                    versionen.forEach(v -> gesucht.add(new Kennung(v.schluessel(), v.versionId())));
                    return manifeste.stream().flatMap(m -> m.dateien().stream())
                            .map(d -> new Kennung(d.schluessel(), d.versionId())).filter(gesucht::contains)
                            .map(k -> new Version(k.key(), k.version())).collect(java.util.stream.Collectors.toSet());
                }
            };
            ablageStub = ablage;
            datenStub = daten;
            um(JETZT);
        }

        private Versionsseite versionen(String praefix, Optional<Versionsseite.Marke> nach, int anzahl) {
            List<Objektversion> liste = bucketVersionen.stream()
                    .filter(k -> k.key().startsWith(praefix))
                    .map(k -> new Objektversion(k.key(), k.version(), false, JETZT, 1))
                    .sorted(java.util.Comparator.comparing(Objektversion::schluessel)
                            .thenComparing(Objektversion::versionId))
                    .filter(v -> nach.map(m -> v.schluessel().compareTo(m.schluessel()) > 0
                            || v.schluessel().equals(m.schluessel()) && v.versionId().compareTo(
                                    m.versionId().orElse("")) > 0).orElse(true))
                    .limit(anzahl).toList();
            return new Versionsseite(liste, Optional.empty());
        }

        private Fixture um(Instant zeit) {
            pruefung = new Sperrpruefung(ablageStub, datenStub, Clock.fixed(zeit, ZoneOffset.UTC));
            return this;
        }

        private Sperrpruefung.Ergebnis pruefen(int n) throws Exception {
            return pruefung.pruefen(MANDANT, n, arbeitsverzeichnis);
        }
    }

}
