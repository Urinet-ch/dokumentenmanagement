package ch.urinet.archiv.dokumentart.adapter.out.persistence;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Zeile in {@code dokumentart} des Mandanten-Schemas. Die Tabelle ist bewusst nicht qualifiziert: Das Schema setzt
 * der Tenant-Resolver je Session (ADR-002). Nur die Spalten, die heute gebraucht werden; die Prüfung je
 * Mandanten-Schema (Req 1.7) prüft sie.
 */
@Entity
@Table(name = "dokumentart")
public class DokumentartEintrag extends PanacheEntityBase {

    @Id
    @Column(name = "code")
    public String code;

    @Column(name = "name", nullable = false)
    public String name;

    @Column(name = "vertraulichkeit", nullable = false)
    public String vertraulichkeit;

    @Column(name = "gesetzliche_pflicht", nullable = false)
    public boolean gesetzlichePflicht;

    @Column(name = "aktiv", nullable = false)
    public boolean aktiv;
}
