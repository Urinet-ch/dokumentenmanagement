package ch.urinet.archiv.mandant.adapter.out.persistence;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

/** Zeile in {@code public.mandant}; nur die Spalten, die das Register heute braucht (Hibernate prüft mit validate). */
@Entity
@Table(name = "mandant", schema = "public")
public class MandantEintrag extends PanacheEntityBase {

    @Id
    @Column(name = "code")
    public String code;

    @Column(name = "name", nullable = false)
    public String name;

    @Column(name = "schema_name", nullable = false, unique = true)
    public String schemaName;

    @Column(name = "bucket", nullable = false, unique = true)
    public String bucket;

    @Column(name = "geschaeftsjahr_ende", nullable = false, columnDefinition = "bpchar(5)")
    public String geschaeftsjahrEnde;

    @Column(name = "aktiv", nullable = false)
    public boolean aktiv;

    @Column(name = "erstellt", nullable = false, insertable = false, updatable = false)
    public OffsetDateTime erstellt;
}
