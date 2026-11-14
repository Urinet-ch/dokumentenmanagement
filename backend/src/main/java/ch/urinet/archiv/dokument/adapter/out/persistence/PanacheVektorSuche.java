package ch.urinet.archiv.dokument.adapter.out.persistence;

import ch.urinet.archiv.ki.domain.Kopfzeile;
import ch.urinet.archiv.ki.domain.Vektortext;
import ch.urinet.archiv.sicherheit.domain.Sichtregel;
import ch.urinet.archiv.suche.application.VektorSuchePort;
import ch.urinet.archiv.suche.domain.Abschnitttreffer;
import ch.urinet.archiv.suche.domain.Suchanfrage;
import ch.urinet.archiv.suche.domain.Suchtreffer;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Datenbankteile der Hybridsuche (Spec 02, Aufgabe 6.4; design.md «Embeddings und Hybridsuche») über {@code chunk},
 * {@code dokument} und {@code dokument_finanz} im Schema des Mandanten der Anfrage, mit denselben Bedingungen wie die
 * Volltextsuche ({@link SuchFilter}: Sicht nach {@link VertraulichkeitFilter}, Filter aus Req 14.2, ohne {@code status}
 * kein {@code VERWORFEN}). Die Sicht kommt immer aus {@code dokument}, nie aus dem Abschnitt.
 * <p>
 * Vektor-Abfrage: Kosinus ({@code <=>} aus {@code public}, HNSW {@code halfvec_cosine_ops}) mit Sicht und Filtern in
 * derselben Abfrage vor der Grenze. pgvector 0.8 liefert über HNSW höchstens {@code hnsw.ef_search} Zeilen (Standard
 * 40) und mit Filter oft weniger; darum setzt die Transaktion {@code hnsw.ef_search} auf {@value #EF_SEARCH},
 * {@code hnsw.iterative_scan = relaxed_order}, {@code hnsw.max_scan_tuples} auf {@value #MAX_SCAN_TUPLES} und
 * {@code hnsw.scan_mem_multiplier} auf 2. Die Suche läuft damit bei selektiven Filtern weiter, bis genug Treffer
 * gefunden sind oder die Obergrenze erreicht ist. Die äussere Abfrage sortiert erneut nach Abstand, weil
 * {@code relaxed_order} leicht ungeordnet liefern darf.
 * {@code statement_timeout} aus {@code archiv.suche.zeitgrenze} wie in der Volltextsuche.
 */
@ApplicationScoped
@Transactional(Transactional.TxType.MANDATORY)
public class PanacheVektorSuche implements VektorSuchePort {

    /** Kandidatenliste von HNSW; mindestens die Grenze von 50 Abschnitten plus Reserve für Filter. */
    static final int EF_SEARCH = 100;
    static final int MAX_SCAN_TUPLES = 100_000;

    private final Duration zeitgrenze;
    private final ObjectMapper json;

    public PanacheVektorSuche(@ConfigProperty(name = "archiv.suche.zeitgrenze") Duration zeitgrenze,
                              ObjectMapper json) {
        this.zeitgrenze = zeitgrenze;
        this.json = json;
    }

    @Override
    public List<Abschnitttreffer> naechste(float[] vektor, Suchanfrage anfrage, Sichtregel regel, int anzahl) {
        Map<String, Object> parameter = new HashMap<>();
        List<String> bedingungen = SuchFilter.bedingungen(anfrage, regel, parameter);
        parameter.put("vektor", Vektortext.text(vektor));
        parameter.put("anzahl", anzahl);
        String abstand = "c.embedding operator(public.<=>) cast(:vektor as public.halfvec)";
        String sql = """
                with naechste as materialized (
                    select c.dokument_id, c.nr, c.seite_von, c.seite_bis, c.text, %s as abstand
                    from chunk c
                    join dokument d on d.id = c.dokument_id
                    left join dokument_finanz f on f.dokument_id = d.id
                    where c.embedding is not null
                      and %s
                    order by %s
                    limit :anzahl
                ), bester as (
                    select distinct on (n.dokument_id) n.*
                    from naechste n
                    order by n.dokument_id, n.abstand, n.nr
                )
                select b.dokument_id, b.nr, b.seite_von, b.seite_bis, b.text, b.abstand, d.titel, a.name,
                    d.partner_name
                from bester b
                join dokument d on d.id = b.dokument_id
                left join dokumentart a on a.code = d.art
                order by b.abstand, b.dokument_id""".formatted(abstand, String.join("\n      and ", bedingungen),
                abstand);
        EntityManager em = vorbereiten();
        em.createNativeQuery("select set_config('hnsw.ef_search', :wert, true)")
                .setParameter("wert", Integer.toString(EF_SEARCH)).getSingleResult();
        em.createNativeQuery("select set_config('hnsw.iterative_scan', 'relaxed_order', true)").getSingleResult();
        em.createNativeQuery("select set_config('hnsw.max_scan_tuples', :wert, true)")
                .setParameter("wert", Integer.toString(MAX_SCAN_TUPLES)).getSingleResult();
        em.createNativeQuery("select set_config('hnsw.scan_mem_multiplier', '2', true)").getSingleResult();
        List<Object[]> zeilen = zeilen(em, sql, parameter);
        List<Abschnitttreffer> treffer = new ArrayList<>(zeilen.size());
        for (Object[] z : zeilen) {
            String text = (String) z[4];
            String kopf = Kopfzeile.von((String) z[6], (String) z[7], (String) z[8]);
            treffer.add(new Abschnitttreffer((UUID) z[0], ((Number) z[1]).intValue(), zahl(z[2]), zahl(z[3]),
                    ((Number) z[5]).doubleValue(), text, Kopfzeile.inhalt(text, kopf)));
        }
        return List.copyOf(treffer);
    }

    @Override
    public Map<UUID, String> texte(Collection<UUID> ids, Suchanfrage anfrage, Sichtregel regel, int zeichen) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> parameter = new HashMap<>();
        List<String> bedingungen = SuchFilter.bedingungen(anfrage, regel, parameter);
        parameter.put("ids", jsonListe(ids.stream().map(UUID::toString).toList()));
        parameter.put("zeichen", zeichen);
        // nie der Dateiname: der Reranker ist ein Modell (Spec 01, Req 3.13)
        String sql = """
                select d.id, d.titel, a.name, d.partner_name, left(d.volltext, :zeichen)
                from dokument d
                left join dokument_finanz f on f.dokument_id = d.id
                left join dokumentart a on a.code = d.art
                where d.id in (select cast(i.value as uuid) from jsonb_array_elements_text(cast(:ids as jsonb)) i)
                  and %s""".formatted(String.join("\n  and ", bedingungen));
        Map<UUID, String> texte = new LinkedHashMap<>();
        for (Object[] z : zeilen(vorbereiten(), sql, parameter)) {
            String kopf = Kopfzeile.von((String) z[1], (String) z[2], (String) z[3]);
            texte.put((UUID) z[0], Kopfzeile.mitInhalt(kopf, z[4] == null ? "" : (String) z[4]));
        }
        return texte;
    }

    @Override
    public List<Suchtreffer> details(List<Abschnitttreffer> abschnitte, Suchanfrage anfrage, Sichtregel regel,
                                     boolean mitDateiname) {
        if (abschnitte.isEmpty()) {
            return List.of();
        }
        Map<String, Object> parameter = new HashMap<>();
        List<String> bedingungen = SuchFilter.bedingungen(anfrage, regel, parameter);
        ArrayNode liste = json.createArrayNode();
        abschnitte.forEach(a -> liste.addObject().put("id", a.dokumentId().toString()).put("inhalt", a.inhalt()));
        parameter.put("abschnitte", liste.toString());
        parameter.put("begriff", anfrage.begriff());
        // Auszug über den Inhalt des besten Abschnitts ohne Kopfzeile und ohne Dateinamen, gleiche Optionen wie im Volltext
        String auszug = "ts_headline('german', translate(x.inhalt, " + PanacheDokumentSuche.MARKEN + ", ''), "
                + PanacheDokumentSuche.TSQ + ", " + PanacheDokumentSuche.AUSZUG_OPTIONEN + ")";
        String sql = """
                select d.id, d.art, d.titel, d.datum, d.eingang, d.status,
                    case when d.bestaetigt is null then 'PERSONAL' else d.vertraulichkeit end as vertraulichkeit,
                    cast(array_to_json(d.tags) as text) as tags, d.geschaeftsjahr, d.partner_id, d.partner_name,
                    f.belegnummer, f.betrag, cast(f.waehrung as text) as waehrung, f.richtung, %s as auszug,
                    %s as dateiname
                from jsonb_to_recordset(cast(:abschnitte as jsonb)) as x(id uuid, inhalt text)
                join dokument d on d.id = x.id
                left join dokument_finanz f on f.dokument_id = d.id
                where %s""".formatted(auszug, mitDateiname ? "d.dateiname" : "cast(null as text)",
                String.join("\n  and ", bedingungen));
        List<Suchtreffer> treffer = new ArrayList<>();
        for (Object[] z : zeilen(vorbereiten(), sql, parameter)) {
            treffer.add(PanacheDokumentSuche.treffer(z, 0, json));
        }
        return List.copyOf(treffer);
    }

    private EntityManager vorbereiten() {
        EntityManager em = DokumentEintrag.getEntityManager();
        em.createNativeQuery("select set_config('statement_timeout', :ms, true)")
                .setParameter("ms", Long.toString(zeitgrenze.toMillis())).getSingleResult();
        return em;
    }

    private static List<Object[]> zeilen(EntityManager em, String sql, Map<String, Object> parameter) {
        Query abfrage = em.createNativeQuery(sql);
        parameter.forEach(abfrage::setParameter);
        @SuppressWarnings("unchecked")
        List<Object[]> zeilen = abfrage.getResultList();
        return zeilen;
    }

    private String jsonListe(List<String> werte) {
        try {
            return json.writeValueAsString(werte);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("IDs nicht als JSON", e);
        }
    }

    private static Integer zahl(Object wert) {
        return wert == null ? null : ((Number) wert).intValue();
    }
}
