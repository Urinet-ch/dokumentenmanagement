package ch.urinet.archiv.protokoll.adapter.out.persistence;

import ch.urinet.archiv.protokoll.domain.ProtokollDetails;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VerwaltungProtokollTest {

    @Test
    void serialisiertNurKennungenRollenUndGeaenderteFeldnamen() throws Exception {
        var id = UUID.randomUUID();
        var ziel = UUID.randomUUID();
        var details = ProtokollDetails.verwaltung(new ProtokollDetails.Verwaltung(id,
                ProtokollDetails.Verwaltungsphase.ABGESCHLOSSEN, ziel, ProtokollDetails.Verwaltungsvorgang.EMAIL_AENDERN,
                ProtokollDetails.Verwaltungsrolle.MITARBEITER, ProtokollDetails.Verwaltungsrolle.INHABER,
                List.of(ProtokollDetails.Verwaltungsfeld.EMAIL, ProtokollDetails.Verwaltungsfeld.VORNAME),
                ProtokollDetails.Verwaltungsgrund.KORREKTUR));
        String text = JdbcZugriffProtokoll.json(details);
        var json = new ObjectMapper().readTree(text);
        assertThat(json.path("vorgangId").asText()).isEqualTo(id.toString());
        assertThat(json.path("ziel").asText()).isEqualTo(ziel.toString());
        assertThat(json.path("vorgang").asText()).isEqualTo("EMAIL_AENDERN");
        assertThat(json.path("rolleAlt").asText()).isEqualTo("mitarbeiter");
        assertThat(json.path("rolleNeu").asText()).isEqualTo("inhaber");
        assertThat(json.path("grund").asText()).isEqualTo("KORREKTUR");
        assertThat(json.path("felder").path("email").asText()).isEqualTo("geändert");
        assertThat(json.path("felder").path("vorname").asText()).isEqualTo("geändert");
        assertThat(text).doesNotContain("@", "://", "Synthetischer Name");
        assertThatThrownBy(() -> new ProtokollDetails("Grund", null, null, null, null, null, null, null, null,
                null, null, details.verwaltung())).isInstanceOf(IllegalArgumentException.class);
    }
}
