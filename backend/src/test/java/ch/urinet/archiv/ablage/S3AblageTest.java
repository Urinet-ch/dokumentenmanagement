package ch.urinet.archiv.ablage;

import ch.urinet.archiv.ablage.adapter.out.AblageEinstellungen;
import ch.urinet.archiv.ablage.adapter.out.s3.BypassVerbot;
import ch.urinet.archiv.ablage.adapter.out.s3.S3Ablage;
import ch.urinet.archiv.ablage.application.AblageService;
import ch.urinet.archiv.ablage.domain.AbgelegteDatei;
import ch.urinet.archiv.ablage.domain.Ablageort;
import ch.urinet.archiv.ablage.domain.ArchivierungAbgebrochen;
import ch.urinet.archiv.ablage.domain.ArchiviertesDokument;
import ch.urinet.archiv.ablage.domain.DateiSchluessel;
import ch.urinet.archiv.ablage.domain.ManifestTeil;
import ch.urinet.archiv.ablage.domain.Objektversion;
import ch.urinet.archiv.ablage.domain.Pruefsumme;
import ch.urinet.archiv.ablage.domain.SperrAenderung;
import ch.urinet.archiv.ablage.domain.Sperrmodus;
import ch.urinet.archiv.ablage.domain.Sperrvorgabe;
import ch.urinet.archiv.ablage.domain.Versionsseite;
import ch.urinet.archiv.ablage.domain.ZuArchivieren;
import ch.urinet.archiv.mandant.domain.MandantCode;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.SdkResponse;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsRequest;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsResponse;
import software.amazon.awssdk.services.s3.model.ObjectLockRetention;
import software.amazon.awssdk.services.s3.model.ObjectLockRetentionMode;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * S3-Adapter der Ablage gegen MinIO (Compose Dev Services) mit dem S3-Client der Anwendung, an dem
 * {@link KeinBypassInterceptor} hängt (Req 6.2–6.5, 6.9, 6.11, 6.12, 14.6). Jeder Test hat einen eigenen Bucket mit
 * Object Lock und Versionierung, ohne Bucket Policy.
 */
@QuarkusTest
class S3AblageTest {

    private static final LocalDate IN_ZEHN_JAHREN = LocalDate.now().plusYears(10);
    /** Eigenes Verzeichnis je Testklasse, damit «leer» nur von diesem Test abhängt. */
    private static final Path ZWISCHENABLAGE = Path.of("target", "arbeit-s3ablage", "ablage").toAbsolutePath();

    @Inject
    S3Client s3;

    private S3Client verwaltung;
    private MandantCode mandant;
    private String bucket;

    @BeforeEach
    void bucketAnlegen() {
        verwaltung = TestBucket.verwaltung();
        mandant = TestBucket.neu(verwaltung, true);
        bucket = TestBucket.bucket(mandant);
    }

    @AfterEach
    void aufraeumen() {
        TestBucket.aufraeumen(verwaltung, mandant);
        verwaltung.close();
    }

    @AfterAll
    static void keinBypassGesendet() {
        assertThat(KeinBypassInterceptor.gesendetAusserSelbsttest()).as("Aufrufe mit Bypass-Header").isEmpty();
    }

    private S3Ablage ablage(Optional<Duration> archivHoechstfrist, Sperrmodus manifestModus,
                            Optional<Duration> manifestHoechstfrist) {
        var einstellungen = new AblageEinstellungen(TestBucket.PRAEFIX, archivHoechstfrist, manifestModus,
                manifestHoechstfrist, Duration.ofMinutes(5), ZWISCHENABLAGE);
        return new S3Ablage(s3, S3Ablage.presigner(s3, true), einstellungen, Clock.systemUTC());
    }

    private S3Ablage ablage() {
        return ablage(Optional.of(Duration.ofDays(1)), Sperrmodus.GOVERNANCE, Optional.of(Duration.ofDays(1)));
    }

