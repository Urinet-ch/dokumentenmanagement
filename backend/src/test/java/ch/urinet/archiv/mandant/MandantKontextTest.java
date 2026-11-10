package ch.urinet.archiv.mandant;

import ch.urinet.archiv.dokumentart.adapter.out.persistence.DokumentartEintrag;
import ch.urinet.archiv.mandant.application.MandantKontext;
import ch.urinet.archiv.mandant.application.MandantProvisionierung;
import ch.urinet.archiv.mandant.domain.MandantCode;
import io.agroal.api.AgroalDataSource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Worker-Kontext (Req 1.6): {@code MandantKontext.mit} setzt den Mandanten für Hibernate, räumt in {@code finally} auf
 * und bricht bei einem Mandantenwechsel ab; ohne Mandant gibt es keinen Zugriff auf Mandanten-Tabellen (Req 1.3).
 * Die Aufrufe laufen in eigenen Threads ohne Request-Kontext, wie im Worker. In einer äusseren Transaktion bricht
 * {@code mit} ab, weil die Session an der Transaktion hängt und den ersten Mandanten behielte.
 */
@QuarkusTest
class MandantKontextTest {

    @Inject
    MandantKontext kontext;

    @Inject
    MandantProvisionierung provisionierung;

    @Inject
    AgroalDataSource datenbank;

    @Inject
    EntityManager em;

    private final ExecutorService threads = Executors.newFixedThreadPool(2);

    @BeforeEach
    void mandanten() throws SQLException {
        for (String code : List.of("urinet", "vhf-gsk")) {
            if (!imRegister(code)) {
                provisionierung.anlegen(code, null, null);
            }
        }
    }

    @Test
    void zweiThreadsVermischenDieMandantenNicht() throws Exception {
        CyclicBarrier gleichzeitig = new CyclicBarrier(2);
        Callable<String> urinet = () -> schemaIm("urinet", gleichzeitig);
        Callable<String> vhfGsk = () -> schemaIm("vhf-gsk", gleichzeitig);
        for (int runde = 0; runde < 5; runde++) {
            Future<String> a = threads.submit(urinet);
            Future<String> b = threads.submit(vhfGsk);
            assertThat(a.get()).isEqualTo("m_urinet");
            assertThat(b.get()).isEqualTo("m_vhf_gsk");
        }
    }

    @Test
    void ausnahmeImBlockHinterlaesstKeinenKontext() throws Exception {
        assertThat(threads.submit(() -> {
            try {
                kontext.mit(new MandantCode("urinet"), (Runnable) () -> {
                    throw new IllegalArgumentException("Abbruch im Auftrag");
                });
            } catch (IllegalArgumentException erwartet) {
                // erwartet
            }
            return kontext.aktuell().isEmpty();
        }).get()).isTrue();
    }

    @Test
    void verschachteltMitAnderemMandantenBrichtAb() throws Exception {
        assertThat(threads.submit(() -> {
            try {
                kontext.mit(new MandantCode("urinet"),
                        () -> kontext.mit(new MandantCode("vhf-gsk"), () -> "nie"));
                return "kein Abbruch";
            } catch (IllegalStateException erwartet) {
                return kontext.aktuell().isEmpty() ? "abgebrochen" : "Kontext bleibt";
            }
        }).get()).isEqualTo("abgebrochen");
        assertThat(threads.submit(() -> kontext.mit(new MandantCode("urinet"),
                () -> kontext.mit(new MandantCode("urinet"), () -> "gleich"))).get()).isEqualTo("gleich");
    }

    @Test
    void zweiMitInEinerAeusserenTransaktionBrechenAb() throws Exception {
        List<String> schemas = new ArrayList<>();
        String ergebnis = threads.submit(() -> {
            try {
                QuarkusTransaction.requiringNew().run(() -> {
                    schemas.add(kontext.mit(new MandantCode("vhf-gsk"), this::aktuellesSchema));
                    schemas.add(kontext.mit(new MandantCode("urinet"), this::aktuellesSchema));
                });
                return "kein Abbruch";
            } catch (RuntimeException e) {
                return meldungen(e).contains("laufenden Transaktion") && kontext.aktuell().isEmpty()
                        ? "abgebrochen" : "anderer Fehler: " + meldungen(e);
            }
        }).get();
        assertThat(ergebnis).as("Schemas " + schemas).isEqualTo("abgebrochen");
        assertThat(schemas).isEmpty();
        // die Transaktion gehört in den Block: so bleibt jeder Mandant in seinem Schema
        assertThat(threads.submit(() -> kontext.mit(new MandantCode("urinet"),
                () -> QuarkusTransaction.requiringNew().call(this::aktuellesSchema))).get()).isEqualTo("m_urinet");
    }

    @Test
    void imAktivenRequestKontextOhnePassendeAnmeldungBrichtAb() {
        // der Test-Thread hat einen aktiven Request-Kontext ohne Anmeldung: kein stiller Mandantenwechsel
        assertThatThrownBy(() -> kontext.mit(new MandantCode("urinet"), () -> "nie"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(kontext.aktuell()).isEmpty();
    }

    @Test
    void ohneMandantKeinZugriffAufMandantenTabellen() {
        // Worker-Thread ohne Kontext: Hibernate erhält keinen Tenant
        assertThatThrownBy(() -> threads.submit(() -> QuarkusTransaction.requiringNew()
                .call(() -> DokumentartEintrag.count())).get())
                .rootCause().hasMessageContaining("no tenant identifier");
        // Request-Kontext ohne Anmeldung: Default-Tenant public, dort gibt es keine Mandanten-Tabellen
        assertThatThrownBy(() -> QuarkusTransaction.requiringNew().call(() -> DokumentartEintrag.count()))
                .rootCause().hasMessageContaining("relation \"dokumentart\" does not exist");
    }

    private String schemaIm(String mandant, CyclicBarrier gleichzeitig) {
        return kontext.mit(new MandantCode(mandant), () -> QuarkusTransaction.requiringNew().call(() -> {
            DokumentartEintrag.count();
            gleichzeitig.await();
            return (String) em.createNativeQuery("select current_schema()").getSingleResult();
        }));
    }

    private String aktuellesSchema() {
        DokumentartEintrag.count();
        return (String) em.createNativeQuery("select current_schema()").getSingleResult();
    }

    private static String meldungen(Throwable fehler) {
        StringBuilder text = new StringBuilder();
        for (Throwable f = fehler; f != null; f = f.getCause()) {
            text.append(f.getMessage()).append(" | ");
        }
        return text.toString();
    }

    private boolean imRegister(String code) throws SQLException {
        try (Connection verbindung = datenbank.getConnection(); PreparedStatement abfrage =
                verbindung.prepareStatement("select 1 from public.mandant where code = ?")) {
            abfrage.setString(1, code);
            try (ResultSet zeile = abfrage.executeQuery()) {
                return zeile.next();
            }
        }
    }
}
