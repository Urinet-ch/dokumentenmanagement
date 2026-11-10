package ch.urinet.archiv.verarbeitung;

import ch.urinet.archiv.dokumentart.adapter.out.persistence.DokumentartEintrag;
import ch.urinet.archiv.mandant.application.MandantKontext;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.verarbeitung.application.Auftrag;
import ch.urinet.archiv.verarbeitung.application.AuftragHandler;
import ch.urinet.archiv.verarbeitung.domain.AuftragTyp;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handler nur für Tests (Typen {@code TEST_*}, Zeitgrenzen in {@code src/test/resources/META-INF}): halten fest, was
 * der Worker mit ihnen gemacht hat.
 */
public final class TestHandler {

    public static final AuftragTyp OK = new AuftragTyp("TEST_OK");
    public static final AuftragTyp FEHLER = new AuftragTyp("TEST_FEHLER");
    public static final AuftragTyp HAENGT = new AuftragTyp("TEST_HAENGT");
    /** Steht in der Meldung der Ausnahme von {@code TEST_FEHLER}; darf nie im Log oder im Auftrag landen (Req 16.7). */
    public static final String GEHEIM = "geheimer-belegwert-4711";

    /** Auftrags-ID → Schema, in dem {@code TEST_OK} gelaufen ist. */
    public static final Map<Long, String> SCHEMAS = new ConcurrentHashMap<>();
    /** Auftrags-ID → Mandant im Kontext, als der Worker {@code nachLetztemFehlversuch} aufrief. */
    public static final Map<Long, MandantCode> NACH_LETZTEM = new ConcurrentHashMap<>();
    public static final Set<Long> UNTERBROCHEN = ConcurrentHashMap.newKeySet();

    private TestHandler() {
    }

    public static void zuruecksetzen() {
        SCHEMAS.clear();
        NACH_LETZTEM.clear();
        UNTERBROCHEN.clear();
    }

    @ApplicationScoped
    public static class Ok implements AuftragHandler {

        // lazy: in Testprofilen ohne Hibernate ist der EntityManager inaktiv, die Handler entstehen beim Start
        @Inject
        Instance<EntityManager> em;

        @Override
        public AuftragTyp typ() {
            return OK;
        }

        @Override
        public void ausfuehren(Auftrag auftrag) {
            SCHEMAS.put(auftrag.id(), QuarkusTransaction.requiringNew().call(() -> {
                DokumentartEintrag.count();
                return (String) em.get().createNativeQuery("select current_schema()").getSingleResult();
            }));
        }
    }

    @ApplicationScoped
    public static class Fehler implements AuftragHandler {

        @Inject
        MandantKontext kontext;

        @Override
        public AuftragTyp typ() {
            return FEHLER;
        }

        @Override
        public void ausfuehren(Auftrag auftrag) throws Exception {
            throw new Exception("Beleg " + GEHEIM + " nicht lesbar");
        }

        @Override
        public void nachLetztemFehlversuch(Auftrag auftrag) {
            NACH_LETZTEM.put(auftrag.id(), kontext.aktuell().orElseThrow());
        }
    }

    @ApplicationScoped
    public static class Haengt implements AuftragHandler {

        @Override
        public AuftragTyp typ() {
            return HAENGT;
        }

        @Override
        public void ausfuehren(Auftrag auftrag) throws InterruptedException {
            try {
                Thread.sleep(60_000);
            } catch (InterruptedException e) {
                UNTERBROCHEN.add(auftrag.id());
                throw e;
            }
        }
    }
}
