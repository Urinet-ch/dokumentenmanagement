package ch.urinet.archiv.wissen.adapter.out.persistence;

import ch.urinet.archiv.sicherheit.application.SichtregelSql;
import ch.urinet.archiv.sicherheit.domain.Sichtregel;
import ch.urinet.archiv.wissen.application.WissenLesenRepository;
import ch.urinet.archiv.wissen.domain.Quelleneintrag;
import ch.urinet.archiv.wissen.domain.Seitentyp;
import ch.urinet.archiv.wissen.domain.Wissensseite;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@ApplicationScoped
public class PanacheWissenLesenRepository implements WissenLesenRepository {

    private static final String SEITENFELDER = """
            s.id, s.pfad, s.typ, s.titel, s.beschreibung, cast(array_to_json(s.tags) as text),
            s.status, s.veraltet_ab, s.vertraulichkeit, s.inhalt, s.version, s.erzeugt_von, s.erzeugt_am,
            s.freigegeben_von, s.freigegeben_am, s.quelle_pruefen, s.widerspruch_offen, s.wissensquelle_id
            """;

    private final Instance<EntityManager> em;
    private final ObjectMapper json;

    public PanacheWissenLesenRepository(Instance<EntityManager> em, ObjectMapper json) {
        this.em = em;
        this.json = json;
    }

    @Override
    public IndexErgebnis index(Sichtregel sichtregel, IndexAnfrage anfrage) {
        WissenSichtbarkeitSql.Ausdruck sicht = WissenSichtbarkeitSql.aktuell("s", sichtregel);
        Map<String, Object> parameter = new HashMap<>(sicht.parameter());
        List<String> bedingungen = new ArrayList<>();
        bedingungen.add(sicht.bedingung());
        if (anfrage.typ() != null) {
            bedingungen.add("s.typ = :typ");
            parameter.put("typ", anfrage.typ().datenbankwert());
        }
        if (anfrage.tag() != null) {
            bedingungen.add(":tag = any(s.tags)");
            parameter.put("tag", anfrage.tag());
        }
        if (anfrage.q() != null) {
            bedingungen.add("""
                    to_tsvector('german', coalesce(s.titel, '') || ' ' || coalesce(s.pfad, '') || ' '
                        || coalesce(s.beschreibung, '') || ' ' || coalesce(s.inhalt, ''))
                        @@ plainto_tsquery('german', :q)
                    """);
            parameter.put("q", anfrage.q());
        }
        parameter.put("groesse", anfrage.groesse());
        parameter.put("offset", anfrage.offset());
        String filter = String.join(" and ", bedingungen);
        Query query = em.get().createNativeQuery("""
                with gefiltert as materialized (
                    select %s from wissensseite s where %s
                ), anzahl as (
                    select count(*) as gesamt from gefiltert
                ), seite as (
                    select * from gefiltert
                    order by %s, titel, pfad
                    limit :groesse offset :offset
                )
                select anzahl.gesamt, seite.id, seite.typ, seite.pfad, seite.titel, seite.beschreibung, seite.status
                from anzahl left join seite on true
                order by %s, seite.titel, seite.pfad
                """.formatted(SEITENFELDER, filter, typSortierung("gefiltert"), typSortierung("seite")));
        parameter.forEach(query::setParameter);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        long gesamt = rows.isEmpty() ? 0 : ((Number) rows.getFirst()[0]).longValue();
        List<IndexZeile> seiten = rows.stream().filter(row -> row[1] != null)
                .map(row -> new IndexZeile(uuid(row[1]), seitentyp(text(row[2])), text(row[3]), text(row[4]),
                        text(row[5]), text(row[6])))
                .toList();
        return new IndexErgebnis(seiten, gesamt);
    }

    @Override
    public Optional<Wissensseite> seite(UUID id, Sichtregel sichtregel) {
        return seite("s.id = :id", Map.of("id", id), sichtregel);
    }

    @Override
    public Optional<Wissensseite> seite(String pfad, Sichtregel sichtregel) {
        return seite("s.pfad = :pfad", Map.of("pfad", pfad), sichtregel);
    }

    @Override
    public Set<String> sichtbarePfade(Sichtregel sichtregel, Set<String> pfade) {
        if (pfade.isEmpty()) {
            return Set.of();
        }
        List<String> parameterNamen = IntStream.range(0, pfade.size())
                .mapToObj(index -> "pfad" + index).toList();
        WissenSichtbarkeitSql.Ausdruck sicht = WissenSichtbarkeitSql.aktuell("s", sichtregel);
        Query query = em.get().createNativeQuery("""
                select s.pfad from wissensseite s
                where s.pfad in (%s) and %s
                """.formatted(parameterNamen.stream().map(name -> ":" + name).collect(Collectors.joining(", ")),
                sicht.bedingung()));
        int index = 0;
        for (String pfad : pfade) {
            query.setParameter(parameterNamen.get(index++), pfad);
        }
        sicht.parameter().forEach(query::setParameter);
        @SuppressWarnings("unchecked")
        List<String> rows = query.getResultList();
        return Set.copyOf(rows);
    }

