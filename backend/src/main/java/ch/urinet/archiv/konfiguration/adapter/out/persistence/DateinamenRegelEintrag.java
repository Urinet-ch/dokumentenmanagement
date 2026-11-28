package ch.urinet.archiv.konfiguration.adapter.out.persistence;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Immutable
@Table(name = "dateinamen_regel")
public class DateinamenRegelEintrag extends PanacheEntityBase {

    @Id
    @Column(name = "nr")
    public int nr;

    @Column(name = "muster", nullable = false)
    public String muster;

    @Column(name = "art", nullable = false)
    public String art;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "felder", nullable = false, columnDefinition = "jsonb")
    public String felder;
}
