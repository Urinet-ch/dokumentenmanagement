package ch.urinet.archiv.verarbeitung;

import ch.urinet.archiv.ablage.application.AblagePort;
import ch.urinet.archiv.ablage.domain.Ablageort;
import ch.urinet.archiv.ablage.domain.DateiRolle;
import ch.urinet.archiv.ablage.domain.DateiSchluessel;
import ch.urinet.archiv.mandant.application.MandantKontext;
import ch.urinet.archiv.verarbeitung.VerarbeitungTestHilfe.Quelle;
import ch.urinet.archiv.verarbeitung.adapter.out.ocr.OcrTestdateien;
import ch.urinet.archiv.verarbeitung.application.AuftragArbeiter;
import ch.urinet.archiv.verarbeitung.application.AuftragHandler;
import ch.urinet.archiv.verarbeitung.application.AuftragHandlerVerzeichnis;
import ch.urinet.archiv.verarbeitung.application.AuftragSchlange;
import ch.urinet.archiv.verarbeitung.application.OcrPort;
import ch.urinet.archiv.verarbeitung.application.VerarbeitenHandler;
import ch.urinet.archiv.verarbeitung.application.VerarbeitungSpeichern;
import ch.urinet.archiv.verarbeitung.domain.AuftragTyp;
import ch.urinet.archiv.verarbeitung.domain.OcrEingabe;
import ch.urinet.archiv.verarbeitung.domain.OcrErgebnis;
import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** Fehlerpfade von VERARBEITEN mit synthetischen Dokumenten (Req 5.4, 5.6 und 5.7). */
@QuarkusTest
class VerarbeitungResilienzTest {

    private static final String MANDANT = "urinet";
    private final List<AuftragArbeiter> arbeiter = new ArrayList<>();

    @Inject VerarbeitungTestHilfe hilfe;
    @Inject AuftragTestHilfe auftraege;
    @Inject AuftragSchlange schlange;
    @Inject MandantKontext kontext;
    @Inject Instance<AuftragHandler> handler;
    @Inject Config config;
    @Inject VerarbeitungSpeichern speichern;
    @Inject AblagePort ablage;
    @Inject OcrPort ocr;
    @Inject Instance<AgroalDataSource> datenquelle;
    @ConfigProperty(name = "archiv.ocr.arbeitsverzeichnis") Path arbeitsverzeichnis;
    @TempDir Path temp;

    @BeforeEach
    void vorbereiten() throws Exception {
        hilfe.vorbereiten();
    }

    @AfterEach
    void aufraeumen() {
        arbeiter.forEach(AuftragArbeiter::herunterfahren);
    }

    @Test
    void unterbrechungMittenImLaufErhaeltOriginalUndErlaubtWiederholung() throws Exception {
        UUID id = dokument();
        List<String> original = original(id);
        long auftrag = hilfe.einreihen(MANDANT, id);
        Halt halt = new Halt();
        AuftragArbeiter worker = worker(ablage, halt.ocr());
        CompletableFuture<Void> lauf = starten(worker);
        assertThat(halt.erreicht.await(30, TimeUnit.SECONDS)).isTrue();
        halt.thread.get().interrupt();
        lauf.get(30, TimeUnit.SECONDS);

        assertUnvollstaendig(id, original);
        assertThat(auftraege.zeile(auftrag).status()).isEqualTo("OFFEN");
        assertThat(auftraege.zeile(auftrag).versuche()).isEqualTo(1);
        auftraege.aendern(auftrag, "nicht_vor = now()");
        starten(worker(ablage, ocr)).get(5, TimeUnit.MINUTES);
        assertFertig(id, auftrag, original);
    }

