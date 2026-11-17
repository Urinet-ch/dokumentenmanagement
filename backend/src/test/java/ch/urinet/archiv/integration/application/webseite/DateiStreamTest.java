package ch.urinet.archiv.integration.application.webseite;

import ch.urinet.archiv.ablage.application.AblagePort;
import ch.urinet.archiv.ablage.domain.Ablageort;
import ch.urinet.archiv.ablage.domain.DateiRolle;
import ch.urinet.archiv.ablage.domain.DateiSchluessel;
import ch.urinet.archiv.dokument.application.DokumentDatei;
import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.integration.domain.webseite.Lesezugriff;
import ch.urinet.archiv.integration.domain.webseite.Zugriffsstufe;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.protokoll.domain.IpAdresse;
import ch.urinet.archiv.ablage.domain.DateiStrom;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.gemeinsam.domain.ZuVieleAnfragen;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Proxy;
import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Spec 03, Aufgabe 11.8 (Req 13.3, 13.4, 13.7, 13.8): {@link StreamLauf} und {@link DateiPlaetze} ohne Quarkus. Der
 * Strom besteht aus einer erzeugten Folge (nie eine ganze Datei im Speicher des Tests), der Client aus einem
 * {@link OutputStream}, der langsam ist, hängt oder abbricht. Zeitlimits laufen echt, aber in Millisekunden.
 */
class DateiStreamTest {

    private static final int BLOCK = StreamLauf.BLOCK_GROESSE;
    private static final Duration LANG = Duration.ofMinutes(5);

    private ScheduledExecutorService zeitgeber;
    private DateiPlaetze plaetze;

    @BeforeEach
    void vorbereiten() {
        zeitgeber = Executors.newSingleThreadScheduledExecutor();
        plaetze = new DateiPlaetze(8, 8);
    }

    @AfterEach
    void aufraeumen() {
        zeitgeber.shutdownNow();
    }

    /** Erzeugter Strom von {@code laenge} Bytes, der zählt, wie viel gelesen wurde, und das Schliessen merkt. */
    private static final class Quelle extends InputStream {
        final long laenge;
        final AtomicLong gelesen = new AtomicLong();
        final AtomicBoolean geschlossen = new AtomicBoolean();
        final AtomicBoolean abgebrochen = new AtomicBoolean();

        Quelle(long laenge) {
            this.laenge = laenge;
        }

        @Override
        public int read() throws IOException {
            byte[] eins = new byte[1];
            return read(eins, 0, 1) < 0 ? -1 : eins[0] & 0xff;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (geschlossen.get()) {
                throw new IOException("geschlossen");
            }
            long rest = laenge - gelesen.get();
            if (rest <= 0) {
                return -1;
            }
            int n = (int) Math.min(len, Math.min(rest, 8192));
            for (int i = 0; i < n; i++) {
                b[off + i] = (byte) ((gelesen.get() + i) % 251);
            }
            gelesen.addAndGet(n);
            return n;
        }

        @Override
        public void close() {
            geschlossen.set(true);
        }

        DateiStrom alsStrom() {
            return new DateiStrom(this, laenge, () -> abgebrochen.set(true));
        }
    }

    private StreamLauf lauf(Quelle quelle, String schluessel, Duration leerlauf, Duration gesamt, Runnable zu) {
        return new StreamLauf(quelle.alsStrom(), plaetze.reservieren(schluessel), leerlauf, gesamt, zeitgeber, zu);
    }

    @Test
    void gibtDieDateiInBloeckenVonHoechstens64KiBWeiter() throws IOException {
        Quelle quelle = new Quelle(3L * BLOCK + 17);
        List<Integer> blockgroessen = new ArrayList<>();
        StreamLauf lauf = lauf(quelle, "a", LANG, LANG, () -> {
        });

        lauf.senden(new OutputStream() {
            @Override
            public void write(int b) {
                blockgroessen.add(1);
            }

            @Override
            public void write(byte[] b, int off, int len) {
                blockgroessen.add(len);
            }
        });

        assertThat(blockgroessen).containsExactly(BLOCK, BLOCK, BLOCK, 17);
        assertThat(lauf.gesendet()).isEqualTo(3L * BLOCK + 17);
        assertThat(lauf.ende()).isEqualTo(StreamLauf.Ende.VOLLSTAENDIG);
        assertThat(BLOCK).isEqualTo(65536);
    }

