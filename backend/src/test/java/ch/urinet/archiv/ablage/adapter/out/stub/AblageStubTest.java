package ch.urinet.archiv.ablage.adapter.out.stub;

import ch.urinet.archiv.ablage.domain.ManifestTeil;
import ch.urinet.archiv.ablage.domain.Objektversion;
import ch.urinet.archiv.ablage.domain.UnsichereFortsetzung;
import ch.urinet.archiv.ablage.domain.Versionsseite;
import ch.urinet.archiv.mandant.domain.MandantCode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Versionsliste des Stubs wie im Object Storage (Review PR #97): Fortsetzen nur mit Schlüssel und Versions-ID. */
class AblageStubTest {

    private static final MandantCode MANDANT = new MandantCode("urinet");

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

    private static AblageStub mitManifesten(int anzahl) {
        AblageStub stub = new AblageStub();
        for (int tag = 1; tag <= anzahl; tag++) {
            stub.ablegenManifest(MANDANT, LocalDate.of(2026, 1, tag), ManifestTeil.JSON,
                    ("{\"tag\":" + tag + "}").getBytes(StandardCharsets.UTF_8), Optional.empty());
        }
        return stub;
    }
}
