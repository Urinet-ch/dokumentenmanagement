package ch.urinet.archiv.dokument.adapter.out.persistence;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Zeile in {@code dokument_version}: vorherige Fassung der Metadaten mit Benutzer, Zeit und Grund (Req 10.1). Nur
 * Einfügen (Req 10.3): Trigger und Rechte verhindern UPDATE, DELETE und TRUNCATE; {@code @Immutable}, damit
 * Hibernate eine geänderte Instanz nie per UPDATE schreibt ({@code KeinLoeschpfadTest}, Req 10.9).
 */
@Entity
@Immutable
@Table(name = "dokument_version")
public class DokumentVersionEintrag extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    public Long id;

    @Column(name = "dokument_id", nullable = false)
    public UUID dokumentId;

    @Column(name = "version", nullable = false)
    public int version;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "daten", nullable = false, columnDefinition = "jsonb")
    public String daten;

    @Column(name = "geaendert_von", nullable = false)
    public String geaendertVon;

    @Column(name = "zeit", nullable = false, insertable = false, updatable = false)
    public OffsetDateTime zeit;

    @Column(name = "grund")
    public String grund;
}
