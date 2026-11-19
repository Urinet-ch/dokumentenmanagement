package ch.urinet.archiv.wissen.adapter.out.persistence;

import ch.urinet.archiv.wissen.application.FreigabeRepository;
import ch.urinet.archiv.ki.application.KiPort;
import ch.urinet.archiv.wissen.domain.Quelleneintrag;
import ch.urinet.archiv.wissen.domain.Seitentyp;
import ch.urinet.archiv.wissen.domain.Wissensseite;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
@Transactional(Transactional.TxType.MANDATORY)
public class JdbcFreigabeRepository implements FreigabeRepository {
    private final Instance<EntityManager> em;
    private final ObjectMapper json;

    public JdbcFreigabeRepository(Instance<EntityManager> em, ObjectMapper json) {
        this.em = em;
        this.json = json;
    }

    @Override
    public Optional<Aenderung> sperren(UUID entwurfId, int nummer) {
        @SuppressWarnings("unchecked")
        List<Object[]> zeilen = em.get().createNativeQuery("""
                select a.id, a.seite_id, e.status, e.anlass, a.status, a.etag, a.aktion, a.pfad, a.basis_version
                from wissens_entwurf e join wissens_aenderung a on a.entwurf_id = e.id
                where e.id = :entwurf and a.nr = :nummer
                for update of e, a
                """).setParameter("entwurf", entwurfId).setParameter("nummer", nummer).getResultList();
        return zeilen.stream().findFirst().map(z -> new Aenderung(((Number) z[0]).longValue(),
                z[1] == null ? null : (UUID) z[1], (String) z[2], (String) z[3], (String) z[4],
                ((Number) z[5]).intValue(), (String) z[6], (String) z[7],
                z[8] == null ? null : ((Number) z[8]).intValue()));
    }

    @Override
    public Optional<Integer> seitenVersionSperren(UUID seiteId) {
        @SuppressWarnings("unchecked")
        List<Number> zeilen = em.get().createNativeQuery(
                        "select version from wissensseite where id = :id for update")
                .setParameter("id", seiteId).getResultList();
        return zeilen.stream().findFirst().map(Number::intValue);
    }

    @Override
    public void alsVeraltetMarkieren(long aenderungId, int etag) {
        int geaendert = em.get().createNativeQuery("""
                update wissens_aenderung set status = 'VERALTET', etag = etag + 1
                where id = :id and status = 'OFFEN' and etag = :etag
                """).setParameter("id", aenderungId).setParameter("etag", etag).executeUpdate();
        if (geaendert != 1) {
            throw new IllegalStateException("Gesperrte Wissensänderung konnte nicht als veraltet markiert werden");
        }
    }

    @Override
    public Vorschlag vorschlagLaden(Aenderung aenderung) {
        Object[] zeile = (Object[]) em.get().createNativeQuery("""
                select cast(a.daten as text), a.inhalt, cast(a.entfernte_aussagen as text),
                       a.vertraulichkeit_kontext, e.dokument_id, e.ki_modell
                from wissens_aenderung a join wissens_entwurf e on e.id = a.entwurf_id
                where a.id = :id
                """).setParameter("id", aenderung.id()).getSingleResult();
        try {
            FassungDaten daten = json.readValue((String) zeile[0], FassungDaten.class);
            Seitentyp typ = Seitentyp.vonDatenbankwert(daten.typ())
                    .orElseThrow(() -> new IllegalStateException("Gespeicherter Seitentyp ist ungültig"));
            Set<String> entfernt = zeile[2] == null ? Set.of()
                    : json.readValue((String) zeile[2], new TypeReference<List<KiPort.EntfernteAussage>>() { })
                    .stream().map(KiPort.EntfernteAussage::schluessel).filter(java.util.Objects::nonNull)
                    .collect(Collectors.toUnmodifiableSet());
            Wissensseite fassung = new Wissensseite(aenderung.seiteId(), aenderung.pfad(), typ, daten.titel(),
                    daten.beschreibung(), daten.tags(), Wissensseite.Status.AKTIV, daten.veraltetAb(),
                    daten.quellen(), Wissensseite.Vertraulichkeit.NORMAL, (String) zeile[1], 0,
                    null, null, null, null, false, false, daten.wissensquelleId());
            return new Vorschlag(fassung, entfernt, (String) zeile[3], (UUID) zeile[4], (String) zeile[5]);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Gespeicherte Änderung ist nicht lesbar", e);
        }
    }

    private record FassungDaten(String typ, String titel, String beschreibung, List<String> tags, String status,
                                java.time.LocalDate veraltetAb, List<Quelleneintrag> quellen, String vertraulichkeit,
                                String inhalt, UUID wissensquelleId) {
    }

    @Override
    public void verwerfen(long aenderungId, int etag, String benutzer, String grund) {
        int geaendert = em.get().createNativeQuery("""
                update wissens_aenderung
                set status = 'VERWORFEN', entschieden_von = :benutzer, entschieden_am = clock_timestamp(),
                    grund = :grund, etag = etag + 1
                where id = :id and status = 'OFFEN' and etag = :etag
                """).setParameter("benutzer", benutzer).setParameter("grund", grund)
                .setParameter("id", aenderungId).setParameter("etag", etag).executeUpdate();
        if (geaendert != 1) {
            throw new IllegalStateException("Gesperrte Wissensänderung konnte nicht verworfen werden");
        }
    }

    @Override
    public boolean entwurfAbschliessen(UUID entwurfId) {
        return em.get().createNativeQuery("""
                update wissens_entwurf e set status = 'ERLEDIGT', abgeschlossen = clock_timestamp()
                where e.id = :id and e.status = 'OFFEN'
                  and not exists (select 1 from wissens_aenderung a where a.entwurf_id = e.id
                                  and a.status in ('OFFEN', 'VERALTET'))
                """).setParameter("id", entwurfId).executeUpdate() == 1;
    }

    @Override
    public void abschlussProtokollieren(UUID entwurfId) {
        em.get().createNativeQuery("""
                insert into wissens_log (zeit, aktion, akteur, seiten, dokument_id, entwurf_id, angaben)
                select clock_timestamp(), e.anlass,
                       case when e.anlass = 'INGEST' then 'process:wissen-ingest'
                            else 'human:' || e.erstellt_von end,
                       array(select a.seite_id from wissens_aenderung a
                             where a.entwurf_id = e.id and a.status = 'ANGENOMMEN'
                               and a.seite_id is not null order by a.nr),
                       e.dokument_id, e.id,
                       case when e.anlass = 'ANALYSE'
                            then jsonb_build_object('vorschlagender', e.erstellt_von) else null end
                from wissens_entwurf e
                where e.id = :id and (e.anlass = 'INGEST' or
                      (e.anlass = 'ANALYSE' and exists (
                          select 1 from wissens_aenderung a
                          where a.entwurf_id = e.id and a.status = 'ANGENOMMEN')))
                """).setParameter("id", entwurfId).executeUpdate();
    }
}
