package ch.urinet.archiv.ablage.adapter.out.s3;

import ch.urinet.archiv.ablage.adapter.out.AblageEinstellungen;
import ch.urinet.archiv.ablage.application.AblagePort;
import ch.urinet.archiv.ablage.domain.AbgelegteDatei;
import ch.urinet.archiv.ablage.domain.Ablageort;
import ch.urinet.archiv.ablage.domain.ArchiviertesDokument;
import ch.urinet.archiv.ablage.domain.ArchivierungAbgebrochen;
import ch.urinet.archiv.ablage.domain.DateiSchluessel;
import ch.urinet.archiv.ablage.domain.ManifestTeil;
import ch.urinet.archiv.ablage.domain.Pruefsumme;
import ch.urinet.archiv.ablage.domain.SperrAenderung;
import ch.urinet.archiv.ablage.domain.Sperrfrist;
import ch.urinet.archiv.ablage.domain.Sperrvorgabe;
import ch.urinet.archiv.ablage.domain.VorsignierteUrl;
import ch.urinet.archiv.ablage.domain.ZuArchivieren;
import ch.urinet.archiv.mandant.domain.MandantCode;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.ObjectLockMode;
import software.amazon.awssdk.services.s3.model.ObjectLockRetention;
import software.amazon.awssdk.services.s3.model.ObjectLockRetentionMode;
import software.amazon.awssdk.services.s3.model.ObjectVersion;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * S3-Adapter der Ablage (design.md «Ablage»; Spike 0.2: Ceph bei Infomaniak). Nutzt nur den S3-Client von Quarkus mit
 * dem App-Schlüssel ({@code s3_app_zugang_id}, {@code s3_app_zugang_geheimnis}). Setzt nie den Bypass-Header, verkürzt
 * nie eine Sperre und löscht unter {@code archiv/} nur eine eigene, fehlerhafte und ungesperrte Kopie (Req 6.5, 15.10).
 * Die SHA-256 entsteht immer durch erneutes Lesen über die Versions-ID: Infomaniak liefert {@code ChecksumCRC64NVME},
 * keinen {@code x-amz-checksum-sha256} (Spike 0.2, T 6a). Logs enthalten nur Schlüssel und Versions-IDs, nie
 * Inhalte oder signierte URLs.
 */
@ApplicationScoped
@Typed(S3Ablage.class)
public class S3Ablage implements AblagePort {

    private static final Logger LOG = Logger.getLogger(S3Ablage.class);
    private static final String ARCHIV = Ablageort.ARCHIV.praefix() + "/";

    private final S3Client s3;
    private final S3Presigner presigner;
    private final AblageEinstellungen einstellungen;
    private final Clock uhr;

    @Inject
    public S3Ablage(S3Client s3, AblageEinstellungen einstellungen,
                    @ConfigProperty(name = "quarkus.s3.path-style-access", defaultValue = "false") boolean pathStyle) {
        this(s3, presigner(s3, pathStyle), einstellungen, Clock.systemUTC());
    }

    public S3Ablage(S3Client s3, S3Presigner presigner, AblageEinstellungen einstellungen, Clock uhr) {
        this.s3 = Objects.requireNonNull(s3, "s3");
        this.presigner = Objects.requireNonNull(presigner, "presigner");
        this.einstellungen = Objects.requireNonNull(einstellungen, "einstellungen");
        this.uhr = Objects.requireNonNull(uhr, "uhr");
    }

