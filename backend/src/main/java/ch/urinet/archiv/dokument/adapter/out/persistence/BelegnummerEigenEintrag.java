package ch.urinet.archiv.dokument.adapter.out.persistence;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * Zeile in {@code belegnummer_eigen}: Belegnummer eindeutig je eigener Art (Req 8.9, 11.3); Fremdbelege stehen nicht
 * hier (Req 8.10). Der Primärschlüssel ist (art, belegnummer); gemappt ist die eindeutige {@code dokument_id}.
 */
@Entity
@Table(name = "belegnummer_eigen")
public class BelegnummerEigenEintrag extends PanacheEntityBase {

    @Id
    @Column(name = "dokument_id", nullable = false)
    public UUID dokumentId;

    @Column(name = "art", nullable = false)
    public String art;

    @Column(name = "belegnummer", nullable = false)
    public String belegnummer;
}
