package ch.urinet.archiv.dokument.adapter.out.persistence;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/** Zeile in {@code dokument_datei}: Original oder Arbeitsfassung eines Dokuments mit SHA-256 (Req 3.4, Regel 3). */
@Entity
@Table(name = "dokument_datei")
@IdClass(DokumentDateiEintrag.Schluessel.class)
public class DokumentDateiEintrag extends PanacheEntityBase {

    @Id
    @Column(name = "dokument_id")
    public UUID dokumentId;

    @Id
    @Column(name = "rolle")
    public String rolle;

    @Id
    @Column(name = "nr")
    public int nr;

    @Column(name = "sha256", nullable = false)
    public String sha256;

    @Column(name = "s3_schluessel")
    public String s3Schluessel;

    @Column(name = "s3_version")
    public String s3Version;

    @Column(name = "mime")
    public String mime;

    @Column(name = "groesse")
    public Long groesse;

    @Column(name = "empfangen", nullable = false)
    public OffsetDateTime empfangen;

    @Column(name = "status", nullable = false)
    public String status;

    /** Zusammengesetzter Schlüssel (dokument_id, rolle, nr). */
    public static class Schluessel implements Serializable {

        private static final long serialVersionUID = 1L;

        public UUID dokumentId;
        public String rolle;
        public int nr;

        @Override
        public boolean equals(Object o) {
            return o instanceof Schluessel s && nr == s.nr && Objects.equals(dokumentId, s.dokumentId)
                    && Objects.equals(rolle, s.rolle);
        }

        @Override
        public int hashCode() {
            return Objects.hash(dokumentId, rolle, nr);
        }
    }
}
