package ch.urinet.archiv.integration.domain.webseite;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Der Cursor des Änderungs-Feeds ist opak und strikt (Spec 03, Aufgabe 11.9, Req 14.3). */
class FeedCursorTest {

    @Test
    void kodierenUndLesenSindUmkehrbar() {
        FeedCursor cursor = new FeedCursor(123456789012L, 42);

        assertThat(FeedCursor.lesen(cursor.kodieren())).isEqualTo(cursor);
        assertThat(cursor.kodieren()).matches("[A-Za-z0-9_-]+");
        assertThat(FeedCursor.vor(7)).isEqualTo(new FeedCursor(7, 0));
    }

    @Test
    void jedeAbweichungIst400OhneDenWertZuNennen() {
        String[] ungueltig = {"", "kein-cursor", "***", "djEuMS4w=", "x".repeat(65), kodiert("v2.1.0"), kodiert("v1.1"),
                kodiert("v1.-1.0"), kodiert("v1.1.0.0"), kodiert("v1.0001.0"), kodiert("v1.9999999999999999999.0"),
                kodiert("v1.1.x"), kodiert(" v1.1.0")};
        for (String text : ungueltig) {
            assertThatThrownBy(() -> FeedCursor.lesen(text)).as(text).isInstanceOfSatisfying(FachFehler.class,
                    e -> assertThat(e.code()).isEqualTo(Fehlercode.ANFRAGE_UNGUELTIG));
        }
        assertThatThrownBy(() -> FeedCursor.lesen(null)).isInstanceOf(FachFehler.class);
    }

    @Test
    void nurEineKanonischeSchreibweiseGilt() {
        // Auffüllzeichen und führende Nullen ergäben denselben Cursor in zwei Schreibweisen
        assertThat(FeedCursor.lesen("djEuMS4w")).isEqualTo(new FeedCursor(1, 0));
        assertThatThrownBy(() -> FeedCursor.lesen("djEuMS4w=")).isInstanceOf(FachFehler.class);
        assertThatThrownBy(() -> FeedCursor.lesen(kodiert("v1.01.0"))).isInstanceOf(FachFehler.class);
    }

    private static String kodiert(String klar) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(klar.getBytes(StandardCharsets.US_ASCII));
    }
}
