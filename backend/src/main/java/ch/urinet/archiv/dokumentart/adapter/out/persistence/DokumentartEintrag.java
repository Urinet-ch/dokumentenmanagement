package ch.urinet.archiv.dokumentart.adapter.out.persistence;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Zeile in {@code dokumentart} des Mandanten-Schemas. Die Tabelle ist bewusst nicht qualifiziert: Das Schema setzt
 * der Tenant-Resolver je Session (ADR-002). Die Prüfung je Mandanten-Schema (Req 1.7) prüft das Mapping.
 */
@Entity
@Table(name = "dokumentart")
public class DokumentartEintrag extends PanacheEntityBase {

    @Id
    @Column(name = "code")
    public String code;

    @Column(name = "name", nullable = false)
    public String name;

    @Column(name = "beschreibung")
    public String beschreibung;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "gruppen", columnDefinition = "text[]")
    public String[] gruppen;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "pflichtfelder", columnDefinition = "text[]")
    public String[] pflichtfelder;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "zusatz_schema", columnDefinition = "jsonb")
    public String zusatzSchema;

    @Column(name = "vertraulichkeit", nullable = false)
    public String vertraulichkeit;

    @Column(name = "aufbewahrung_jahre")
    public Integer aufbewahrungJahre;

    @Column(name = "aufbewahrung_ab")
    public String aufbewahrungAb;

    @Column(name = "gesetzliche_pflicht", nullable = false)
    public boolean gesetzlichePflicht;

    @Column(name = "aktiv", nullable = false)
    public boolean aktiv;
}
