package ch.urinet.archiv.ablage;

import ch.urinet.archiv.ablage.application.AblagePort;
import ch.urinet.archiv.ablage.application.AblageService;
import ch.urinet.archiv.ablage.domain.AbgelegteDatei;
import ch.urinet.archiv.ablage.domain.Ablageort;
import ch.urinet.archiv.ablage.domain.ArchivierungAbgebrochen;
import ch.urinet.archiv.ablage.domain.ArchiviertesDokument;
import ch.urinet.archiv.ablage.domain.DateiSchluessel;
import ch.urinet.archiv.ablage.domain.Pruefsumme;
import ch.urinet.archiv.ablage.domain.Sperrfrist;
import ch.urinet.archiv.ablage.domain.Sperrvorgabe;
import ch.urinet.archiv.ablage.domain.VorsignierteUrl;
import ch.urinet.archiv.ablage.domain.ZuArchivieren;
import ch.urinet.archiv.mandant.domain.MandantCode;
import io.cucumber.java.After;
import io.cucumber.java.de.Angenommen;
import io.cucumber.java.de.Dann;
import io.cucumber.java.de.Und;
import io.cucumber.java.de.Wenn;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;
import software.amazon.awssdk.services.s3.model.ObjectLockRetentionMode;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.ByteArrayInputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Schritte zu Req 6.2, 6.3, 6.4, 6.9, 6.12 und 14.6: der Ablage-Port der Anwendung gegen MinIO oder Ceph RGW. */
public class AblageSchritte {

    private static final DateTimeFormatter DATUM = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    @Inject
    AblagePort ablage;

    @Inject
    AblageService service;

    @ConfigProperty(name = "archiv.ablage.bucket-praefix")
    String praefix;

    private S3Client verwaltung;
    private MandantCode mandant;
    private String bucket;
    private UUID docId;
    private final List<ZuArchivieren> eingang = new ArrayList<>();
    private AbgelegteDatei abgelegt;
    private ArchiviertesDokument archiviert;
    private int imEingangBeimFesthalten = -1;
    private RuntimeException fehler;
    private VorsignierteUrl url;
    private Instant urlVerlangt;

