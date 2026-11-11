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
import org.hibernate.Session;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Handler nur für Tests (Typen {@code TEST_*}, Zeitgrenzen in {@code src/test/resources/META-INF}): halten fest, was
 * der Worker mit ihnen gemacht hat.
 */
public final class TestHandler {

    public static final AuftragTyp OK = new AuftragTyp("TEST_OK");
    public static final AuftragTyp FEHLER = new AuftragTyp("TEST_FEHLER");
    public static final AuftragTyp HAENGT = new AuftragTyp("TEST_HAENGT");
    /** Ignoriert jede Unterbrechung und endet erst mit {@link #sturFreigeben()} (höchstens nach 30 s). */
    public static final AuftragTyp STUR = new AuftragTyp("TEST_STUR");
    /** Schläft 60 s; schluckt eine Unterbrechung und kehrt normal zurück (Verstoss gegen die Handler-Pflichten). */
    public static final AuftragTyp SCHLUCKT = new AuftragTyp("TEST_SCHLUCKT");
    /** Liest in einer Transaktion im Schema des Mandanten und scheitert dann mit einem SQL-Fehler (Rollback). */
    public static final AuftragTyp SQL_FEHLER = new AuftragTyp("TEST_SQL_FEHLER");
    /**
     * Scheitert im Handler; der Hook nach dem letzten Fehlversuch ignoriert jede Unterbrechung und endet erst mit
     * {@link #sturFreigeben()} (höchstens nach 30 s), dann erfolgreich.
     */
    public static final AuftragTyp HOOK_STUR = new AuftragTyp("TEST_HOOK_STUR");
    /**
     * Platzhalter für {@code KI_VORSCHLAG}, bis Aufgabe 3.2 den echten Handler bringt (dann entfällt er): merkt sich nur
     * die Dokument-IDs, damit die Tests zu {@code VERARBEITEN} den Folgeauftrag sehen.
     */
    public static final AuftragTyp KI_VORSCHLAG = new AuftragTyp("KI_VORSCHLAG");
    /** Dokument-IDs aus den Nutzlasten von {@code KI_VORSCHLAG}, in der Reihenfolge der Ausführung. */
    public static final List<UUID> KI_DOKUMENTE = new CopyOnWriteArrayList<>();
    /** Steht in der Meldung der Ausnahme von {@code TEST_FEHLER}; darf nie im Log oder im Auftrag landen (Req 16.7). */
    public static final String GEHEIM = "geheimer-belegwert-4711";

    /** Auftrags-ID → Schema, in dem {@code TEST_OK} gelaufen ist. */
    public static final Map<Long, String> SCHEMAS = new ConcurrentHashMap<>();
    /** Auftrags-ID → Mandant im Kontext, als der Worker {@code nachLetztemFehlversuch} aufrief. */
    public static final Map<Long, MandantCode> NACH_LETZTEM = new ConcurrentHashMap<>();
    /** Auftrags-ID → Fehler-ID, die der Hook {@code nachLetztemFehlversuch} erhielt. */
    public static final Map<Long, UUID> NACH_LETZTEM_FEHLER = new ConcurrentHashMap<>();
    /** Wie oft der Hook {@code nachLetztemFehlversuch} von {@code TEST_FEHLER} aufgerufen wurde. */
    public static final AtomicInteger NACH_LETZTEM_AUFRUFE = new AtomicInteger();
    /** Lässt den nächsten Aufruf des Hooks scheitern (Absturz oder Fehler nach dem Speichern von FEHLER). */
    public static final AtomicBoolean HOOK_SCHEITERT = new AtomicBoolean();
    public static final Set<Long> UNTERBROCHEN = ConcurrentHashMap.newKeySet();
    /** Auftrags-ID → was der Handler in der Datenbank gesehen hat. */
    public static final Map<Long, Sicht> SICHTEN = new ConcurrentHashMap<>();
    /** Auftrags-ID → gleichzeitig laufende Ausführungen von {@code TEST_STUR} (jetzt und höchstens). */
    public static final Map<Long, AtomicInteger> STUR_JETZT = new ConcurrentHashMap<>();
    public static final Map<Long, AtomicInteger> STUR_HOECHSTENS = new ConcurrentHashMap<>();
    public static final AtomicInteger STUR_GESTARTET = new AtomicInteger();
    /** Aufrufe des Hooks von {@code TEST_HOOK_STUR}, gleichzeitig laufende jetzt und höchstens. */
    public static final AtomicInteger HOOK_STUR_AUFRUFE = new AtomicInteger();
    public static final AtomicInteger HOOK_STUR_JETZT = new AtomicInteger();
    public static final AtomicInteger HOOK_STUR_HOECHSTENS = new AtomicInteger();
    private static volatile CountDownLatch sturFreigabe = new CountDownLatch(1);

    /** Schema ({@code current_schema()}), Hibernate-Tenant und Backend-Prozess der Verbindung. */
    public record Sicht(String schema, Object tenant, int pid) {
    }

    private TestHandler() {
    }