    @Test
    void langsamerClientHaeltDasLesenAuf_hoechstensEinBlockGelesenAberNichtWeitergegeben() throws IOException {
        Quelle quelle = new Quelle(40L * BLOCK);
        AtomicLong angenommen = new AtomicLong();
        AtomicLong groessterVorsprung = new AtomicLong();
        StreamLauf lauf = lauf(quelle, "a", LANG, LANG, () -> {
        });

        lauf.senden(new OutputStream() {
            @Override
            public void write(int b) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                // Vorsprung = gelesen, aber noch nicht angenommen: nie mehr als der Block, der gerade kommt
                groessterVorsprung.accumulateAndGet(quelle.gelesen.get() - angenommen.get(), Math::max);
                try {
                    Thread.sleep(2);
                } catch (InterruptedException e) {
                    throw new IOException(e);
                }
                angenommen.addAndGet(len);
            }
        });

        assertThat(angenommen.get()).isEqualTo(40L * BLOCK);
        assertThat(groessterVorsprung.get()).isLessThanOrEqualTo(BLOCK);
    }

    @Test
    void messfall50MBAnEinenLangsamenClientHaeltKeinenVollenPufferImSpeicher() throws IOException {
        long fuenfzigMB = 50L * 1024 * 1024;
        Quelle quelle = new Quelle(fuenfzigMB);
        AtomicLong angenommen = new AtomicLong();
        StreamLauf lauf = lauf(quelle, "a", LANG, LANG, () -> {
        });
        var threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long vorher = threads.getThreadAllocatedBytes(Thread.currentThread().threadId());

        lauf.senden(new OutputStream() {
            @Override
            public void write(int b) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                angenommen.addAndGet(len);
                try {
                    Thread.sleep(1); // gedrosselter Client: rund 64 MB/s, 800 Blöcke
                } catch (InterruptedException e) {
                    throw new IOException(e);
                }
            }
        });

        long belegt = threads.getThreadAllocatedBytes(Thread.currentThread().threadId()) - vorher;
        assertThat(angenommen.get()).isEqualTo(fuenfzigMB);
        // ein Puffer von 64 KiB und etwas Verwaltung; ein ganzes Einlesen (readAllBytes) belegte mindestens 50 MB
        assertThat(belegt).as("Bytes, die der Lauf auf dem Heap belegt hat").isLessThan(4L * 1024 * 1024);
        assertThat(lauf.ende()).isEqualTo(StreamLauf.Ende.VOLLSTAENDIG);
    }

    @Test
    void abbruchDesClientsGibtPlatzUndSpeicherverbindungFrei() {
        Quelle quelle = new Quelle(10L * BLOCK);
        StreamLauf lauf = lauf(quelle, "a", LANG, LANG, () -> {
        });
        assertThat(plaetze.belegt()).isEqualTo(1);
        AtomicInteger schreibvorgaenge = new AtomicInteger();

        assertThatThrownBy(() -> lauf.senden(new OutputStream() {
            @Override
            public void write(int b) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                if (schreibvorgaenge.incrementAndGet() == 2) {
                    throw new IOException("Client hat die Verbindung geschlossen");
                }
            }
        })).isInstanceOf(IOException.class);

        assertThat(lauf.ende()).isEqualTo(StreamLauf.Ende.ABBRUCH);
        assertThat(quelle.geschlossen).isTrue();
        assertThat(quelle.abgebrochen).as("Speicherverbindung verworfen, nicht leergelesen").isTrue();
        assertThat(plaetze.belegt()).isZero();
        assertThat(plaetze.belegt("a")).isZero();
        assertThat(quelle.gelesen.get()).as("kein Weiterlesen nach dem Abbruch").isLessThan(10L * BLOCK);
    }

    @Test
    void ausfallDesSpeichersMitten_imStreamGibtPlatzFrei() {
        InputStream kaputt = new InputStream() {
            int n;

            @Override
            public int read() {
                throw new UnsupportedOperationException();
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                if (n++ == 1) {
                    throw new IOException("Speicher getrennt");
                }
                return Math.min(len, 1000);
            }
        };
        StreamLauf lauf = new StreamLauf(new DateiStrom(kaputt, 10L * BLOCK), plaetze.reservieren("a"), LANG, LANG,
                zeitgeber, () -> {
        });

        assertThatThrownBy(() -> lauf.senden(OutputStream.nullOutputStream())).isInstanceOf(IOException.class);

        assertThat(plaetze.belegt()).isZero();
    }

    @Test
    void zuKurzeDateiIstEinFehlerNichtEinVollstaendigerStream() {
        Quelle quelle = new Quelle(1000);
        StreamLauf lauf = new StreamLauf(new DateiStrom(quelle, 2000), plaetze.reservieren("a"), LANG, LANG,
                zeitgeber, () -> {
        });

        assertThatThrownBy(() -> lauf.senden(OutputStream.nullOutputStream())).isInstanceOf(IOException.class)
                .hasMessageContaining("kürzer");

        assertThat(lauf.ende()).isEqualTo(StreamLauf.Ende.ABBRUCH);
        assertThat(plaetze.belegt()).isZero();
    }

    /** Client, der nie mehr annimmt, bis die Verbindung verworfen wird. */
    private static final class HaengenderClient extends OutputStream {
        final CountDownLatch zu = new CountDownLatch(1);
        final CountDownLatch blockiert = new CountDownLatch(1);

        @Override
        public void write(int b) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            blockiert.countDown();
            try {
                zu.await();
            } catch (InterruptedException e) {
                throw new IOException(e);
            }
            throw new IOException("Verbindung verworfen");
        }
    }

    @Test
    void leerlaufOhneBlockBrichtAbUndGibtAllesFrei() throws Exception {
        Quelle quelle = new Quelle(10L * BLOCK);
        HaengenderClient client = new HaengenderClient();
        StreamLauf lauf = lauf(quelle, "a", Duration.ofMillis(150), LANG, client.zu::countDown);

        assertThatThrownBy(() -> lauf.senden(client)).isInstanceOf(IOException.class);

        assertThat(lauf.ende()).isEqualTo(StreamLauf.Ende.LEERLAUF);
        assertThat(client.zu.getCount()).as("die Verbindung zum Client wurde verworfen").isZero();
        assertThat(quelle.abgebrochen).as("die Verbindung zum Speicher wurde verworfen").isTrue();
        assertThat(quelle.geschlossen).isTrue();
        assertThat(plaetze.belegt()).isZero();
    }

    @Test
    void gesamtZeitlimitBrichtAuchEinenLangsamenAberStetigenStreamAb() {
        Quelle quelle = new Quelle(Long.MAX_VALUE);
        AtomicBoolean verworfen = new AtomicBoolean();
        StreamLauf lauf = lauf(quelle, "a", Duration.ofSeconds(30), Duration.ofMillis(300), () -> verworfen.set(true));

        assertThatThrownBy(() -> lauf.senden(new OutputStream() {
            @Override
            public void write(int b) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                if (verworfen.get()) {
                    throw new IOException("Verbindung verworfen");
                }
                try {
                    Thread.sleep(20); // stetiger Fortschritt: der Leerlauf greift nie
                } catch (InterruptedException e) {
                    throw new IOException(e);
                }
            }
        })).isInstanceOf(IOException.class);

        assertThat(lauf.ende()).isEqualTo(StreamLauf.Ende.ZEITLIMIT);
        assertThat(quelle.abgebrochen).isTrue();
        assertThat(plaetze.belegt()).isZero();
    }

    @Test
    void langsamerAberStetigerClientWirdNichtVomLeerlaufAbgebrochen() throws IOException {
        Quelle quelle = new Quelle(12L * BLOCK);
        StreamLauf lauf = lauf(quelle, "a", Duration.ofMillis(400), LANG, () -> {
        });

        lauf.senden(new OutputStream() {
            @Override
            public void write(int b) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                try {
                    Thread.sleep(100); // insgesamt über dem Leerlauf, aber jeder Block kommt rechtzeitig
                } catch (InterruptedException e) {
                    throw new IOException(e);
                }
            }
        });

        assertThat(lauf.ende()).isEqualTo(StreamLauf.Ende.VOLLSTAENDIG);
    }

    @Test
    void einNieGestarteterStreamGibtSeinenPlatzNachDemLeerlaufFrei() throws Exception {
        Quelle quelle = new Quelle(BLOCK);
        lauf(quelle, "a", Duration.ofMillis(100), LANG, () -> {
        });
        assertThat(plaetze.belegt()).isEqualTo(1);

        long bis = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (plaetze.belegt() > 0 && System.nanoTime() < bis) {
            Thread.sleep(20);
        }

        assertThat(plaetze.belegt()).isZero();
        assertThat(quelle.abgebrochen).isTrue();
    }

    @Test
    void platzeGesamtUndJeSchluesselOhneWarten() {
        DateiPlaetze p = new DateiPlaetze(3, 2);
        var a1 = p.reservieren("a");
        var a2 = p.reservieren("a");

        assertThatThrownBy(() -> p.reservieren("a")).isInstanceOfSatisfying(ZuVieleAnfragen.class, e -> {
            assertThat(e.code()).isEqualTo(Fehlercode.ZU_VIELE_ANFRAGEN);
            assertThat(e.wartezeitSekunden()).isEqualTo(5);
        });
        var b1 = p.reservieren("b");
        assertThat(p.belegt()).isEqualTo(3);
        // gesamt voll: auch ein anderer Schlüssel bekommt keinen Platz, und sein Zähler bleibt sauber
        assertThatThrownBy(() -> p.reservieren("c")).isInstanceOf(ZuVieleAnfragen.class);
        assertThat(p.belegt("c")).isZero();

        a1.close();
        a1.close(); // zweites Schliessen gibt nichts frei
        assertThat(p.belegt()).isEqualTo(2);
        assertThat(p.belegt("a")).isEqualTo(1);
        var c1 = p.reservieren("c");
        assertThat(p.belegt()).isEqualTo(3);
        a2.close();
        b1.close();
        c1.close();
        assertThat(p.belegt()).isZero();
    }

    /**
     * Keine Transaktion während des Streams (Req 13.8): Der Ablauf läuft nie in einer Transaktion ({@code NEVER}: in
     * einer offenen Transaktion wirft der Aufruf), und der {@link StreamLauf} kennt weder Transaktion noch
     * Datenbank. Den Beweis am laufenden System (keine offene Transaktion der API, Protokoll vor dem ersten Byte)
     * führt das Szenario {@code @req-13.8}.
     */
    @Test
    void ablaufUndLaufKennenKeineTransaktion() {
        var tx = DateiAuslieferung.class.getAnnotation(jakarta.transaction.Transactional.class);
        assertThat(tx).isNotNull();
        assertThat(tx.value()).isEqualTo(jakarta.transaction.Transactional.TxType.NEVER);
        assertThat(StreamLauf.class.getAnnotation(jakarta.transaction.Transactional.class)).isNull();
        for (var feld : StreamLauf.class.getDeclaredFields()) {
            assertThat(feld.getType().getName()).as(feld.getName()).doesNotContain("transaction", "sql", "hibernate",
                    "agroal", "Repository");
        }
    }

    @Test
    void ungueltigeStartwerteBrechenAb() {
        assertThatThrownBy(() -> new DateiPlaetze(0, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DateiPlaetze(1, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void streamsLaufenGleichzeitigUndDieGrenzeHaeltUnterWettlauf() throws Exception {
        DateiPlaetze p = new DateiPlaetze(5, 5);
        var pool = Executors.newFixedThreadPool(20);
        AtomicInteger erhalten = new AtomicInteger();
        CountDownLatch los = new CountDownLatch(1);
        List<java.util.concurrent.Future<?>> alle = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            alle.add(pool.submit(() -> {
                los.await();
                try {
                    p.reservieren("a");
                    erhalten.incrementAndGet();
                } catch (ZuVieleAnfragen e) {
                    // abgewiesen
                }
                return null;
            }));
        }
        los.countDown();
        for (var f : alle) {
            f.get(10, TimeUnit.SECONDS);
        }
        pool.shutdownNow();

        assertThat(erhalten.get()).isEqualTo(5);
        assertThat(p.belegt()).isEqualTo(5);
    }

    @Test
    void vollstaendigerStreamVerwirftDieSpeicherverbindungNicht() throws IOException {
        Quelle quelle = new Quelle(2L * BLOCK);
        StreamLauf lauf = lauf(quelle, "a", LANG, LANG, () -> {
        });

        lauf.senden(OutputStream.nullOutputStream());

        assertThat(quelle.abgebrochen).isFalse();
        assertThat(quelle.geschlossen).isTrue();
    }

    @Test
    void abbrechenOhneSendenGibtPlatzUndSpeicherverbindungFrei() {
        Quelle quelle = new Quelle(BLOCK);
        StreamLauf lauf = lauf(quelle, "a", LANG, LANG, () -> {
        });

        lauf.abbrechen();

        assertThat(quelle.abgebrochen).isTrue();
        assertThat(plaetze.belegt()).isZero();
    }

    @Test
    void auchDerPruefstreamNutztDenKleinenPufferUndProtokolliertVorDemErstenByte() throws IOException {
        Quelle quelle = new Quelle(50L * 1024 * 1024);
        AtomicBoolean protokolliert = new AtomicBoolean();
        UUID id = UUID.randomUUID();
        var benutzer = new ch.urinet.archiv.sicherheit.domain.Benutzer("token:probe",
                ch.urinet.archiv.sicherheit.domain.Rolle.GESCHAEFTSSTELLE);
        var ort = new DokumentDatei.Dateiort(Ablageort.values()[0],
                new DateiSchluessel(2026, id, DateiRolle.ORIGINAL, 1, "pdf"), "v-original");
        WebseiteDatei datei = new WebseiteDatei(null, null, null) {
            @Override
            public DokumentDatei.Dateiort erfassungVorbereiten(ch.urinet.archiv.sicherheit.domain.Benutzer wer,
                                                               UUID dokument) {
                assertThat(wer).isEqualTo(benutzer);
                assertThat(dokument).isEqualTo(id);
                return ort;
            }

            @Override
            public void erfassungProtokollieren(UUID dokument, ch.urinet.archiv.sicherheit.domain.Benutzer wer,
                                                Optional<IpAdresse> ip, String bearbeiter) {
                assertThat(bearbeiter).isEqualTo("probe/wp:1");
                assertThat(quelle.gelesen).hasValue(0);
                protokolliert.set(true);
            }
        };
        AblagePort ablage = (AblagePort) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{AblagePort.class}, (proxy, methode, args) -> {
                    assertThat(methode.getName()).isEqualTo("oeffnen");
                    assertThat(args[3]).isEqualTo("v-original");
                    return quelle.alsStrom();
                });
        StreamWaechter waechter = new StreamWaechter() {
            @Override
            public ScheduledExecutorService zeitgeber() {
                return zeitgeber;
            }
        };
        DateiAuslieferung auslieferung = new DateiAuslieferung(datei, ablage, plaetze, waechter, LANG, LANG);
        var threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long vorher = threads.getThreadAllocatedBytes(Thread.currentThread().threadId());
        var strom = auslieferung.erfassung(new MandantCode("urinet"), benutzer, id, Optional.empty(),
                "probe/wp:1", () -> {});
        strom.lauf().senden(new OutputStream() {
            @Override
            public void write(int b) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void write(byte[] b, int off, int len) {
                if (!protokolliert.get() || len > BLOCK) {
                    throw new AssertionError("Protokoll vor Bytes und höchstens ein Block");
                }
            }
        });
        long speicher = threads.getThreadAllocatedBytes(Thread.currentThread().threadId()) - vorher;
        assertThat(strom.medientyp()).isEqualTo("application/pdf");
        assertThat(strom.lauf().gesendet()).isEqualTo(quelle.laenge);
        assertThat(speicher).isLessThan(1024 * 1024);
        assertThat(plaetze.belegt()).isZero();
    }

    @Test
    void speicherNichtErreichbarBeimOeffnenGibtPlatzFreiUndProtokolliertNicht() {
        AtomicInteger protokolliert = new AtomicInteger();
        var ziel = new WebseiteDatei.Stream(new DokumentDatei.Dateiort(Ablageort.values()[0],
                new DateiSchluessel(2026, UUID.randomUUID(), DateiRolle.PDFA, 0, "pdf"), "v1"));
        // ohne Mockito: Unterklasse ohne Datenbank und Proxy für den Port
        WebseiteDatei datei = new WebseiteDatei(null, null, null) {
            @Override
            public Ziel vorbereiten(MandantCode mandant, Lesezugriff zugriff, UUID id, Optional<String> benutzer,
                                    Optional<IpAdresse> ip) {
                return ziel;
            }

            @Override
            public void protokollieren(UUID id, Optional<String> benutzer, Optional<IpAdresse> ip) {
                protokolliert.incrementAndGet();
            }
        };
        AblagePort ablage = (AblagePort) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{AblagePort.class}, (proxy, methode, args) -> {
                    throw new FachFehler(Fehlercode.SPEICHER_NICHT_ERREICHBAR);
                });
        DateiAuslieferung auslieferung = new DateiAuslieferung(datei, ablage, plaetze, new StreamWaechter(),
                Duration.ofSeconds(30), LANG);

        assertThatThrownBy(() -> auslieferung.ausliefern(new MandantCode("urinet"),
                new Lesezugriff("w", Zugriffsstufe.values()[0], Zugriffsstufe.values()[0], Optional.empty()),
                UUID.randomUUID(), Optional.of("schluessel"), Optional.empty(), () -> {
                }))
                .isInstanceOfSatisfying(FachFehler.class,
                        e -> assertThat(e.code()).isEqualTo(Fehlercode.SPEICHER_NICHT_ERREICHBAR));

        assertThat(plaetze.belegt()).isZero();
        assertThat(plaetze.belegt("schluessel")).isZero();
        assertThat(protokolliert).hasValue(0);
    }
}