    /** Presigner mit Endpoint, Region und Zugang des S3-Clients (die Erweiterung stellt keinen bereit). */
    public static S3Presigner presigner(S3Client s3, boolean pathStyle) {
        var konfiguration = s3.serviceClientConfiguration();
        var bauer = S3Presigner.builder()
                .region(konfiguration.region())
                .credentialsProvider(konfiguration.credentialsProvider())
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(pathStyle).build());
        konfiguration.endpointOverride().ifPresent(bauer::endpointOverride);
        return bauer.build();
    }

    @PreDestroy
    void schliessen() {
        presigner.close();
    }

    @Override
    public AbgelegteDatei ablegenEingang(MandantCode mandant, DateiSchluessel schluessel, InputStream inhalt,
                                         long laenge) {
        String bucket = bucket(mandant);
        String key = schluessel.pfad(Ablageort.EINGANG);
        Path temp = null;
        try {
            // zwischenspeichern: ein Retry des SDK braucht einen wiederholbaren Inhalt, die SHA-256 entsteht dabei
            temp = Files.createTempFile("ablage-", ".tmp");
            MessageDigest digest = Pruefsumme.sha256();
            long gelesen;
            try (InputStream ein = new DigestInputStream(inhalt, digest); OutputStream aus = Files.newOutputStream(temp)) {
                gelesen = ein.transferTo(aus);
            }
            if (laenge >= 0 && gelesen != laenge) {
                throw new IllegalArgumentException("Länge der Datei weicht ab: " + key);
            }
            String sha256 = Pruefsumme.hex(digest);
            Path datei = temp;
            String versionId = s3.putObject(b -> b.bucket(bucket).key(key), RequestBody.fromFile(datei)).versionId();
            pruefeVersion(versionId, bucket);
            LOG.debugf("Eingang abgelegt: %s (Version %s)", key, versionId);
            return new AbgelegteDatei(key, versionId, sha256, gelesen, Optional.empty());
        } catch (IOException e) {
            throw new UncheckedIOException("Datei konnte nicht zwischengespeichert werden: " + key, e);
        } finally {
            loeschenTemp(temp);
        }
    }

    @Override
    public ArchiviertesDokument archivieren(MandantCode mandant, List<ZuArchivieren> dateien, Sperrvorgabe vorgabe) {
        String bucket = bucket(mandant);
        Optional<Instant> bis = Sperrfrist.retainUntil(vorgabe, uhr.instant(), einstellungen.archivHoechstfrist());
        List<AbgelegteDatei> abgelegt = new ArrayList<>();
        for (ZuArchivieren datei : dateien) {
            AbgelegteDatei kopie = kopieUebernehmen(bucket, datei).orElseGet(() -> kopieren(bucket, datei));
            abgelegt.add(bis.map(b -> sperren(bucket, kopie, b)).orElse(kopie));
        }
        return new ArchiviertesDokument(abgelegt, bis);
    }

    @Override
    public List<SperrAenderung> sperreVerlaengern(MandantCode mandant, List<AbgelegteDatei> dateien,
                                                  Sperrvorgabe vorgabe) {
        String bucket = bucket(mandant);
        Instant jetzt = uhr.instant();
        Optional<Instant> soll = Sperrfrist.retainUntil(vorgabe, jetzt, einstellungen.archivHoechstfrist());
        List<SperrAenderung> aenderungen = new ArrayList<>();
        for (AbgelegteDatei datei : dateien) {
            if (!datei.schluessel().startsWith(ARCHIV) || datei.versionId() == null) {
                throw new IllegalArgumentException("Sperren nur für Versionen unter archiv/: " + datei.schluessel());
            }
            aenderungen.add(sperreAnpassen(bucket, datei, soll, jetzt));
        }
        return aenderungen;
    }

    @Override
    public void loeschenEingang(MandantCode mandant, int jahr, UUID docId) {
        String bucket = bucket(mandant);
        String ordner = DateiSchluessel.ordner(Ablageort.EINGANG, jahr, docId);
        List<String[]> versionen = new ArrayList<>();
        for (var seite : s3.listObjectVersionsPaginator(b -> b.bucket(bucket).prefix(ordner))) {
            seite.versions().forEach(v -> versionen.add(new String[]{v.key(), v.versionId()}));
            seite.deleteMarkers().forEach(m -> versionen.add(new String[]{m.key(), m.versionId()}));
        }
        for (String[] version : versionen) {
            if (!version[0].startsWith(ordner)) {
                throw new IllegalStateException("Schlüssel ausserhalb von " + ordner + ": " + version[0]);
            }
            s3.deleteObject(b -> b.bucket(bucket).key(version[0]).versionId(version[1]));
        }
        LOG.debugf("Eingang gelöscht: %s (%d Versionen)", ordner, versionen.size());
    }

    @Override
    public VorsignierteUrl vorsignierteUrl(MandantCode mandant, Ablageort ort, DateiSchluessel schluessel,
                                           String versionId) {
        if (versionId == null || versionId.isBlank()) {
            throw new IllegalArgumentException("Vorsignierte URL nur mit Versions-ID");
        }
        String bucket = bucket(mandant);
        String key = schluessel.pfad(ort);
        var signiert = presigner.presignGetObject(r -> r.signatureDuration(einstellungen.urlGueltigkeit())
                .getObjectRequest(g -> g.bucket(bucket).key(key).versionId(versionId)));
        try {
            // die URL ist für ihre Dauer ein Zugangsschlüssel: nie loggen
            return new VorsignierteUrl(signiert.url().toURI(), signiert.expiration());
        } catch (URISyntaxException e) {
            throw new IllegalStateException("Vorsignierte URL ungültig: " + key, e);
        }
    }

    @Override
    public AbgelegteDatei ablegenManifest(MandantCode mandant, LocalDate datum, ManifestTeil teil, byte[] inhalt,
                                          Optional<LocalDate> spaetestesAufbewahrenBis) {
        String bucket = bucket(mandant);
        String key = teil.pfad(datum);
        String sha256 = Pruefsumme.von(inhalt);
        for (ObjectVersion vorhanden : versionen(bucket, key)) {
            Lesung lesung = lesen(bucket, key, vorhanden.versionId());
            if (!lesung.sha256().equals(sha256)) {
                throw new IllegalStateException("Unter " + key + " liegt schon ein anderes Manifest");
            }
            return new AbgelegteDatei(key, vorhanden.versionId(), sha256, lesung.groesse(),
                    retention(bucket, key, vorhanden.versionId()).map(ObjectLockRetention::retainUntilDate));
        }
        Instant bis = Sperrfrist.manifest(spaetestesAufbewahrenBis, uhr.instant(), einstellungen.manifestHoechstfrist());
        ObjectLockMode modus = ObjectLockMode.fromValue(einstellungen.manifestSperrmodus().name());
        String versionId = s3.putObject(b -> b.bucket(bucket).key(key).objectLockMode(modus)
                .objectLockRetainUntilDate(bis), RequestBody.fromBytes(inhalt)).versionId();
        pruefeVersion(versionId, bucket);
        Optional<ObjectLockRetention> ist = retention(bucket, key, versionId);
        if (ist.isEmpty() || !ist.get().modeAsString().equals(modus.toString())
                || ist.get().retainUntilDate().isBefore(bis)) {
            throw new IllegalStateException("Sperre des Manifests nicht gesetzt: " + key);
        }
        LOG.debugf("Manifest abgelegt: %s (Version %s, %s)", key, versionId, modus);
        return new AbgelegteDatei(key, versionId, sha256, inhalt.length, Optional.of(ist.get().retainUntilDate()));
    }

    /** Neuer Versuch (Req 15.10): eine korrekte Kopie übernehmen, eine fehlerhafte und ungesperrte entfernen. */
    private Optional<AbgelegteDatei> kopieUebernehmen(String bucket, ZuArchivieren datei) {
        String ziel = datei.schluessel().pfad(Ablageort.ARCHIV);
        Optional<AbgelegteDatei> korrekt = Optional.empty();
        List<String> loeschmarken = new ArrayList<>();
        for (var seite : s3.listObjectVersionsPaginator(b -> b.bucket(bucket).prefix(ziel))) {
            seite.deleteMarkers().stream().filter(m -> m.key().equals(ziel)).forEach(m -> loeschmarken.add(m.versionId()));
        }
        for (ObjectVersion version : versionen(bucket, ziel)) {
            Lesung lesung = lesen(bucket, ziel, version.versionId());
            if (lesung.sha256().equals(datei.sha256()) && korrekt.isEmpty()) {
                korrekt = Optional.of(new AbgelegteDatei(ziel, version.versionId(), lesung.sha256(), lesung.groesse(),
                        Optional.empty()));
            } else if (gesperrt(bucket, ziel, version.versionId())) {
                // eine gesperrte fremde Version lässt sich nicht entfernen: nicht weiter archivieren
                throw new ArchivierungAbgebrochen(ArchivierungAbgebrochen.Grund.PRUEFSUMME, ziel);
            } else {
                versionLoeschen(bucket, ziel, version.versionId());
            }
        }
        for (String marke : loeschmarken) {
            versionLoeschen(bucket, ziel, marke);
        }
        korrekt.ifPresent(k -> LOG.debugf("Archiv-Kopie übernommen: %s (Version %s)", ziel, k.versionId()));
        return korrekt;
    }

    private AbgelegteDatei kopieren(String bucket, ZuArchivieren datei) {
        String quelle = datei.schluessel().pfad(Ablageort.EINGANG);
        String ziel = datei.schluessel().pfad(Ablageort.ARCHIV);
        String versionId = s3.copyObject(b -> b.sourceBucket(bucket).sourceKey(quelle)
                .sourceVersionId(datei.eingangVersionId()).destinationBucket(bucket).destinationKey(ziel)).versionId();
        pruefeVersion(versionId, bucket);
        Lesung lesung = lesen(bucket, ziel, versionId);
        if (!lesung.sha256().equals(datei.sha256())) {
            versionLoeschen(bucket, ziel, versionId);
            LOG.warnf("Prüfsumme der Archiv-Kopie weicht ab: %s (Version %s)", ziel, versionId);
            throw new ArchivierungAbgebrochen(ArchivierungAbgebrochen.Grund.PRUEFSUMME, ziel);
        }
        LOG.debugf("Archiv-Kopie erstellt: %s (Version %s)", ziel, versionId);
        return new AbgelegteDatei(ziel, versionId, lesung.sha256(), lesung.groesse(), Optional.empty());
    }

    /** Sperrt GOVERNANCE bis {@code bis}; eine längere Sperre bleibt, eine kürzere wird verlängert. */
    private AbgelegteDatei sperren(String bucket, AbgelegteDatei datei, Instant bis) {
        try {
            Optional<ObjectLockRetention> ist = retention(bucket, datei.schluessel(), datei.versionId());
            if (ist.isEmpty() || ist.get().retainUntilDate().isBefore(bis)) {
                setzen(bucket, datei, ist.map(ObjectLockRetention::mode).orElse(ObjectLockRetentionMode.GOVERNANCE), bis);
            }
            Optional<ObjectLockRetention> kontrolle = retention(bucket, datei.schluessel(), datei.versionId());
            if (kontrolle.isEmpty() || kontrolle.get().retainUntilDate().isBefore(bis)) {
                throw new ArchivierungAbgebrochen(ArchivierungAbgebrochen.Grund.SPERRE, datei.schluessel());
            }
            return new AbgelegteDatei(datei.schluessel(), datei.versionId(), datei.sha256(), datei.groesse(),
                    Optional.of(kontrolle.get().retainUntilDate()));
        } catch (S3Exception e) {
            if (e.statusCode() >= 400 && e.statusCode() < 500) {
                LOG.warnf("Sperre nicht gesetzt: %s (Version %s, HTTP %d)", datei.schluessel(), datei.versionId(),
                        e.statusCode());
                throw new ArchivierungAbgebrochen(ArchivierungAbgebrochen.Grund.SPERRE, datei.schluessel(), e);
            }
            throw e;
        }
    }

    private SperrAenderung sperreAnpassen(String bucket, AbgelegteDatei datei, Optional<Instant> soll, Instant jetzt) {
        Optional<ObjectLockRetention> ist = retention(bucket, datei.schluessel(), datei.versionId())
                .filter(r -> r.retainUntilDate() != null && r.retainUntilDate().isAfter(jetzt));
        Optional<Instant> istBis = ist.map(ObjectLockRetention::retainUntilDate);
        if (soll.isEmpty()) {
            return aenderung(datei, istBis.isPresent() ? SperrAenderung.Ergebnis.UNVERAENDERT
                    : SperrAenderung.Ergebnis.KEINE_SPERRE, istBis);
        }
        Instant bis = soll.get();
        if (ist.isPresent()) {
            int vergleich = istBis.get().truncatedTo(ChronoUnit.SECONDS).compareTo(bis);
            if (vergleich > 0) {
                // ein früheres Datum verkürzt nie (Req 9.7, 6.5)
                return aenderung(datei, SperrAenderung.Ergebnis.KONFLIKT, istBis);
            }
            if (vergleich == 0) {
                return aenderung(datei, SperrAenderung.Ergebnis.UNVERAENDERT, istBis);
            }
        }
        setzen(bucket, datei, ist.map(ObjectLockRetention::mode).orElse(ObjectLockRetentionMode.GOVERNANCE), bis);
        Optional<Instant> kontrolle = retention(bucket, datei.schluessel(), datei.versionId())
                .map(ObjectLockRetention::retainUntilDate);
        if (kontrolle.isEmpty() || kontrolle.get().isBefore(bis)) {
            throw new IllegalStateException("Sperre nicht gesetzt: " + datei.schluessel());
        }
        return aenderung(datei, ist.isPresent() ? SperrAenderung.Ergebnis.VERLAENGERT : SperrAenderung.Ergebnis.GESETZT,
                kontrolle);
    }

    private void setzen(String bucket, AbgelegteDatei datei, ObjectLockRetentionMode modus, Instant bis) {
        s3.putObjectRetention(b -> b.bucket(bucket).key(datei.schluessel()).versionId(datei.versionId())
                .retention(r -> r.mode(modus).retainUntilDate(bis)));
        LOG.debugf("Sperre gesetzt: %s (Version %s, %s)", datei.schluessel(), datei.versionId(), modus);
    }

    private static SperrAenderung aenderung(AbgelegteDatei datei, SperrAenderung.Ergebnis ergebnis,
                                            Optional<Instant> bis) {
        return new SperrAenderung(datei.schluessel(), datei.versionId(), ergebnis, bis);
    }

    private Optional<ObjectLockRetention> retention(String bucket, String key, String versionId) {
        try {
            return Optional.ofNullable(s3.getObjectRetention(b -> b.bucket(bucket).key(key).versionId(versionId))
                    .retention()).filter(r -> r.retainUntilDate() != null);
        } catch (S3Exception e) {
            // ohne Sperre: 404 (NoSuchObjectLockConfiguration) bzw. bei MinIO 400 (NoSuchObjectLockConfiguration)
            if (e.statusCode() == 404 || "NoSuchObjectLockConfiguration".equals(fehlercode(e))) {
                return Optional.empty();
            }
            throw e;
        }
    }

    private boolean gesperrt(String bucket, String key, String versionId) {
        return retention(bucket, key, versionId).map(r -> r.retainUntilDate().isAfter(uhr.instant())).orElse(false);
    }

    private static String fehlercode(S3Exception e) {
        return e.awsErrorDetails() == null ? null : e.awsErrorDetails().errorCode();
    }

    /** Versionen genau dieses Schlüssels, die neueste zuerst. */
    private List<ObjectVersion> versionen(String bucket, String key) {
        List<ObjectVersion> versionen = new ArrayList<>();
        for (var seite : s3.listObjectVersionsPaginator(b -> b.bucket(bucket).prefix(key))) {
            seite.versions().stream().filter(v -> v.key().equals(key)).forEach(versionen::add);
        }
        versionen.sort(Comparator.comparing(ObjectVersion::lastModified, Comparator.nullsLast(Comparator.reverseOrder())));
        return versionen;
    }

    private void versionLoeschen(String bucket, String key, String versionId) {
        s3.deleteObject(b -> b.bucket(bucket).key(key).versionId(versionId));
        LOG.debugf("Version gelöscht: %s (Version %s)", key, versionId);
    }

    private Lesung lesen(String bucket, String key, String versionId) {
        MessageDigest digest = Pruefsumme.sha256();
        long groesse;
        try (InputStream ein = new DigestInputStream(s3.getObject(b -> b.bucket(bucket).key(key).versionId(versionId)),
                digest)) {
            groesse = ein.transferTo(OutputStream.nullOutputStream());
        } catch (IOException e) {
            throw SdkClientException.create("Lesen fehlgeschlagen: " + key, e);
        }
        return new Lesung(Pruefsumme.hex(digest), groesse);
    }

    private String bucket(MandantCode mandant) {
        return mandant.bucket(einstellungen.bucketPraefix());
    }

    private static void pruefeVersion(String versionId, String bucket) {
        if (versionId == null || versionId.isBlank() || "null".equals(versionId)) {
            throw new IllegalStateException("Bucket " + bucket + " ohne Versionierung");
        }
    }

    private static void loeschenTemp(Path temp) {
        if (temp == null) {
            return;
        }
        try {
            Files.deleteIfExists(temp);
        } catch (IOException e) {
            LOG.warnf("Zwischendatei nicht gelöscht: %s", temp.getFileName());
        }
    }

    private record Lesung(String sha256, long groesse) {
    }
}
