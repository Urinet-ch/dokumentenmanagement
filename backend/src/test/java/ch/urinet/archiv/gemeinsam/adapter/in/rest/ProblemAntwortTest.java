package ch.urinet.archiv.gemeinsam.adapter.in.rest;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

/** Ursache zur Fehler-ID ohne Meldungen (Req 16.7, Kann 7 aus dem Review von PR #37). */
class ProblemAntwortTest {

    @Test
    void ursachenNennenKlassenUndSqlStateOhneMeldung() {
        SQLException sql = new SQLException("Detail: Key (code)=(GEHEIM-4711) already exists.", "23505");
        RuntimeException fehler = new IllegalStateException("GEHEIM-4711", new RuntimeException(sql));

        assertThat(ProblemAntwort.ursachen(fehler)).isEqualTo("java.lang.IllegalStateException"
                + " <- java.lang.RuntimeException <- java.sql.SQLException[sqlstate=23505]");
    }

    @Test
    void zyklischeUndLangeKettenEnden() {
        Exception a = new Exception("a");
        Exception b = new Exception("b", a);
        a.initCause(b);
        Throwable lang = new Error("x");
        for (int i = 0; i < 50; i++) {
            lang = new RuntimeException(lang);
        }

        assertThat(ProblemAntwort.ursachen(a).split(" <- ")).hasSize(2);
        assertThat(ProblemAntwort.ursachen(lang).split(" <- ")).hasSize(10);
    }
}