    @Test
    void herunterfahrenUndNeuerWorkerSetzenDenAuftragFort() throws Exception {
        UUID id = dokument();
        List<String> original = original(id);
        long auftrag = hilfe.einreihen(MANDANT, id);
        Halt halt = new Halt();
        AuftragArbeiter alterWorker = worker(ablage, halt.ocr());
        CompletableFuture<Void> lauf = starten(alterWorker);
        assertThat(halt.erreicht.await(30, TimeUnit.SECONDS)).isTrue();
        alterWorker.herunterfahren();
        lauf.get(30, TimeUnit.SECONDS);

        assertUnvollstaendig(id, original);
        assertThat(auftraege.zeile(auftrag).status()).isEqualTo("OFFEN");
        assertThat(auftraege.zeile(auftrag).versuche()).isZero();
        starten(worker(ablage, ocr)).get(5, TimeUnit.MINUTES);
        assertFertig(id, auftrag, original);
    }

    @Test
    void doppelteZustellungVeraendertDasErgebnisNicht() throws Exception {
        UUID id = dokument();
        List<String> original = original(id);
        long erster = hilfe.einreihen(MANDANT, id);
        AuftragArbeiter worker = worker(ablage, ocr);
        starten(worker).get(5, TimeUnit.MINUTES);
        assertFertig(id, erster, original);
        Map<String, Object> stand = hilfe.dokument(MANDANT, id);
        List<String> pdfa = hilfe.versionen(MANDANT, VerarbeitungTestHilfe.pdfaSchluessel(id));
        List<String> vorschau = hilfe.versionen(MANDANT, VerarbeitungTestHilfe.vorschauOrdner(id));
        List<?> folgeauftraege = hilfe.auftraege("KI_VORSCHLAG", id);

        long zweiter = hilfe.einreihen(MANDANT, id);
        starten(worker).get(5, TimeUnit.MINUTES);
        assertThat(auftraege.zeile(zweiter).status()).isEqualTo("FERTIG");
        assertThat(hilfe.dokument(MANDANT, id)).isEqualTo(stand);
        assertThat(hilfe.versionen(MANDANT, VerarbeitungTestHilfe.pdfaSchluessel(id))).isEqualTo(pdfa);
        assertThat(hilfe.versionen(MANDANT, VerarbeitungTestHilfe.vorschauOrdner(id))).isEqualTo(vorschau);
        assertThat(hilfe.auftraege("KI_VORSCHLAG", id)).isEqualTo(folgeauftraege);
    }

