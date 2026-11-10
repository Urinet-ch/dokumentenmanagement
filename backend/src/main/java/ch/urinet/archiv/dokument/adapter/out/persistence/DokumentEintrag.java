package ch.urinet.archiv.dokument.adapter.out.persistence;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Zeile in {@code dokument} des Mandanten-Schemas (Tabelle bewusst nicht qualifiziert, ADR-002). Die generierte Spalte
 * {@code volltext_tsv} ist nicht gemappt; die Datenbank berechnet sie (design.md «Suche»).
 */
@Entity
@Table(name = "dokument")
public class DokumentEintrag extends PanacheEntityBase {

    @Id
    @Column(name = "id")
    public UUID id;

    @Column(name = "art")
    public String art;

    @Column(name = "titel")
    public String titel;

    @Column(name = "datum")
    public LocalDate datum;

    @Column(name = "eingang", nullable = false)
    public OffsetDateTime eingang;

    @Column(name = "quelle", nullable = false)
    public String quelle;

    @Column(name = "partner_id")
    public UUID partnerId;

    @Column(name = "partner_name")
    public String partnerName;

    @Column(name = "projekt")
    public String projekt;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "tags", nullable = false, columnDefinition = "text[]")
    public String[] tags;

    @Column(name = "vertraulichkeit", nullable = false)
    public String vertraulichkeit;

    @Column(name = "person_benutzer")
    public String personBenutzer;

    @Column(name = "erstellt_von", nullable = false)
    public String erstelltVon;

    @Column(name = "status", nullable = false)
    public String status;

    @Column(name = "status_grund")
    public String statusGrund;

    @Column(name = "fehler")
    public String fehler;

    @Column(name = "bestaetigt")
    public OffsetDateTime bestaetigt;

    @Column(name = "bestaetigt_von")
    public String bestaetigtVon;

    @Column(name = "geschaeftsjahr")
    public Integer geschaeftsjahr;

    @Column(name = "aufbewahren_bis")
    public LocalDate aufbewahrenBis;

    @Column(name = "sperre_bis")
    public LocalDate sperreBis;

    @Column(name = "frist_abgelaufen", nullable = false)
    public boolean fristAbgelaufen;

    @Column(name = "archiviert")
    public OffsetDateTime archiviert;

    @Column(name = "sha256_pdfa")
    public String sha256Pdfa;

    @Column(name = "s3_pdfa")
    public String s3Pdfa;

    @Column(name = "s3_version_pdfa")
    public String s3VersionPdfa;

    @Column(name = "pdfa_fehlt", nullable = false)
    public boolean pdfaFehlt;

    @Column(name = "seiten")
    public Integer seiten;

    @Column(name = "volltext")
    public String volltext;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "ki_vorschlag", columnDefinition = "jsonb")
    public String kiVorschlag;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "ki_sicherheit", columnDefinition = "jsonb")
    public String kiSicherheit;

    @Column(name = "hinweis")
    public String hinweis;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "zusatz", columnDefinition = "jsonb")
    public String zusatz;

    @Version
    @Column(name = "version", nullable = false)
    public int version;

    @Column(name = "erstellt", nullable = false)
    public OffsetDateTime erstellt;

    @Column(name = "geaendert", nullable = false)
    public OffsetDateTime geaendert;
}