    public static void zuruecksetzen() {
        sturFreigeben();
        sturFreigabe = new CountDownLatch(1);
        SCHEMAS.clear();
        NACH_LETZTEM.clear();
        NACH_LETZTEM_FEHLER.clear();
        NACH_LETZTEM_AUFRUFE.set(0);
        HOOK_SCHEITERT.set(false);
        UNTERBROCHEN.clear();
        SICHTEN.clear();
        STUR_JETZT.clear();
        STUR_HOECHSTENS.clear();
        STUR_GESTARTET.set(0);
        HOOK_STUR_AUFRUFE.set(0);
        HOOK_STUR_JETZT.set(0);
        HOOK_STUR_HOECHSTENS.set(0);
        KI_DOKUMENTE.clear();
    }

    /** Lässt alle laufenden {@code TEST_STUR}-Handler enden. */
    public static void sturFreigeben() {
        sturFreigabe.countDown();
    }

    static Sicht sicht(EntityManager em) {
        Object[] zeile = (Object[]) em.createNativeQuery("select current_schema(), pg_backend_pid()")
                .getSingleResult();
        return new Sicht((String) zeile[0], em.unwrap(Session.class).getTenantIdentifierValue(),
                ((Number) zeile[1]).intValue());
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
            Sicht sicht = QuarkusTransaction.requiringNew().call(() -> {
                DokumentartEintrag.count();
                return sicht(em.get());
            });
            SICHTEN.put(auftrag.id(), sicht);
            SCHEMAS.put(auftrag.id(), sicht.schema());
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
        public void nachLetztemFehlversuch(Auftrag auftrag, UUID fehlerId) {
            NACH_LETZTEM_AUFRUFE.incrementAndGet();
            if (HOOK_SCHEITERT.getAndSet(false)) {
                throw new IllegalStateException("Hook gescheitert " + GEHEIM);
            }
            NACH_LETZTEM.put(auftrag.id(), kontext.aktuell().orElseThrow());
            NACH_LETZTEM_FEHLER.put(auftrag.id(), fehlerId);
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

    @ApplicationScoped
    public static class Schluckt implements AuftragHandler {

        @Override
        public AuftragTyp typ() {
            return SCHLUCKT;
        }

        @Override
        public void ausfuehren(Auftrag auftrag) {
            try {
                Thread.sleep(60_000);
            } catch (InterruptedException e) {
                UNTERBROCHEN.add(auftrag.id());
            }
        }
    }

    @ApplicationScoped
    public static class Stur implements AuftragHandler {

        @Override
        public AuftragTyp typ() {
            return STUR;
        }

        @Override
        public void ausfuehren(Auftrag auftrag) {
            STUR_GESTARTET.incrementAndGet();
            int jetzt = STUR_JETZT.computeIfAbsent(auftrag.id(), id -> new AtomicInteger()).incrementAndGet();
            STUR_HOECHSTENS.computeIfAbsent(auftrag.id(), id -> new AtomicInteger()).accumulateAndGet(jetzt, Math::max);
            try {
                if (sturWarten()) {
                    UNTERBROCHEN.add(auftrag.id());
                }
            } finally {
                STUR_JETZT.get(auftrag.id()).decrementAndGet();
            }
        }
    }

    @ApplicationScoped
    public static class HookStur implements AuftragHandler {

        @Override
        public AuftragTyp typ() {
            return HOOK_STUR;
        }

        @Override
        public void ausfuehren(Auftrag auftrag) throws Exception {
            throw new Exception("Beleg nicht lesbar");
        }

        @Override
        public void nachLetztemFehlversuch(Auftrag auftrag, UUID fehlerId) {
            HOOK_STUR_AUFRUFE.incrementAndGet();
            HOOK_STUR_HOECHSTENS.accumulateAndGet(HOOK_STUR_JETZT.incrementAndGet(), Math::max);
            try {
                sturWarten();
            } finally {
                HOOK_STUR_JETZT.decrementAndGet();
            }
        }
    }

    /** Wartet auf {@link #sturFreigeben()} (höchstens 30 s) und ignoriert Unterbrechungen; {@code true}, wenn welche kamen. */
    private static boolean sturWarten() {
        CountDownLatch freigabe = sturFreigabe;
        long bis = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        boolean unterbrochen = false;
        while (freigabe.getCount() > 0 && System.nanoTime() - bis < 0) {
            try {
                freigabe.await(bis - System.nanoTime(), TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                // wie blockierendes I/O, das den Interrupt nicht beachtet
                unterbrochen = true;
            }
        }
        return unterbrochen;
    }

    @ApplicationScoped
    public static class KiVorschlag implements AuftragHandler {

        @Override
        public AuftragTyp typ() {
            return KI_VORSCHLAG;
        }

        @Override
        public void ausfuehren(Auftrag auftrag) {
            KI_DOKUMENTE.add(auftrag.nutzlast().uuid("dokumentId").orElseThrow());
        }
    }

    @ApplicationScoped
    public static class SqlFehler implements AuftragHandler {

        @Inject
        Instance<EntityManager> em;

        @Override
        public AuftragTyp typ() {
            return SQL_FEHLER;
        }

        @Override
        public void ausfuehren(Auftrag auftrag) {
            QuarkusTransaction.requiringNew().run(() -> {
                DokumentartEintrag.count();
                SICHTEN.put(auftrag.id(), sicht(em.get()));
                em.get().createNativeQuery("select 1 / 0").getSingleResult();
            });
        }
    }
}
