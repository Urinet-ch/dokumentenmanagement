package ch.urinet.archiv.wissen.adapter.out.persistence;

import ch.urinet.archiv.wissen.application.FreigabeRepository;
import ch.urinet.archiv.ki.application.KiPort;
import ch.urinet.archiv.wissen.domain.Quelleneintrag;
import ch.urinet.archiv.wissen.domain.Seitentyp;
import ch.urinet.archiv.wissen.domain.WissensStufe;
import ch.urinet.archiv.wissen.domain.Wissensseite;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
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
    public void manuellenEntwurfAnlegen(UUID entwurfId, String benutzer) {
        em.get().createNativeQuery("""
                insert into wissens_entwurf (id, anlass, status, erstellt_von, erstellt)
                values (:id, 'MANUELL', 'OFFEN', :benutzer, now())
                """).setParameter("id", entwurfId).setParameter("benutzer", benutzer).executeUpdate();
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
    public void neuerzeugungStarten(UUID entwurfId) {
        int geaendert = em.get().createNativeQuery("""
                update wissens_entwurf set status = 'IN_ARBEIT', schritt = 'NEU_ERZEUGEN'
                where id = :id and status = 'OFFEN'
                """).setParameter("id", entwurfId).executeUpdate();
        if (geaendert != 1) {
            throw new IllegalStateException("Gesperrter Wissensentwurf konnte nicht zur Neuerzeugung gestartet werden");
        }
    }

    @Override
    public Optional<NeuerzeugungsDaten> neuerzeugungsDaten(UUID entwurfId) {
        @SuppressWarnings("unchecked")
        List<Object[]> zeilen = em.get().createNativeQuery("""
                select anlass, tokens_ein, tokens_aus from wissens_entwurf
                where id = :id and status = 'IN_ARBEIT' and schritt = 'NEU_ERZEUGEN'
                """).setParameter("id", entwurfId).getResultList();
        return zeilen.stream().findFirst().map(z -> new NeuerzeugungsDaten((String) z[0],
                ((Number) z[1]).intValue(), ((Number) z[2]).intValue()));
    }

    @Override
    public void neuerzeugungsModellSetzen(UUID entwurfId, String modell) {
        int geschrieben = em.get().createNativeQuery("""
                update wissens_entwurf set ki_modell = :modell
                where id = :id and anlass = 'MANUELL' and status = 'IN_ARBEIT' and schritt = 'NEU_ERZEUGEN'
                """).setParameter("modell", modell).setParameter("id", entwurfId).executeUpdate();
        if (geschrieben != 1) {
            throw new IllegalStateException("Modell für die Neuerzeugung konnte nicht gespeichert werden");
        }
    }

    @Override
    public boolean neuererNeuerzeugungsauftrag(UUID entwurfId, String mandant, long auftragId) {
        return Boolean.TRUE.equals(em.get().createNativeQuery("""
                select exists(select 1 from public.auftrag where id > :auftrag and mandant = :mandant
                    and typ = 'WISSEN_INGEST' and nutzlast->>'entwurfId' = :entwurf
                    and nutzlast->>'aenderungNr' is not null)
                """).setParameter("auftrag", auftragId).setParameter("mandant", mandant)
                .setParameter("entwurf", entwurfId.toString()).getSingleResult());
    }

    @Override
    public Vorschlag vorschlagLaden(Aenderung aenderung) {
        Object[] zeile = (Object[]) em.get().createNativeQuery("""
                select cast(a.daten as text), a.inhalt, cast(a.entfernte_aussagen as text),
                       a.vertraulichkeit_kontext, e.dokument_id, e.ki_modell, e.erstellt_von
                from wissens_aenderung a join wissens_entwurf e on e.id = a.entwurf_id
                where a.id = :id
                """).setParameter("id", aenderung.id()).getSingleResult();
        try {
            FassungDaten daten = json.readValue((String) zeile[0], FassungDaten.class);
            Seitentyp typ = Seitentyp.vonDatenbankwert(daten.typ())
                    .orElseThrow(() -> new IllegalStateException("Gespeicherter Seitentyp ist ungültig"));
            Set<String> entfernt = zeile[2] == null ? Set.of()
                    : json.readValue((String) zeile[2], new TypeReference<List<KiPort.EntfernteAussage>>() { })
                    .stream().filter(a -> a.begruendung() != null && !a.begruendung().isBlank())
                    .map(KiPort.EntfernteAussage::schluessel).filter(java.util.Objects::nonNull)
                    .collect(Collectors.toUnmodifiableSet());
            Wissensseite fassung = new Wissensseite(aenderung.seiteId(), aenderung.pfad(), typ, daten.titel(),
                    daten.beschreibung(), daten.tags(), Wissensseite.Status.AKTIV, daten.veraltetAb(),
                    daten.quellen(), Wissensseite.Vertraulichkeit.NORMAL, (String) zeile[1], 0,
                    null, null, null, null, false, false, daten.wissensquelleId());
            return new Vorschlag(fassung, entfernt, (String) zeile[3], (UUID) zeile[4], (String) zeile[5],
                    (String) zeile[6]);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Gespeicherte Änderung ist nicht lesbar", e);
        }
    }

    private record FassungDaten(String typ, String titel, String beschreibung, List<String> tags, String status,
                                java.time.LocalDate veraltetAb, List<Quelleneintrag> quellen, String vertraulichkeit,
                                String inhalt, UUID wissensquelleId,
                                ch.urinet.archiv.wissen.domain.paket.PaketSeite paketSeite) {
    }

    @Override
    public void fassungSpeichern(UUID entwurfId, Aenderung aenderung, Wissensseite fassung,
                                 WissensStufe mindeststufe, WissensStufe wirksameStufe, String erzeugtVon,
                                 String benutzer, String grund, Set<String> links) {
        String tags;
        String quellen;
        try {
            tags = json.writeValueAsString(fassung.tags());
            quellen = json.writeValueAsString(fassung.quellen());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Wissensseitenfassung ist nicht serialisierbar", e);
        }
        Query schreiben = em.get().createNativeQuery("""
                insert into wissensseite
                    (id, pfad, typ, titel, beschreibung, tags, status, veraltet_ab, vertraulichkeit,
                     vertraulichkeit_mindest, inhalt, version, erzeugt_von, erzeugt_am, freigegeben_von,
                     freigegeben_am, wissensquelle_id)
                values (:id, :pfad, :typ, :titel, :beschreibung,
                        array(select jsonb_array_elements_text(cast(:tags as jsonb))), :status,
                        cast(:veraltet as date), :wirksam, :mindest, :inhalt, :version, :erzeugt, now(),
                        :freigegeben, now(), :quelle)
                on conflict (id) do update set
                    typ = excluded.typ, titel = excluded.titel, beschreibung = excluded.beschreibung, tags = excluded.tags,
                    status = excluded.status, veraltet_ab = excluded.veraltet_ab,
                    vertraulichkeit = excluded.vertraulichkeit,
                    vertraulichkeit_mindest = excluded.vertraulichkeit_mindest,
                    inhalt = excluded.inhalt, version = excluded.version, erzeugt_von = excluded.erzeugt_von,
                    erzeugt_am = excluded.erzeugt_am, freigegeben_von = excluded.freigegeben_von,
                    freigegeben_am = excluded.freigegeben_am, wissensquelle_id = excluded.wissensquelle_id,
                    quelle_pruefen = false
                """);
        schreiben.setParameter("id", fassung.id()).setParameter("pfad", fassung.pfad())
                .setParameter("typ", fassung.typ().datenbankwert()).setParameter("titel", fassung.titel())
                .setParameter("beschreibung", fassung.beschreibung()).setParameter("tags", tags)
                .setParameter("status", fassung.status().name()).setParameter("veraltet", fassung.veraltetAb())
                .setParameter("mindest", mindeststufe.name()).setParameter("wirksam", wirksameStufe.name())
                .setParameter("inhalt", fassung.inhalt())
                .setParameter("version", fassung.version()).setParameter("erzeugt", erzeugtVon)
                .setParameter("freigegeben", "human:" + benutzer).setParameter("quelle", fassung.wissensquelleId())
                .executeUpdate();
        em.get().createNativeQuery("""
                insert into wissensseite_version
                    (seite_id, version, daten, inhalt, erzeugt_von, freigegeben_von, zeit, grund, entwurf_id,
                     aenderung_id)
                select s.id, s.version,
                       jsonb_build_object('pfad', s.pfad, 'typ', s.typ, 'titel', s.titel,
                           'beschreibung', s.beschreibung, 'tags', to_jsonb(s.tags), 'status', s.status,
                           'veraltet_ab', s.veraltet_ab, 'vertraulichkeit', s.vertraulichkeit,
                           'vertraulichkeit_mindest', s.vertraulichkeit_mindest,
                           'quellen', cast(:quellen as jsonb), 'wissensquelle_id', s.wissensquelle_id),
                       s.inhalt, s.erzeugt_von, s.freigegeben_von, now(), :grund, :entwurf, :aenderung
                from wissensseite s where s.id = :id
                """).setParameter("quellen", quellen).setParameter("grund", grund)
                .setParameter("entwurf", entwurfId).setParameter("aenderung", aenderung.id())
                .setParameter("id", fassung.id()).executeUpdate();
        em.get().createNativeQuery("delete from wissensseite_quelle where seite_id = :id")
                .setParameter("id", fassung.id()).executeUpdate();
        for (Quelleneintrag quelle : fassung.quellen()) {
            em.get().createNativeQuery("""
                    insert into wissensseite_quelle
                        (seite_id, schluessel, dokument_id, quell_seite_id, seite_von, seite_bis, auszug)
                    values (:seite, :schluessel, :dokument, :quellseite, :von, :bis, :auszug)
                    """).setParameter("seite", fassung.id()).setParameter("schluessel", quelle.schluessel())
                    .setParameter("dokument", quelle.dokumentId()).setParameter("quellseite", quelle.quellSeiteId())
                    .setParameter("von", quelle.seiteVon()).setParameter("bis", quelle.seiteBis())
                    .setParameter("auszug", quelle.auszug()).executeUpdate();
        }
        em.get().createNativeQuery("delete from wissensseite_link where von_seite_id = :id")
                .setParameter("id", fassung.id()).executeUpdate();
        for (String link : links) {
            em.get().createNativeQuery("insert into wissensseite_link (von_seite_id, nach_pfad) values (:id, :pfad)")
                    .setParameter("id", fassung.id()).setParameter("pfad", link).executeUpdate();
        }
        int entschieden = em.get().createNativeQuery("""
                update wissens_aenderung set status = 'ANGENOMMEN', seite_id = :seite, ergebnis_version = :version,
                    entschieden_von = :benutzer, entschieden_am = now(), grund = :grund, etag = etag + 1
                where id = :id and status = 'OFFEN' and etag = :etag
                """).setParameter("seite", fassung.id()).setParameter("version", fassung.version())
                .setParameter("benutzer", benutzer).setParameter("grund", grund)
                .setParameter("id", aenderung.id()).setParameter("etag", aenderung.etag()).executeUpdate();
        if (entschieden != 1) {
            throw new IllegalStateException("Gesperrte Wissensänderung konnte nicht angenommen werden");
        }
        String aktion = switch (aenderung.aktion()) {
            case "NEU" -> "CREATION";
            case "AENDERN" -> "UPDATE";
            case "VERALTEN" -> "DEPRECATION";
            default -> throw new IllegalStateException("Ungültige Wissensänderungsaktion");
        };
        em.get().createNativeQuery("""
                insert into wissens_log (zeit, aktion, akteur, seiten, entwurf_id)
                values (now(), :aktion, :akteur, array[cast(:seite as uuid)], :entwurf)
                """).setParameter("aktion", aktion).setParameter("akteur", "human:" + benutzer)
                .setParameter("seite", fassung.id()).setParameter("entwurf", entwurfId).executeUpdate();
    }

    @Override
    public void verwerfen(long aenderungId, int etag, String benutzer, String grund) {
        int geaendert = em.get().createNativeQuery("""
                update wissens_aenderung
                set status = 'VERWORFEN', entschieden_von = :benutzer, entschieden_am = clock_timestamp(),
                    grund = :grund, etag = etag + 1
                where id = :id and status in ('OFFEN', 'VERALTET') and etag = :etag
                """).setParameter("benutzer", benutzer).setParameter("grund", grund)
                .setParameter("id", aenderungId).setParameter("etag", etag).executeUpdate();
        if (geaendert != 1) {
            throw new IllegalStateException("Gesperrte Wissensänderung konnte nicht verworfen werden");
        }
    }

    @Override
    public boolean entwurfAbschliessen(UUID entwurfId) {
        return em.get().createNativeQuery("""
                update wissens_entwurf e set status = case when e.anlass = 'IMPORT'
                    and not exists(select 1 from wissens_aenderung a where a.entwurf_id = e.id
                        and a.status = 'ANGENOMMEN') then 'ABGELEHNT' else 'ERLEDIGT' end,
                    abgeschlossen = clock_timestamp()
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
                where e.id = :id and (e.anlass in ('INGEST', 'IMPORT') or
                      (e.anlass = 'ANALYSE' and exists (
                          select 1 from wissens_aenderung a
                          where a.entwurf_id = e.id and a.status = 'ANGENOMMEN')))
                """).setParameter("id", entwurfId).executeUpdate();
    }
}