    private Optional<Wissensseite> seite(String bedingung, Map<String, Object> parameter,
                                         Sichtregel sichtregel) {
        WissenSichtbarkeitSql.Ausdruck sicht = WissenSichtbarkeitSql.aktuell("s", sichtregel);
        Query query = em.get().createNativeQuery("select " + SEITENFELDER + " from wissensseite s where "
                + bedingung + " and " + sicht.bedingung());
        parameter.forEach(query::setParameter);
        sicht.parameter().forEach(query::setParameter);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Object[] row = rows.getFirst();
        UUID id = uuid(row[0]);
        return Optional.of(new Wissensseite(id, text(row[1]), seitentyp(text(row[2])), text(row[3]), text(row[4]),
                tags(text(row[5])), Wissensseite.Status.valueOf(text(row[6])), datum(row[7]), quellen(id, sichtregel),
                Wissensseite.Vertraulichkeit.valueOf(text(row[8])), text(row[9]),
                ((Number) row[10]).intValue(), text(row[11]), zeit(row[12]), text(row[13]), zeit(row[14]),
                bool(row[15]), bool(row[16]), uuidNullable(row[17])));
    }

    @Override
    public List<VersionZeile> versionen(UUID seiteId, Sichtregel sichtregel) {
        WissenSichtbarkeitSql.Ausdruck aktuelle = WissenSichtbarkeitSql.aktuell("s", sichtregel);
        WissenSichtbarkeitSql.Ausdruck version = WissenSichtbarkeitSql.version("v", sichtregel);
        SichtregelSql.Ausdruck dokumentSicht = SichtregelSql.dokument(sichtregel, "dd");
        Map<String, Object> parameter = new HashMap<>(WissenSichtbarkeitSql.parameter(aktuelle, version));
        parameter.putAll(dokumentSicht.parameter());
        Query query = em.get().createNativeQuery("""
                select v.version, case when erlaubt.sichtbar then cast(v.daten as text) end,
                       case when erlaubt.sichtbar then v.inhalt end,
                       case when erlaubt.sichtbar then v.erzeugt_von end,
                       case when erlaubt.sichtbar then v.freigegeben_von end,
                       case when erlaubt.sichtbar then v.zeit end,
                       case when erlaubt.sichtbar then v.grund end,
                       case when erlaubt.sichtbar and (e.dokument_id is null or exists (
                           select 1 from dokument dd where dd.id = e.dokument_id and %s
                       )) then v.entwurf_id end,
                       case when erlaubt.sichtbar and (e.dokument_id is null or exists (
                           select 1 from dokument dd where dd.id = e.dokument_id and %s
                       )) then e.anlass end,
                       case when erlaubt.sichtbar and exists (
                           select 1 from dokument dd where dd.id = e.dokument_id and %s
                       ) then e.dokument_id end
                from wissensseite s
                join wissensseite_version v on v.seite_id = s.id
                left join wissens_entwurf e on e.id = v.entwurf_id
                cross join lateral (select %s as sichtbar) erlaubt
                where s.id = :seiteId and %s
                order by v.version desc
                """.formatted(dokumentSicht.bedingung(), dokumentSicht.bedingung(), dokumentSicht.bedingung(),
                version.bedingung(), aktuelle.bedingung()));
        query.setParameter("seiteId", seiteId);
        parameter.forEach(query::setParameter);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        return rows.stream().map(row -> new VersionZeile(((Number) row[0]).intValue(),
                row[1] == null ? Optional.empty() : Optional.of(version(row)))).toList();
    }

