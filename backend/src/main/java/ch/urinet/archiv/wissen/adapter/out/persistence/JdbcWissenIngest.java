package ch.urinet.archiv.wissen.adapter.out.persistence;

import ch.urinet.archiv.wissen.application.WissenIngestPort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.hibernate.query.NativeQuery;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** {@link WissenIngestPort} mit SQL im Schema des Mandanten aus dem Hibernate-Kontext. */
@ApplicationScoped
@Transactional(Transactional.TxType.MANDATORY)
public class JdbcWissenIngest implements WissenIngestPort {

    private static final String ZEITGRENZE = "10s";
    private static final String WISSEN_ZWECKE = "'WISSEN_INGEST', 'WISSEN_LINT', 'WISSEN_EMBEDDING'";

    private final Instance<EntityManager> entityManager;

    public JdbcWissenIngest(Instance<EntityManager> entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public Optional<Quelle> quelleLaden(UUID dokumentId) {
        zeitgrenze();
        @SuppressWarnings("unchecked")
        List<Object[]> zeilen = em().createNativeQuery("""
                        select d.art, d.status, d.vertraulichkeit, d.volltext, d.seiten,
                            (d.s3_pdfa is not null and d.s3_version_pdfa is not null and not d.pdfa_fehlt),
                            e.budget_tokens, e.monatsbudget_tokens
                        from dokument d left join wissens_einstellung e on e.id = 1
                        where d.id = :id""")
                .setParameter("id", dokumentId)
                .getResultList();
        if (zeilen.isEmpty()) {
            return Optional.empty();
        }
        Object[] d = zeilen.getFirst();
        return Optional.of(new Quelle((String) d[0], (String) d[1], (String) d[2], (String) d[3],
                d[4] == null ? null : ((Number) d[4]).intValue(), (Boolean) d[5],
                d[6] == null ? null : ((Number) d[6]).intValue(),
                d[7] == null ? null : ((Number) d[7]).intValue()));
    }

    @Override
    public boolean entwurfFuerDokumentLaufend(UUID dokumentId) {
        zeitgrenze();
        return em().createNativeQuery("""
                        select exists (
                            select 1 from wissens_entwurf
                            where anlass = 'INGEST' and dokument_id = :id
                                and status in ('WARTEND', 'IN_ARBEIT'))""")
                .setParameter("id", dokumentId).getSingleResult().equals(Boolean.TRUE);
    }

    @Override
    public boolean warteschlangeBelegt() {
        zeitgrenze();
        return em().createNativeQuery("""
                        select exists (
                            select 1 from wissens_entwurf
                            where anlass = 'INGEST' and status in ('WARTEND', 'IN_ARBEIT', 'OFFEN'))""")
                .getSingleResult().equals(Boolean.TRUE);
    }

    @Override
    public UUID entwurfAnlegen(NeuerEntwurf entwurf) {
        zeitgrenze();
        em().createNativeQuery("""
                        insert into wissens_entwurf (id, anlass, dokument_id, seite_von, seite_bis, status, schritt,
                            hinweis, ki_modell, erstellt_von, erstellt, abgeschlossen)
                        values (:id, 'INGEST', :dokumentId, :seiteVon, :seiteBis, :status, :schritt,
                            :hinweis, :modell, :erstelltVon, clock_timestamp(),
                            case when :status = 'FEHLER' then clock_timestamp() end)""")
                .unwrap(NativeQuery.class)
                .setParameter("id", entwurf.id())
                .setParameter("dokumentId", entwurf.dokumentId())
                .setParameter("seiteVon", entwurf.seiteVon(), Integer.class)
                .setParameter("seiteBis", entwurf.seiteBis(), Integer.class)
                .setParameter("status", entwurf.status())
                .setParameter("schritt", entwurf.schritt(), String.class)
                .setParameter("hinweis", entwurf.hinweis(), String.class)
                .setParameter("modell", entwurf.modell())
                .setParameter("erstelltVon", entwurf.erstelltVon())
                .executeUpdate();
        return entwurf.id();
    }

    @Override
    public long verbrauchImZeitraum(Instant von, Instant bis) {
        zeitgrenze();
        Object wert = em().createNativeQuery("select least(coalesce(sum(tokens_ein::numeric + tokens_aus::numeric), 0), "
                        + "9223372036854775807)::bigint "
                        + "from ki_verbrauch where zweck in (" + WISSEN_ZWECKE + ") "
                        + "and zeit >= :von and zeit < :bis")
                .setParameter("von", von).setParameter("bis", bis).getSingleResult();
        return ((Number) wert).longValue();
    }

    private void zeitgrenze() {
        em().createNativeQuery("select set_config('statement_timeout', '" + ZEITGRENZE + "', true)").getSingleResult();
    }

    private EntityManager em() {
        return entityManager.get();
    }

}
