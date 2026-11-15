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
}
