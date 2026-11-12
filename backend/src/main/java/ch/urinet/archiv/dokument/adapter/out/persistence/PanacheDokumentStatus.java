package ch.urinet.archiv.dokument.adapter.out.persistence;

import ch.urinet.archiv.dokument.application.DokumentStatusPort;
import ch.urinet.archiv.dokument.domain.DokumentStatus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * {@link DokumentStatusPort} im Schema des Mandanten aus dem Kontext (ADR-002), in der Transaktion des Aufrufers. Die
 * bestätigten Metadaten schreibt der Adapter über die Entitäten, nachdem er die Zeile mit {@code for update} neu gelesen
 * hat; die Statuswechsel ohne Metadaten mit nativem SQL.
 */
@ApplicationScoped
@Transactional(Transactional.TxType.MANDATORY)
public class PanacheDokumentStatus implements DokumentStatusPort {

    @Override
    public Optional<Stand> stand(UUID id, boolean sperren) {
        @SuppressWarnings("unchecked")
        List<Object[]> zeilen = em().createNativeQuery("select status, version, eingang, pdfa_fehlt, sha256_pdfa "
                        + "from dokument where id = :id" + (sperren ? " for update" : ""))
                .setParameter("id", id).getResultList();
        if (zeilen.isEmpty()) {
            return Optional.empty();
        }
        Object[] d = zeilen.getFirst();
        @SuppressWarnings("unchecked")
        List<String> zusatz = em().createNativeQuery("""
                        select z.key from dokument d, jsonb_each(d.zusatz) z
                        where d.id = :id and z.value not in ('null'::jsonb, '""'::jsonb, '[]'::jsonb, '{}'::jsonb)""")
                .setParameter("id", id).getResultList();
        @SuppressWarnings("unchecked")
        List<String> sha256 = em().createNativeQuery("""
                        select sha256 from dokument_datei where dokument_id = :id and status = 'AKTIV'
                        order by rolle desc, nr""")
                .setParameter("id", id).getResultList();
        List<String> alle = new ArrayList<>(sha256);
        if (d[4] != null) {
            alle.add((String) d[4]);
        }
        return Optional.of(new Stand(id, DokumentStatus.valueOf((String) d[0]), ((Number) d[1]).intValue(),
                zeitpunkt(d[2]), (Boolean) d[3], Set.copyOf(zusatz), alle));
    }

    @Override
    public void fristAbgelaufenMarkieren(UUID id) {
        em().createNativeQuery("update dokument set frist_abgelaufen = true "
                        + "where id = :id and status = 'EINGANGSKORB' and not frist_abgelaufen")
                .setParameter("id", id).executeUpdate();
    }

    @Override
    public Optional<UUID> belegnummerInhaber(String art, String belegnummer) {
        @SuppressWarnings("unchecked")
        List<UUID> zeilen = em().createNativeQuery(
                        "select dokument_id from belegnummer_eigen where art = :art and belegnummer = :nr", UUID.class)
                .setParameter("art", art).setParameter("nr", belegnummer).getResultList();
        return zeilen.stream().findFirst();
    }

    @Override
    public boolean belegnummerReservieren(String art, String belegnummer, UUID id) {
        em().createNativeQuery("delete from belegnummer_eigen where dokument_id = :id "
                        + "and (art <> :art or belegnummer <> :nr)")
                .setParameter("id", id).setParameter("art", art).setParameter("nr", belegnummer).executeUpdate();
        em().createNativeQuery("insert into belegnummer_eigen (art, belegnummer, dokument_id) values (:art, :nr, :id) "
                        + "on conflict do nothing")
                .setParameter("id", id).setParameter("art", art).setParameter("nr", belegnummer).executeUpdate();
        return belegnummerInhaber(art, belegnummer).filter(id::equals).isPresent();
    }

    @Override
    public void belegnummerFreigeben(UUID id) {
        em().createNativeQuery("delete from belegnummer_eigen where dokument_id = :id")
                .setParameter("id", id).executeUpdate();
    }

