package ch.urinet.archiv.dokument.adapter.out.persistence;

import ch.urinet.archiv.ablage.domain.AbgelegteDatei;
import ch.urinet.archiv.dokument.application.DokumentAenderungPort;
import ch.urinet.archiv.dokument.application.DokumentStatusPort.Finanz;
import ch.urinet.archiv.dokument.application.DokumentStatusPort.Vertrag;
import ch.urinet.archiv.dokument.domain.DokumentStatus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * {@link DokumentAenderungPort} über die Entitäten im Schema des Mandanten aus dem Kontext (ADR-002), in der
 * Transaktion des Aufrufers. Jede Änderung erhöht {@code version} (optimistisches Locking).
 */
@ApplicationScoped
@Transactional(Transactional.TxType.MANDATORY)
public class PanacheDokumentAenderung implements DokumentAenderungPort {

    /** Vorherige Fassung der Metadaten als JSON (Req 10.1); Volltext und Suchvektor sind keine Metadaten. */
    private static final String VERSION_SICHERN = """
            insert into dokument_version (dokument_id, version, daten, geaendert_von, grund)
            select d.id, d.version, jsonb_build_object(
                'dokument', to_jsonb(d) - 'volltext' - 'volltext_tsv',
                'finanz', (select to_jsonb(f) - 'dokument_id' from dokument_finanz f where f.dokument_id = d.id),
                'vertrag', (select to_jsonb(v) - 'dokument_id' from dokument_vertrag v where v.dokument_id = d.id)),
                :benutzer, :grund
            from dokument d where d.id = :id""";

    @Override
    public Optional<Metadaten> ladenUndSperren(UUID id) {
        DokumentEintrag e = em().find(DokumentEintrag.class, id);
        if (e == null) {
            return Optional.empty();
        }
        em().refresh(e, LockModeType.PESSIMISTIC_WRITE);
        @SuppressWarnings("unchecked")
        List<String> zusatzFelder = em().createNativeQuery("""
                        select z.key from dokument d, jsonb_each(d.zusatz) z
                        where d.id = :id and z.value not in ('null'::jsonb, '""'::jsonb, '[]'::jsonb, '{}'::jsonb)""")
                .setParameter("id", id).getResultList();
        Optional<Finanz> finanz = Optional.ofNullable(em().find(DokumentFinanzEintrag.class, id))
                .map(f -> new Finanz(f.belegnummer, f.richtung, f.betrag, f.waehrung, f.faelligkeit, f.qrReferenz,
                        f.konto));
        Optional<Vertrag> vertrag = Optional.ofNullable(em().find(DokumentVertragEintrag.class, id))
                .map(v -> new Vertrag(v.gueltigAb, v.gueltigBis, v.kuendigungsfrist, v.naechsteFrist));
        return Optional.of(new Metadaten(e.id, DokumentStatus.valueOf(e.status), e.version, e.eingang.toInstant(),
                e.art, e.titel, e.datum, e.partnerId, e.partnerName, e.projekt,
                e.tags == null ? List.of() : Arrays.asList(e.tags), e.vertraulichkeit, e.personBenutzer,
                e.geschaeftsjahr, e.aufbewahrenBis, e.fristAbgelaufen, e.sperreBis, e.zusatz, Set.copyOf(zusatzFelder),
                finanz, vertrag));
    }

    @Override
    public void versionSichern(UUID id, String benutzer, String grund) {
        em().createNativeQuery(VERSION_SICHERN).setParameter("id", id).setParameter("benutzer", benutzer)
                .setParameter("grund", grund).executeUpdate();
    }

    @Override
    public int speichern(Metadaten daten) {
        DokumentEintrag e = em().find(DokumentEintrag.class, daten.id());
        if (e == null || e.version != daten.version()) {
            throw new IllegalStateException("Dokument " + daten.id() + " nicht mehr in Version " + daten.version());
        }
        e.art = daten.art();
        e.titel = daten.titel();
        e.datum = daten.datum();
        e.partnerId = daten.partnerId();
        e.partnerName = daten.partnerName();
        e.projekt = daten.projekt();
        e.tags = daten.tags().toArray(String[]::new);
        e.vertraulichkeit = daten.vertraulichkeit();
        e.personBenutzer = daten.personBenutzer();
        e.geschaeftsjahr = daten.geschaeftsjahr();
        e.aufbewahrenBis = daten.aufbewahrenBis();
        e.fristAbgelaufen = daten.fristAbgelaufen();
        e.sperreBis = daten.sperreBis();
        e.zusatz = daten.zusatz();
        // macht die Zeile immer schmutzig, damit die Version auch bei Änderungen nur an Finanz oder Vertrag steigt
        e.geaendert = OffsetDateTime.now(ZoneOffset.UTC);
        daten.finanz().ifPresent(f -> {
            DokumentFinanzEintrag z = em().find(DokumentFinanzEintrag.class, daten.id());
            boolean neu = z == null;
            if (neu) {
                z = new DokumentFinanzEintrag();
                z.dokumentId = daten.id();
            }
            // Zahlstatus und AbaNinja-Felder bleiben (Regel 8)
            z.belegnummer = f.belegnummer();
            z.richtung = f.richtung();
            z.betrag = f.betrag();
            z.waehrung = f.waehrung();
            z.faelligkeit = f.faelligkeit();
            z.qrReferenz = f.qrReferenz();
            z.konto = f.konto();
            if (neu) {
                em().persist(z);
            }
        });
        daten.vertrag().ifPresent(v -> {
            DokumentVertragEintrag z = em().find(DokumentVertragEintrag.class, daten.id());
            boolean neu = z == null;
            if (neu) {
                z = new DokumentVertragEintrag();
                z.dokumentId = daten.id();
            }
            z.gueltigAb = v.gueltigAb();
            z.gueltigBis = v.gueltigBis();
            z.kuendigungsfrist = v.kuendigungsfrist();
            z.naechsteFrist = v.naechsteFrist();
            if (neu) {
                em().persist(z);
            }
        });
        em().flush();
        return e.version;
    }

    @Override
    public List<AbgelegteDatei> archivDateien(UUID id) {
        @SuppressWarnings("unchecked")
        List<Object[]> zeilen = em().createNativeQuery("""
                        select s3_schluessel, s3_version, sha256, groesse from dokument_datei
                        where dokument_id = :id and status = 'AKTIV' and s3_schluessel like 'archiv/%'
                            and s3_version is not null
                        union all
                        select s3_pdfa, s3_version_pdfa, sha256_pdfa, null from dokument
                        where id = :id and s3_pdfa like 'archiv/%' and s3_version_pdfa is not null
                        order by 1""")
                .setParameter("id", id).getResultList();
        List<AbgelegteDatei> dateien = new ArrayList<>();
        for (Object[] z : zeilen) {
            dateien.add(new AbgelegteDatei((String) z[0], (String) z[1], (String) z[2],
                    z[3] == null ? 0 : ((Number) z[3]).longValue(), Optional.empty()));
        }
        return dateien;
    }

    private static EntityManager em() {
        return DokumentEintrag.getEntityManager();
    }
}
