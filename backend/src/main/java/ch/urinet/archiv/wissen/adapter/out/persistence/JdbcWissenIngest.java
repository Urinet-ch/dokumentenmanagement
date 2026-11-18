package ch.urinet.archiv.wissen.adapter.out.persistence;

import ch.urinet.archiv.wissen.application.WissenIngestPort;
import ch.urinet.archiv.wissen.application.WissenIngestPort.Fortschritt;
import ch.urinet.archiv.wissen.application.WissenIngestPort.NeueAenderung;
import ch.urinet.archiv.wissen.application.WissenIngestPort.SeitenFassung;
import ch.urinet.archiv.wissen.application.WissenIngestPort.SeitenMetadaten;
import ch.urinet.archiv.wissen.domain.Quelleneintrag;
import ch.urinet.archiv.wissen.domain.WissensPruefung;
import ch.urinet.archiv.gemeinsam.domain.Seitentext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.transaction.Transactional;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.hibernate.query.NativeQuery;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** {@link WissenIngestPort} mit SQL im Schema des Mandanten aus dem Hibernate-Kontext. */
@ApplicationScoped
@Transactional(Transactional.TxType.MANDATORY)
public class JdbcWissenIngest implements WissenIngestPort {

    private static final String ZEITGRENZE = "10s";
    private static final Pattern WORT = Pattern.compile("[\\p{L}][\\p{L}\\p{N}]*");
    private static final int MAX_SUCHBEGRIFFE = 200;
    private static final String WISSEN_ZWECKE = "'WISSEN_INGEST', 'WISSEN_LINT', 'WISSEN_EMBEDDING'";

    private final Instance<EntityManager> entityManager;

    @Inject
    ObjectMapper objectMapper;