    @Override
    public Optional<WissensVersion> version(UUID seiteId, int nummer, Sichtregel sichtregel) {
        WissenSichtbarkeitSql.Ausdruck aktuelle = WissenSichtbarkeitSql.aktuell("s", sichtregel);
        WissenSichtbarkeitSql.Ausdruck version = WissenSichtbarkeitSql.version("v", sichtregel);
        SichtregelSql.Ausdruck dokumentSicht = SichtregelSql.dokument(sichtregel, "dd");
        Map<String, Object> parameter = new HashMap<>(WissenSichtbarkeitSql.parameter(aktuelle, version));
        parameter.putAll(dokumentSicht.parameter());
        Query query = em.get().createNativeQuery("""
                select v.version, cast(v.daten as text), v.inhalt, v.erzeugt_von, v.freigegeben_von, v.zeit, v.grund,
                       case when e.dokument_id is null or exists (
                           select 1 from dokument dd where dd.id = e.dokument_id and %s
                       ) then v.entwurf_id end,
                       case when e.dokument_id is null or exists (
                           select 1 from dokument dd where dd.id = e.dokument_id and %s
                       ) then e.anlass end,
                       case when exists (
                           select 1 from dokument dd where dd.id = e.dokument_id and %s
                       ) then e.dokument_id end
                from wissensseite s
                join wissensseite_version v on v.seite_id = s.id
                left join wissens_entwurf e on e.id = v.entwurf_id
                where s.id = :seiteId and v.version = :version and %s and %s
                """.formatted(dokumentSicht.bedingung(), dokumentSicht.bedingung(), dokumentSicht.bedingung(),
                aktuelle.bedingung(), version.bedingung()));
        query.setParameter("seiteId", seiteId);
        query.setParameter("version", nummer);
        parameter.forEach(query::setParameter);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        return rows.stream().findFirst().map(this::version);
    }

    @Override
    public List<IndexZeile> eingehend(UUID seiteId, Sichtregel sichtregel) {
        WissenSichtbarkeitSql.Ausdruck zielSicht = WissenSichtbarkeitSql.aktuell("z", sichtregel);
        WissenSichtbarkeitSql.Ausdruck quelleSicht = WissenSichtbarkeitSql.aktuell("s", sichtregel);
        Query query = em.get().createNativeQuery("""
                select s.id, s.typ, s.pfad, s.titel, s.beschreibung, s.status
                from wissensseite z
                join wissensseite s on s.id <> z.id
                where z.id = :seiteId and %s and %s
                  and (exists (
                      select 1 from wissensseite_link l where l.von_seite_id = s.id and l.nach_pfad = z.pfad
                  ) or (z.typ = 'Quelle' and exists (
                      select 1 from wissensseite_quelle q
                      where q.seite_id = s.id and q.dokument_id = z.wissensquelle_id
                  )))
                order by %s, s.titel, s.pfad
                """.formatted(zielSicht.bedingung(), quelleSicht.bedingung(), typSortierung("s")));
        query.setParameter("seiteId", seiteId);
        WissenSichtbarkeitSql.parameter(zielSicht, quelleSicht).forEach(query::setParameter);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        return rows.stream().map(row -> new IndexZeile(uuid(row[0]), seitentyp(text(row[1])), text(row[2]),
                text(row[3]), text(row[4]), text(row[5]))).toList();
    }

    @Override
    public LogSeite log(Sichtregel sichtregel, LogCursor vor, int groesse) {
        WissenSichtbarkeitSql.Ausdruck sicht = WissenSichtbarkeitSql.aktuell("s", sichtregel);
        SichtregelSql.Ausdruck dokumentSicht = SichtregelSql.dokument(sichtregel, "d");
        Map<String, Object> parameter = new HashMap<>(sicht.parameter());
        parameter.putAll(dokumentSicht.parameter());
        String cursor = "";
        if (vor != null) {
            cursor = "and (l.zeit, l.id) < (:vorZeit, :vorId)";
            parameter.put("vorZeit", vor.zeit());
            parameter.put("vorId", vor.id());
        }
        parameter.put("limit", groesse + 1);
        Query query = em.get().createNativeQuery("""
                select l.id, l.zeit, l.aktion, l.akteur,
                       cast(coalesce((
                           select jsonb_agg(jsonb_build_object('id', s.id, 'pfad', s.pfad, 'titel', s.titel)
                               order by s.pfad)
                           from unnest(l.seiten) as p(seite_id)
                           join wissensseite s on s.id = p.seite_id
                           where %s
                       ), '[]'::jsonb) as text) as seiten,
                       case when l.dokument_id is not null and exists (
                           select 1 from dokument d where d.id = l.dokument_id and %s
                       ) then l.dokument_id end,
                       case when e.id is not null and (e.dokument_id is null or exists (
                           select 1 from dokument d where d.id = e.dokument_id and %s
                       )) then l.entwurf_id end
                from wissens_log l
                left join wissens_entwurf e on e.id = l.entwurf_id
                where exists (
                    select p.seite_id from unnest(l.seiten) as p(seite_id)
                    join wissensseite s on s.id = p.seite_id
                    where %s
                )
                  %s
                order by l.zeit desc, l.id desc
                limit :limit
                """.formatted(sicht.bedingung(), dokumentSicht.bedingung(), dokumentSicht.bedingung(),
                sicht.bedingung(), cursor));
        parameter.forEach(query::setParameter);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        List<LogEintrag> eintraege = rows.stream().map(this::logEintrag).toList();
        Optional<LogCursor> naechster = Optional.empty();
        if (eintraege.size() > groesse) {
            eintraege = new ArrayList<>(eintraege.subList(0, groesse));
            LogEintrag letzte = eintraege.getLast();
            naechster = Optional.of(new LogCursor(letzte.zeit(), letzte.id()));
        }
        return new LogSeite(eintraege, naechster);
    }

