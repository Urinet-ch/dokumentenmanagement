package ch.urinet.archiv.wissen.adapter.out.persistence;

import ch.urinet.archiv.sicherheit.application.SichtregelSql;
import ch.urinet.archiv.sicherheit.domain.Sichtregel;
import ch.urinet.archiv.wissen.application.WissensquelleMarkierungPort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
@Transactional(Transactional.TxType.MANDATORY)
public class JdbcWissensquelleMarkierung implements WissensquelleMarkierungPort {
    private final EntityManager em;

    public JdbcWissensquelleMarkierung(EntityManager em) {
        this.em = em;
    }

    @Override
    public boolean dokumentSperren(UUID dokumentId) {
        return !em.createNativeQuery("select id from dokument where id = :id for update")
                .setParameter("id", dokumentId).getResultList().isEmpty();
    }

    @Override
    public boolean aktiv(UUID dokumentId) {
        return Boolean.TRUE.equals(em.createNativeQuery("""
                select exists (select 1 from wissensquelle_markierung
                    where dokument_id = :id and beendet_am is null)""")
                .setParameter("id", dokumentId).getSingleResult());
    }

    @Override
    public void markieren(UUID dokumentId, String benutzer) {
        em.createNativeQuery("""
                insert into wissensquelle_markierung (dokument_id, markiert_von, markiert_am)
                values (:id, :benutzer, clock_timestamp())""")
                .setParameter("id", dokumentId).setParameter("benutzer", benutzer).executeUpdate();
    }

    @Override
    public boolean entfernen(UUID dokumentId, String benutzer) {
        return em.createNativeQuery("""
                update wissensquelle_markierung set beendet_am = clock_timestamp(), beendet_von = :benutzer
                where dokument_id = :id and beendet_am is null""")
                .setParameter("id", dokumentId).setParameter("benutzer", benutzer).executeUpdate() == 1;
    }

    @Override
    public List<Quelle> quellen(Sichtregel sicht) {
        SichtregelSql.Ausdruck filter = SichtregelSql.dokument(sicht, "d");
        var abfrage = em.createNativeQuery("""
                select d.id, d.titel, d.art, m.markiert_am,
                    (select e.status from wissens_entwurf e
                     where e.dokument_id = d.id and e.anlass = 'INGEST'
                     order by e.erstellt desc, e.id desc limit 1)
                from wissensquelle_markierung m join dokument d on d.id = m.dokument_id
                where m.beendet_am is null and """ + filter.bedingung() + " order by m.markiert_am desc, d.id");
        filter.parameter().forEach(abfrage::setParameter);
        @SuppressWarnings("unchecked")
        List<Object[]> zeilen = abfrage.getResultList();
        return zeilen.stream().map(z -> new Quelle((UUID) z[0], (String) z[1], (String) z[2],
                zeit(z[3]), (String) z[4])).toList();
    }

    private static Instant zeit(Object wert) {
        return switch (wert) {
            case Instant instant -> instant;
            case OffsetDateTime offset -> offset.toInstant();
            case Timestamp timestamp -> timestamp.toInstant();
            default -> OffsetDateTime.parse(wert.toString()).toInstant();
        };
    }
}
