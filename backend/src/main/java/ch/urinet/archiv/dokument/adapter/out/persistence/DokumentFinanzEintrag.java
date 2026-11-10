package ch.urinet.archiv.dokument.adapter.out.persistence;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Zeile in {@code dokument_finanz}: Betrag als {@code NUMERIC(12,2)} mit Währung (Regel 9); {@code zahlstatus} und
 * {@code bezahlt_am} kommen nur aus AbaNinja (Regel 8, Spec 02).
 */
@Entity
@Table(name = "dokument_finanz")
public class DokumentFinanzEintrag extends PanacheEntityBase {

    @Id
    @Column(name = "dokument_id")
    public UUID dokumentId;

    @Column(name = "belegnummer")
    public String belegnummer;

    @Column(name = "richtung")
    public String richtung;

    @Column(name = "betrag", precision = 12, scale = 2)
    public BigDecimal betrag;

    @Column(name = "waehrung", nullable = false, columnDefinition = "bpchar(3)")
    public String waehrung;

    @Column(name = "faelligkeit")
    public LocalDate faelligkeit;

    @Column(name = "zahlstatus")
    public String zahlstatus;

    @Column(name = "bezahlt_am")
    public LocalDate bezahltAm;

    @Column(name = "qr_referenz")
    public String qrReferenz;

    @Column(name = "konto")
    public String konto;

    @Column(name = "abaninja_uuid")
    public String abaninjaUuid;
}