    @Angenommen("ein Mandanten-Bucket mit Object Lock und Versionierung")
    public void bucket() {
        verwaltung = TestBucket.verwaltung();
        mandant = new MandantCode("a" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        bucket = mandant.bucket(praefix);
        verwaltung.createBucket(b -> b.bucket(bucket).objectLockEnabledForBucket(true));
        verwaltung.putBucketVersioning(b -> b.bucket(bucket)
                .versioningConfiguration(v -> v.status(BucketVersioningStatus.ENABLED)));
        docId = UUID.randomUUID();
    }

    @After("@spec-01 and (@req-6.2 or @req-6.3 or @req-6.4 or @req-6.9 or @req-6.12 or @req-14.6)")
    public void aufraeumen() {
        if (verwaltung != null) {
            TestBucket.aufraeumen(verwaltung, bucket);
            verwaltung.close();
        }
    }

    @Wenn("die Anwendung ein Original im Eingang ablegt")
    public void originalAblegen() {
        byte[] daten = inhalt("einzeln");
        abgelegt = ablage.ablegenEingang(mandant, DateiSchluessel.original(2026, docId, 1, "pdf"),
                new ByteArrayInputStream(daten), daten.length);
    }

    @Dann("liegt das Original unter «eingang\\/» mit Versions-ID und SHA-256")
    public void originalImEingang() {
        assertThat(abgelegt.schluessel()).startsWith("eingang/2026/");
        assertThat(abgelegt.versionId()).isNotBlank();
        assertThat(abgelegt.sha256()).isEqualTo(Pruefsumme.von(inhalt("einzeln")));
    }

    @Und("die Datei im Eingang ist nicht gesperrt")
    public void eingangUngesperrt() {
        assertThat(abgelegt.sperreBis()).isEmpty();
        assertThat(gesperrt(abgelegt.schluessel(), abgelegt.versionId())).isEmpty();
    }

    @Und("die Anwendung kann die Datei im Eingang löschen")
    public void eingangLoeschbar() {
        ablage.loeschenEingang(mandant, 2026, docId);
        assertThat(versionen("eingang/2026/" + docId)).isZero();
    }

    @Angenommen("ein Dokument mit Original, Arbeitsfassung und PDF\\/A im Eingang")
    public void dreiDateien() {
        ablegen(DateiSchluessel.original(2026, docId, 1, "jpg"), "Foto Seite 1");
        ablegen(DateiSchluessel.arbeitsfassung(2026, docId, 1), "Arbeitsfassung Seite 1");
        ablegen(DateiSchluessel.pdfa(2026, docId), "PDF/A");
    }

    @Angenommen("ein Dokument mit Original im Eingang")
    public void einOriginal() {
        ablegen(DateiSchluessel.original(2026, docId, 1, "pdf"), "Original");
    }

    @Wenn("die Anwendung das Dokument einer Art mit gesetzlicher Pflicht bis «{word}» archiviert")
    public void archivierenBis(String datum) {
        archivieren(Sperrvorgabe.bis(LocalDate.parse(datum, DATUM)));
    }

    @Wenn("die Anwendung das Dokument einer Art mit gesetzlicher Pflicht ohne Frist archiviert")
    public void archivierenUnbefristet() {
        archivieren(Sperrvorgabe.unbefristet());
    }

    @Wenn("die Anwendung das Dokument einer Art ohne gesetzliche Pflicht archiviert")
    public void archivierenOhnePflicht() {
        archivieren(Sperrvorgabe.ohnePflicht());
    }

    @Wenn("die Anwendung das Dokument mit einer falschen Prüfsumme archiviert")
    public void archivierenFalsch() {
        ZuArchivieren richtig = eingang.getFirst();
        eingang.set(0, new ZuArchivieren(richtig.schluessel(), richtig.eingangVersionId(),
                Pruefsumme.von(inhalt("etwas anderes"))));
        try {
            archivieren(Sperrvorgabe.unbefristet());
        } catch (RuntimeException e) {
            fehler = e;
        }
    }

    @Dann("liegen {int} Dateien unter «archiv\\/» mit derselben SHA-256 wie im Eingang")
    public void imArchiv(int anzahl) {
        assertThat(archiviert.dateien()).hasSize(anzahl);
        assertThat(versionen("archiv/2026/" + docId)).isEqualTo(anzahl);
        for (int i = 0; i < anzahl; i++) {
            AbgelegteDatei datei = archiviert.dateien().get(i);
            assertThat(datei.schluessel()).isEqualTo(eingang.get(i).schluessel().pfad(Ablageort.ARCHIV));
            assertThat(datei.sha256()).isEqualTo(eingang.get(i).sha256());
        }
    }

    @Und("jede archivierte Datei hat eine Versions-ID und ist in GOVERNANCE gesperrt")
    @Dann("hat jede archivierte Datei eine Versions-ID und ist in GOVERNANCE gesperrt")
    public void governance() {
        assertThat(archiviert.dateien()).isNotEmpty();
        for (AbgelegteDatei datei : archiviert.dateien()) {
            assertThat(datei.versionId()).isNotBlank();
            assertThat(gesperrt(datei.schluessel(), datei.versionId())).contains(ObjectLockRetentionMode.GOVERNANCE);
            assertThat(datei.sperreBis()).isPresent();
        }
    }

    @Und("keine archivierte Datei ist gesperrt")
    public void ungesperrt() {
        assertThat(archiviert.sperreBis()).isEmpty();
        for (AbgelegteDatei datei : archiviert.dateien()) {
            assertThat(gesperrt(datei.schluessel(), datei.versionId())).isEmpty();
        }
    }

    @Und("beim Festhalten lagen die Dateien noch im Eingang")
    public void festhaltenVorLoeschen() {
        assertThat(imEingangBeimFesthalten).isEqualTo(eingang.size());
    }

    @Und("im Eingang des Dokuments liegt keine Version mehr")
    public void eingangLeer() {
        assertThat(versionen("eingang/2026/" + docId)).isZero();
    }

    @Und("ohne Höchstfrist endet die Sperre eines am «{word}» archivierten unbefristeten Dokuments am «{word}»")
    public void zehnJahre(String am, String bis) {
        Instant jetzt = LocalDate.parse(am, DATUM).atTime(10, 0).atZone(Sperrfrist.ZUERICH).toInstant();
        Instant ende = Sperrfrist.retainUntil(Sperrvorgabe.unbefristet(), jetzt, Optional.empty()).orElseThrow();
        assertThat(LocalDate.ofInstant(ende, Sperrfrist.ZUERICH)).isEqualTo(LocalDate.parse(bis, DATUM));
    }

    @Dann("bricht die Archivierung mit «{word}» ab")
    public void abgebrochen(String grund) {
        assertThat(fehler).isInstanceOfSatisfying(ArchivierungAbgebrochen.class,
                e -> assertThat(e.grund().name()).isEqualTo(grund));
    }

    @Und("unter «archiv\\/» liegt keine Datei")
    public void archivLeer() {
        assertThat(versionen("archiv/")).isZero();
    }

    @Und("das Original liegt unverändert im Eingang")
    public void eingangUnveraendert() {
        String key = eingang.getFirst().schluessel().pfad(Ablageort.EINGANG);
        byte[] gelesen = verwaltung.getObjectAsBytes(b -> b.bucket(bucket).key(key)
                .versionId(eingang.getFirst().eingangVersionId())).asByteArray();
        assertThat(gelesen).isEqualTo(inhalt("Original"));
        assertThat(versionen("eingang/2026/" + docId)).isEqualTo(1);
    }

    @Und("unter dem Schlüssel des PDF\\/A liegt eine neuere Version")
    public void neuereVersion() {
        String key = DateiSchluessel.pdfa(2026, docId).pfad(Ablageort.ARCHIV);
        verwaltung.putObject(b -> b.bucket(bucket).key(key), RequestBody.fromString("neuere Fassung"));
    }

    @Wenn("die Anwendung die Download-Adresse für die gespeicherte Versions-ID des PDF\\/A ausgibt")
    public void downloadAdresse() {
        AbgelegteDatei pdfa = archiviert.dateien().getLast();
        urlVerlangt = Instant.now();
        url = ablage.vorsignierteUrl(mandant, Ablageort.ARCHIV, DateiSchluessel.pdfa(2026, docId), pdfa.versionId());
    }

    @Dann("liefert die Adresse den Inhalt der gespeicherten Version")
    public void inhaltDerVersion() throws Exception {
        try (HttpClient http = HttpClient.newHttpClient()) {
            var antwort = http.send(HttpRequest.newBuilder(url.url()).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(antwort.statusCode()).isEqualTo(200);
            assertThat(antwort.body()).isEqualTo("PDF/A");
        }
    }

    @Und("die Adresse ist höchstens 5 Minuten gültig")
    public void fuenfMinuten() {
        assertThat(url.gueltigBis()).isBeforeOrEqualTo(urlVerlangt.plus(Duration.ofMinutes(5)).plusSeconds(1));
        assertThat(url.url().getQuery()).contains("versionId=");
    }

    private void ablegen(DateiSchluessel schluessel, String text) {
        byte[] daten = inhalt(text);
        AbgelegteDatei datei = ablage.ablegenEingang(mandant, schluessel, new ByteArrayInputStream(daten), daten.length);
        eingang.add(new ZuArchivieren(schluessel, datei.versionId(), datei.sha256()));
    }

    private void archivieren(Sperrvorgabe vorgabe) {
        archiviert = service.archivieren(mandant, List.copyOf(eingang), vorgabe,
                d -> imEingangBeimFesthalten = versionen("eingang/2026/" + docId));
    }

    private int versionen(String praefixImBucket) {
        var liste = verwaltung.listObjectVersions(b -> b.bucket(bucket).prefix(praefixImBucket));
        return liste.versions().size() + liste.deleteMarkers().size();
    }

    private Optional<ObjectLockRetentionMode> gesperrt(String key, String versionId) {
        try {
            var sperre = verwaltung.getObjectRetention(b -> b.bucket(bucket).key(key).versionId(versionId)).retention();
            return Optional.ofNullable(sperre).filter(s -> s.retainUntilDate() != null).map(s -> s.mode());
        } catch (S3Exception e) {
            return Optional.empty();
        }
    }

    private static byte[] inhalt(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
