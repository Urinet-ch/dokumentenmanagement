package ch.urinet.archiv.ablage.adapter.out.s3;

import ch.urinet.archiv.ablage.adapter.out.AblageEinstellungen;
import ch.urinet.archiv.ablage.domain.Pruefsumme;
import ch.urinet.archiv.ablage.domain.Sperrmodus;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.sicherung.application.WiederherstellungsZiel;
import ch.urinet.archiv.sicherung.application.WiederherstellungsZielNichtGeeignet;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;
import software.amazon.awssdk.services.s3.model.ObjectLockEnabled;
import software.amazon.awssdk.services.s3.model.ObjectLockRetention;
import software.amazon.awssdk.services.s3.model.ObjectLockRetentionMode;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;

/** S3-Ziel für eine Wiederherstellung; lädt zuerst ungesperrt und setzt die geprüfte Sperre danach. */
@ApplicationScoped
public class S3Wiederherstellung implements WiederherstellungsZiel {

    private static final String OBJEKT_TYP = "application/octet-stream";

    private final S3Client s3;
    private final AblageEinstellungen einstellungen;
    private final ThreadLocal<Zielzustand> zustand = new ThreadLocal<>();

    @Inject
    public S3Wiederherstellung(S3Client s3, AblageEinstellungen einstellungen) {
        this.s3 = BypassVerbot.pruefen(Objects.requireNonNull(s3, "s3"));
        this.einstellungen = Objects.requireNonNull(einstellungen, "einstellungen");
    }

    @Override
    public Quittung schreiben(String schluessel, String versionId, Instant lastModified, InputStream inhalt,
                              long groesse) {
        Objects.requireNonNull(schluessel, "schluessel");
        Objects.requireNonNull(versionId, "versionId");
        Objects.requireNonNull(lastModified, "lastModified");
        Objects.requireNonNull(inhalt, "inhalt");
        if (versionId.isBlank() || groesse < 0) {
            throw new IllegalArgumentException("Ungültige Wiederherstellungsdatei");
        }
        Zielzustand ziel = zielzustand();
        try {
            Quittung quittung = groesse <= einstellungen.exportTeil()
                    ? kleinSchreiben(ziel, schluessel, inhalt, groesse)
                    : grossSchreiben(ziel, schluessel, inhalt, groesse);
            ziel.ausstehend = new Ausstehend(schluessel, quittung);
            return quittung;
        } catch (IOException e) {
            throw new UncheckedIOException("Wiederherstellungsdatei nicht vollständig lesbar", e);
        }
    }

    private Quittung kleinSchreiben(Zielzustand ziel, String schluessel, InputStream inhalt, long groesse)
            throws IOException {
        byte[] daten = inhalt.readNBytes(Math.toIntExact(groesse));
        if (daten.length != groesse) {
            throw new EOFException("Wiederherstellungsdatei unvollständig");
        }
        String sha256 = sha256(daten);
        String versionId = s3.putObject(b -> b.bucket(ziel.bucket).key(schluessel).contentType(OBJEKT_TYP),
                RequestBody.fromBytes(daten)).versionId();
        pruefeVersion(versionId);
        return new Quittung(versionId, sha256);
    }

    private Quittung grossSchreiben(Zielzustand ziel, String schluessel, InputStream inhalt, long groesse)
            throws IOException {
        try (S3Exportziel upload = new S3Exportziel(s3, ziel.bucket, schluessel, einstellungen.exportTeil(), OBJEKT_TYP)) {
            long gelesen = inhalt.transferTo(upload.strom());
            if (gelesen != groesse) {
                throw new EOFException("Wiederherstellungsdatei unvollständig");
            }
            var abgelegt = upload.abschliessen();
            return new Quittung(abgelegt.versionId(), abgelegt.sha256());
        }
    }

    @Override
    public void sperren(Quittung quittung, Sperrmodus modus, Instant bis) {
        Objects.requireNonNull(modus, "modus");
        Objects.requireNonNull(bis, "bis");
        Zielzustand ziel = zielzustand();
        Ausstehend ausstehend = ausstehend(ziel, quittung);
        ObjectLockRetentionMode erwartet = ObjectLockRetentionMode.fromValue(modus.name());
        Optional<ObjectLockRetention> vorhandeneSperre = retention(ziel.bucket, ausstehend.schluessel(), quittung);
        if (vorhandeneSperre.isPresent()) {
            ObjectLockRetention ist = vorhandeneSperre.get();
            if (ist.mode() != erwartet) {
                throw new IllegalStateException("Sperrmodus der wiederhergestellten Version weicht ab");
            }
            if (!ist.retainUntilDate().isBefore(bis)) {
                ziel.ausstehend = null;
                return;
            }
        }
        s3.putObjectRetention(b -> b.bucket(ziel.bucket).key(ausstehend.schluessel())
                .versionId(quittung.versionId()).retention(r -> r.mode(erwartet).retainUntilDate(bis)));
        ObjectLockRetention gesetzt = retention(ziel.bucket, ausstehend.schluessel(), quittung)
                .orElseThrow(() -> new IllegalStateException("Sperre der wiederhergestellten Version fehlt"));
        if (gesetzt.mode() != erwartet || gesetzt.retainUntilDate().isBefore(bis)) {
            throw new IllegalStateException("Sperre der wiederhergestellten Version weicht ab");
        }
        ziel.ausstehend = null;
    }