    private static byte[] inhalt(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private ZuArchivieren eingang(S3Ablage ablage, DateiSchluessel schluessel, byte[] daten) {
        AbgelegteDatei datei = ablage.ablegenEingang(mandant, schluessel, new ByteArrayInputStream(daten), daten.length);
        return new ZuArchivieren(schluessel, datei.versionId(), datei.sha256());
    }

    private Optional<ObjectLockRetention> retention(String key, String versionId) {
        try {
            return Optional.ofNullable(verwaltung.getObjectRetention(b -> b.bucket(bucket).key(key).versionId(versionId))
                    .retention());
        } catch (S3Exception e) {
            return Optional.empty();
        }
    }

    @Test
    void eingangOhneSperreMitVersionUndPruefsumme() {
        var schluessel = DateiSchluessel.original(2026, UUID.randomUUID(), 1, "pdf");
        byte[] daten = inhalt("Beleg");

        AbgelegteDatei datei = ablage().ablegenEingang(mandant, schluessel, new ByteArrayInputStream(daten), daten.length);

        assertThat(datei.schluessel()).isEqualTo(schluessel.pfad(Ablageort.EINGANG)).startsWith("eingang/2026/");
        assertThat(datei.versionId()).isNotBlank();
        assertThat(datei.sha256()).isEqualTo(Pruefsumme.von(daten));
        assertThat(datei.sperreBis()).isEmpty();
        assertThat(retention(datei.schluessel(), datei.versionId())).isEmpty();
    }

    /** Die Zwischenkopie liegt im Arbeitsverzeichnis, nie in /tmp, und ist danach gelöscht, auch im Fehlerfall. */
    @Test
    void zwischenkopieImArbeitsverzeichnisUndDanachGeloescht() throws IOException {
        S3Ablage ablage = ablage();
        byte[] daten = inhalt("Zwischenkopie");
        ablage.ablegenEingang(mandant, DateiSchluessel.original(2026, UUID.randomUUID(), 1, "pdf"),
                new ByteArrayInputStream(daten), daten.length);
        assertThatThrownBy(() -> ablage.ablegenEingang(mandant, DateiSchluessel.original(2026, UUID.randomUUID(), 1,
                "pdf"), new ByteArrayInputStream(daten), daten.length + 1L)).isInstanceOf(IllegalArgumentException.class);

        assertThat(ZWISCHENABLAGE).isDirectory();
        try (Stream<Path> rest = Files.list(ZWISCHENABLAGE)) {
            assertThat(rest).isEmpty();
        }
    }

    @Test
    void mitPflichtGovernanceGesperrtUndDieAnwendungKannWederLoeschenNochVerkuerzen() {
        S3Ablage ablage = ablage();
        var schluessel = DateiSchluessel.original(2026, UUID.randomUUID(), 1, "pdf");
        ZuArchivieren zu = eingang(ablage, schluessel, inhalt("Rechnung"));

        ArchiviertesDokument archiviert = ablage.archivieren(mandant, List.of(zu), Sperrvorgabe.bis(IN_ZEHN_JAHREN));

        AbgelegteDatei kopie = archiviert.dateien().getFirst();
        assertThat(kopie.schluessel()).isEqualTo(schluessel.pfad(Ablageort.ARCHIV));
        assertThat(kopie.sha256()).isEqualTo(zu.sha256());
        ObjectLockRetention sperre = retention(kopie.schluessel(), kopie.versionId()).orElseThrow();
        assertThat(sperre.mode()).isEqualTo(ObjectLockRetentionMode.GOVERNANCE);
        // Stufe A und Tests: Höchstfrist 1 Tag statt aufbewahren_bis (Req 6.11)
        assertThat(sperre.retainUntilDate()).isAfter(Instant.now().plus(Duration.ofHours(23)))
                .isBeforeOrEqualTo(Instant.now().plus(Duration.ofDays(1)).plusSeconds(1));

        assertThatThrownBy(() -> s3.deleteObject(b -> b.bucket(bucket).key(kopie.schluessel())
                .versionId(kopie.versionId()))).isInstanceOf(S3Exception.class);
        assertThatThrownBy(() -> s3.putObjectRetention(b -> b.bucket(bucket).key(kopie.schluessel())
                .versionId(kopie.versionId()).retention(r -> r.mode(ObjectLockRetentionMode.GOVERNANCE)
                        .retainUntilDate(Instant.now().plus(Duration.ofHours(1))))))
                .isInstanceOf(S3Exception.class);
        assertThat(retention(kopie.schluessel(), kopie.versionId()).orElseThrow().retainUntilDate())
                .isEqualTo(sperre.retainUntilDate());
    }

    @Test
    void ohnePflichtUndNachAbgelaufenerFristKeineSperre() {
        S3Ablage ablage = ablage();
        var ohnePflicht = eingang(ablage, DateiSchluessel.original(2026, UUID.randomUUID(), 1, "pdf"), inhalt("A"));
        var abgelaufen = eingang(ablage, DateiSchluessel.original(2026, UUID.randomUUID(), 1, "pdf"), inhalt("B"));

        var ohne = ablage.archivieren(mandant, List.of(ohnePflicht), Sperrvorgabe.ohnePflicht());
        var vorbei = ablage.archivieren(mandant, List.of(abgelaufen), new Sperrvorgabe(true, IN_ZEHN_JAHREN, true));

        for (ArchiviertesDokument dokument : List.of(ohne, vorbei)) {
            assertThat(dokument.sperreBis()).isEmpty();
            AbgelegteDatei kopie = dokument.dateien().getFirst();
            assertThat(kopie.sperreBis()).isEmpty();
            assertThat(retention(kopie.schluessel(), kopie.versionId())).isEmpty();
        }
    }

    @Test
    void abweichendePruefsummeBrichtAbOhneKopieUnterArchiv() {
        S3Ablage ablage = ablage();
        var schluessel = DateiSchluessel.original(2026, UUID.randomUUID(), 1, "pdf");
        ZuArchivieren zu = eingang(ablage, schluessel, inhalt("Original"));
        var falsch = new ZuArchivieren(schluessel, zu.eingangVersionId(), Pruefsumme.von(inhalt("anders")));

        assertThatThrownBy(() -> ablage.archivieren(mandant, List.of(falsch), Sperrvorgabe.unbefristet()))
                .isInstanceOfSatisfying(ArchivierungAbgebrochen.class,
                        e -> assertThat(e.grund()).isEqualTo(ArchivierungAbgebrochen.Grund.PRUEFSUMME));
        assertThat(verwaltung.listObjectVersions(b -> b.bucket(bucket).prefix("archiv/")).versions()).isEmpty();
        assertThat(verwaltung.listObjectVersions(b -> b.bucket(bucket).prefix("eingang/")).versions()).hasSize(1);
    }

    @Test
    void neuerVersuchUebernimmtDieKorrekteKopie() {
        S3Ablage ablage = ablage();
        ZuArchivieren zu = eingang(ablage, DateiSchluessel.pdfa(2026, UUID.randomUUID()), inhalt("PDF/A"));

        var erster = ablage.archivieren(mandant, List.of(zu), Sperrvorgabe.unbefristet());
        var zweiter = ablage.archivieren(mandant, List.of(zu), Sperrvorgabe.unbefristet());

        assertThat(zweiter.dateien().getFirst().versionId()).isEqualTo(erster.dateien().getFirst().versionId());
        assertThat(verwaltung.listObjectVersions(b -> b.bucket(bucket).prefix("archiv/")).versions()).hasSize(1);
    }

    @Test
    void neuerVersuchLoeschtDieArchivKopieNichtOhneGepruefteQuelle() {
        S3Ablage ablage = ablage();
        var ohneEingang = DateiSchluessel.original(2026, UUID.randomUUID(), 1, "pdf");
        ZuArchivieren fehlt = eingang(ablage, ohneEingang, inhalt("Original"));
        // einzige, ungesperrte Kopie unter archiv/ weicht ab, der Eingang ist schon gelöscht
        verwaltung.putObject(r -> r.bucket(bucket).key(ohneEingang.pfad(Ablageort.ARCHIV)),
                RequestBody.fromString("abweichend"));
        ablage.loeschenEingang(mandant, 2026, ohneEingang.docId());
        var andereQuelle = DateiSchluessel.original(2026, UUID.randomUUID(), 1, "pdf");
        ZuArchivieren quelle = eingang(ablage, andereQuelle, inhalt("Original"));
        // Quelle vorhanden, aber ihre SHA-256 stimmt nicht mit der erwarteten
        var falsch = new ZuArchivieren(andereQuelle, quelle.eingangVersionId(), Pruefsumme.von(inhalt("anders")));
        verwaltung.putObject(r -> r.bucket(bucket).key(andereQuelle.pfad(Ablageort.ARCHIV)),
                RequestBody.fromString("Original"));
        var nieKopiert = DateiSchluessel.original(2026, UUID.randomUUID(), 1, "pdf");
        ZuArchivieren ohneKopie = eingang(ablage, nieKopiert, inhalt("Z"));
        ablage.loeschenEingang(mandant, 2026, nieKopiert.docId());

        for (ZuArchivieren zu : List.of(fehlt, falsch, ohneKopie)) {
            assertThatThrownBy(() -> ablage.archivieren(mandant, List.of(zu), Sperrvorgabe.unbefristet()))
                    .as(zu.schluessel().docId().toString())
                    .isInstanceOfSatisfying(ArchivierungAbgebrochen.class,
                            e -> assertThat(e.grund()).isEqualTo(ArchivierungAbgebrochen.Grund.PRUEFSUMME));
        }
        for (DateiSchluessel s : List.of(ohneEingang, andereQuelle)) {
            assertThat(verwaltung.listObjectVersions(b -> b.bucket(bucket).prefix(s.pfad(Ablageort.ARCHIV))).versions())
                    .as(s.pfad(Ablageort.ARCHIV)).hasSize(1);
        }
    }

    @Test
    void neuerVersuchErsetztEineFehlerhafteKopieNachGepruefterQuelle() {
        S3Ablage ablage = ablage();
        var schluessel = DateiSchluessel.original(2026, UUID.randomUUID(), 1, "pdf");
        ZuArchivieren zu = eingang(ablage, schluessel, inhalt("Original"));
        String ziel = schluessel.pfad(Ablageort.ARCHIV);
        verwaltung.putObject(r -> r.bucket(bucket).key(ziel), RequestBody.fromString("abweichend"));
        verwaltung.deleteObject(r -> r.bucket(bucket).key(ziel));

        AbgelegteDatei kopie = ablage.archivieren(mandant, List.of(zu), Sperrvorgabe.ohnePflicht()).dateien().getFirst();

        assertThat(kopie.sha256()).isEqualTo(zu.sha256());
        var rest = verwaltung.listObjectVersions(b -> b.bucket(bucket).prefix(ziel));
        assertThat(rest.versions()).singleElement().satisfies(v -> assertThat(v.versionId()).isEqualTo(kopie.versionId()));
        assertThat(rest.deleteMarkers()).isEmpty();
    }

    @Test
    void neuerVersuchMitZweiGleichenKopienUebernimmtDieGesperrteOhneAbbruch() {
        S3Ablage ablage = ablage();
        var schluessel = DateiSchluessel.original(2026, UUID.randomUUID(), 1, "pdf");
        ZuArchivieren zu = eingang(ablage, schluessel, inhalt("Original"));
        String ziel = schluessel.pfad(Ablageort.ARCHIV);
        // zwei frühere Versuche: die ältere Kopie gesperrt, die neuere gleiche ungesperrt
        String gesperrt = verwaltung.putObject(r -> r.bucket(bucket).key(ziel), RequestBody.fromString("Original"))
                .versionId();
        Instant bis = Instant.now().plus(Duration.ofHours(1)).truncatedTo(ChronoUnit.SECONDS);
        verwaltung.putObjectRetention(r -> r.bucket(bucket).key(ziel).versionId(gesperrt)
                .retention(s -> s.mode(ObjectLockRetentionMode.GOVERNANCE).retainUntilDate(bis)));
        verwaltung.putObject(r -> r.bucket(bucket).key(ziel), RequestBody.fromString("Original"));

        ArchiviertesDokument archiviert = ablage.archivieren(mandant, List.of(zu), Sperrvorgabe.ohnePflicht());

        AbgelegteDatei kopie = archiviert.dateien().getFirst();
        assertThat(kopie.versionId()).isEqualTo(gesperrt);
        assertThat(kopie.sperreBis()).contains(bis);
        assertThat(verwaltung.listObjectVersions(b -> b.bucket(bucket).prefix(ziel)).versions())
                .singleElement().satisfies(v -> assertThat(v.versionId()).isEqualTo(gesperrt));
    }

    @Test
    void neuerVersuchMeldetDieSperreDerUebernommenenKopie() {
        S3Ablage ablage = ablage();
        ZuArchivieren zu = eingang(ablage, DateiSchluessel.original(2026, UUID.randomUUID(), 1, "pdf"), inhalt("G"));
        AbgelegteDatei erste = ablage.archivieren(mandant, List.of(zu), Sperrvorgabe.unbefristet()).dateien().getFirst();
        Instant sperre = retention(erste.schluessel(), erste.versionId()).orElseThrow().retainUntilDate();

        // inzwischen ohne Pflicht: die Kopie bleibt gesperrt, das Ergebnis muss das melden
        ArchiviertesDokument zweiter = ablage.archivieren(mandant, List.of(zu), Sperrvorgabe.ohnePflicht());

        assertThat(zweiter.dateien().getFirst().versionId()).isEqualTo(erste.versionId());
        assertThat(zweiter.dateien().getFirst().sperreBis()).contains(sperre);
        assertThat(zweiter.sperreBis()).contains(sperre);
    }

    @Test
    void eineFehlendeVersionGiltNichtAlsOhneSperre() {
        S3Ablage ablage = ablage();
        ZuArchivieren zu = eingang(ablage, DateiSchluessel.original(2026, UUID.randomUUID(), 1, "pdf"), inhalt("V"));
        AbgelegteDatei kopie = ablage.archivieren(mandant, List.of(zu), Sperrvorgabe.ohnePflicht()).dateien().getFirst();
        assertThat(ablage.sperreVerlaengern(mandant, List.of(kopie), Sperrvorgabe.ohnePflicht()).getFirst().ergebnis())
                .isEqualTo(SperrAenderung.Ergebnis.KEINE_SPERRE);

        verwaltung.deleteObject(b -> b.bucket(bucket).key(kopie.schluessel()).versionId(kopie.versionId()));

        assertThatThrownBy(() -> ablage.sperreVerlaengern(mandant, List.of(kopie), Sperrvorgabe.ohnePflicht()))
                .isInstanceOfSatisfying(S3Exception.class, e -> assertThat(e.awsErrorDetails().errorCode())
                        .isIn("NoSuchKey", "NoSuchVersion"));
    }

    @Test
    void sperreWirdNurVerlaengertNieVerkuerzt() {
        ZuArchivieren zu = eingang(ablage(), DateiSchluessel.original(2026, UUID.randomUUID(), 1, "pdf"), inhalt("X"));
        AbgelegteDatei kopie = ablage(Optional.of(Duration.ofHours(2)), Sperrmodus.GOVERNANCE, Optional.empty())
                .archivieren(mandant, List.of(zu), Sperrvorgabe.unbefristet()).dateien().getFirst();
        Instant vorher = kopie.sperreBis().orElseThrow();

        List<SperrAenderung> laenger = ablage(Optional.of(Duration.ofHours(3)), Sperrmodus.GOVERNANCE, Optional.empty())
                .sperreVerlaengern(mandant, List.of(kopie), Sperrvorgabe.unbefristet());
        List<SperrAenderung> kuerzer = ablage(Optional.of(Duration.ofHours(1)), Sperrmodus.GOVERNANCE, Optional.empty())
                .sperreVerlaengern(mandant, List.of(kopie), Sperrvorgabe.unbefristet());

        assertThat(laenger.getFirst().ergebnis()).isEqualTo(SperrAenderung.Ergebnis.VERLAENGERT);
        Instant nachher = laenger.getFirst().sperreBis().orElseThrow();
        assertThat(nachher).isAfter(vorher);
        assertThat(kuerzer.getFirst().ergebnis()).isEqualTo(SperrAenderung.Ergebnis.KONFLIKT);
        assertThat(retention(kopie.schluessel(), kopie.versionId()).orElseThrow().retainUntilDate()).isEqualTo(nachher);
    }

    @Test
    void festhaltenVorDemLoeschenDesEingangs() {
        S3Ablage ablage = ablage();
        var service = new AblageService(ablage);
        UUID docId = UUID.randomUUID();
        var original = DateiSchluessel.original(2026, docId, 1, "jpg");
        ZuArchivieren a = eingang(ablage, original, inhalt("Seite 1"));
        ZuArchivieren b = eingang(ablage, DateiSchluessel.arbeitsfassung(2026, docId, 1), inhalt("Seite 1 klein"));
        // zweite Version und Löschmarke unter eingang/: auch sie werden entfernt
        verwaltung.putObject(r -> r.bucket(bucket).key(original.pfad(Ablageort.EINGANG)), RequestBody.fromString("neu"));
        verwaltung.deleteObject(r -> r.bucket(bucket).key(original.pfad(Ablageort.EINGANG)));
        ZuArchivieren fremd = eingang(ablage, DateiSchluessel.original(2026, UUID.randomUUID(), 1, "pdf"), inhalt("Y"));

        assertThatThrownBy(() -> service.archivieren(mandant, List.of(a, b), Sperrvorgabe.unbefristet(), d -> {
            throw new IllegalStateException("Datenbank nicht erreichbar");
        })).hasMessageContaining("Datenbank");
        assertThat(verwaltung.listObjectVersions(r -> r.bucket(bucket).prefix("eingang/2026/" + docId)).versions())
                .hasSize(3);

        var festgehalten = new java.util.ArrayList<ArchiviertesDokument>();
        service.archivieren(mandant, List.of(a, b), Sperrvorgabe.unbefristet(), festgehalten::add);

        assertThat(festgehalten).hasSize(1);
        var rest = verwaltung.listObjectVersions(r -> r.bucket(bucket).prefix("eingang/2026/" + docId));
        assertThat(rest.versions()).isEmpty();
        assertThat(rest.deleteMarkers()).isEmpty();
        assertThat(verwaltung.listObjectVersions(r -> r.bucket(bucket).prefix("archiv/2026/" + docId)).versions())
                .hasSize(2);
        assertThat(verwaltung.listObjectVersions(r -> r.bucket(bucket).prefix(fremd.schluessel().pfad(Ablageort.EINGANG)))
                .versions()).hasSize(1);
    }

    @Test
    void vorsignierteUrlNurFuerDieseVersionUndHoechstensFuenfMinuten() throws Exception {
        S3Ablage ablage = ablage();
        var schluessel = DateiSchluessel.original(2026, UUID.randomUUID(), 1, "pdf");
        ZuArchivieren zu = eingang(ablage, schluessel, inhalt("erste Fassung"));
        verwaltung.putObject(r -> r.bucket(bucket).key(schluessel.pfad(Ablageort.EINGANG)),
                RequestBody.fromString("zweite Fassung"));

        Instant vorher = Instant.now();
        var url = ablage.vorsignierteUrl(mandant, Ablageort.EINGANG, schluessel, zu.eingangVersionId());

        assertThat(url.gueltigBis()).isBeforeOrEqualTo(vorher.plus(Duration.ofMinutes(5)).plusSeconds(1));
        assertThat(url.toString()).doesNotContain("Signature");
        try (HttpClient http = HttpClient.newHttpClient()) {
            var antwort = http.send(HttpRequest.newBuilder(url.url()).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(antwort.statusCode()).isEqualTo(200);
            assertThat(antwort.body()).isEqualTo("erste Fassung");
            var put = http.send(HttpRequest.newBuilder(url.url()).PUT(HttpRequest.BodyPublishers.ofString("x")).build(),
                    HttpResponse.BodyHandlers.discarding());
            assertThat(put.statusCode()).isEqualTo(403);
        }
        assertThatThrownBy(() -> ablage.vorsignierteUrl(mandant, Ablageort.EINGANG, schluessel, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AblageEinstellungen(TestBucket.PRAEFIX, Optional.empty(), Sperrmodus.COMPLIANCE,
                Optional.empty(), Duration.ofMinutes(6), ZWISCHENABLAGE)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void manifestInComplianceAuchMitBypassWederLoeschbarNochVerkuerzbar() {
        // kurze Frist, damit der Test-Bucket bald wieder löschbar ist
        S3Ablage ablage = ablage(Optional.of(Duration.ofDays(1)), Sperrmodus.COMPLIANCE, Optional.of(Duration.ofSeconds(20)));
        LocalDate datum = LocalDate.of(2026, 11, 10);
        byte[] manifest = inhalt("{\"dateien\":[]}");

        AbgelegteDatei datei = ablage.ablegenManifest(mandant, datum, ManifestTeil.JSON, manifest, Optional.empty());

        assertThat(datei.schluessel()).isEqualTo(ManifestTeil.JSON.pfad(datum)).startsWith("manifest/");
        ObjectLockRetention sperre = retention(datei.schluessel(), datei.versionId()).orElseThrow();
        assertThat(sperre.mode()).isEqualTo(ObjectLockRetentionMode.COMPLIANCE);
        assertThatThrownBy(() -> verwaltung.deleteObject(b -> b.bucket(bucket).key(datei.schluessel())
                .versionId(datei.versionId()).bypassGovernanceRetention(true))).isInstanceOf(S3Exception.class);
        assertThatThrownBy(() -> verwaltung.putObjectRetention(b -> b.bucket(bucket).key(datei.schluessel())
                .versionId(datei.versionId()).bypassGovernanceRetention(true)
                .retention(r -> r.mode(ObjectLockRetentionMode.COMPLIANCE).retainUntilDate(Instant.now().plusSeconds(5)))))
                .isInstanceOf(S3Exception.class);
        assertThat(retention(datei.schluessel(), datei.versionId()).orElseThrow().retainUntilDate())
                .isEqualTo(sperre.retainUntilDate());

        assertThat(ablage.ablegenManifest(mandant, datum, ManifestTeil.JSON, manifest, Optional.empty()).versionId())
                .isEqualTo(datei.versionId());
        assertThatThrownBy(() -> ablage.ablegenManifest(mandant, datum, ManifestTeil.JSON, inhalt("anders"),
                Optional.empty())).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void vorhandenesManifestGiltNurMitPassenderSperre() {
        LocalDate datum = LocalDate.of(2026, 11, 11);
        byte[] manifest = inhalt("{\"dateien\":[1]}");
        S3Ablage kurz = ablage(Optional.of(Duration.ofDays(1)), Sperrmodus.GOVERNANCE, Optional.of(Duration.ofMinutes(1)));
        S3Ablage lang = ablage(Optional.of(Duration.ofDays(1)), Sperrmodus.GOVERNANCE, Optional.of(Duration.ofMinutes(10)));
        S3Ablage compliance = ablage(Optional.of(Duration.ofDays(1)), Sperrmodus.COMPLIANCE,
                Optional.of(Duration.ofSeconds(20)));

        AbgelegteDatei erst = kurz.ablegenManifest(mandant, datum, ManifestTeil.JSON, manifest, Optional.empty());
        Instant vorher = retention(erst.schluessel(), erst.versionId()).orElseThrow().retainUntilDate();
        AbgelegteDatei wieder = lang.ablegenManifest(mandant, datum, ManifestTeil.JSON, manifest, Optional.empty());
        Instant verlaengert = retention(erst.schluessel(), erst.versionId()).orElseThrow().retainUntilDate();
        assertThat(wieder.versionId()).isEqualTo(erst.versionId());
        assertThat(verlaengert).isAfter(vorher.plus(Duration.ofMinutes(5)));
        assertThat(wieder.sperreBis()).contains(verlaengert);
        // nie verkürzt
        assertThat(kurz.ablegenManifest(mandant, datum, ManifestTeil.JSON, manifest, Optional.empty()).sperreBis())
                .contains(verlaengert);
        // anderer Modus
        assertThatThrownBy(() -> compliance.ablegenManifest(mandant, datum, ManifestTeil.JSON, manifest,
                Optional.empty())).isInstanceOf(IllegalStateException.class);
        // ohne Sperre
        verwaltung.putObject(r -> r.bucket(bucket).key(ManifestTeil.SIG.pfad(datum)), RequestBody.fromBytes(manifest));
        assertThatThrownBy(() -> lang.ablegenManifest(mandant, datum, ManifestTeil.SIG, manifest, Optional.empty()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(verwaltung.listObjectVersions(b -> b.bucket(bucket).prefix("manifest/")).versions()).hasSize(2);
    }

    /** Ceph RGW 20.x: CopyObject ohne Versions-ID; die Version kommt dann über HeadObject mit passendem ETag. */
    @Test
    void kopieOhneVersionsIdInDerAntwortWieBeiCephRgw() {
        try (S3Client ceph = TestBucket.client(new BypassVerbot(), new KeinBypassInterceptor(),
                new OhneKopieVersion(false))) {
            var ablage = new S3Ablage(ceph, S3Ablage.presigner(ceph, true), new AblageEinstellungen(TestBucket.PRAEFIX,
                    Optional.of(Duration.ofDays(1)), Sperrmodus.GOVERNANCE, Optional.of(Duration.ofDays(1)),
                    Duration.ofMinutes(5), ZWISCHENABLAGE), Clock.systemUTC());
            ZuArchivieren zu = eingang(ablage, DateiSchluessel.original(2026, UUID.randomUUID(), 1, "pdf"),
                    inhalt("Kopie ohne Versions-ID"));

            AbgelegteDatei kopie = ablage.archivieren(mandant, List.of(zu), Sperrvorgabe.unbefristet()).dateien()
                    .getFirst();

            var versionen = verwaltung.listObjectVersions(b -> b.bucket(bucket).prefix("archiv/")).versions();
            assertThat(versionen).hasSize(1);
            assertThat(kopie.versionId()).isEqualTo(versionen.getFirst().versionId());
            assertThat(kopie.sha256()).isEqualTo(zu.sha256());
            assertThat(retention(kopie.schluessel(), kopie.versionId())).get()
                    .extracting(ObjectLockRetention::mode).isEqualTo(ObjectLockRetentionMode.GOVERNANCE);
        }
    }

    /** Passt das ETag der aktuellen Version nicht zur Kopie, bricht die Archivierung ab und löscht nichts. */
    @Test
    void kopieOhneVersionsIdUndFremdemEtagBrichtAbOhneZuLoeschen() {
        try (S3Client ceph = TestBucket.client(new BypassVerbot(), new KeinBypassInterceptor(),
                new OhneKopieVersion(true))) {
            var ablage = new S3Ablage(ceph, S3Ablage.presigner(ceph, true), new AblageEinstellungen(TestBucket.PRAEFIX,
                    Optional.of(Duration.ofDays(1)), Sperrmodus.GOVERNANCE, Optional.of(Duration.ofDays(1)),
                    Duration.ofMinutes(5), ZWISCHENABLAGE), Clock.systemUTC());
            ZuArchivieren zu = eingang(ablage, DateiSchluessel.original(2026, UUID.randomUUID(), 1, "pdf"),
                    inhalt("Kopie mit fremdem ETag"));

            assertThatThrownBy(() -> ablage.archivieren(mandant, List.of(zu), Sperrvorgabe.unbefristet()))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("nicht bestimmbar");
            assertThat(verwaltung.listObjectVersions(b -> b.bucket(bucket).prefix("archiv/")).versions()).hasSize(1);
            assertThat(verwaltung.listObjectVersions(b -> b.bucket(bucket).prefix("eingang/")).versions()).hasSize(1);
        }
    }

    @Test
    void derInterceptorLaesstKeinenBypassHinaus() {
        String key = KeinBypassInterceptor.SELBSTTEST + "/objekt";
        assertThatThrownBy(() -> s3.deleteObject(b -> b.bucket(bucket).key(key).bypassGovernanceRetention(true)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining(KeinBypassInterceptor.HEADER);
    }

    /** Fortsetzung über mehrere Seiten (Req 15.10): jede Version und jede Löschmarke genau einmal. */
    @Test
    void versionenUeberMehrereSeiten() {
        String a = "archiv/2026/" + UUID.randomUUID() + "/1-original.pdf";
        String b = "archiv/2026/" + UUID.randomUUID() + "/1-original.pdf";
        verwaltung.putObject(r -> r.bucket(bucket).key(a), RequestBody.fromString("a1"));
        verwaltung.putObject(r -> r.bucket(bucket).key(a), RequestBody.fromString("a2"));
        verwaltung.putObject(r -> r.bucket(bucket).key(b), RequestBody.fromString("b1"));
        verwaltung.deleteObject(r -> r.bucket(bucket).key(b));
        verwaltung.putObject(r -> r.bucket(bucket).key("eingang/2026/x.pdf"), RequestBody.fromString("x"));

        S3Ablage ablage = ablage();
        List<Objektversion> alle = new ArrayList<>();
        Optional<Versionsseite.Marke> marke = Optional.empty();
        int seiten = 0;
        do {
            Versionsseite seite = ablage.versionen(mandant, "archiv/", marke, 1);
            alle.addAll(seite.versionen());
            marke = seite.weiter();
            assertThat(++seiten).isLessThan(10);
        } while (marke.isPresent());

        var erwartet = verwaltung.listObjectVersions(r -> r.bucket(bucket).prefix("archiv/"));
        assertThat(seiten).isGreaterThanOrEqualTo(4);
        assertThat(alle).doesNotHaveDuplicates().hasSize(4)
                .containsAll(erwartet.versions().stream().map(v -> new Objektversion(v.key(), v.versionId(), false))
                        .toList())
                .containsAll(erwartet.deleteMarkers().stream()
                        .map(m -> new Objektversion(m.key(), m.versionId(), true)).toList());
    }

    /**
     * Ohne {@code NextVersionIdMarker} in der Antwort geht es nur mit dem key-marker weiter, nie mit einem leeren
     * version-id-marker (Req 15.10).
     */
    @Test
    void ohneNaechsteVersionsIdKeinLeererVersionIdMarker() {
        List<String> schluessel = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            String key = "manifest/2026/11/" + (10 + i) + ".json";
            verwaltung.putObject(r -> r.bucket(bucket).key(key), RequestBody.fromString("m" + key));
            schluessel.add(key);
        }
        List<ListObjectVersionsRequest> anfragen = new ArrayList<>();
        ExecutionInterceptor ohneVersionsMarker = new ExecutionInterceptor() {
            @Override
            public void beforeExecution(Context.BeforeExecution kontext, ExecutionAttributes attribute) {
                if (kontext.request() instanceof ListObjectVersionsRequest anfrage) {
                    anfragen.add(anfrage);
                }
            }

            @Override
            public SdkResponse modifyResponse(Context.ModifyResponse kontext, ExecutionAttributes attribute) {
                return kontext.response() instanceof ListObjectVersionsResponse antwort
                        ? antwort.toBuilder().nextVersionIdMarker(null).build()
                        : kontext.response();
            }
        };
        try (S3Client ohne = TestBucket.client(new BypassVerbot(), new KeinBypassInterceptor(), ohneVersionsMarker)) {
            var ablage = new S3Ablage(ohne, S3Ablage.presigner(ohne, true), new AblageEinstellungen(TestBucket.PRAEFIX,
                    Optional.of(Duration.ofDays(1)), Sperrmodus.GOVERNANCE, Optional.of(Duration.ofDays(1)),
                    Duration.ofMinutes(5), ZWISCHENABLAGE), Clock.systemUTC());
            List<String> gelistet = new ArrayList<>();
            Optional<Versionsseite.Marke> marke = Optional.empty();
            do {
                Versionsseite seite = ablage.versionen(mandant, "manifest/", marke, 1);
                seite.versionen().forEach(v -> gelistet.add(v.schluessel()));
                marke = seite.weiter();
                marke.ifPresent(m -> assertThat(m.versionId()).isEmpty());
                assertThat(gelistet).hasSizeLessThan(10);
            } while (marke.isPresent());

            assertThat(gelistet).containsExactlyElementsOf(schluessel);
            assertThat(anfragen).hasSizeGreaterThanOrEqualTo(3);
            assertThat(anfragen.getFirst().keyMarker()).isNull();
            assertThat(anfragen.subList(1, anfragen.size())).allSatisfy(anfrage -> {
                assertThat(anfrage.keyMarker()).isNotBlank();
                assertThat(anfrage.versionIdMarker()).isNull();
            });
        }
    }
}
