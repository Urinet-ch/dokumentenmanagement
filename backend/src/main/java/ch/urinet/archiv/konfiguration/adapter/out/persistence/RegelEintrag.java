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
@Table(name = "regel")
public class RegelEintrag extends PanacheEntityBase {

    @Id
    @Column(name = "code")
    public String code;

    @Column(name = "art")
    public String art;

    @Column(name = "typ", nullable = false)
    public String typ;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "parameter", nullable = false, columnDefinition = "jsonb")
    public String parameter;

    @Column(name = "schwere", nullable = false)
    public String schwere;

    @Column(name = "meldung", nullable = false)
    public String meldung;

    @Column(name = "aktiv", nullable = false)
    public boolean aktiv;
}
