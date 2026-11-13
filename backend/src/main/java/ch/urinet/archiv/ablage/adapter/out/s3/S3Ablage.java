package ch.urinet.archiv.ablage.adapter.out.s3;

import ch.urinet.archiv.ablage.adapter.out.AblageEinstellungen;
import ch.urinet.archiv.ablage.domain.AblageGestoert;
import ch.urinet.archiv.ablage.application.AblagePort;
import ch.urinet.archiv.ablage.domain.AbgelegteDatei;
import ch.urinet.archiv.ablage.domain.Ablageort;
import ch.urinet.archiv.ablage.domain.ArchiviertesDokument;
import ch.urinet.archiv.ablage.domain.ArchivierungAbgebrochen;
import ch.urinet.archiv.ablage.application.Exportziel;
import ch.urinet.archiv.ablage.domain.DateiSchluessel;
import ch.urinet.archiv.ablage.domain.Dateipruefung;
import ch.urinet.archiv.ablage.domain.Exportdatei;
import ch.urinet.archiv.ablage.domain.Kopie;
import ch.urinet.archiv.ablage.domain.ManifestDatei;
import ch.urinet.archiv.ablage.domain.ManifestTeil;
import ch.urinet.archiv.ablage.domain.Objektversion;
import ch.urinet.archiv.ablage.domain.Pruefsumme;
import ch.urinet.archiv.ablage.domain.SperrAenderung;
import ch.urinet.archiv.ablage.domain.Sperrfrist;
import ch.urinet.archiv.ablage.domain.Sperrvorgabe;
import ch.urinet.archiv.ablage.domain.Versionsseite;
import ch.urinet.archiv.ablage.domain.VorsignierteUrl;
import ch.urinet.archiv.ablage.domain.Importdatei;
import ch.urinet.archiv.ablage.domain.Vorschau;
import ch.urinet.archiv.ablage.domain.ZuArchivieren;
import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.gemeinsam.domain.Ursachen;
import ch.urinet.archiv.mandant.domain.MandantCode;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import software.amazon.awssdk.core.exception.AbortedException;
import software.amazon.awssdk.core.exception.ApiCallAttemptTimeoutException;
import software.amazon.awssdk.core.exception.ApiCallTimeoutException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CopyObjectResponse;
import software.amazon.awssdk.services.s3.model.ExpirationStatus;
import software.amazon.awssdk.services.s3.model.LifecycleRule;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsResponse;
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
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * S3-Adapter der Ablage (design.md «Ablage»; Spike 0.2: Ceph bei Infomaniak). Nutzt nur den S3-Client von Quarkus mit
 * dem App-Schlüssel ({@code s3_app_zugang_id}, {@code s3_app_zugang_geheimnis}). Setzt nie den Bypass-Header, verkürzt
 * nie eine Sperre und löscht unter {@code archiv/} nur eine eigene, fehlerhafte und ungesperrte Kopie (Req 6.5, 15.10).
 * Die SHA-256 entsteht immer durch erneutes Lesen über die Versions-ID: Infomaniak liefert {@code ChecksumCRC64NVME},
 * keine SHA-256-Prüfsumme (Spike 0.2, T 6a). Logs enthalten nur Schlüssel und Versions-IDs, nie Inhalte oder
 * signierte URLs. Der S3-Client muss {@link BypassVerbot} tragen, sonst scheitert das Anlegen der Ablage.
 */
@ApplicationScoped
@Typed(S3Ablage.class)
public class S3Ablage implements AblagePort {

