package ch.urinet.archiv.wissen.adapter.out.persistence;

import ch.urinet.archiv.wissen.application.OeffentlichesWissenPort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.transaction.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
@Transactional(Transactional.TxType.MANDATORY)
public class JdbcOeffentlichesWissen implements OeffentlichesWissenPort {
    private final Instance<EntityManager> em;

    public JdbcOeffentlichesWissen(Instance<EntityManager> em) {
        this.em = em;
    }

    @Override
    public boolean hatFreigegebeneSeite() {
        Query abfrage = abfrage("""
                select exists (select 1 from wissensseite s where s.fuer_webseite and %s)
                """);
        return Boolean.TRUE.equals(abfrage.getSingleResult());
    }

    @Override
    public List<Indexzeile> suchen(String begriff, int hoechstens) {
        Query abfrage = abfrage("""
                select s.id, s.version, s.quellen_revision, s.pfad, s.titel, s.typ, s.beschreibung
                from wissensseite s
                where s.fuer_webseite and %s
                  and s.inhalt_tsv @@ plainto_tsquery('german', :begriff)
                order by ts_rank(s.inhalt_tsv, plainto_tsquery('german', :begriff)) desc, s.pfad
                limit :max
                """);
        abfrage.setParameter("begriff", begriff).setParameter("max", hoechstens);
        return zeilen(abfrage).stream().map(z -> new Indexzeile(stand(z), (String) z[3], (String) z[4],
                (String) z[5], (String) z[6])).toList();
    }

    @Override
    public Optional<Seite> seite(String pfad) {
        Query abfrage = abfrage("""
                select s.id, s.version, s.quellen_revision, s.pfad, s.titel, s.typ, s.inhalt, s.freigegeben_am
                from wissensseite s where s.fuer_webseite and %s and s.pfad = :pfad
                """);
        abfrage.setParameter("pfad", pfad);
        return zeilen(abfrage).stream().findFirst().map(z -> new Seite(stand(z), (String) z[3], (String) z[4],
                (String) z[5], (String) z[6], zeit(z[7]), dokumente((UUID) z[0])));
    }

    @Override
    public boolean aktuell(Stand stand) {
        Query abfrage = abfrage("""
                select exists (select 1 from wissensseite s where s.fuer_webseite and %s
                    and s.id = :id and s.version = :version and s.quellen_revision = :revision)
                """);
        abfrage.setParameter("id", stand.id()).setParameter("version", stand.version())
                .setParameter("revision", stand.quellenRevision());
        return Boolean.TRUE.equals(abfrage.getSingleResult());
    }

    private List<Dokument> dokumente(UUID seiteId) {
        Query query = em.get().createNativeQuery("""
                select d.id, coalesce(nullif(d.titel, ''), 'Dokument'), coalesce(d.art, ''),
                       coalesce(a.name, ''), d.zusatz ->> 'ausgabe'
                from wissensseite_dokument h join dokument d on d.id = h.dokument_id
                left join dokumentart a on a.code = d.art
                where h.seite_id = :id order by d.id limit 5
                """).setParameter("id", seiteId);
        return zeilen(query).stream().map(z -> new Dokument((UUID) z[0], (String) z[1], (String) z[2],
                (String) z[3], (String) z[4])).toList();
    }

    private Query abfrage(String sql) {
        var sicht = WissenWebseiteSql.freigabefaehig("s");
        Query query = em.get().createNativeQuery(sql.formatted(sicht.bedingung()));
        sicht.parameter().forEach(query::setParameter);
        return query;
    }

    private static Stand stand(Object[] zeile) {
        return new Stand((UUID) zeile[0], ((Number) zeile[1]).intValue(), ((Number) zeile[2]).longValue());
    }

    private static Instant zeit(Object wert) {
        return switch (wert) {
            case Instant instant -> instant;
            case OffsetDateTime offset -> offset.toInstant();
            case Timestamp timestamp -> timestamp.toInstant();
            default -> OffsetDateTime.parse(wert.toString()).toInstant();
        };
    }

    private static List<Object[]> zeilen(Query query) {
        @SuppressWarnings("unchecked")
        List<Object[]> ergebnis = query.getResultList();
        return ergebnis;
    }
}
