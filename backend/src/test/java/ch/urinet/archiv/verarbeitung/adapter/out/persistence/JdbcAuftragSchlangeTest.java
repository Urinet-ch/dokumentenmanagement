package ch.urinet.archiv.verarbeitung.adapter.out.persistence;

import ch.urinet.archiv.gemeinsam.domain.Ursachen;
import ch.urinet.archiv.verarbeitung.domain.Nutzlast;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Nutzlast als JSON in {@code public.auftrag}: Hin- und Rückweg verlustfrei, freier Text wird nicht angenommen. */
class JdbcAuftragSchlangeTest {

    @Test
    void jsonHinUndZurueck() {
        Nutzlast nutzlast = new Nutzlast(Map.of("dokumentId", UUID.randomUUID(), "seite", 5_000_000_000L,
                "erneut", false));
        assertThat(JdbcAuftragSchlange.nutzlast(JdbcAuftragSchlange.json(nutzlast))).isEqualTo(nutzlast);
        assertThat(JdbcAuftragSchlange.nutzlast("{}")).isEqualTo(Nutzlast.leer());
    }

    @Test
    void textImJsonIstUngueltigOhneWert() {
        assertThatThrownBy(() -> JdbcAuftragSchlange.nutzlast("{\"kunde\":\"FC Beispiel\"}"))
                .isInstanceOf(IllegalStateException.class).hasNoCause().message().doesNotContain("FC Beispiel");
    }

    @Test
    void sqlStateBleibtImLogMeldungNicht() {
        RuntimeException fehler = JdbcAuftragSchlange.fehler("Auftrag nicht gespeichert",
                new SQLException("Schlüssel (kunde)=(FC Beispiel) existiert bereits", "23505"));
        assertThat(Ursachen.kette(fehler)).contains("[sqlstate=23505]").doesNotContain("FC Beispiel");
    }
}