    @Override
    public void verwerfen(Quittung quittung) {
        Zielzustand ziel = zielzustand();
        Ausstehend ausstehend = ausstehend(ziel, quittung);
        s3.deleteObject(b -> b.bucket(ziel.bucket).key(ausstehend.schluessel())
                .versionId(quittung.versionId()));
        ziel.ausstehend = null;
    }

    @Override
    public String praefix() {
        return einstellungen.bucketPraefix();
    }

    @Override
    public boolean leer(MandantCode mandant) {
        String bucket = mandant.bucket(einstellungen.bucketPraefix());
        try {
            s3.headBucket(b -> b.bucket(bucket));
            if (s3.getBucketVersioning(b -> b.bucket(bucket)).status() != BucketVersioningStatus.ENABLED) {
                throw new WiederherstellungsZielNichtGeeignet(mandant);
            }
            var konfiguration = s3.getObjectLockConfiguration(b -> b.bucket(bucket)).objectLockConfiguration();
            if (konfiguration == null || konfiguration.objectLockEnabled() != ObjectLockEnabled.ENABLED) {
                throw new WiederherstellungsZielNichtGeeignet(mandant);
            }
            zustand.set(new Zielzustand(bucket));
            var objekte = s3.listObjectVersions(b -> b.bucket(bucket).maxKeys(1));
            return objekte.versions().isEmpty() && objekte.deleteMarkers().isEmpty();
        } catch (WiederherstellungsZielNichtGeeignet e) {
            throw e;
        } catch (S3Exception e) {
            if (bucketFehlt(e) || objectLockFehlt(e)) {
                throw new WiederherstellungsZielNichtGeeignet(mandant);
            }
            throw e;
        }
    }

    private static boolean bucketFehlt(S3Exception e) {
        return e.statusCode() == 404 || "NoSuchBucket".equals(fehlercode(e));
    }

    private static boolean objectLockFehlt(S3Exception e) {
        return S3Ablage.KEINE_SPERRE.contains(fehlercode(e))
                || "ObjectLockConfigurationNotFoundError".equals(fehlercode(e));
    }

    private static String fehlercode(S3Exception e) {
        return e.awsErrorDetails() == null ? null : e.awsErrorDetails().errorCode();
    }

    private Optional<ObjectLockRetention> retention(String bucket, String schluessel, Quittung quittung) {
        try {
            return Optional.ofNullable(s3.getObjectRetention(b -> b.bucket(bucket).key(schluessel)
                    .versionId(quittung.versionId())).retention()).filter(r -> r.retainUntilDate() != null);
        } catch (S3Exception e) {
            if (S3Ablage.KEINE_SPERRE.contains(fehlercode(e))) {
                return Optional.empty();
            }
            throw e;
        }
    }

    private static String sha256(byte[] daten) {
        return HexFormat.of().formatHex(Pruefsumme.sha256().digest(daten));
    }

    private static void pruefeVersion(String versionId) {
        if (versionId == null || versionId.isBlank() || "null".equals(versionId)) {
            throw new IllegalStateException("Wiederherstellungsziel ohne Versionierung");
        }
    }

    private static Ausstehend ausstehend(Zielzustand ziel, Quittung quittung) {
        Objects.requireNonNull(quittung, "quittung");
        if (ziel.ausstehend == null || !ziel.ausstehend.quittung().equals(quittung)) {
            throw new IllegalStateException("Version gehört nicht zum aktuellen Wiederherstellungsschritt");
        }
        return ziel.ausstehend;
    }

    private Zielzustand zielzustand() {
        Zielzustand ziel = zustand.get();
        if (ziel == null) {
            throw new IllegalStateException("Wiederherstellungsziel wurde nicht geprüft");
        }
        return ziel;
    }

    private static final class Zielzustand {
        private final String bucket;
        private Ausstehend ausstehend;

        private Zielzustand(String bucket) {
            this.bucket = bucket;
        }
    }

    private record Ausstehend(String schluessel, Quittung quittung) {
    }

}
