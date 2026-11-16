package ch.urinet.archiv.sicherung.adapter.in.kommando;

import ch.urinet.archiv.ablage.domain.Objektversion;
import ch.urinet.archiv.sicherung.application.SicherungBucketExport;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InventarAbgleichTest {

    @Test
    void prueftAuchNichtVerglicheneZeilenAmEndeDesVorinventars() {
        String vorher = """
                {"format":"%s","schluessel":"archiv/z","versionId":"v1","loeschmarke":false,"groesse":1,"lastModified":"2026-01-01T00:00:00Z","sha256":"","sperrmodus":null,"retainUntil":null}
                kaputt
                """.formatted(SicherungBucketExport.INVENTAR_FORMAT);
        var abgleich = new InventarAbgleich(new StringReader(vorher), new ObjectMapper());

        assertThat(abgleich.test(new Objektversion("archiv/a", "v2", false,
                Instant.parse("2026-01-01T00:00:00Z"), 1))).isTrue();

        assertThatThrownBy(abgleich::vollstaendigPruefen)
                .isInstanceOf(InventarDateiFehler.class);
    }

    private static String zeile(String schluessel, String versionId, String zeit) {
        return ("{\"format\":\"%s\",\"schluessel\":\"%s\",\"versionId\":\"%s\",\"loeschmarke\":false,"
                + "\"groesse\":1,\"lastModified\":\"%s\",\"sha256\":\"\",\"sperrmodus\":null,\"retainUntil\":null}\n")
                .formatted(SicherungBucketExport.INVENTAR_FORMAT, schluessel, versionId, zeit);
    }

    /** Gleicher Schlüssel und gleiche Zeit: die Reihenfolge der Versions-IDs ist egal, zugeordnet wird über die ID. */
    @Test
    void gleicheZeitInAndererReihenfolgeWirdOhneFehlerZugeordnet() {
        String zeit = "2026-01-01T00:00:00Z";
        String vorher = zeile("archiv/a", "v2", zeit) + zeile("archiv/a", "v1", zeit) + zeile("archiv/b", "v9", zeit);
        var abgleich = new InventarAbgleich(new StringReader(vorher), new ObjectMapper());
        Instant t = Instant.parse(zeit);

        assertThat(abgleich.test(new Objektversion("archiv/a", "v1", false, t, 1))).isFalse();
        assertThat(abgleich.test(new Objektversion("archiv/a", "v2", false, t, 1))).isFalse();
        assertThat(abgleich.test(new Objektversion("archiv/a", "v3", false, t, 1))).isTrue();
        assertThat(abgleich.test(new Objektversion("archiv/b", "v9", false, t, 1))).isFalse();
        abgleich.vollstaendigPruefen();
    }

    @Test
    void doppelteVersionImVorinventarIstUngueltig() {
        String zeit = "2026-01-01T00:00:00Z";
        String vorher = zeile("archiv/a", "v1", zeit) + zeile("archiv/a", "v1", zeit);

        assertThatThrownBy(() -> new InventarAbgleich(new StringReader(vorher), new ObjectMapper())
                .vollstaendigPruefen()).isInstanceOf(InventarDateiFehler.class);
    }

    @Test
    void leeresVorinventarBehandeltJedeVersionAlsNeu() {
        var abgleich = new InventarAbgleich(new StringReader(""), new ObjectMapper());

        assertThat(abgleich.test(new Objektversion("archiv/a", "v1", false,
                Instant.parse("2026-01-01T00:00:00Z"), 1))).isTrue();

        assertThat(abgleich.test(new Objektversion("manifest/b", "v2", false,
                Instant.parse("2026-01-02T00:00:00Z"), 2))).isTrue();
        abgleich.vollstaendigPruefen();
    }
}
