package ch.urinet.archiv.dokument.adapter.out.persistence;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.util.UUID;

/** Zeile in {@code dokument_vertrag}: Laufzeit und Fristen eines Vertrags. */
@Entity
@Table(name = "dokument_vertrag")
public class DokumentVertragEintrag extends PanacheEntityBase {

    @Id
    @Column(name = "dokument_id")
    public UUID dokumentId;

    @Column(name = "gueltig_ab")
    public LocalDate gueltigAb;

    @Column(name = "gueltig_bis")
    public LocalDate gueltigBis;

    @Column(name = "kuendigungsfrist")
    public String kuendigungsfrist;

    @Column(name = "naechste_frist")
    public LocalDate naechsteFrist;
}