    @Override
    public boolean bestaetigen(UUID id, int version, Bestaetigt daten) {
        DokumentEintrag e = em().find(DokumentEintrag.class, id);
        if (e == null) {
            return false;
        }
        em().refresh(e, LockModeType.PESSIMISTIC_WRITE);
        if (!DokumentStatus.EINGANGSKORB.name().equals(e.status) || e.version != version) {
            return false;
        }
        OffsetDateTime jetzt = OffsetDateTime.now(ZoneOffset.UTC);
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
        e.status = DokumentStatus.ARCHIVIERUNG.name();
        e.statusGrund = null;
        e.fehler = null;
        e.bestaetigt = jetzt;
        e.bestaetigtVon = daten.bestaetigtVon();
        e.geaendert = jetzt;
        daten.finanz().ifPresent(f -> {
            DokumentFinanzEintrag z = em().find(DokumentFinanzEintrag.class, id);
            boolean neu = z == null;
            if (neu) {
                z = new DokumentFinanzEintrag();
                z.dokumentId = id;
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
            DokumentVertragEintrag z = em().find(DokumentVertragEintrag.class, id);
            boolean neu = z == null;
            if (neu) {
                z = new DokumentVertragEintrag();
                z.dokumentId = id;
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
        return true;
    }

    @Override
    public boolean verwerfen(UUID id, int version, String grund) {
        int geaendert = em().createNativeQuery("""
                        update dokument set status = 'VERWORFEN', status_grund = :grund, version = version + 1,
                            geaendert = clock_timestamp()
                        where id = :id and version = :version and status in ('EINGANGSKORB', 'FEHLER')""")
                .setParameter("grund", grund).setParameter("id", id).setParameter("version", version)
                .executeUpdate();
        if (geaendert != 1) {
            return false;
        }
        em().createNativeQuery("update dokument_datei set status = 'VERWORFEN' where dokument_id = :id")
                .setParameter("id", id).executeUpdate();
        belegnummerFreigeben(id);
        return true;
    }

    @Override
    public boolean verarbeitungWiederholen(UUID id, int version) {
        return wechseln(id, version, DokumentStatus.FEHLER, DokumentStatus.NEU);
    }

    @Override
    public boolean archivierungWiederholen(UUID id, int version) {
        return wechseln(id, version, DokumentStatus.ARCHIVIERUNG_FEHLER, DokumentStatus.ARCHIVIERUNG);
    }

    @Override
    public Optional<UUID> gleicherBeleg(UUID id) {
        @SuppressWarnings("unchecked")
        List<UUID> zeilen = em().createNativeQuery("""
                        select a.id from dokument d
                        join dokument_finanz df on df.dokument_id = d.id
                        join dokument_finanz af on af.belegnummer = df.belegnummer and af.dokument_id <> d.id
                        join dokument a on a.id = af.dokument_id
                        where d.id = :id and df.belegnummer is not null and d.partner_id is not null
                            and d.art is not null and a.art = d.art and a.partner_id = d.partner_id
                            and a.status <> 'VERWORFEN'
                        order by a.eingang, a.id
                        limit 1""", UUID.class)
                .setParameter("id", id).getResultList();
        return zeilen.stream().findFirst();
    }

    private boolean wechseln(UUID id, int version, DokumentStatus von, DokumentStatus nach) {
        return em().createNativeQuery("""
                        update dokument set status = :nach, fehler = null, version = version + 1,
                            geaendert = clock_timestamp()
                        where id = :id and version = :version and status = :von""")
                .setParameter("nach", nach.name()).setParameter("von", von.name())
                .setParameter("id", id).setParameter("version", version).executeUpdate() == 1;
    }

    private static Instant zeitpunkt(Object wert) {
        return switch (wert) {
            case Instant i -> i;
            case OffsetDateTime o -> o.toInstant();
            case Timestamp t -> t.toInstant();
            default -> throw new IllegalStateException("Unerwarteter Typ für eingang: " + wert.getClass().getName());
        };
    }

    private static EntityManager em() {
        return DokumentEintrag.getEntityManager();
    }
}
