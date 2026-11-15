package ch.urinet.archiv.verarbeitung;

import ch.urinet.archiv.dokument.Testdokumente;
import ch.urinet.archiv.gemeinsam.SchedulerTakt;
import ch.urinet.archiv.mandant.application.MandantAusAnmeldung;
import ch.urinet.archiv.mandant.application.MandantBucket;
import ch.urinet.archiv.mandant.application.MandantProvisionierung;
import ch.urinet.archiv.mandant.application.MandantRegister;
import ch.urinet.archiv.mandant.domain.Mandant;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.protokoll.application.ManifestErzeugen;
import ch.urinet.archiv.verarbeitung.adapter.in.job.PrueflaufZeitplan;
import ch.urinet.archiv.verarbeitung.application.AuftragArbeiter;
import ch.urinet.archiv.verarbeitung.application.PrueflaufStarten;
import io.agroal.api.AgroalDataSource;
import io.cucumber.java.After;
import io.cucumber.java.de.Angenommen;
import io.cucumber.java.de.Dann;
import io.cucumber.java.de.Wenn;
import io.quarkus.scheduler.Scheduler;
import io.restassured.response.Response;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ObjectLockMode;
import software.amazon.awssdk.services.s3.model.ObjectLockRetention;
import software.amazon.awssdk.services.s3.model.ObjectLockRetentionMode;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Schritte zum monatlichen Prüflauf (Aufgabe 5.3; Req 6.9, 15.5, 15.9, 15.10). Legt archivierte Dokumente direkt in
 * Datenbank und Object Storage an, wie sie {@code ARCHIVIEREN} hinterlässt. Bucket und Datenbank teilen sich alle
 * Szenarien; geprüft werden darum nur die eigenen Dokumente und der eigene Lauf. Nur synthetische Inhalte (Req 16.15).
 */
public class PrueflaufSchritte {

    private static final String MANDANT = "urinet";
    private static final String ERSTELLER = "test-prueflauf";
    private static final DateTimeFormatter DATUM = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final int MAIL_HOECHSTENS = 50;
    private static final ZoneId ZUERICH = ZoneId.of("Europe/Zurich");

    /** Archiviertes Testdokument: Schlüssel und Versions-ID des Originals, Sperre beim Anlegen. */
    private record Archiviert(UUID id, String titel, String schluessel, String version, Instant sperre) {
    }

    @Inject
    Instance<AgroalDataSource> datenbank;

    @Inject
    AuftragTestHilfe auftraege;

    @Inject
    AuftragArbeiter arbeiter;

    @Inject
    PrueflaufStarten starten;

    @Inject
    MandantBucket buckets;

    @Inject
    MandantProvisionierung provisionierung;

    @Inject
    S3Client s3;

    @Inject
    Scheduler scheduler;

    @Inject
    MandantAusAnmeldung anmeldung;

    @ConfigProperty(name = "archiv.ablage.bucket-praefix")
    String bucketPraefix;

    @ConfigProperty(name = "archiv.entwicklung.mailpit-port")
    Optional<Integer> mailpitPort;

    private final ExecutorService threads = Executors.newSingleThreadExecutor();
    private final Map<String, Archiviert> dokumente = new HashMap<>();
    private final Map<String, Archiviert> manifestDokumente = new HashMap<>();
    private final List<UUID> laeufe = new ArrayList<>();
    private final List<UUID> manifestLaeufe = new ArrayList<>();
    private String manifestMandant;
    private Instant laufStart;
    private Optional<UUID> zweiterStart;
    private String manifestSchluessel;
    private String manifestVersion;
    private Instant manifestSperre;

    @After
    public void aufraeumen() throws SQLException {
        Testdokumente.loeschen(datenbank.get(), MANDANT, dokumente.values().stream().map(Archiviert::id).toList());
        for (UUID lauf : laeufe) {
            auftraege.ausfuehren("delete from " + schema() + ".prueflauf_abweichung where lauf_id = '" + lauf + "'");
            auftraege.ausfuehren("delete from " + schema() + ".prueflauf where id = '" + lauf + "'");
        }
        auftraege.ausfuehren("delete from public.auftrag where typ = 'PRUEFLAUF'");
        threads.shutdownNow();
    }