    public JdbcWissenIngest(Instance<EntityManager> entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public Optional<Quelle> quelleLaden(UUID dokumentId) {
        zeitgrenze();
        @SuppressWarnings("unchecked")
        List<Object[]> zeilen = em().createNativeQuery("""
                        select d.art, d.status, d.vertraulichkeit, (d.bestaetigt is not null), d.volltext, d.seiten,
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
        return Optional.of(new Quelle((String) d[0], (String) d[1], (String) d[2], Boolean.TRUE.equals(d[3]),
                (String) d[4], d[5] == null ? null : ((Number) d[5]).intValue(), Boolean.TRUE.equals(d[6]),
                d[7] == null ? null : ((Number) d[7]).intValue(),
                d[8] == null ? null : ((Number) d[8]).intValue()));
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

    @Override
    public BudgetReservierung budgetReservieren(UUID entwurfId, long tokens, long budgetTokens,
                                                long monatsbudgetTokens, Instant von, Instant bis) {
        zeitgrenze();
        em().createNativeQuery("select pg_advisory_xact_lock(hashtextextended(current_schema() "
                        + "|| ':wissen-budget', 0))")
                .getSingleResult();
        em().createNativeQuery("delete from ki_verbrauch_reservierung where ablauf <= current_timestamp")
                .executeUpdate();
        long monatsverbrauch = zahl(em().createNativeQuery("select least(coalesce("
                        + "sum(tokens_ein::numeric + tokens_aus::numeric), 0), 9223372036854775807)::bigint "
                        + "from ki_verbrauch where zweck in (" + WISSEN_ZWECKE + ") "
                        + "and zeit >= :von and zeit < :bis")
                .setParameter("von", von).setParameter("bis", bis).getSingleResult());
        long monatsreserviert = zahl(em().createNativeQuery("""
                select least(coalesce(sum(tokens::numeric), 0), 9223372036854775807)::bigint
                from ki_verbrauch_reservierung
                where ablauf > current_timestamp""").getSingleResult());
        if (addiere(monatsverbrauch, monatsreserviert, tokens) > monatsbudgetTokens) {
            return new BudgetReservierung(null, true);
        }

        long laufverbrauch = zahl(em().createNativeQuery("""
                select least(coalesce(sum(tokens_ein::numeric + tokens_aus::numeric), 0),
                    9223372036854775807)::bigint
                from ki_verbrauch where zweck = 'WISSEN_INGEST' and ingest_entwurf_id = :id""")
                .setParameter("id", entwurfId).getSingleResult());
        long laufreserviert = zahl(em().createNativeQuery("""
                select least(coalesce(sum(tokens::numeric), 0), 9223372036854775807)::bigint
                from ki_verbrauch_reservierung
                where entwurf_id = :id and ablauf > current_timestamp""")
                .setParameter("id", entwurfId).getSingleResult());
        if (addiere(laufverbrauch, laufreserviert, tokens) > budgetTokens) {
            return new BudgetReservierung(null, false);
        }

        UUID id = UUID.randomUUID();
        em().createNativeQuery("""
                insert into ki_verbrauch_reservierung (id, entwurf_id, tokens, ablauf)
                values (:id, :entwurfId, :tokens, current_timestamp + interval '5 minutes')""")
                .setParameter("id", id).setParameter("entwurfId", entwurfId).setParameter("tokens", tokens)
                .executeUpdate();
        return new BudgetReservierung(id, false);
    }

    @Override
    public void budgetFreigeben(UUID reservierungId) {
        zeitgrenze();
        em().createNativeQuery("delete from ki_verbrauch_reservierung where id = :id")
                .setParameter("id", reservierungId).executeUpdate();
    }

    @Override
    public Optional<Lauf> laufLaden(UUID entwurfId) {
        zeitgrenze();
        @SuppressWarnings("unchecked")
        List<Object[]> zeilen = em().createNativeQuery("""
                        select e.id, e.dokument_id, e.status, d.status, d.vertraulichkeit,
                            (d.bestaetigt is not null), d.titel, d.datum, d.volltext, d.seiten,
                            (d.s3_pdfa is not null and d.s3_version_pdfa is not null and not d.pdfa_fehlt),
                            e.seite_von, e.seite_bis, e.schritt, e.tokens_ein, e.tokens_aus,
                            e.ki_modell, e.ingest_daten, e.unvollstaendig,
                            we.budget_tokens, we.monatsbudget_tokens, d.art
                        from wissens_entwurf e join dokument d on d.id = e.dokument_id
                        left join wissens_einstellung we on we.id = 1
                        where e.id = :id and e.anlass = 'INGEST'""")
                .setParameter("id", entwurfId)
                .getResultList();
        if (zeilen.isEmpty()) {
            return Optional.empty();
        }
        Object[] d = zeilen.getFirst();
        return Optional.of(new Lauf(uuid(d[0]), uuid(d[1]), (String) d[2], (String) d[3], (String) d[21],
                (String) d[4],
                Boolean.TRUE.equals(d[5]), (String) d[6], datum(d[7]),
                (String) d[8], d[9] == null ? null : ((Number) d[9]).intValue(), Boolean.TRUE.equals(d[10]),
                d[11] == null ? null : ((Number) d[11]).intValue(),
                d[12] == null ? null : ((Number) d[12]).intValue(), (String) d[13],
                ((Number) d[14]).intValue(), ((Number) d[15]).intValue(),
                (String) d[16], Boolean.TRUE.equals(d[18]),
                d[19] == null ? null : ((Number) d[19]).intValue(),
                d[20] == null ? null : ((Number) d[20]).intValue(), fortschritt(d[17])));
    }

    @Override
    public List<SeitenMetadaten> seitenMetadatenLaden() {
        zeitgrenze();
        return seitenMetadaten(em().createNativeQuery("""
                select s.id, s.pfad, s.typ, s.titel, s.beschreibung, to_json(s.tags), s.status, s.version,
                       s.vertraulichkeit_mindest
                from wissensseite s order by s.pfad
                """));
    }

    @Override
    public Set<UUID> personalDokumenteLaden(Set<UUID> dokumentIds) {
        if (dokumentIds.isEmpty()) {
            return Set.of();
        }
        zeitgrenze();
        List<UUID> ids = List.copyOf(dokumentIds);
        String parameter = java.util.stream.IntStream.range(0, ids.size())
                .mapToObj(index -> ":dokument" + index).collect(java.util.stream.Collectors.joining(", "));
        Query query = em().createNativeQuery("""
                select d.id from dokument d
                where d.id in (%s) and (d.bestaetigt is null or d.vertraulichkeit = 'PERSONAL')
                """.formatted(parameter));
        for (int i = 0; i < ids.size(); i++) {
            query.setParameter("dokument" + i, ids.get(i));
        }
        @SuppressWarnings("unchecked")
        List<Object> rows = query.getResultList();
        Set<UUID> result = new HashSet<>();
        rows.forEach(row -> result.add(uuid(row)));
        return Set.copyOf(result);
    }

    @Override
    public Map<UUID, WissensPruefung.DokumentQuelle> dokumentQuellenLaden(Set<UUID> dokumentIds) {
        if (dokumentIds.isEmpty()) {
            return Map.of();
        }
        zeitgrenze();
        List<UUID> ids = List.copyOf(dokumentIds);
        String parameter = java.util.stream.IntStream.range(0, ids.size())
                .mapToObj(index -> ":dokument" + index).collect(java.util.stream.Collectors.joining(", "));
        Query query = em().createNativeQuery("""
                select d.id, (d.status = 'ARCHIVIERT'), (d.bestaetigt is null or d.vertraulichkeit = 'PERSONAL'),
                       d.seiten, d.datum, d.volltext
                from dokument d where d.id in (%s)
                """.formatted(parameter));
        for (int i = 0; i < ids.size(); i++) {
            query.setParameter("dokument" + i, ids.get(i));
        }
        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        Map<UUID, WissensPruefung.DokumentQuelle> result = new HashMap<>();
        for (Object[] row : rows) {
            Integer pages = row[3] == null ? null : ((Number) row[3]).intValue();
            String fullText = (String) row[5];
            result.put(uuid(row[0]), new WissensPruefung.DokumentQuelle(true, Boolean.TRUE.equals(row[1]),
                    Boolean.TRUE.equals(row[2]), pages, datum(row[4]),
                    Seitentext.von(fullText, pages)));
        }
        return Map.copyOf(result);
    }

    @Override
    public List<SeitenMetadaten> seitenIndexLaden(Set<UUID> seiteIds, String suchtext) {
        String begriffe = oderSuche(suchtext);
        if (seiteIds.isEmpty() || begriffe.isEmpty()) {
            return List.of();
        }
        zeitgrenze();
        List<UUID> ids = List.copyOf(seiteIds);
        String parameter = java.util.stream.IntStream.range(0, ids.size())
                .mapToObj(index -> ":seite" + index).collect(java.util.stream.Collectors.joining(", "));
        String sql = """
                select s.id, s.pfad, s.typ, s.titel, s.beschreibung, to_json(s.tags), s.status, s.version,
                       s.vertraulichkeit_mindest
                from wissensseite s
                where s.id in (%s) and s.inhalt_tsv @@ websearch_to_tsquery('german', :suchtext)
                order by ts_rank_cd(s.inhalt_tsv, websearch_to_tsquery('german', :suchtext)) desc, s.pfad
                limit 500
                """.formatted(parameter);
        Query query = em().createNativeQuery(sql).setParameter("suchtext", begriffe);
        for (int i = 0; i < ids.size(); i++) {
            query.setParameter("seite" + i, ids.get(i));
        }
        return seitenMetadaten(query);
    }

    /**
     * Verbindet die Wörter der Kernaussagen mit «or» für {@code websearch_to_tsquery}: Ohne «or» verknüpft die Suche
     * alle Wörter mit UND und findet bei mehreren Aussagen kaum eine Seite (Req 5.4). Zahlen und Wörter unter drei
     * Zeichen tragen nichts zur Auswahl bei.
     */
    static String oderSuche(String suchtext) {
        if (suchtext == null) {
            return "";
        }
        Set<String> woerter = new LinkedHashSet<>();
        Matcher treffer = WORT.matcher(suchtext);
        while (treffer.find() && woerter.size() < MAX_SUCHBEGRIFFE) {
            String wort = treffer.group().toLowerCase(Locale.ROOT);
            if (wort.codePointCount(0, wort.length()) >= 3) {
                woerter.add(wort);
            }
        }
        return String.join(" or ", woerter);
    }

    @Override
    public Optional<UUID> quelleSeiteFinden(UUID dokumentId) {
        zeitgrenze();
        @SuppressWarnings("unchecked")
        List<Object> zeilen = em().createNativeQuery(
                        "select s.id from wissensseite s where s.wissensquelle_id = :dokument limit 1")
                .setParameter("dokument", dokumentId)
                .getResultList();
        return zeilen.stream().findFirst().map(JdbcWissenIngest::uuid);
    }

    @Override
    public Optional<SeitenFassung> seiteLaden(UUID seiteId) {
        zeitgrenze();
        @SuppressWarnings("unchecked")
        List<Object[]> zeilen = em().createNativeQuery("""
                        select s.id, s.pfad, s.typ, s.titel, s.beschreibung, to_json(s.tags), s.status, s.version,
                               s.vertraulichkeit_mindest, s.inhalt, s.wissensquelle_id
                        from wissensseite s where s.id = :id""")
                .setParameter("id", seiteId)
                .getResultList();
        if (zeilen.isEmpty()) {
            return Optional.empty();
        }
        Object[] s = zeilen.getFirst();
        SeitenMetadaten metadaten = new SeitenMetadaten(uuid(s[0]), (String) s[1], (String) s[2], (String) s[3],
                (String) s[4], tags(s[5]), (String) s[6], ((Number) s[7]).intValue(), (String) s[8]);
        return Optional.of(new SeitenFassung(metadaten, (String) s[9],
                s[10] == null ? null : uuid(s[10]), quellenLaden(seiteId)));
    }

    private List<Quelleneintrag> quellenLaden(UUID seiteId) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em().createNativeQuery("""
                        select schluessel, dokument_id, seite_von, seite_bis, auszug, quell_seite_id
                        from wissensseite_quelle where seite_id = :id order by schluessel""")
                .setParameter("id", seiteId)
                .getResultList();
        return rows.stream().map(row -> new Quelleneintrag((String) row[0],
                row[1] == null ? null : uuid(row[1]),
                row[2] == null ? null : ((Number) row[2]).intValue(),
                row[3] == null ? null : ((Number) row[3]).intValue(),
                (String) row[4], row[5] == null ? null : uuid(row[5]))).toList();
    }

    @Override
    public void aenderungAnlegen(NeueAenderung aenderung) {
        zeitgrenze();
        em().createNativeQuery("""
                        insert into wissens_aenderung (entwurf_id, nr, aktion, seite_id, pfad, basis_version,
                            daten, inhalt, diff, begruendung, widersprueche, entfernte_aussagen, befunde, hinweise,
                            vertraulichkeit_kontext, status)
                        values (:entwurfId, :nr, :aktion, :seiteId, :pfad, :basisVersion,
                            cast(:daten as jsonb), :inhalt, cast(:diff as jsonb), :begruendung,
                            cast(:widersprueche as jsonb), cast(:entfernte as jsonb), cast(:befunde as jsonb),
                            cast(:hinweise as jsonb), :kontext, 'OFFEN')
                        on conflict (entwurf_id, nr) do nothing""")
                .unwrap(NativeQuery.class)
                .setParameter("entwurfId", aenderung.entwurfId())
                .setParameter("nr", aenderung.nummer())
                .setParameter("aktion", aenderung.aktion())
                .setParameter("seiteId", aenderung.seiteId(), UUID.class)
                .setParameter("pfad", aenderung.pfad())
                .setParameter("basisVersion", aenderung.basisVersion(), Integer.class)
                .setParameter("daten", json(new AenderungsDaten(aenderung.daten().typ().datenbankwert(),
                        aenderung.daten().titel(), aenderung.daten().beschreibung(), aenderung.daten().tags(),
                        aenderung.daten().status().name(), aenderung.daten().veraltetAb(),
                        aenderung.daten().quellen(), aenderung.daten().vertraulichkeit().name(),
                        aenderung.daten().inhalt(), aenderung.daten().wissensquelleId())))
                .setParameter("inhalt", aenderung.daten().inhalt())
                .setParameter("diff", aenderung.diff())
                .setParameter("begruendung", aenderung.begruendung())
                .setParameter("widersprueche", json(aenderung.widersprueche()))
                .setParameter("entfernte", json(aenderung.entfernteAussagen()))
                .setParameter("befunde", json(aenderung.befunde()))
                .setParameter("hinweise", json(aenderung.hinweise()))
                .setParameter("kontext", aenderung.vertraulichkeitKontext())
                .executeUpdate();
    }

    @Override
    public void fortschrittSichern(UUID entwurfId, String erwarteterSchritt, String neuerSchritt,
                                    Fortschritt fortschritt, long tokensEin, long tokensAus,
                                    boolean unvollstaendig, String hinweis) {
        zeitgrenze();
        int aktualisiert = em().createNativeQuery("""
                        update wissens_entwurf
                        set schritt = :neu, ingest_daten = cast(:fortschritt as jsonb),
                            kernaussagen = cast(:kernaussagen as jsonb),
                            tokens_ein = :tokensEin, tokens_aus = :tokensAus,
                            unvollstaendig = :unvollstaendig,
                            hinweis = case when :hinweis is null then hinweis else :hinweis end
                        where id = :id and status = 'IN_ARBEIT' and schritt = :erwartet""")
                .unwrap(NativeQuery.class)
                .setParameter("neu", neuerSchritt)
                .setParameter("fortschritt", json(fortschritt))
                .setParameter("kernaussagen", json(fortschritt.kernaussagen()))
                .setParameter("tokensEin", Math.toIntExact(Math.min(Integer.MAX_VALUE, Math.max(0, tokensEin))))
                .setParameter("tokensAus", Math.toIntExact(Math.min(Integer.MAX_VALUE, Math.max(0, tokensAus))))
                .setParameter("unvollstaendig", unvollstaendig)
                .setParameter("hinweis", hinweis, String.class)
                .setParameter("id", entwurfId)
                .setParameter("erwartet", erwarteterSchritt)
                .executeUpdate();
        if (aktualisiert != 1) {
            throw new IllegalStateException("Ingest-Fortschritt nicht mehr aktuell");
        }
    }

    @Override
    public void entwurfSchliessen(UUID entwurfId, String erwarteterSchritt, String status, Fortschritt fortschritt,
                                   long tokensEin, long tokensAus, boolean unvollstaendig, String hinweis) {
        zeitgrenze();
        int aktualisiert = em().createNativeQuery("""
                        update wissens_entwurf
                        set status = :status, schritt = null, ingest_daten = cast(:fortschritt as jsonb),
                            kernaussagen = cast(:kernaussagen as jsonb),
                            tokens_ein = :tokensEin, tokens_aus = :tokensAus,
                            unvollstaendig = :unvollstaendig, hinweis = coalesce(:hinweis, hinweis),
                            abgeschlossen = clock_timestamp()
                        where id = :id and status = 'IN_ARBEIT' and schritt = :erwartet""")
                .unwrap(NativeQuery.class)
                .setParameter("status", status)
                .setParameter("fortschritt", json(fortschritt))
                .setParameter("kernaussagen", json(fortschritt.kernaussagen()))
                .setParameter("tokensEin", Math.toIntExact(Math.min(Integer.MAX_VALUE, Math.max(0, tokensEin))))
                .setParameter("tokensAus", Math.toIntExact(Math.min(Integer.MAX_VALUE, Math.max(0, tokensAus))))
                .setParameter("unvollstaendig", unvollstaendig)
                .setParameter("hinweis", hinweis, String.class)
                .setParameter("id", entwurfId)
                .setParameter("erwartet", erwarteterSchritt)
                .executeUpdate();
        if (aktualisiert != 1) {
            throw new IllegalStateException("Ingest-Entwurf nicht mehr aktuell");
        }
    }

    @Override
    public int handlerFehltNachholen(String hinweis) {
        zeitgrenze();
        return em().createNativeQuery("""
                        update wissens_entwurf alt
                        set status = 'WARTEND', schritt = 'KERNAUSSAGEN', kernaussagen = null,
                            ingest_daten = '{}'::jsonb, tokens_ein = 0, tokens_aus = 0,
                            unvollstaendig = false, hinweis = null, abgeschlossen = null
                        where alt.anlass = 'INGEST' and alt.status = 'FEHLER' and alt.dokument_id is not null
                            and alt.hinweis = :hinweis
                            and exists (
                                select 1 from dokument d
                                where d.id = alt.dokument_id and d.art = 'WISSENSQUELLE'
                            )
                            and not exists (
                                select 1 from wissens_entwurf neu
                                where neu.anlass = 'INGEST' and neu.dokument_id = alt.dokument_id
                                    and (neu.erstellt, neu.id) > (alt.erstellt, alt.id)
                            )""")
                .setParameter("hinweis", hinweis)
                .executeUpdate();
    }

    @Override
    public Optional<UUID> wartendenAktivieren(long monatsbudgetTokens, Instant von, Instant bis) {
        zeitgrenze();
        em().createNativeQuery("select pg_advisory_xact_lock(hashtextextended(current_schema() "
                        + "|| ':wissen-ingest-queue', 0))")
                .getSingleResult();
        boolean aktiverIngest = em().createNativeQuery("""
                        select exists (
                            select 1 from wissens_entwurf
                            where anlass = 'INGEST' and status in ('IN_ARBEIT', 'OFFEN'))""")
                .getSingleResult().equals(Boolean.TRUE);
        if (aktiverIngest) {
            return Optional.empty();
        }
        @SuppressWarnings("unchecked")
        List<Object> ids = em().createNativeQuery("""
                        select id from wissens_entwurf
                        where anlass = 'INGEST' and status = 'WARTEND'
                        order by erstellt, id
                        for update skip locked limit 1""")
                .getResultList();
        if (ids.isEmpty()) {
            return Optional.empty();
        }
        em().createNativeQuery("select pg_advisory_xact_lock(hashtextextended(current_schema() "
                        + "|| ':wissen-budget', 0))")
                .getSingleResult();
        long budget = zahl(em().createNativeQuery("""
                        select coalesce((select monatsbudget_tokens from wissens_einstellung where id = 1),
                            :standard)""")
                .setParameter("standard", monatsbudgetTokens)
                .getSingleResult());
        long verbraucht = zahl(em().createNativeQuery("""
                        select least(coalesce(sum(tokens_ein::numeric + tokens_aus::numeric), 0),
                            9223372036854775807)::bigint
                        from ki_verbrauch
                        where zweck in (%s) and zeit >= :von and zeit < :bis
                        """.formatted(WISSEN_ZWECKE))
                .setParameter("von", von).setParameter("bis", bis)
                .getSingleResult());
        long reserviert = zahl(em().createNativeQuery("""
                        select least(coalesce(sum(tokens::numeric), 0), 9223372036854775807)::bigint
                        from ki_verbrauch_reservierung where ablauf > current_timestamp""")
                .getSingleResult());
        if (addiere(verbraucht, reserviert) >= budget) {
            em().createNativeQuery("""
                            update wissens_entwurf set status = 'FEHLER', schritt = null,
                                hinweis = 'Budget erschöpft', abgeschlossen = clock_timestamp()
                            where anlass = 'INGEST' and status = 'WARTEND'""")
                    .executeUpdate();
            return Optional.empty();
        }
        UUID id = uuid(ids.getFirst());
        int aktualisiert = em().createNativeQuery("""
                        update wissens_entwurf set status = 'IN_ARBEIT', schritt = 'KERNAUSSAGEN',
                            kernaussagen = null, ingest_daten = '{}'::jsonb, tokens_ein = 0, tokens_aus = 0,
                            unvollstaendig = false, hinweis = null
                        where id = :id and status = 'WARTEND'""")
                .setParameter("id", id)
                .executeUpdate();
        return aktualisiert == 1 ? Optional.of(id) : Optional.empty();
    }

    private List<SeitenMetadaten> seitenMetadaten(Query query) {
        @SuppressWarnings("unchecked")
        List<Object[]> zeilen = query.getResultList();
        return seitenMetadaten(zeilen);
    }

    private List<SeitenMetadaten> seitenMetadaten(List<Object[]> zeilen) {
        List<SeitenMetadaten> result = new ArrayList<>(zeilen.size());
        for (Object[] s : zeilen) {
            result.add(new SeitenMetadaten(uuid(s[0]), (String) s[1], (String) s[2], (String) s[3], (String) s[4],
                    tags(s[5]), (String) s[6], ((Number) s[7]).intValue(), (String) s[8]));
        }
        return List.copyOf(result);
    }

    private List<String> tags(Object wert) {
        try {
            return objectMapper.readValue(wert.toString(), new TypeReference<>() {
            });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Wissensseiten-Schlagwörter nicht lesbar", e);
        }
    }

    private Fortschritt fortschritt(Object wert) {
        try {
            return objectMapper.readValue(wert.toString(), Fortschritt.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Ingest-Fortschritt nicht lesbar", e);
        }
    }

    private String json(Object wert) {
        try {
            return objectMapper.writeValueAsString(wert);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Ingest-Daten nicht serialisierbar", e);
        }
    }

    private static java.time.LocalDate datum(Object wert) {
        return switch (wert) {
            case null -> null;
            case java.time.LocalDate datum -> datum;
            case java.sql.Date datum -> datum.toLocalDate();
            default -> throw new IllegalStateException("Dokumentdatum hat einen ungültigen Datenbanktyp");
        };
    }

    private void zeitgrenze() {
        em().createNativeQuery("select set_config('statement_timeout', '" + ZEITGRENZE + "', true)").getSingleResult();
    }

    private EntityManager em() {
        return entityManager.get();
    }

    private static UUID uuid(Object wert) {
        return wert instanceof UUID id ? id : UUID.fromString(wert.toString());
    }

    private static long zahl(Object wert) {
        return wert == null ? 0 : ((Number) wert).longValue();
    }

    private static long addiere(long... werte) {
        long summe = 0;
        try {
            for (long wert : werte) {
                summe = Math.addExact(summe, Math.max(0, wert));
            }
            return summe;
        } catch (ArithmeticException e) {
            return Long.MAX_VALUE;
        }
    }

    private record AenderungsDaten(String typ, String titel, String beschreibung, List<String> tags, String status,
                                   java.time.LocalDate veraltetAb, List<ch.urinet.archiv.wissen.domain.Quelleneintrag> quellen,
                                   String vertraulichkeit, String inhalt, UUID wissensquelleId) {
    }
}
