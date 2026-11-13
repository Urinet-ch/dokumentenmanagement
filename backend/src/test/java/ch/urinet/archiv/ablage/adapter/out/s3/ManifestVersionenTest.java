package ch.urinet.archiv.ablage.adapter.out.s3;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.model.ObjectVersion;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Auswahl der ältesten Version eines Manifest-Schlüssels (Review PR #87, Nachtrag Punkt 5). */
class ManifestVersionenTest {

    private static final String SCHLUESSEL = "manifest/2026/01/2026-01-10.json";
    private static final Instant T = Instant.parse("2026-01-11T01:00:00Z");

    @Test
    void dieAeltesteKommtZuerstAuchWennSieZuletztGelistetIst() {
        List<ObjectVersion> sortiert = S3Ablage.aeltesteZuerst(SCHLUESSEL,
                List.of(version("neu", T.plusSeconds(5)), version("alt", T)));

        assertThat(sortiert).extracting(ObjectVersion::versionId).containsExactly("alt", "neu");
    }

    @Test
    void gleicherZeitpunktDerAeltestenIstEinFehler() {
        List<ObjectVersion> versionen = List.of(version("b", T), version("a", T), version("c", T.plusSeconds(1)));

        assertThatThrownBy(() -> S3Ablage.aeltesteZuerst(SCHLUESSEL, versionen))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Unter " + SCHLUESSEL + " liegen mehrere Versionen mit gleichem Zeitpunkt; "
                        + "die älteste ist nicht eindeutig");
    }

    @Test
    void gleicherZeitpunktSpaetererVersionenUndEineEinzelneOhneZeitpunktSindEindeutig() {
        assertThat(S3Ablage.aeltesteZuerst(SCHLUESSEL,
                List.of(version("b", T.plusSeconds(1)), version("c", T.plusSeconds(1)), version("a", T))))
                .first().extracting(ObjectVersion::versionId).isEqualTo("a");
        assertThat(S3Ablage.aeltesteZuerst(SCHLUESSEL, List.of(version("a", null)))).hasSize(1);
        assertThatThrownBy(() -> S3Ablage.aeltesteZuerst(SCHLUESSEL, List.of(version("a", T), version("b", null))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("nicht eindeutig");
    }

    private static ObjectVersion version(String id, Instant zeit) {
        return ObjectVersion.builder().key(SCHLUESSEL).versionId(id).lastModified(zeit).build();
    }
}
