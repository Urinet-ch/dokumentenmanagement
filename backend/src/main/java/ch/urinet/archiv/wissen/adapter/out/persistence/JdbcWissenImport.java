package ch.urinet.archiv.wissen.adapter.out.persistence;

import ch.urinet.archiv.wissen.application.WissenImportRepository;
import ch.urinet.archiv.wissen.application.WissenIngestPort;
import ch.urinet.archiv.wissen.application.WissenImport;
import ch.urinet.archiv.wissen.domain.paket.PaketKopf;
import ch.urinet.archiv.wissen.domain.paket.PaketSeite;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
@Transactional(Transactional.TxType.MANDATORY)
public class JdbcWissenImport implements WissenImportRepository {
    private final Instance<EntityManager> em;
    private final ObjectMapper json;

    public JdbcWissenImport(Instance<EntityManager> em, ObjectMapper json) {
        this.em = em;
        this.json = json;
    }

    @Override
    public void sperren(long verbleibendeMillis) {
        em.get().createNativeQuery("select set_config('statement_timeout', :zeit, true), "
                + "set_config('transaction_timeout', :zeit, true)")
                .setParameter("zeit", verbleibendeMillis + "ms").getSingleResult();
        em.get().createNativeQuery("select pg_advisory_xact_lock(hashtextextended(current_schema() "
                + "|| ':wissen-import', 0))").getSingleResult();
    }

    @Override
    public Optional<UUID> vorhandenerImport(String sha256) {
        List<?> zeilen = em.get().createNativeQuery("select id from wissens_entwurf where paket_sha256 = :sha", UUID.class)
                .setParameter("sha", sha256).getResultList();
        return zeilen.isEmpty() ? Optional.empty() : Optional.of(UUID.class.cast(zeilen.getFirst()));
    }

    @Override
    public long offeneImporte() {
        return ((Number) em.get().createNativeQuery("select count(*) from wissens_entwurf "
                + "where anlass = 'IMPORT' and status = 'OFFEN'").getSingleResult()).longValue();
    }

    @Override
    public void anlegen(UUID id, String benutzer, String sha256, PaketKopf kopf, int unveraendert, int ignoriert) {
        em.get().createNativeQuery("""
                insert into wissens_entwurf (id, anlass, status, erstellt_von, erstellt, paket_sha256, paket_angaben)
                values (:id, 'IMPORT', 'OFFEN', :benutzer, now(), :sha, cast(:angaben as jsonb))
                """).setParameter("id", id).setParameter("benutzer", benutzer).setParameter("sha", sha256)
                .setParameter("angaben", schreiben(Map.of("kopf", kopf, "unveraendert", unveraendert,
                        "ignoriert", ignoriert))).executeUpdate();
    }

    @Override
    public Optional<PaketKopf> kopfLaden(UUID id) {
        List<?> zeilen = em.get().createNativeQuery("""
                select cast(paket_angaben->'kopf' as text) from wissens_entwurf
                where id = :id and anlass = 'IMPORT'
                """).setParameter("id", id).getResultList();
        return zeilen.isEmpty() ? Optional.empty() : Optional.of(lesen(String.class.cast(zeilen.getFirst()), PaketKopf.class));
    }

    @Override
    public PaketSeite seiteLaden(UUID id, int nummer) {
        String wert = (String) em.get().createNativeQuery("""
                select cast(daten->'paketSeite' as text) from wissens_aenderung
                where entwurf_id = :id and nr = :nr
                """).setParameter("id", id).setParameter("nr", nummer).getSingleResult();
        return lesen(wert, PaketSeite.class);
    }

    @Override
    public void paketSeiteSpeichern(UUID id, int nummer, PaketSeite seite, boolean veraltet) {
        int geschrieben = em.get().createNativeQuery("""
                update wissens_aenderung set daten = jsonb_set(daten, '{paketSeite}', cast(:seite as jsonb)),
                    status = case when :veraltet then 'VERALTET' else status end
                where entwurf_id = :id and nr = :nr
                """).setParameter("seite", schreiben(seite)).setParameter("veraltet", veraltet)
                .setParameter("id", id).setParameter("nr", nummer).executeUpdate();
        if (geschrieben != 1) {
            throw new IllegalStateException("Importierte Wissensänderung fehlt");
        }
    }

    @Override
    public void pruefungSpeichern(WissenIngestPort.NeueAenderung aenderung) {
        var fassung = aenderung.daten();
        int geschrieben = em.get().createNativeQuery("""
                update wissens_aenderung set
                    daten = daten || jsonb_build_object('quellen', cast(:quellen as jsonb),
                        'wissensquelleId', cast(:quelle as uuid)),
                    befunde = cast(:befunde as jsonb), hinweise = cast(:hinweise as jsonb),
                    vertraulichkeit_kontext = case when vertraulichkeit_kontext = 'INTERN'
                        or :kontext = 'INTERN' then 'INTERN' else 'NORMAL' end,
                    etag = etag + 1
                where entwurf_id = :id and nr = :nr and status in ('OFFEN', 'VERALTET')
                """).setParameter("quellen", schreiben(fassung.quellen()))
                .setParameter("quelle", fassung.wissensquelleId())
                .setParameter("befunde", schreiben(aenderung.befunde()))
                .setParameter("hinweise", schreiben(aenderung.hinweise()))
                .setParameter("kontext", aenderung.vertraulichkeitKontext())
                .setParameter("id", aenderung.entwurfId()).setParameter("nr", aenderung.nummer()).executeUpdate();
        if (geschrieben != 1) {
            throw new IllegalStateException("Offene Importprüfung konnte nicht gespeichert werden");
        }
    }

    private String schreiben(Object wert) {
        try {
            return json.writeValueAsString(wert);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Importangaben sind nicht serialisierbar", e);
        }
    }

    @Override
    public void quellenPruefungSpeichern(UUID id, int nummer, List<WissenImport.QuellenPruefung> pruefungen) {
        int geschrieben = em.get().createNativeQuery("""
                update wissens_aenderung set daten = jsonb_set(daten, '{quellenPruefung}', cast(:pruefungen as jsonb))
                where entwurf_id = :id and nr = :nr
                """).setParameter("pruefungen", schreiben(pruefungen))
                .setParameter("id", id).setParameter("nr", nummer).executeUpdate();
        if (geschrieben != 1) {
            throw new IllegalStateException("Importierte Quellenprüfung fehlt");
        }
    }

    private <T> T lesen(String wert, Class<T> typ) {
        if (wert == null) {
            throw new IllegalStateException("Gespeicherte Importangaben fehlen");
        }
        try {
            return json.readValue(wert, typ);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Gespeicherte Importangaben sind nicht lesbar", e);
        }
    }
}