    private static final Logger LOG = Logger.getLogger(S3Ablage.class);
    private static final String ARCHIV = Ablageort.ARCHIV.praefix() + "/";
    /**
     * «Keine Sperre» bei {@code GetObjectRetention}: {@code ObjectLockConfigurationNotFoundError} (404) aus dem
     * Ceph-Quelltext ({@code rgw_common.cc}, nicht live gegen Infomaniak geprüft), {@code NoSuchObjectLockConfiguration}
     * (AWS, MinIO mit 400). Jeder andere Fehler, auch {@code NoSuchKey} oder {@code NoSuchVersion}, ist ein Fehler.
     */
    public static final Set<String> KEINE_SPERRE = Set.of("NoSuchObjectLockConfiguration",
            "ObjectLockConfigurationNotFoundError");
    private static final Set<String> FEHLT = Set.of("NoSuchKey", "NoSuchVersion");
    private static final Set<String> ZEITUEBERSCHREITUNG = Set.of("RequestTimeout", "RequestTimeoutException");
    private static final String MANIFEST = "manifest/";
    private static final Set<String> PRUEFBEREICHE = Set.of(ARCHIV, MANIFEST);
    static final String EXPORT_REGEL = "exporte-7-tage";
    private static final Duration MAX_SIGNATUR = Duration.ofDays(7);
    private static final Pattern EXPORT_NAME = Pattern.compile("^[A-Za-z0-9._-]{1,100}\\.zip$");

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
        this.s3 = BypassVerbot.pruefen(Objects.requireNonNull(s3, "s3"));
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
            Files.createDirectories(einstellungen.zwischenablage());
            temp = Files.createTempFile(einstellungen.zwischenablage(), "ablage-", ".tmp");
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
            String versionId = hochladen(bucket, key, datei);
            LOG.debugf("Eingang abgelegt: %s (Version %s)", key, versionId);
            return new AbgelegteDatei(key, versionId, sha256, gelesen, Optional.empty());
        } catch (IOException e) {
            throw new UncheckedIOException("Datei konnte nicht zwischengespeichert werden: " + key, e);
        } finally {
            loeschenTemp(temp);
        }
    }

    /** Die Datei liegt schon auf der Platte (Upload): hashen und ohne weitere Kopie hochladen. */
    @Override
    public AbgelegteDatei ablegenEingang(MandantCode mandant, DateiSchluessel schluessel, Path datei) {
        String bucket = bucket(mandant);
        String key = schluessel.pfad(Ablageort.EINGANG);
        MessageDigest digest = Pruefsumme.sha256();
        long gelesen;
        try (InputStream ein = new DigestInputStream(Files.newInputStream(datei), digest)) {
            gelesen = ein.transferTo(OutputStream.nullOutputStream());
        } catch (IOException e) {
            throw new UncheckedIOException("Datei konnte nicht gelesen werden: " + key, e);
        }
        String versionId = hochladen(bucket, key, datei);
        LOG.debugf("Eingang abgelegt: %s (Version %s)", key, versionId);
        return new AbgelegteDatei(key, versionId, Pruefsumme.hex(digest), gelesen, Optional.empty());
    }

    /**
     * Ein nicht erreichbarer Object Storage (Netz, Zeitlimit) oder eine Antwort 5xx ergibt
     * {@code SPEICHER_NICHT_ERREICHBAR} (503 mit {@code Retry-After}, design.md «Error Handling»); 4xx bleibt ein Fehler
     * der Anwendung oder Konfiguration.
     */
    private String hochladen(String bucket, String key, Path datei) {
        String versionId;
        try {
            versionId = s3.putObject(b -> b.bucket(bucket).key(key), RequestBody.fromFile(datei)).versionId();
        } catch (SdkClientException e) {
            LOG.warnf("Object Storage nicht erreichbar: %s (%s)", key, e.getClass().getSimpleName());
            throw new FachFehler(Fehlercode.SPEICHER_NICHT_ERREICHBAR);
        } catch (S3Exception e) {
            if (e.statusCode() >= 500) {
                LOG.warnf("Object Storage antwortet mit HTTP %d: %s", e.statusCode(), key);
                throw new FachFehler(Fehlercode.SPEICHER_NICHT_ERREICHBAR);
            }
            throw e;
        }
        pruefeVersion(versionId, bucket);
        return versionId;
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
        return ArchiviertesDokument.aus(abgelegt);
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
        Instant bis = Sperrfrist.manifest(spaetestesAufbewahrenBis, uhr.instant(), einstellungen.manifestHoechstfrist());
        ObjectLockMode modus = ObjectLockMode.fromValue(einstellungen.manifestSperrmodus().name());
        Manifestversionen bisher = manifestVersionen(bucket, key);
        if (bisher.weitere() > 0) {
            throw new IllegalStateException("Unter " + key + " liegen weitere Versionen oder Löschmarken");
        }
        for (ObjectVersion vorhanden : bisher.versionen()) {
            Lesung lesung = lesen(bucket, key, vorhanden.versionId());
            if (!lesung.sha256().equals(sha256)) {
                throw new IllegalStateException("Unter " + key + " liegt schon ein anderes Manifest");
            }
            return vorhandenesManifest(bucket, new AbgelegteDatei(key, vorhanden.versionId(), sha256, lesung.groesse(),
                    Optional.empty()), modus, bis);
        }
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

    @Override
    public Optional<ManifestDatei> lesenManifest(MandantCode mandant, LocalDate datum, ManifestTeil teil) {
        String bucket = bucket(mandant);
        String key = teil.pfad(datum);
        Manifestversionen vorhanden = manifestVersionen(bucket, key);
        if (vorhanden.versionen().isEmpty()) {
            return Optional.empty();
        }
        String versionId = vorhanden.versionen().getFirst().versionId();
        byte[] inhalt;
        try (InputStream ein = s3.getObject(b -> b.bucket(bucket).key(key).versionId(versionId))) {
            inhalt = ein.readNBytes(MANIFEST_MAX_GROESSE + 1);
        } catch (IOException e) {
            throw SdkClientException.create("Lesen fehlgeschlagen: " + key, e);
        }
        if (inhalt.length > MANIFEST_MAX_GROESSE) {
            throw new IllegalStateException("Manifest-Teil zu gross: " + key);
        }
        Optional<Instant> sperre = retention(bucket, key, versionId).map(ObjectLockRetention::retainUntilDate);
        return Optional.of(new ManifestDatei(inhalt, new AbgelegteDatei(key, versionId, Pruefsumme.von(inhalt),
                inhalt.length, sperre), vorhanden.weitere()));
    }

    /** Versionen eines Manifest-Schlüssels, älteste zuerst, und die Zahl weiterer Versionen samt Löschmarken. */
    private Manifestversionen manifestVersionen(String bucket, String key) {
        List<ObjectVersion> versionen = new ArrayList<>();
        int loeschmarken = 0;
        for (var seite : s3.listObjectVersionsPaginator(b -> b.bucket(bucket).prefix(key))) {
            seite.versions().stream().filter(v -> v.key().equals(key)).forEach(versionen::add);
            loeschmarken += (int) seite.deleteMarkers().stream().filter(m -> m.key().equals(key)).count();
        }
        List<ObjectVersion> sortiert = aeltesteZuerst(key, versionen);
        int weitere = Math.max(0, sortiert.size() - 1) + loeschmarken;
        return new Manifestversionen(sortiert, weitere);
    }

    /**
     * Sortiert die Versionen nach {@code lastModified}, älteste zuerst. Ist die älteste nicht eindeutig (gleicher
     * Zeitpunkt wie die nächste oder ohne Zeitpunkt), bricht es ab, statt eine zufällig zuerst gelistete zu wählen:
     * S3 nennt {@code lastModified} nur auf die Sekunde genau, und die Reihenfolge der Liste ist kein Beleg.
     */
    static List<ObjectVersion> aeltesteZuerst(String key, List<ObjectVersion> versionen) {
        List<ObjectVersion> sortiert = new ArrayList<>(versionen);
        if (sortiert.size() < 2) {
            return sortiert;
        }
        if (sortiert.stream().anyMatch(v -> v.lastModified() == null)) {
            throw new IllegalStateException("Unter " + key + " liegen mehrere Versionen, nicht alle mit Zeitpunkt; "
                    + "die älteste ist nicht eindeutig");
        }
        sortiert.sort(Comparator.comparing(ObjectVersion::lastModified));
        if (sortiert.get(0).lastModified().equals(sortiert.get(1).lastModified())) {
            throw new IllegalStateException("Unter " + key + " liegen mehrere Versionen mit gleichem Zeitpunkt; "
                    + "die älteste ist nicht eindeutig");
        }
        return sortiert;
    }

    private record Manifestversionen(List<ObjectVersion> versionen, int weitere) {
    }

    /**
     * Ein vorhandenes Manifest gilt nur als abgelegt, wenn es im verlangten Modus gesperrt ist; eine kürzere Sperre
     * wird auf {@code bis} verlängert (nie verkürzt), eine fehlende oder eine in anderem Modus bricht ab.
     */
    private AbgelegteDatei vorhandenesManifest(String bucket, AbgelegteDatei datei, ObjectLockMode modus, Instant bis) {
        Optional<ObjectLockRetention> ist = retention(bucket, datei.schluessel(), datei.versionId());
        if (ist.isEmpty() || !ist.get().modeAsString().equals(modus.toString())) {
            throw new IllegalStateException("Sperre des vorhandenen Manifests fehlt oder weicht ab: " + datei.schluessel());
        }
        if (ist.get().retainUntilDate().isBefore(bis)) {
            setzen(bucket, datei, ist.get().mode(), bis);
            ist = retention(bucket, datei.schluessel(), datei.versionId());
            if (ist.isEmpty() || !ist.get().modeAsString().equals(modus.toString())
                    || ist.get().retainUntilDate().isBefore(bis)) {
                throw new IllegalStateException("Sperre des Manifests nicht gesetzt: " + datei.schluessel());
            }
        }
        return new AbgelegteDatei(datei.schluessel(), datei.versionId(), datei.sha256(), datei.groesse(),
                Optional.of(ist.get().retainUntilDate()));
    }

    /**
     * Neuer Versuch (Req 15.10): eine korrekte Kopie übernehmen, eine fehlerhafte und ungesperrte entfernen. Gelöscht
     * wird erst, wenn die Quelle unter {@code eingang/} über ihre Versions-ID lesbar ist und ihre SHA-256 stimmt; sonst
     * wäre die Kopie unter {@code archiv/} womöglich die einzige. Liegen mehrere korrekte Kopien vor, gilt die mit der
     * längsten Sperre (sonst die neueste); weitere korrekte, ungesperrte Kopien werden entfernt, gesperrte bleiben.
     * Die übernommene Kopie trägt ihre bestehende Sperre.
     */
    private Optional<AbgelegteDatei> kopieUebernehmen(String bucket, ZuArchivieren datei) {
        String ziel = datei.schluessel().pfad(Ablageort.ARCHIV);
        List<String> loeschmarken = new ArrayList<>();
        for (var seite : s3.listObjectVersionsPaginator(b -> b.bucket(bucket).prefix(ziel))) {
            seite.deleteMarkers().stream().filter(m -> m.key().equals(ziel)).forEach(m -> loeschmarken.add(m.versionId()));
        }
        List<AbgelegteDatei> korrekte = new ArrayList<>();
        List<String> fehlerhaft = new ArrayList<>();
        for (ObjectVersion version : versionen(bucket, ziel)) {
            Lesung lesung = lesen(bucket, ziel, version.versionId());
            Optional<Instant> sperre = aktiveSperre(bucket, ziel, version.versionId());
            if (lesung.sha256().equals(datei.sha256())) {
                korrekte.add(new AbgelegteDatei(ziel, version.versionId(), lesung.sha256(), lesung.groesse(), sperre));
            } else if (sperre.isPresent()) {
                // eine gesperrte fremde Version lässt sich nicht entfernen: nicht weiter archivieren
                throw new ArchivierungAbgebrochen(ArchivierungAbgebrochen.Grund.PRUEFSUMME, ziel);
            } else {
                fehlerhaft.add(version.versionId());
            }
        }
        if (!fehlerhaft.isEmpty()) {
            quellePruefen(bucket, datei);
        }
        // bei gleicher Sperre gewinnt die erste, also die neueste Version
        Optional<AbgelegteDatei> korrekt = korrekte.stream()
                .max(Comparator.comparing(k -> k.sperreBis().orElse(Instant.MIN)));
        for (String versionId : fehlerhaft) {
            versionLoeschen(bucket, ziel, versionId);
        }
        for (AbgelegteDatei doppelt : korrekte) {
            if (doppelt == korrekt.orElseThrow()) {
                continue;
            }
            if (doppelt.sperreBis().isEmpty()) {
                // gleicher Inhalt wie die übernommene Kopie: Entfernen verliert nichts
                versionLoeschen(bucket, ziel, doppelt.versionId());
            } else {
                LOG.warnf("Gesperrte zweite Archiv-Kopie bleibt: %s (Version %s)", ziel, doppelt.versionId());
            }
        }
        for (String marke : loeschmarken) {
            versionLoeschen(bucket, ziel, marke);
        }
        korrekt.ifPresent(k -> LOG.debugf("Archiv-Kopie übernommen: %s (Version %s)", ziel, k.versionId()));
        return korrekt;
    }

    /** Liest die Quelle über ihre Versions-ID; fehlt sie oder weicht ihre SHA-256 ab, bricht die Archivierung ab. */
    private void quellePruefen(String bucket, ZuArchivieren datei) {
        String quelle = datei.schluessel().pfad(Ablageort.EINGANG);
        String ziel = datei.schluessel().pfad(Ablageort.ARCHIV);
        Lesung lesung;
        try {
            lesung = lesen(bucket, quelle, datei.eingangVersionId());
        } catch (S3Exception e) {
            throw quelleFehlt(e, quelle, datei.eingangVersionId(), ziel);
        }
        if (!lesung.sha256().equals(datei.sha256())) {
            LOG.warnf("Prüfsumme der Quelle weicht ab: %s (Version %s)", quelle, datei.eingangVersionId());
            throw new ArchivierungAbgebrochen(ArchivierungAbgebrochen.Grund.PRUEFSUMME, ziel);
        }
    }

    private static RuntimeException quelleFehlt(S3Exception e, String quelle, String versionId, String ziel) {
        if (!FEHLT.contains(fehlercode(e))) {
            return e;
        }
        LOG.warnf("Quelle fehlt: %s (Version %s)", quelle, versionId);
        return new ArchivierungAbgebrochen(ArchivierungAbgebrochen.Grund.PRUEFSUMME, ziel, e);
    }

    private AbgelegteDatei kopieren(String bucket, ZuArchivieren datei) {
        String quelle = datei.schluessel().pfad(Ablageort.EINGANG);
        String ziel = datei.schluessel().pfad(Ablageort.ARCHIV);
        CopyObjectResponse antwort;
        try {
            antwort = s3.copyObject(b -> b.sourceBucket(bucket).sourceKey(quelle)
                    .sourceVersionId(datei.eingangVersionId()).destinationBucket(bucket).destinationKey(ziel));
        } catch (S3Exception e) {
            throw quelleFehlt(e, quelle, datei.eingangVersionId(), ziel);
        }
        String versionId = ohneVersion(antwort.versionId()) ? versionDerKopie(bucket, ziel, antwort) : antwort.versionId();
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
            if (KEINE_SPERRE.contains(fehlercode(e))) {
                return Optional.empty();
            }
            throw e;
        }
    }

    private Optional<Instant> aktiveSperre(String bucket, String key, String versionId) {
        return retention(bucket, key, versionId).map(ObjectLockRetention::retainUntilDate)
                .filter(bis -> bis.isAfter(uhr.instant()));
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

    @Override
    public void lesenEingang(MandantCode mandant, DateiSchluessel schluessel, String versionId, String sha256,
                             Path ziel) {
        if (versionId == null || versionId.isBlank()) {
            throw new IllegalArgumentException("Lesen aus dem Eingang nur mit Versions-ID");
        }
        String bucket = bucket(mandant);
        String key = schluessel.pfad(Ablageort.EINGANG);
        // ganz in eine Datei innerhalb des Aufrufs: apiCallTimeout begrenzt so auch das Lesen des Inhalts
        s3.getObject(b -> b.bucket(bucket).key(key).versionId(versionId), ResponseTransformer.toFile(ziel));
        String gelesen = pruefsumme(ziel, key);
        if (!gelesen.equals(sha256)) {
            loeschenTemp(ziel);
            throw new IllegalStateException("Prüfsumme weicht ab: " + key + " (Version " + versionId + ")");
        }
        LOG.debugf("Eingang gelesen: %s (Version %s)", key, versionId);
    }

    @Override
    public AbgelegteDatei ablegenVorschau(MandantCode mandant, UUID docId, int seite, Path datei) {
        String bucket = bucket(mandant);
        String key = Vorschau.schluessel(docId, seite);
        String sha256 = pruefsumme(datei, key);
        long groesse;
        try {
            groesse = Files.size(datei);
        } catch (IOException e) {
            throw new UncheckedIOException("Vorschau nicht lesbar: " + key, e);
        }
        String versionId = s3.putObject(b -> b.bucket(bucket).key(key).contentType("image/png"),
                RequestBody.fromFile(datei)).versionId();
        pruefeVersion(versionId, bucket);
        LOG.debugf("Vorschau abgelegt: %s (Version %s)", key, versionId);
        return new AbgelegteDatei(key, versionId, sha256, groesse, Optional.empty());
    }

    @Override
    public Optional<InputStream> lesenVorschau(MandantCode mandant, UUID docId, int seite) {
        String bucket = bucket(mandant);
        String key = Vorschau.schluessel(docId, seite);
        try {
            return Optional.of(s3.getObject(b -> b.bucket(bucket).key(key)));
        } catch (S3Exception e) {
            if (FEHLT.contains(fehlercode(e))) {
                return Optional.empty();
            }
            throw e;
        }
    }

    @Override
    public AbgelegteDatei ablegenImport(MandantCode mandant, UUID laufId, Path datei) {
        String bucket = bucket(mandant);
        String key = Importdatei.schluessel(laufId);
        String sha256 = pruefsumme(datei, key);
        long groesse;
        try {
            groesse = Files.size(datei);
        } catch (IOException e) {
            throw new UncheckedIOException("Importdatei nicht lesbar: " + key, e);
        }
        String versionId = hochladen(bucket, key, datei);
        LOG.debugf("Importdatei abgelegt: %s (Version %s)", key, versionId);
        return new AbgelegteDatei(key, versionId, sha256, groesse, Optional.empty());
    }

    @Override
    public int abgeleiteteBereinigen(MandantCode mandant, int jahr, UUID docId, Collection<AbgelegteDatei> behalten,
                                     Runnable vorDemLoeschen) {
        String bucket = bucket(mandant);
        String pdfa = DateiSchluessel.pdfa(jahr, docId).pfad(Ablageort.EINGANG);
        Set<String> bleiben = new HashSet<>();
        behalten.forEach(d -> bleiben.add(d.schluessel() + "\n" + d.versionId()));
        List<String[]> loeschen = new ArrayList<>();
        for (String praefix : List.of(pdfa, Vorschau.ordner(docId))) {
            for (var seite : s3.listObjectVersionsPaginator(b -> b.bucket(bucket).prefix(praefix))) {
                seite.versions().forEach(v -> loeschen.add(new String[]{v.key(), v.versionId()}));
                seite.deleteMarkers().forEach(m -> loeschen.add(new String[]{m.key(), m.versionId()}));
            }
        }
        loeschen.removeIf(version -> !(version[0].equals(pdfa) || version[0].startsWith(Vorschau.ordner(docId)))
                || bleiben.contains(version[0] + "\n" + version[1]));
        if (!loeschen.isEmpty()) {
            vorDemLoeschen.run();
        }
        for (String[] version : loeschen) {
            versionLoeschen(bucket, version[0], version[1]);
        }
        int anzahl = loeschen.size();
        LOG.debugf("Abgeleitete Dateien bereinigt: %s (%d Versionen)", docId, anzahl);
        return anzahl;
    }

    @Override
    public Optional<Dateipruefung> pruefen(MandantCode mandant, String schluessel, String versionId) {
        bereichPruefen(schluessel, versionId);
        return pruefzugriff(schluessel, () -> pruefenOhneUebersetzung(mandant, schluessel, versionId));
    }

    private Optional<Dateipruefung> pruefenOhneUebersetzung(MandantCode mandant, String schluessel, String versionId) {
        String bucket = bucket(mandant);
        Lesung lesung;
        try {
            lesung = lesen(bucket, schluessel, versionId);
        } catch (S3Exception e) {
            // eine Löschmarke liefert bei GET mit Versions-ID 405 (MethodNotAllowed)
            if (FEHLT.contains(fehlercode(e)) || e.statusCode() == 405) {
                return Optional.empty();
            }
            throw e;
        }
        Optional<Instant> sperre = retention(bucket, schluessel, versionId).map(ObjectLockRetention::retainUntilDate);
        return Optional.of(new Dateipruefung(lesung.sha256(), lesung.groesse(), sperre));
    }

    @Override
    public Versionsseite versionen(MandantCode mandant, String praefix, Optional<Versionsseite.Marke> ab,
                                   int anzahl) {
        if (!PRUEFBEREICHE.contains(praefix)) {
            throw new IllegalArgumentException("Versionsliste nur unter archiv/ oder manifest/: " + praefix);
        }
        if (anzahl < 1 || anzahl > 1000) {
            throw new IllegalArgumentException("anzahl zwischen 1 und 1000");
        }
        String bucket = bucket(mandant);
        // immer key-marker und version-id-marker zusammen; nur mit key-marker fielen Versionen des Schlüssels weg
        Optional<String> versionIdMarker = ab.map(m -> m.versionIdZumFortsetzen(praefix));
        ListObjectVersionsResponse antwort = pruefzugriff(praefix, () -> s3.listObjectVersions(b -> {
            b.bucket(bucket).prefix(praefix).maxKeys(anzahl);
            ab.ifPresent(m -> b.keyMarker(m.schluessel()).versionIdMarker(versionIdMarker.orElseThrow()));
        }));
        List<Objektversion> versionen = new ArrayList<>();
        antwort.versions().forEach(v -> versionen.add(new Objektversion(v.key(), v.versionId(), false)));
        antwort.deleteMarkers().forEach(m -> versionen.add(new Objektversion(m.key(), m.versionId(), true)));
        versionen.sort(Comparator.comparing(Objektversion::schluessel));
        return new Versionsseite(versionen, Versionsseite.weiter(praefix, Boolean.TRUE.equals(antwort.isTruncated()),
                antwort.nextKeyMarker(), antwort.nextVersionIdMarker()));
    }

    @Override
    public SperrAenderung sperreVerlaengernBis(MandantCode mandant, String schluessel, String versionId,
                                               Instant bis) {
        bereichPruefen(schluessel, versionId);
        return pruefzugriff(schluessel, () -> verlaengern(mandant, schluessel, versionId, bis));
    }

    private SperrAenderung verlaengern(MandantCode mandant, String schluessel, String versionId, Instant bis) {
        String bucket = bucket(mandant);
        Instant jetzt = uhr.instant();
        Optional<Duration> hoechstfrist = schluessel.startsWith(ARCHIV)
                ? einstellungen.archivHoechstfrist() : einstellungen.manifestHoechstfrist();
        Instant gekappt = hoechstfrist.map(jetzt::plus).filter(grenze -> grenze.isBefore(bis)).orElse(bis)
                .truncatedTo(ChronoUnit.SECONDS);
        Optional<ObjectLockRetention> ist = retention(bucket, schluessel, versionId)
                .filter(r -> r.retainUntilDate().isAfter(jetzt));
        AbgelegteDatei datei = new AbgelegteDatei(schluessel, versionId, null, 0, Optional.empty());
        if (ist.isEmpty()) {
            return aenderung(datei, SperrAenderung.Ergebnis.KEINE_SPERRE, Optional.empty());
        }
        Instant istBis = ist.get().retainUntilDate();
        if (!gekappt.isAfter(istBis)) {
            return aenderung(datei, SperrAenderung.Ergebnis.UNVERAENDERT, Optional.of(istBis));
        }
        setzen(bucket, datei, ist.get().mode(), gekappt);
        Optional<ObjectLockRetention> kontrolle = retention(bucket, schluessel, versionId);
        if (kontrolle.isEmpty() || kontrolle.get().mode() != ist.get().mode()
                || kontrolle.get().retainUntilDate().isBefore(gekappt)) {
            throw new IllegalStateException("Sperre nicht verlängert: " + schluessel);
        }
        return aenderung(datei, SperrAenderung.Ergebnis.VERLAENGERT, Optional.of(kontrolle.get().retainUntilDate()));
    }

    @Override
    public Kopie kopieren(MandantCode mandant, String schluessel, String versionId, OutputStream ziel) {
        bereichPruefen(schluessel, versionId);
        Objects.requireNonNull(ziel, "ziel");
        String bucket = bucket(mandant);
        boolean[] begonnen = {false};
        // ein Retry des SDK riefe den Transformer erneut auf; die Bytes stünden dann doppelt im Ziel
        return s3.getObject(b -> b.bucket(bucket).key(schluessel).versionId(versionId), (antwort, inhalt) -> {
            if (begonnen[0]) {
                throw new IllegalStateException("Kopie nach Teilabbruch nicht wiederholbar: " + schluessel);
            }
            begonnen[0] = true;
            MessageDigest digest = Pruefsumme.sha256();
            long groesse;
            try (InputStream ein = new DigestInputStream(inhalt, digest)) {
                groesse = ein.transferTo(ziel);
            }
            return new Kopie(Pruefsumme.hex(digest), groesse);
        });
    }

    @Override
    public Exportziel exportSchreiben(MandantCode mandant, UUID exportId) {
        return new S3Exportziel(s3, bucket(mandant), Exportdatei.schluessel(exportId), einstellungen.exportTeil());
    }

    @Override
    public VorsignierteUrl exportUrl(MandantCode mandant, String schluessel, String versionId, Instant gueltigBis,
                                     String dateiname) {
        if (schluessel == null || !schluessel.startsWith(Exportdatei.PRAEFIX) || ohneVersion(versionId)) {
            throw new IllegalArgumentException("Nur Versionen unter exporte/: " + schluessel);
        }
        if (dateiname == null || !EXPORT_NAME.matcher(dateiname).matches()) {
            throw new IllegalArgumentException("Ungültiger Name des Exports");
        }
        Duration dauer = Duration.between(uhr.instant(), gueltigBis);
        if (dauer.compareTo(Duration.ofSeconds(1)) < 0 || dauer.compareTo(MAX_SIGNATUR) > 0) {
            throw new IllegalArgumentException("Gültigkeit der Export-Adresse ausserhalb 1 s bis 7 Tage");
        }
        String bucket = bucket(mandant);
        var signiert = presigner.presignGetObject(r -> r.signatureDuration(dauer)
                .getObjectRequest(g -> g.bucket(bucket).key(schluessel).versionId(versionId)
                        .responseContentType("application/zip")
                        .responseContentDisposition("attachment; filename=\"" + dateiname + "\"")));
        try {
            // die URL ist für ihre Dauer ein Zugangsschlüssel: nie loggen
            return new VorsignierteUrl(signiert.url().toURI(), signiert.expiration());
        } catch (URISyntaxException e) {
            throw new IllegalStateException("Vorsignierte URL ungültig: " + schluessel, e);
        }
    }

    /**
     * Regel {@value #EXPORT_REGEL}: aktuelle Version nach 7 Tagen ablaufen lassen (im versionierten Bucket wird sie zur
     * älteren Version), ältere Versionen nach einem weiteren Tag löschen, unvollständige Uploads nach einem Tag.
     * Liest die bestehende Konfiguration und ersetzt nur diese Regel, weil {@code PutBucketLifecycleConfiguration} die
     * ganze Konfiguration ersetzt. Sperren berührt die Regel nicht ({@code exporte/} wird nie gesperrt).
     */
    @Override
    public void exportLebenszyklusSichern(MandantCode mandant) {
        String bucket = bucket(mandant);
        List<LifecycleRule> bisher;
        try {
            bisher = s3.getBucketLifecycleConfiguration(b -> b.bucket(bucket)).rules();
        } catch (S3Exception e) {
            if (!"NoSuchLifecycleConfiguration".equals(fehlercode(e))) {
                throw e;
            }
            bisher = List.of();
        }
        LifecycleRule soll = exportRegel();
        if (bisher.contains(soll)) {
            return;
        }
        List<LifecycleRule> regeln = new ArrayList<>(bisher.stream().filter(r -> !EXPORT_REGEL.equals(r.id())).toList());
        regeln.add(soll);
        s3.putBucketLifecycleConfiguration(b -> b.bucket(bucket).lifecycleConfiguration(l -> l.rules(regeln)));
        LOG.infof("Lebenszyklus für %s in %s gesetzt", Exportdatei.PRAEFIX, bucket);
    }

    static LifecycleRule exportRegel() {
        return LifecycleRule.builder().id(EXPORT_REGEL).status(ExpirationStatus.ENABLED)
                .filter(f -> f.prefix(Exportdatei.PRAEFIX))
                .expiration(e -> e.days(Exportdatei.LEBENSZYKLUS_TAGE))
                .noncurrentVersionExpiration(n -> n.noncurrentDays(1))
                .abortIncompleteMultipartUpload(a -> a.daysAfterInitiation(1))
                .build();
    }

    /**
     * Fehler beim Prüflauf übersetzen (Req 15.5): Eine Unterbrechung (Zeitgrenze, Herunterfahren) bleibt eine
     * Unterbrechung, auch wenn das SDK das Interrupt-Flag gelöscht hat ({@link AbortedException}); das Flag wird neu
     * gesetzt. Netz- und Lesefehler, Zeitüberschreitungen des SDK und des Servers (408, {@code RequestTimeout}), 5xx
     * und 429 sind vorübergehend ({@link AblageGestoert}), der Prüflauf wiederholt dann den Teil. Alles andere bleibt unverändert.
     */
    static <T> T pruefzugriff(String schluessel, Supplier<T> aufruf) {
        try {
            return aufruf.get();
        } catch (RuntimeException e) {
            // der eigene Timer des SDK unterbricht ebenfalls; das ist eine Zeitüberschreitung, kein Abbruch
            boolean zeitueberschreitung = Ursachen.enthaelt(e, ApiCallTimeoutException.class)
                    || Ursachen.enthaelt(e, ApiCallAttemptTimeoutException.class);
            if (!zeitueberschreitung && (Ursachen.enthaelt(e, AbortedException.class) || Ursachen.unterbrechung(e))) {
                Thread.currentThread().interrupt();
                throw e;
            }
            if (e instanceof SdkClientException || (e instanceof S3Exception s3e && voruebergehend(s3e))) {
                throw new AblageGestoert(schluessel, e);
            }
            throw e;
        }
    }

    // RequestTimeout meldet der Server mit 400 (bei AWS) oder 408; das SDK stuft den Code als wiederholbar ein
    private static boolean voruebergehend(S3Exception e) {
        return e.statusCode() >= 500 || e.statusCode() == 429 || e.statusCode() == 408
                || (fehlercode(e) != null && ZEITUEBERSCHREITUNG.contains(fehlercode(e)));
    }

    private static void bereichPruefen(String schluessel, String versionId) {
        if (schluessel == null || PRUEFBEREICHE.stream().noneMatch(schluessel::startsWith) || ohneVersion(versionId)) {
            throw new IllegalArgumentException("Nur Versionen unter archiv/ oder manifest/: " + schluessel);
        }
    }

    private static String pruefsumme(Path datei, String key) {
        MessageDigest digest = Pruefsumme.sha256();
        try (InputStream ein = new DigestInputStream(Files.newInputStream(datei), digest)) {
            ein.transferTo(OutputStream.nullOutputStream());
        } catch (IOException e) {
            throw new UncheckedIOException("Datei nicht lesbar: " + key, e);
        }
        return Pruefsumme.hex(digest);
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

    /**
     * Ceph RGW 20.x lässt {@code x-amz-version-id} in der Antwort von CopyObject weg
     * (tracker.ceph.com/issues/77796). Die Kopie ist dann die aktuelle Version des Ziels, aber nur, wenn deren ETag
     * zum Ergebnis der Kopie passt; sonst hat inzwischen jemand anderes geschrieben, und die Archivierung bricht ab,
     * ohne etwas zu löschen. Die SHA-256 prüft der Aufrufer danach wie immer über die Versions-ID.
     */
    private String versionDerKopie(String bucket, String ziel, CopyObjectResponse antwort) {
        String etag = antwort.copyObjectResult() == null ? null : antwort.copyObjectResult().eTag();
        HeadObjectResponse kopf = s3.headObject(b -> b.bucket(bucket).key(ziel));
        if (etag == null || !etag.equals(kopf.eTag())) {
            throw new IllegalStateException("Versions-ID der Archiv-Kopie nicht bestimmbar: " + ziel);
        }
        LOG.debugf("CopyObject ohne Versions-ID, Version über HeadObject bestimmt: %s", ziel);
        return kopf.versionId();
    }

    private static boolean ohneVersion(String versionId) {
        return versionId == null || versionId.isBlank() || "null".equals(versionId);
    }

    private static void pruefeVersion(String versionId, String bucket) {
        if (ohneVersion(versionId)) {
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
