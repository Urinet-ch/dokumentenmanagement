package ch.urinet.archiv.konfiguration.adapter.out.persistence;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

@Entity
@Immutable
@Table(name = "konfiguration_version")
public class KonfigurationVersionEintrag extends PanacheEntityBase {

    @Id
    @Column(name = "version")
    public int version;

    @Column(name = "zeit", nullable = false, insertable = false, updatable = false)
    public OffsetDateTime zeit;

    @Column(name = "wer", nullable = false)
    public String wer;

    @Column(name = "grund")
    public String grund;

    @Column(name = "quelle", nullable = false)
    public String quelle;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "inhalt", nullable = false, columnDefinition = "jsonb")
    public String inhalt;

    @Column(name = "sha256", nullable = false, length = 64)
    public String sha256;
}