    private List<Quelleneintrag> quellen(UUID seiteId, Sichtregel sichtregel) {
        SichtregelSql.Ausdruck dokumentSicht = SichtregelSql.dokument(sichtregel, "d");
        WissenSichtbarkeitSql.Ausdruck seiteSicht = WissenSichtbarkeitSql.aktuell("z", sichtregel);
        Map<String, Object> parameter = new HashMap<>(dokumentSicht.parameter());
        parameter.putAll(seiteSicht.parameter());
        Query query = em.get().createNativeQuery("""
                select q.schluessel, q.dokument_id, q.seite_von, q.seite_bis, q.auszug, q.quell_seite_id
                from wissensseite_quelle q
                where q.seite_id = :seiteId and (
                    (q.dokument_id is not null and exists (
                        select 1 from dokument d where d.id = q.dokument_id and %s
                    ))
                    or (q.quell_seite_id is not null and exists (
                        select 1 from wissensseite z where z.id = q.quell_seite_id and %s
                    ))
                )
                order by q.schluessel
                """.formatted(dokumentSicht.bedingung(), seiteSicht.bedingung()));
        query.setParameter("seiteId", seiteId);
        parameter.forEach(query::setParameter);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        return rows.stream().map(row -> new Quelleneintrag(text(row[0]), uuidNullable(row[1]),
                integer(row[2]), integer(row[3]), nullableText(row[4]), uuidNullable(row[5]))).toList();
    }

    private WissensVersion version(Object[] row) {
        String daten = text(row[1]);
        try {
            Map<String, Object> felder = json.readValue(daten, new TypeReference<>() {
            });
            return new WissensVersion(((Number) row[0]).intValue(), felder, text(row[2]), text(row[3]),
                    text(row[4]), zeit(row[5]), nullableText(row[6]), uuidNullable(row[7]), nullableText(row[8]),
                    uuidNullable(row[9]));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Wissensseitenversion ist ungültig", e);
        }
    }

    private LogEintrag logEintrag(Object[] row) {
        try {
            List<Map<String, Object>> seitenJson = json.readValue(text(row[4]), new TypeReference<>() {
            });
            List<SeiteVerweis> seiten = seitenJson.stream()
                    .map(p -> new SeiteVerweis(UUID.fromString(p.get("id").toString()), text(p.get("pfad")),
                            text(p.get("titel"))))
                    .toList();
            return new LogEintrag(((Number) row[0]).longValue(), zeit(row[1]), text(row[2]), text(row[3]), seiten,
                    uuidNullable(row[5]), uuidNullable(row[6]));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Wissensbasis-Log ist ungültig", e);
        }
    }

    private List<String> tags(String wert) {
        try {
            return json.readValue(wert, new TypeReference<>() {
            });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Wissensseitentags sind ungültig", e);
        }
    }

    private static Seitentyp seitentyp(String wert) {
        return Seitentyp.vonDatenbankwert(wert)
                .orElseThrow(() -> new IllegalStateException("Unbekannter Wissensseitentyp"));
    }

    private static UUID uuid(Object wert) {
        return wert instanceof UUID id ? id : UUID.fromString(wert.toString());
    }

    private static String typSortierung(String alias) {
        return """
                case %s.typ when 'Synthese' then 0 when 'Quelle' then 1 when 'Konzept' then 2
                    when 'Akteur' then 3 when 'Projekt' then 4 when 'Analyse' then 5 else 6 end
                """.formatted(alias);
    }

    private static UUID uuidNullable(Object wert) {
        return wert == null ? null : uuid(wert);
    }

    private static String text(Object wert) {
        return wert == null ? null : wert.toString();
    }

    private static String nullableText(Object wert) {
        return wert == null ? null : wert.toString();
    }

    private static Integer integer(Object wert) {
        return wert == null ? null : ((Number) wert).intValue();
    }

    private static boolean bool(Object wert) {
        return Boolean.TRUE.equals(wert);
    }

    private static LocalDate datum(Object wert) {
        if (wert == null) {
            return null;
        }
        if (wert instanceof LocalDate date) {
            return date;
        }
        if (wert instanceof java.sql.Date date) {
            return date.toLocalDate();
        }
        return LocalDate.parse(wert.toString());
    }

    private static Instant zeit(Object wert) {
        return switch (wert) {
            case Instant instant -> instant;
            case OffsetDateTime offset -> offset.toInstant();
            case Timestamp timestamp -> timestamp.toInstant();
            case null -> null;
            default -> OffsetDateTime.parse(wert.toString()).toInstant();
        };
    }
}
