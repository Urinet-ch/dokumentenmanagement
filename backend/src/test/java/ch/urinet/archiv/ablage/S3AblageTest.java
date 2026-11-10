package ch.urinet.archiv.ablage;

import ch.urinet.archiv.ablage.adapter.out.AblageEinstellungen;
import ch.urinet.archiv.ablage.adapter.out.s3.S3Ablage;
import ch.urinet.archiv.ablage.application.AblageService;
import ch.urinet.archiv.ablage.domain.AbgelegteDatei;
import ch.urinet.archiv.ablage.domain.Ablageort;
import ch.urinet.archiv.ablage.domain.ArchivierungAbgebrochen;
import ch.urinet.archiv.ablage.domain.ArchiviertesDokument;
import ch.urinet.archiv.ablage.domain.DateiSchluessel;
import ch.urinet.archiv.ablage.domain.ManifestTeil;
import ch.urinet.archiv.ablage.domain.Pruefsumme;
import ch.urinet.archiv.ablage.domain.SperrAenderung;
import ch.urinet.archiv.ablage.domain.Sperrmodus;
import ch.urinet.archiv.ablage.domain.Sperrvorgabe;
import ch.urinet.archiv.ablage.domain.ZuArchivieren;
import ch.urinet.archiv.mandant.domain.MandantCode;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ObjectLockRetention;
import software.amazon.awssdk.services.s3.model.ObjectLockRetentionMode;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.ByteArrayInputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

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
                manifestHoechstfrist, Duration.ofMinutes(5));
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
                Optional.empty(), Duration.ofMinutes(6))).isInstanceOf(IllegalStateException.class);
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
    void derInterceptorLaesstKeinenBypassHinaus() {
        String key = KeinBypassInterceptor.SELBSTTEST + "/objekt";
        assertThatThrownBy(() -> s3.deleteObject(b -> b.bucket(bucket).key(key).bypassGovernanceRetention(true)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining(KeinBypassInterceptor.HEADER);
    }
}
