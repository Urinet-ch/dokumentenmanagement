package ch.urinet.archiv.ablage.adapter.out.s3;

import ch.urinet.archiv.ablage.application.Exportziel;
import ch.urinet.archiv.ablage.domain.AbgelegteDatei;
import ch.urinet.archiv.ablage.domain.Pruefsumme;
import org.jboss.logging.Logger;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ChecksumAlgorithm;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.UploadPartResponse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Multipart-Upload eines Export-ZIP (Spec 02, Req 7.1): puffert höchstens einen Teil von {@code teilGroesse} Bytes
 * (mindestens 5 MiB, Grenze von S3 für alle Teile ausser dem letzten), lädt ihn hoch und verwendet den Puffer weiter.
 * Die SHA-256 entsteht über alle geschriebenen Bytes. Jeder Teil trägt eine CRC32-Prüfsumme, die beim Anlegen des
 * Uploads angekündigt ist und beim Abschluss je Teil mitgeht (AWS, MinIO und Ceph verlangen das übereinstimmend).
 * Ein Retry des SDK liest den Teil über einen neuen Strom auf denselben Puffer, ohne Kopie. Ohne Abschluss bricht
 * {@link #close} den Upload ab.
 */
final class S3Exportziel extends OutputStream implements Exportziel {

    static final int MIN_TEIL = 5 * 1024 * 1024;
    private static final Logger LOG = Logger.getLogger(S3Exportziel.class);

    private final S3Client s3;
    private final String bucket;
    private final String key;
    private final byte[] puffer;
    private final MessageDigest digest = Pruefsumme.sha256();
    private final List<CompletedPart> teile = new ArrayList<>();
    private int belegt;
    private long groesse;
    private String uploadId;
    private boolean beendet;

    S3Exportziel(S3Client s3, String bucket, String key, int teilGroesse) {
        if (teilGroesse < MIN_TEIL) {
            throw new IllegalArgumentException("Teile eines Multipart-Uploads sind mindestens 5 MiB gross");
        }
        this.s3 = Objects.requireNonNull(s3, "s3");
        this.bucket = Objects.requireNonNull(bucket, "bucket");
        this.key = Objects.requireNonNull(key, "key");
        this.puffer = new byte[teilGroesse];
    }

    @Override
    public OutputStream strom() {
        return this;
    }

    @Override
    public void write(int b) throws IOException {
        offen();
        if (belegt == puffer.length) {
            teilHochladen();
        }
        puffer[belegt++] = (byte) b;
        digest.update((byte) b);
        groesse++;
    }

    @Override
    public void write(byte[] daten, int ab, int laenge) throws IOException {
        Objects.checkFromIndexSize(ab, laenge, daten.length);
        offen();
        digest.update(daten, ab, laenge);
        groesse += laenge;
        while (laenge > 0) {
            if (belegt == puffer.length) {
                teilHochladen();
            }
            int n = Math.min(laenge, puffer.length - belegt);
            System.arraycopy(daten, ab, puffer, belegt, n);
            belegt += n;
            ab += n;
            laenge -= n;
        }
    }

    @Override
    public AbgelegteDatei abschliessen() throws IOException {
        offen();
        if (belegt > 0 || teile.isEmpty()) {
            teilHochladen();
        }
        String versionId = s3.completeMultipartUpload(b -> b.bucket(bucket).key(key).uploadId(uploadId)
                .multipartUpload(m -> m.parts(teile))).versionId();
        beendet = true;
        if (versionId == null || versionId.isBlank() || "null".equals(versionId)) {
            throw new IllegalStateException("Bucket " + bucket + " ohne Versionierung");
        }
        LOG.debugf("Export abgelegt: %s (Version %s, %d Teile)", key, versionId, teile.size());
        return new AbgelegteDatei(key, versionId, Pruefsumme.hex(digest), groesse, Optional.empty());
    }

    /**
     * Ohne Abschluss: Upload abbrechen; ein Fehler dabei bleibt im Log, die Teile entfernt der Lebenszyklus (nicht
     * bei MinIO). Nach einer Unterbrechung (Zeitgrenze, Herunterfahren) ist das Interrupt-Flag gesetzt, und das SDK
     * bräche jeden Aufruf sofort ab ({@code SdkInterruptedException}); darum löscht der Abbruch das Flag für diesen
     * einen Aufruf und setzt es danach wieder.
     */
    @Override
    public void close() {
        if (beendet) {
            return;
        }
        beendet = true;
        if (uploadId == null) {
            return;
        }
        boolean unterbrochen = Thread.interrupted();
        try {
            s3.abortMultipartUpload(b -> b.bucket(bucket).key(key).uploadId(uploadId));
            LOG.debugf("Export-Upload abgebrochen: %s", key);
        } catch (RuntimeException e) {
            LOG.warnf("Export-Upload nicht abgebrochen: %s (%s)", key, e.getClass().getName());
        } finally {
            if (unterbrochen) {
                Thread.currentThread().interrupt();
            }
        }
    }

    int teile() {
        return teile.size();
    }

    /** Lädt den Puffer als nächsten Teil hoch; der letzte Teil darf kleiner als 5 MiB sein. */
    private void teilHochladen() {
        if (uploadId == null) {
            uploadId = s3.createMultipartUpload(b -> b.bucket(bucket).key(key).contentType("application/zip")
                    .checksumAlgorithm(ChecksumAlgorithm.CRC32)).uploadId();
        }
        int nummer = teile.size() + 1;
        int laenge = belegt;
        UploadPartResponse antwort = s3.uploadPart(b -> b.bucket(bucket).key(key).uploadId(uploadId)
                        .partNumber(nummer).contentLength((long) laenge).checksumAlgorithm(ChecksumAlgorithm.CRC32),
                RequestBody.fromContentProvider(() -> new ByteArrayInputStream(puffer, 0, laenge), laenge,
                        "application/octet-stream"));
        teile.add(CompletedPart.builder().partNumber(nummer).eTag(antwort.eTag())
                .checksumCRC32(antwort.checksumCRC32()).build());
        belegt = 0;
    }

    private void offen() throws IOException {
        if (beendet) {
            throw new IOException("Export-Upload ist beendet: " + key);
        }
    }
}