    @Test
    void vollerDatentraegerBeimSchreibenErhaeltOriginalUndErlaubtWiederholung() throws Exception {
        UUID id = dokument();
        List<String> original = original(id);
        long auftrag = hilfe.einreihen(MANDANT, id);
        AblagePort fehlerhafteAblage = AblagePort.class.cast(Proxy.newProxyInstance(
                AblagePort.class.getClassLoader(), new Class<?>[]{AblagePort.class}, (proxy, methode, argumente) -> {
                    if (methode.getName().equals("lesenEingang")) {
                        throw new UncheckedIOException(new FileSystemException("synthetische-quelle", null,
                                "No space left on device"));
                    }
                    try {
                        return methode.invoke(ablage, argumente);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                }));
        starten(worker(fehlerhafteAblage, ocr)).get(30, TimeUnit.SECONDS);
        assertUnvollstaendig(id, original);
        assertThat(auftraege.zeile(auftrag).status()).isEqualTo("OFFEN");
        assertThat(auftraege.zeile(auftrag).versuche()).isEqualTo(1);

        auftraege.aendern(auftrag, "nicht_vor = now()");
        starten(worker(ablage, ocr)).get(5, TimeUnit.MINUTES);
        assertFertig(id, auftrag, original);
    }

    @Test
    void datenbankverbindungBeimSpeichernAbgebrochenRolltZurueckUndErlaubtWiederholung() throws Exception {
        UUID id = dokument();
        List<String> original = original(id);
        long auftrag = hilfe.einreihen(MANDANT, id);
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String funktion = "resilienz_" + suffix;
        int schluessel1 = 1_000_000 + (id.hashCode() & 0x3fffffff);
        int schluessel2 = 1_000_000 + (suffix.hashCode() & 0x3fffffff);
        OcrPort schnell = schnellesOcr();
        try (Connection sperrVerbindung = datenquelle.get().getConnection();
             Connection steuerung = datenquelle.get().getConnection();
             Statement sql = steuerung.createStatement()) {
            sperrVerbindung.setAutoCommit(false);
            try (PreparedStatement sperren = sperrVerbindung.prepareStatement("select pg_advisory_xact_lock(?, ?)")) {
                sperren.setInt(1, schluessel1);
                sperren.setInt(2, schluessel2);
                sperren.execute();
            }
            sql.execute("create function public." + funktion + "() returns trigger language plpgsql as $$ "
                    + "begin if new.id = '" + id + "'::uuid and new.s3_pdfa is not null then "
                    + "perform pg_advisory_xact_lock(" + schluessel1 + ", " + schluessel2 + "); "
                    + "end if; return new; end $$");
            try {
                sql.execute("create trigger " + funktion + " after update of s3_pdfa on m_urinet.dokument "
                        + "for each row execute function public." + funktion + "()");
                try {
                    CompletableFuture<Void> lauf = starten(worker(ablage, schnell));
                    int pid = wartendeSitzung(steuerung, schluessel1, schluessel2, lauf);
                    try (PreparedStatement beenden = steuerung.prepareStatement("select pg_terminate_backend(?)")) {
                        beenden.setInt(1, pid);
                        try (ResultSet ergebnis = beenden.executeQuery()) {
                            assertThat(ergebnis.next() && ergebnis.getBoolean(1)).isTrue();
                        }
                    }
                    lauf.get(30, TimeUnit.SECONDS);
                } finally {
                    sperrVerbindung.rollback();
                    sql.execute("drop trigger " + funktion + " on m_urinet.dokument");
                }
            } finally {
                sql.execute("drop function public." + funktion + "()");
            }
        }
        assertThat(original(id)).isEqualTo(original);
        assertThat(hilfe.dokument(MANDANT, id)).containsEntry("status", "VERARBEITUNG")
                .containsEntry("s3_pdfa", null).containsEntry("seiten", null).containsEntry("volltext", null);
        assertThat(hilfe.auftraege("KI_VORSCHLAG", id)).isEmpty();
        assertThat(hilfe.versionen(MANDANT, VerarbeitungTestHilfe.pdfaSchluessel(id))).hasSize(1);
        assertThat(auftraege.zeile(auftrag).status()).isEqualTo("OFFEN");
        assertThat(auftraege.zeile(auftrag).versuche()).isEqualTo(1);
        auftraege.aendern(auftrag, "nicht_vor = now()");
        starten(worker(ablage, schnell)).get(30, TimeUnit.SECONDS);
        assertFertig(id, auftrag, original);
    }

    private int wartendeSitzung(Connection verbindung, int schluessel1, int schluessel2,
                                CompletableFuture<Void> lauf) throws Exception {
        long frist = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        try (PreparedStatement abfrage = verbindung.prepareStatement(
                "select pid from pg_locks where locktype = 'advisory' and classid = ?::oid "
                        + "and objid = ?::oid and objsubid = 2 and not granted")) {
            abfrage.setInt(1, schluessel1);
            abfrage.setInt(2, schluessel2);
            while (System.nanoTime() < frist && !lauf.isDone()) {
                try (ResultSet zeile = abfrage.executeQuery()) {
                    if (zeile.next()) {
                        return zeile.getInt(1);
                    }
                }
                Thread.yield();
            }
        }
        throw new AssertionError("Speichern erreichte die Datenbanksperre nicht");
    }

    private OcrPort schnellesOcr() throws IOException {
        Path pdf = temp.resolve("synthetisch.pdf");
        Files.write(pdf, OcrTestdateien.textPdf("Synthetische PDF/A-Attrappe"));
        return new OcrPort() {
            @Override
            public <T> T verarbeiten(OcrEingabe eingabe, Verwertung<T> verwertung) throws InterruptedException {
                try {
                    return verwertung.verwerten(new OcrErgebnis(Optional.of(pdf), Optional.empty(), "synthetisch",
                            List.of(), false, false));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }

            @Override
            public String layoutText(Path datei, Duration zeitgrenze) throws InterruptedException {
                return ocr.layoutText(datei, zeitgrenze);
            }

            @Override
            public <T> T seitenbilder(Path datei, Render render, Seitenverwertung<T> verwertung)
                    throws InterruptedException {
                return ocr.seitenbilder(datei, render, verwertung);
            }
        };
    }

    private UUID dokument() throws Exception {
        return hilfe.anlegen(MANDANT, "SCAN", List.of(new Quelle(DateiRolle.ORIGINAL, 1, "pdf",
                "application/pdf", OcrTestdateien.textPdf("Synthetischer Resilienztest"))));
    }

    private List<String> original(UUID id) {
        String schluessel = DateiSchluessel.original(VerarbeitungTestHilfe.JAHR, id, 1, "pdf")
                .pfad(Ablageort.EINGANG);
        return hilfe.versionen(MANDANT, schluessel);
    }

    private void assertUnvollstaendig(UUID id, List<String> original) throws Exception {
        assertThat(original).hasSize(1);
        assertThat(original(id)).isEqualTo(original);
        assertThat(hilfe.dokument(MANDANT, id)).containsEntry("status", "VERARBEITUNG")
                .containsEntry("s3_pdfa", null).containsEntry("seiten", null).containsEntry("volltext", null);
        assertThat(hilfe.versionen(MANDANT, VerarbeitungTestHilfe.pdfaSchluessel(id))).isEmpty();
        assertThat(hilfe.auftraege("KI_VORSCHLAG", id)).isEmpty();
    }

    private void assertFertig(UUID id, long auftrag, List<String> original) throws Exception {
        assertThat(auftraege.zeile(auftrag).status()).isEqualTo("FERTIG");
        assertThat(original(id)).isEqualTo(original);
        assertThat(hilfe.dokument(MANDANT, id).get("s3_version_pdfa")).isNotNull();
        assertThat(hilfe.versionen(MANDANT, VerarbeitungTestHilfe.pdfaSchluessel(id))).hasSize(1);
    }

    private AuftragArbeiter worker(AblagePort testAblage, OcrPort testOcr) {
        var verarbeiten = new VerarbeitenHandler(speichern, testAblage, testOcr, arbeitsverzeichnis);
        var verzeichnis = new AuftragHandlerVerzeichnis(handler, config) {
            @Override
            public Set<AuftragTyp> typen() {
                return Set.of(VerarbeitenHandler.TYP);
            }

            @Override
            public AuftragHandler handler(AuftragTyp typ) {
                return verarbeiten;
            }
        };
        var worker = new AuftragArbeiter(schlange, verzeichnis, kontext, 1, Duration.ofSeconds(1),
                Duration.ofSeconds(5), "A");
        arbeiter.add(worker);
        return worker;
    }

    private CompletableFuture<Void> starten(AuftragArbeiter worker) {
        List<CompletableFuture<Void>> laeufe = worker.abholen();
        assertThat(laeufe).hasSize(1);
        return laeufe.getFirst();
    }

    private class Halt {
        private final CountDownLatch erreicht = new CountDownLatch(1);
        private final AtomicReference<Thread> thread = new AtomicReference<>();

        OcrPort ocr() {
            return new OcrPort() {
                @Override
                public <T> T verarbeiten(OcrEingabe eingabe, Verwertung<T> verwertung) throws InterruptedException {
                    thread.set(Thread.currentThread());
                    erreicht.countDown();
                    new CountDownLatch(1).await();
                    throw new AssertionError("Unterbrechung erwartet");
                }

                @Override
                public String layoutText(Path pdf, Duration zeitgrenze) throws InterruptedException {
                    return ocr.layoutText(pdf, zeitgrenze);
                }

                @Override
                public <T> T seitenbilder(Path pdf, Render render, Seitenverwertung<T> verwertung)
                        throws InterruptedException {
                    return ocr.seitenbilder(pdf, render, verwertung);
                }
            };
        }
    }
}