    @Angenommen("der Prüflauf ist bereit")
    public void bereit() throws Exception {
        auftraege.vorbereiten();
        auftraege.ausfuehren("delete from public.auftrag where typ = 'PRUEFLAUF'");
        buckets.einrichten(bucket());
        for (UUID alt : abfragen("select id from " + schema() + ".prueflauf where monat = '2999-01'", UUID.class)) {
            laeufe.add(alt);
        }
        aufraeumenLaeufe();
    }

    @Angenommen("ein eigener Mandant «{word}» für den Manifest-Prüflauf")
    public void manifestMandant(String code) throws SQLException {
        manifestMandant = code;
        provisionierung.anlegen(manifestMandant, null, null);
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement aendern = verbindung.prepareStatement(
                     "update public.mandant set erstellt = ? where code = ?")) {
            aendern.setTimestamp(1, java.sql.Timestamp.from(zeit("09.01.2026", "08:00")));
            aendern.setString(2, manifestMandant);
            assertThat(aendern.executeUpdate()).isEqualTo(1);
        }
        buckets.einrichten(new MandantCode(manifestMandant).bucket(bucketPraefix));
    }

    @Angenommen("im Testmandanten ist das Dokument «{word}» am «{word}» um «{word}» archiviert")
    public void manifestDokument(String name, String datum, String uhrzeit) throws Exception {
        archivieren(manifestMandant, name, null, 2, manifestDokumente);
        manifestArchivierungszeitpunkt(name, datum, uhrzeit);
    }

    @Angenommen("im Testmandanten liegt das Dokument «{word}» ohne Archivierungszeitpunkt")
    public void manifestDokumentOhneArchivierungszeitpunkt(String name) throws Exception {
        archivieren(manifestMandant, name, null, 2, manifestDokumente);
    }

    @Angenommen("der Archivierungszeitpunkt des Testdokuments «{word}» wird auf «{word}» um «{word}» gesetzt")
    public void manifestArchivierungszeitpunkt(String name, String datum, String uhrzeit) throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement aendern = verbindung.prepareStatement("update " + schema(manifestMandant)
                     + ".dokument set archiviert = ? where id = ?")) {
            aendern.setTimestamp(1, java.sql.Timestamp.from(zeit(datum, uhrzeit)));
            aendern.setObject(2, manifestDokument(name).id());
            assertThat(aendern.executeUpdate()).isEqualTo(1);
        }
    }

    @Angenommen("die gespeicherte Prüfsumme des Testdokuments «{word}» wird verfälscht")
    public void manifestPruefsummeVerfaelscht(String name) throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement aendern = verbindung.prepareStatement("update " + schema(manifestMandant)
                     + ".dokument_datei set sha256 = ? where dokument_id = ?")) {
            aendern.setString(1, "0".repeat(64));
            aendern.setObject(2, manifestDokument(name).id());
            assertThat(aendern.executeUpdate()).isEqualTo(1);
        }
    }

    @Wenn("für den Testmandanten das Tages-Manifest bis «{word}» erzeugt wird")
    public void testManifestErzeugen(String bis) throws Exception {
        long auftrag = auftraege.rohEinreihen(manifestMandant, ManifestErzeugen.TYP,
                "{\"" + ManifestErzeugen.BIS_TAG + "\": " + LocalDate.parse(bis, DATUM).toEpochDay() + "}");
        auftraege.abarbeiten();
        assertThat(auftraege.zeile(auftrag).status()).isEqualTo("FERTIG");
    }

    @Wenn("der Prüflauf für den Testmandanten läuft")
    public void testPrueflaufLaeuft() throws Exception {
        UUID lauf = starten(manifestMandant, Optional.empty()).orElseThrow();
        manifestLaeufe.add(lauf);
        for (int runde = 0; runde < 500 && !"ABGESCHLOSSEN".equals(phase(manifestMandant, lauf)); runde++) {
            List<CompletableFuture<Void>> geholt = arbeiter.abholen();
            if (geholt.isEmpty()) {
                break;
            }
            CompletableFuture.allOf(geholt.toArray(CompletableFuture[]::new)).get(2, TimeUnit.MINUTES);
        }
    }

    @Dann("ist der Prüflauf im Testmandanten abgeschlossen")
    public void testPrueflaufAbgeschlossen() throws SQLException {
        assertThat(phase(manifestMandant, manifestLaeufe.getLast())).isEqualTo("ABGESCHLOSSEN");
    }

    @Dann("meldet der Prüflauf im Testmandanten für «{word}» keine Manifest-Abweichung")
    public void testKeineManifestAbweichung(String name) throws SQLException {
        assertThat(abfragen("select art from " + schema(manifestMandant) + ".prueflauf_abweichung where lauf_id = '"
                + manifestLaeufe.getLast() + "' and dokument_id = '" + manifestDokument(name).id()
                + "' and art in ('MANIFEST_EINTRAG_FEHLT', 'MANIFEST_PRUEFSUMME')", String.class)).isEmpty();
    }

    @Dann("meldet der Prüflauf im Testmandanten für «{word}» «{word}» und «{word}»")
    public void testManifestAbweichungen(String name, String erste, String zweite) throws SQLException {
        assertThat(abfragen("select art from " + schema(manifestMandant) + ".prueflauf_abweichung where lauf_id = '"
                + manifestLaeufe.getLast() + "' and dokument_id = '" + manifestDokument(name).id()
                + "' order by id", String.class)).containsExactly(erste, zweite);
    }

    @Dann("meldet der Prüflauf im Testmandanten für «{word}» nur «{word}»")
    public void testManifestAbweichung(String name, String art) throws SQLException {
        assertThat(abfragen("select art from " + schema(manifestMandant) + ".prueflauf_abweichung where lauf_id = '"
                + manifestLaeufe.getLast() + "' and dokument_id = '" + manifestDokument(name).id()
                + "' order by id", String.class)).containsExactly(art);
    }

    @Dann("hat der Inhaber eine Mail zum Test-Prüflauf erhalten, die nur IDs nennt")
    public void testPrueflaufMail() {
        UUID lauf = manifestLaeufe.getLast();
        String mailpit = "http://localhost:" + mailpitPort.orElseThrow();
        Response nachrichten = given().baseUri(mailpit)
                .queryParam("query", "subject:\"" + lauf + "\"").when().get("/api/v1/search");
        assertThat(nachrichten.statusCode()).isEqualTo(200);
        List<String> ids = nachrichten.jsonPath().getList("messages.ID");
        assertThat(ids).hasSize(1);
        Response nachricht = given().baseUri(mailpit).when().get("/api/v1/message/" + ids.getFirst());
        String text = nachricht.jsonPath().getString("Text");
        assertThat(text).contains(manifestDokument("A").id().toString())
                .doesNotContainPattern("[0-9a-f]{64}");
    }

    @Angenommen("im Mandanten «urinet» ist das unbefristete Dokument «{word}» archiviert, gesperrt für {int} Stunden")
    public void unbefristet(String name, int stunden) throws Exception {
        archivieren(name, null, stunden);
    }

    @Angenommen("im Mandanten «urinet» ist das Dokument «{word}» bis {word} aufzubewahren und archiviert, "
            + "gesperrt für {int} Stunden")
    public void befristet(String name, String bis, int stunden) throws Exception {
        archivieren(name, LocalDate.parse(bis, DATUM), stunden);
    }

    @Angenommen("die gespeicherte Prüfsumme von «{word}» ist verfälscht")
    public void verfaelscht(String name) throws SQLException {
        auftraege.ausfuehren("update " + schema() + ".dokument_datei set sha256 = '" + "0".repeat(64)
                + "' where dokument_id = '" + dokument(name).id() + "'");
    }

    @Angenommen("unter dem Schlüssel des Originals von «{word}» liegt eine zweite Version")
    public void zweiteVersion(String name) {
        Archiviert d = dokument(name);
        s3.putObject(b -> b.bucket(bucket()).key(d.schluessel()).objectLockMode(ObjectLockMode.GOVERNANCE)
                        .objectLockRetainUntilDate(d.sperre()),
                RequestBody.fromBytes(VerarbeitungTestHilfe.zufall(64)));
    }

    @Angenommen("das Original von «{word}» hat eine Löschmarke")
    public void loeschmarke(String name) {
        s3.deleteObject(b -> b.bucket(bucket()).key(dokument(name).schluessel()));
    }

    @Wenn("der Prüflauf für «urinet» läuft")
    public void laeuft() throws Exception {
        laufStart = Instant.now();
        UUID lauf = starten(Optional.empty()).orElseThrow();
        laeufe.add(lauf);
        for (int runde = 0; runde < 500 && !"ABGESCHLOSSEN".equals(phase(lauf)); runde++) {
            List<CompletableFuture<Void>> geholt = arbeiter.abholen();
            if (geholt.isEmpty()) {
                break;
            }
            CompletableFuture.allOf(geholt.toArray(CompletableFuture[]::new)).get(2, TimeUnit.MINUTES);
        }
    }

    @Wenn("der Prüflauf für «urinet» und den Monat «{word}» zweimal gestartet wird")
    public void zweimal(String monat) throws Exception {
        UUID erster = starten(Optional.of(YearMonth.parse(monat))).orElseThrow();
        laeufe.add(erster);
        zweiterStart = starten(Optional.of(YearMonth.parse(monat)));
        zweiterStart.ifPresent(laeufe::add);
    }

    /**
     * Wie im Betrieb über einen Job des Quarkus-Schedulers mit Request-Kontext und ohne Anmeldung ({@link SchedulerTakt};
     * Review PR #130); der Zeitplan in der Konfiguration ist im Profil {@code test} aus.
     */
    @Wenn("der Takt des Zeitplans am {word} um 04:00 auslöst")
    public void takt(String datum) throws Exception {
        Clock uhr = Clock.fixed(LocalDate.parse(datum, DATUM).atTime(4, 0).atZone(ZUERICH).toInstant(), ZUERICH);
        MandantRegister nurUrinet = new MandantRegister() {
            @Override
            public boolean existiert(MandantCode code) {
                return MANDANT.equals(code.wert());
            }

            @Override
            public void eintragen(Mandant mandant) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<Mandant> aktive() {
                MandantCode code = new MandantCode(MANDANT);
                return List.of(new Mandant(code, MANDANT, code.schema(), bucket(), null, true));
            }

            @Override
            public Optional<Instant> erstellt(MandantCode code) {
                throw new UnsupportedOperationException();
            }
        };
        PrueflaufZeitplan zeitplan = new PrueflaufZeitplan(nurUrinet, starten, "api,worker", uhr);
        List<UUID> vorher = laeufeIm("2999-01");
        SchedulerTakt.ausloesen(scheduler, anmeldung, "prueflauf", zeitplan::starten);
        List<UUID> neu = laeufeIm("2999-01").stream().filter(id -> !vorher.contains(id)).toList();
        if (laeufe.isEmpty()) {
            assertThat(neu).as("der erste Takt holt den Lauf des Monats nach").hasSize(1);
        } else {
            zweiterStart = neu.stream().findFirst();
        }
        laeufe.addAll(neu);
    }

    @Angenommen("im Mandanten «urinet» liegt ein Manifest im Modus COMPLIANCE, gesperrt für {int} Stunden")
    public void manifest(int stunden) {
        manifestSchluessel = "manifest/prueflauf-probe/" + UUID.randomUUID() + ".json";
        manifestSperre = Instant.now().plus(Duration.ofHours(stunden)).truncatedTo(ChronoUnit.SECONDS);
        manifestVersion = s3.putObject(b -> b.bucket(bucket()).key(manifestSchluessel)
                        .objectLockMode(ObjectLockMode.COMPLIANCE).objectLockRetainUntilDate(manifestSperre),
                RequestBody.fromBytes(VerarbeitungTestHilfe.zufall(64))).versionId();
    }

    @Angenommen("das Manifest hat eine Löschmarke")
    public void manifestLoeschmarke() {
        // ohne Versions-ID: legt eine Löschmarke an, die gesperrte Version bleibt
        s3.deleteObject(b -> b.bucket(bucket()).key(manifestSchluessel));
    }

    @Dann("meldet der Prüflauf für das Manifest nur eine «LOESCHMARKE» ohne Dokument")
    public void manifestLoeschmarkeGemeldet() throws SQLException {
        assertThat(abfragen("select art || ':' || coalesce(dokument_id::text, '-') from " + schema()
                + ".prueflauf_abweichung where lauf_id = '" + laeufe.getLast() + "' and s3_schluessel = '"
                + manifestSchluessel + "'", String.class)).containsExactly("LOESCHMARKE:-");
    }

    @Dann("meldet der Prüflauf für das Manifest nichts")
    public void manifestOhneMeldung() throws SQLException {
        assertThat(abfragen("select art from " + schema() + ".prueflauf_abweichung where lauf_id = '"
                + laeufe.getLast() + "' and s3_schluessel = '" + manifestSchluessel + "'", String.class)).isEmpty();
    }

    @Dann("ist die Sperre des Manifests im Modus COMPLIANCE verlängert, höchstens bis jetzt plus {int} Tag")
    public void manifestVerlaengert(int tage) {
        ObjectLockRetention sperre = s3.getObjectRetention(b -> b.bucket(bucket()).key(manifestSchluessel)
                .versionId(manifestVersion)).retention();
        assertThat(sperre.mode()).isEqualTo(ObjectLockRetentionMode.COMPLIANCE);
        assertThat(sperre.retainUntilDate()).isAfter(manifestSperre)
                .isBeforeOrEqualTo(Instant.now().plus(Duration.ofDays(tage)).plusSeconds(1))
                .isAfterOrEqualTo(laufStart.plus(Duration.ofDays(tage)).minusSeconds(1));
    }

    @Dann("ist der Prüflauf abgeschlossen")
    public void abgeschlossen() throws SQLException {
        UUID lauf = laeufe.getLast();
        assertThat(phase(lauf)).isEqualTo("ABGESCHLOSSEN");
        assertThat(abfragen("select beendet is not null from " + schema() + ".prueflauf where id = '" + lauf + "'",
                Boolean.class)).containsExactly(true);
        assertThat(abfragen("select count(*) from public.auftrag where typ = 'PRUEFLAUF' "
                + "and nutzlast ->> 'laufId' = '" + lauf + "' and status <> 'FERTIG'", Long.class))
                .containsExactly(0L);
    }

    @Dann("meldet der Prüflauf für «{word}» nur «{word}»")
    public void meldet(String name, String art) throws SQLException {
        assertThat(arten(name)).containsExactly(art);
    }

    @Dann("meldet der Prüflauf für «{word}» nichts")
    public void meldetNichts(String name) throws SQLException {
        assertThat(arten(name)).isEmpty();
    }

    @Dann("hat der Inhaber eine Mail zum Prüflauf erhalten, die nur IDs nennt")
    public void mail() throws SQLException {
        UUID lauf = laeufe.getLast();
        String mailpit = "http://localhost:" + mailpitPort.orElseThrow();
        Response nachrichten = given().baseUri(mailpit)
                .queryParam("query", "subject:\"" + lauf + "\"").when().get("/api/v1/search");
        assertThat(nachrichten.statusCode()).isEqualTo(200);
        List<String> ids = nachrichten.jsonPath().getList("messages.ID");
        assertThat(ids).as(nachrichten.asString()).hasSize(1);
        Response nachricht = given().baseUri(mailpit).when().get("/api/v1/message/" + ids.getFirst());
        assertThat(nachricht.jsonPath().<String>getList("To.Address")).containsExactly("inhaber@example.invalid");
        String text = nachricht.jsonPath().getString("Text");
        // die Mail nennt die ersten Abweichungen in der Reihenfolge der Tabelle, je mit Schlüssel und Version
        List<String> erste = abfragen("select s3_schluessel from " + schema() + ".prueflauf_abweichung where lauf_id = '"
                + lauf + "' order by id limit " + MAIL_HOECHSTENS, String.class);
        assertThat(erste).isNotEmpty();
        erste.forEach(schluessel -> assertThat(text).contains(schluessel));
        dokumente.values().forEach(d -> assertThat(text).doesNotContain(d.titel()));
    }

    @Dann("ist die Sperre des Originals von «{word}» verlängert, höchstens bis jetzt plus {int} Tag")
    public void verlaengert(String name, int tage) {
        Archiviert d = dokument(name);
        Instant sperre = sperre(d);
        assertThat(sperre).isAfter(d.sperre())
                .isBeforeOrEqualTo(Instant.now().plus(Duration.ofDays(tage)).plusSeconds(1))
                .isAfterOrEqualTo(laufStart.plus(Duration.ofDays(tage)).minusSeconds(1));
    }

    @Dann("ist die Sperre des Originals von «{word}» unverändert")
    public void unveraendert(String name) {
        Archiviert d = dokument(name);
        assertThat(sperre(d)).isEqualTo(d.sperre());
    }

    @Dann("hat nur der erste Start einen Lauf angelegt")
    public void nurEinLauf() throws SQLException {
        assertThat(zweiterStart).isEmpty();
        assertThat(abfragen("select count(*) from " + schema() + ".prueflauf where monat = '2999-01'", Long.class))
                .containsExactly(1L);
        assertThat(abfragen("select count(*) from public.auftrag where typ = 'PRUEFLAUF' and nutzlast ->> 'laufId' = '"
                + laeufe.getFirst() + "'", Long.class)).containsExactly(1L);
    }

    private void archivieren(String name, LocalDate aufbewahrenBis, int stunden) throws Exception {
        archivieren(MANDANT, name, aufbewahrenBis, stunden, dokumente);
    }

    private void archivieren(String mandant, String name, LocalDate aufbewahrenBis, int stunden,
                             Map<String, Archiviert> ziel) throws Exception {
        UUID id = UUID.randomUUID();
        String titel = "Prüflauf-Probe " + UUID.randomUUID();
        String schluessel = "archiv/" + VerarbeitungTestHilfe.JAHR + "/" + id + "/1-original.pdf";
        byte[] inhalt = VerarbeitungTestHilfe.zufall(256);
        Instant sperre = Instant.now().plus(Duration.ofHours(stunden)).truncatedTo(ChronoUnit.SECONDS);
        MandantCode code = new MandantCode(mandant);
        String version = s3.putObject(b -> b.bucket(code.bucket(bucketPraefix)).key(schluessel)
                        .objectLockMode(ObjectLockMode.GOVERNANCE)
                .objectLockRetainUntilDate(sperre), RequestBody.fromBytes(inhalt)).versionId();
        try (Connection verbindung = datenbank.get().getConnection()) {
            try (PreparedStatement dokument = verbindung.prepareStatement("insert into " + code.schema()
                    + ".dokument (id, eingang, quelle, erstellt_von, status, art, titel, aufbewahren_bis) "
                    + "values (?, ?, 'UPLOAD', ?, 'ARCHIVIERT', 'QUITTUNG', ?, ?)")) {
                dokument.setObject(1, id);
                dokument.setTimestamp(2, java.sql.Timestamp.from(VerarbeitungTestHilfe.EINGANG));
                dokument.setString(3, ERSTELLER);
                dokument.setString(4, titel);
                dokument.setDate(5, aufbewahrenBis == null ? null : Date.valueOf(aufbewahrenBis));
                dokument.executeUpdate();
            }
            try (PreparedStatement datei = verbindung.prepareStatement("insert into " + code.schema()
                    + ".dokument_datei (dokument_id, nr, rolle, sha256, s3_schluessel, s3_version, mime, groesse) "
                    + "values (?, 1, 'ORIGINAL', ?, ?, ?, 'application/pdf', ?)")) {
                datei.setObject(1, id);
                datei.setString(2, VerarbeitungTestHilfe.sha256(inhalt));
                datei.setString(3, schluessel);
                datei.setString(4, version);
                datei.setLong(5, inhalt.length);
                datei.executeUpdate();
            }
        }
        ziel.put(name, new Archiviert(id, titel, schluessel, version, sperre));
    }

    /** Startet wie der Zeitplan in einem eigenen Thread ohne Request-Kontext und ohne Transaktion. */
    private Optional<UUID> starten(Optional<YearMonth> monat) throws Exception {
        return starten(MANDANT, monat);
    }

    private Optional<UUID> starten(String mandant, Optional<YearMonth> monat) throws Exception {
        return threads.submit(() -> starten.starten(new MandantCode(mandant), monat)).get(30, TimeUnit.SECONDS);
    }

    private List<String> arten(String name) throws SQLException {
        return abfragen("select art from " + schema() + ".prueflauf_abweichung where lauf_id = '" + laeufe.getLast()
                + "' and dokument_id = '" + dokument(name).id() + "' order by art", String.class);
    }

    private String phase(UUID lauf) throws SQLException {
        return abfragen("select phase from " + schema() + ".prueflauf where id = '" + lauf + "'", String.class)
                .getFirst();
    }

    private String phase(String mandant, UUID lauf) throws SQLException {
        return abfragen("select phase from " + schema(mandant) + ".prueflauf where id = '" + lauf + "'", String.class)
                .getFirst();
    }

    private Instant sperre(Archiviert d) {
        return s3.getObjectRetention(b -> b.bucket(bucket()).key(d.schluessel()).versionId(d.version()))
                .retention().retainUntilDate();
    }

    private List<UUID> laeufeIm(String monat) throws SQLException {
        return abfragen("select id from " + schema() + ".prueflauf where monat = '" + monat + "'", UUID.class);
    }

    private void aufraeumenLaeufe() throws SQLException {
        for (UUID lauf : laeufe) {
            auftraege.ausfuehren("delete from " + schema() + ".prueflauf_abweichung where lauf_id = '" + lauf + "'");
            auftraege.ausfuehren("delete from " + schema() + ".prueflauf where id = '" + lauf + "'");
        }
        laeufe.clear();
    }

    private <T> List<T> abfragen(String sql, Class<T> typ) throws SQLException {
        List<T> werte = new ArrayList<>();
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement(sql); ResultSet z = abfrage.executeQuery()) {
            while (z.next()) {
                werte.add(z.getObject(1, typ));
            }
        }
        return werte;
    }

    private Archiviert dokument(String name) {
        return dokumente.get(name);
    }

    private Archiviert manifestDokument(String name) {
        return manifestDokumente.get(name);
    }

    private String bucket() {
        return new MandantCode(MANDANT).bucket(bucketPraefix);
    }

    private static String schema() {
        return new MandantCode(MANDANT).schema();
    }

    private static String schema(String mandant) {
        return new MandantCode(mandant).schema();
    }

    private static Instant zeit(String datum, String uhrzeit) {
        return LocalDate.parse(datum, DATUM).atTime(LocalTime.parse(uhrzeit)).atZone(ZUERICH).toInstant();
    }
}
