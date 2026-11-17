package ch.urinet.archiv.ablage.adapter.out.stub;

import ch.urinet.archiv.ablage.domain.ManifestTeil;
import ch.urinet.archiv.ablage.domain.Objektversion;
import ch.urinet.archiv.ablage.domain.UnsichereFortsetzung;
import ch.urinet.archiv.ablage.domain.Versionsseite;
import ch.urinet.archiv.mandant.domain.MandantCode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.io.TempDir;

/** Versionsliste des Stubs wie im Object Storage (Review PR #97): Fortsetzen nur mit Schlüssel und Versions-ID. */
class AblageStubTest {

    private static final MandantCode MANDANT = new MandantCode("urinet");

    @TempDir
    Path temp;

    @Test
    void blaettertUeberAlleVersionen() {
        AblageStub stub = mitManifesten(3);
        List<Objektversion> alle = new ArrayList<>();
        Optional<Versionsseite.Marke> ab = Optional.empty();
        do {
            Versionsseite seite = stub.versionen(MANDANT, "manifest/", ab, 1);
            alle.addAll(seite.versionen());
            ab = seite.weiter();
        } while (ab.isPresent());
        assertThat(alle).extracting(Objektversion::schluessel).containsExactly("manifest/2026-01-01.json",
                "manifest/2026-01-02.json", "manifest/2026-01-03.json");
    }

    @Test
    void abgeschnitteneSeiteOhneVersionsIdMarkerBrichtAb() {
        AblageStub stub = mitManifesten(2);
        stub.ohneVersionsIdMarker(true);
        assertThatThrownBy(() -> stub.versionen(MANDANT, "manifest/", Optional.empty(), 1))
                .isInstanceOf(UnsichereFortsetzung.class).hasMessageContaining("manifest/2026-01-01.json");
        assertThat(stub.versionen(MANDANT, "manifest/", Optional.empty(), 2).weiter()).isEmpty();
    }

    @Test
    void markeOhneVersionsIdSetztNichtFort() {
        AblageStub stub = mitManifesten(2);
        assertThatThrownBy(() -> stub.versionen(MANDANT, "manifest/",
                Optional.of(new Versionsseite.Marke("manifest/2026-01-01.json", Optional.empty())), 1))
                .isInstanceOf(UnsichereFortsetzung.class);
    }

    @Test
    void abbauLoeschtNurEigeneUngelesperrteVersionen() throws Exception {
        AblageStub stub = new AblageStub();
        Instant jetzt = Instant.parse("2026-01-01T00:00:00Z");
        LocalDate datum = LocalDate.of(2026, 1, 1);
        stub.ablegenManifest(MANDANT, datum, ManifestTeil.JSON, "{\"tenant\":\"urinet\"}"
                .getBytes(StandardCharsets.UTF_8), Optional.of(LocalDate.of(2027, 1, 1)));
        MandantCode andererMandant = new MandantCode("vhf-gsk");
        stub.ablegenManifest(andererMandant, datum, ManifestTeil.JSON, "{\"tenant\":\"vhf-gsk\"}"
                .getBytes(StandardCharsets.UTF_8), Optional.empty());
        Path importDatei = temp.resolve("synthetisch.csv");
        Files.writeString(importDatei, "id;wert\n1;test\n", StandardCharsets.UTF_8);
        stub.ablegenImport(MANDANT, UUID.randomUUID(), importDatei);

        var ergebnis = stub.mandantAbbauen(MANDANT, jetzt);

        assertThat(ergebnis.geloescht()).isEqualTo(1);
        assertThat(ergebnis.gesperrt()).singleElement()
                .satisfies(objekt -> assertThat(objekt.schluessel()).startsWith("manifest/"));
        assertThat(stub.versionen(MANDANT, "importe/", Optional.empty(), 10).versionen()).isEmpty();
        assertThat(stub.lesenManifest(MANDANT, datum, ManifestTeil.JSON)).isPresent();
        assertThat(stub.lesenManifest(andererMandant, datum, ManifestTeil.JSON)).isPresent();
    }

    @Test
    void abbauVorschauZaehltOhneZuLoeschen() throws Exception {
        AblageStub stub = new AblageStub();
        Instant jetzt = Instant.parse("2026-01-01T00:00:00Z");
        stub.ablegenManifest(MANDANT, LocalDate.of(2026, 1, 1), ManifestTeil.JSON,
                "{\"tenant\":\"urinet\"}".getBytes(StandardCharsets.UTF_8), Optional.empty());
        Path importDatei = temp.resolve("synthetisch-vorschau.csv");
        Files.writeString(importDatei, "id;wert\n1;test\n", StandardCharsets.UTF_8);
        stub.ablegenImport(MANDANT, UUID.randomUUID(), importDatei);

        var vorschau = stub.mandantAbbauPlanen(MANDANT, jetzt);

        assertThat(vorschau.geloescht()).isZero();
        assertThat(vorschau.loeschbar()).isEqualTo(1);
        assertThat(vorschau.gesperrt()).hasSize(1);
        assertThat(stub.versionen(MANDANT, "importe/", Optional.empty(), 10).versionen()).hasSize(1);
        assertThat(stub.versionen(MANDANT, "manifest/", Optional.empty(), 10).versionen()).hasSize(1);
    }

    @Test
    void speicherbedarfIstJeMandantGetrennt() {
        AblageStub stub = new AblageStub();
        MandantCode andererMandant = new MandantCode("vhf-gsk");
        stub.ablegenManifest(MANDANT, LocalDate.of(2026, 1, 1), ManifestTeil.JSON,
                "{\"x\":1}".getBytes(StandardCharsets.UTF_8), Optional.empty());
        stub.ablegenManifest(MANDANT, LocalDate.of(2026, 1, 2), ManifestTeil.JSON,
                "{\"x\":2}".getBytes(StandardCharsets.UTF_8), Optional.empty());
        stub.ablegenManifest(andererMandant, LocalDate.of(2026, 1, 1), ManifestTeil.JSON,
                "{\"x\":3}".getBytes(StandardCharsets.UTF_8), Optional.empty());

        assertThat(stub.speicherbedarf(MANDANT)).isEqualTo(14);
        assertThat(stub.speicherbedarf(andererMandant)).isEqualTo(7);
    }

    private static AblageStub mitManifesten(int anzahl) {
        AblageStub stub = new AblageStub();
        for (int tag = 1; tag <= anzahl; tag++) {
            stub.ablegenManifest(MANDANT, LocalDate.of(2026, 1, tag), ManifestTeil.JSON,
                    ("{\"tag\":" + tag + "}").getBytes(StandardCharsets.UTF_8), Optional.empty());
        }
        return stub;
    }
}
