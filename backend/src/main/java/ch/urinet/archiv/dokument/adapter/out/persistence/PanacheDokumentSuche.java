package ch.urinet.archiv.dokument.adapter.out.persistence;

import ch.urinet.archiv.sicherheit.domain.Sichtregel;
import ch.urinet.archiv.suche.application.SuchePort;
import ch.urinet.archiv.suche.domain.Auszug;
import ch.urinet.archiv.suche.domain.Sortierung;
import ch.urinet.archiv.suche.domain.Suchanfrage;
import ch.urinet.archiv.suche.domain.Suchergebnis;
import ch.urinet.archiv.suche.domain.Suchtreffer;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Volltextsuche mit Filtern über {@code dokument} und {@code dokument_finanz} im Schema des Mandanten der Anfrage
 * (Aufgabe 4.5, Req 14.1–14.5, 14.7; design.md «Suche»). Eine Abfrage liefert Gesamtzahl und Seite unter demselben
 * {@link VertraulichkeitFilter}. Das SQL entsteht nur aus festen Stücken; jeder Wert der Anfrage wird gebunden. Der
 * Suchbegriff geht über {@code websearch_to_tsquery} (keine tsquery-Syntax des Benutzers, kein Fehler bei
 * Sonderzeichen); {@code statement_timeout} begrenzt die Kosten je Abfrage ({@code archiv.suche.zeitgrenze}).
 */
@ApplicationScoped
@Transactional(Transactional.TxType.MANDATORY)
public class PanacheDokumentSuche implements SuchePort {

    /** Höchstens so viele Zeichen des Volltexts gehen in {@code ts_headline} (Kosten der Textstelle). */
    static final int AUSZUG_ZEICHEN = 20_000;

    private static final String TSQ = "websearch_to_tsquery('german', :begriff)";
    private static final String MARKEN = "chr(2) || chr(3) || chr(31)";
    private static final String AUSZUG = "ts_headline('german', translate(concat_ws(' ', d.titel, d.partner_name, "
            + "archiv_tags_text(d.tags), left(d.volltext, " + AUSZUG_ZEICHEN + ")), " + MARKEN + ", ''), " + TSQ + ", "
            + "'StartSel=' || chr(2) || ', StopSel=' || chr(3) || ', FragmentDelimiter=' || chr(31) "
            + "|| ', MaxFragments=2, MaxWords=20, MinWords=8')";
    private static final String RELEVANZ = "belegtreffer desc, rang desc, eingang desc, id desc";
    private static final String DATUM = "datum desc nulls last, eingang desc, id desc";
    private static final TypeReference<List<String>> TEXTE = new TypeReference<>() {
    };

    private final Duration zeitgrenze;
    private final ObjectMapper json;

    public PanacheDokumentSuche(@ConfigProperty(name = "archiv.suche.zeitgrenze") Duration zeitgrenze,
                                ObjectMapper json) {
        this.zeitgrenze = zeitgrenze;
        this.json = json;
    }

    @Override
    public Suchergebnis suchen(Suchanfrage anfrage, Sichtregel regel) {
        Map<String, Object> parameter = new HashMap<>();
        String sql = sql(anfrage, regel, parameter);
        EntityManager em = DokumentEintrag.getEntityManager();
        em.createNativeQuery("select set_config('statement_timeout', :ms, true)")
                .setParameter("ms", Long.toString(zeitgrenze.toMillis())).getSingleResult();
        Query abfrage = em.createNativeQuery(sql);
        parameter.forEach(abfrage::setParameter);
        @SuppressWarnings("unchecked")
        List<Object[]> zeilen = abfrage.getResultList();
        long gesamt = ((Number) zeilen.getFirst()[0]).longValue();
        List<Suchtreffer> treffer = new ArrayList<>();
        for (Object[] z : zeilen) {
            if (z[1] != null) {
                treffer.add(treffer(z));
            }
        }
        return new Suchergebnis(treffer, gesamt);
    }

    /** Baut das SQL aus festen Stücken und legt die Werte in {@code parameter} ab. */
    static String sql(Suchanfrage a, Sichtregel regel, Map<String, Object> parameter) {
        VertraulichkeitFilter sicht = VertraulichkeitFilter.sql(regel, "d");
        parameter.putAll(sicht.parameter());
        List<String> bedingungen = new ArrayList<>();
        bedingungen.add(sicht.bedingung());
        boolean mitBegriff = a.begriff() != null;
        if (mitBegriff) {
            // exakte Belegnummer zählt als Treffer, auch ohne Volltext (Req 14.7)
            bedingungen.add("(d.volltext_tsv @@ " + TSQ + " or d.id = any (array(select b.dokument_id "
                    + "from dokument_finanz b where b.belegnummer = :begriff)))");
            parameter.put("begriff", a.begriff());
        }
        filter(bedingungen, parameter, "d.art = :art", "art", a.art());
        filter(bedingungen, parameter, "d.partner_id = :partner", "partner", a.partner());
        filter(bedingungen, parameter, "d.datum >= :datumVon", "datumVon", a.datumVon());
        filter(bedingungen, parameter, "d.datum <= :datumBis", "datumBis", a.datumBis());
        filter(bedingungen, parameter, "f.betrag >= :betragVon", "betragVon", a.betragVon());
        filter(bedingungen, parameter, "f.betrag <= :betragBis", "betragBis", a.betragBis());
        filter(bedingungen, parameter, "f.richtung = :richtung", "richtung",
                a.richtung() == null ? null : a.richtung().name());
        filter(bedingungen, parameter, "d.geschaeftsjahr = :geschaeftsjahr", "geschaeftsjahr", a.geschaeftsjahr());
        filter(bedingungen, parameter, "f.belegnummer = :belegnummer", "belegnummer", a.belegnummer());
        if (a.status() == null) {
            bedingungen.add("d.status <> 'VERWORFEN'");
        } else {
            filter(bedingungen, parameter, "d.status = :status", "status", a.status());
        }
        if (!a.tags().isEmpty()) {
            List<String> namen = new ArrayList<>();
            for (int i = 0; i < a.tags().size(); i++) {
                namen.add(":tag" + i);
                parameter.put("tag" + i, a.tags().get(i));
            }
            bedingungen.add("d.tags @> cast(array[" + String.join(", ", namen) + "] as text[])");
        }
        parameter.put("groesse", a.groesse());
        parameter.put("start", a.seite() * a.groesse());
        String ordnung = a.wirksameSortierung() == Sortierung.RELEVANZ ? RELEVANZ : DATUM;
        return """
                with treffer as materialized (
                    select d.id, d.datum, d.eingang, %s as rang, %s as belegtreffer
                    from dokument d left join dokument_finanz f on f.dokument_id = d.id
                    where %s
                ), seite as (
                    select * from treffer order by %s limit :groesse offset :start
                )
                select g.gesamt, s.id, s.art, s.titel, s.datum, s.eingang, s.status, s.vertraulichkeit, s.tags,
                    s.geschaeftsjahr, s.partner_id, s.partner_name, s.belegnummer, s.betrag, s.waehrung, s.richtung,
                    s.auszug
                from (select count(*) as gesamt from treffer) g
                left join (
                    select d.id, d.art, d.titel, d.datum, d.eingang, d.status,
                        case when d.bestaetigt is null then 'PERSONAL' else d.vertraulichkeit end as vertraulichkeit,
                        cast(array_to_json(d.tags) as text) as tags, d.geschaeftsjahr, d.partner_id, d.partner_name,
                        f.belegnummer, f.betrag, cast(f.waehrung as text) as waehrung, f.richtung, %s as auszug,
                        p.rang, p.belegtreffer
                    from seite p join dokument d on d.id = p.id
                    left join dokument_finanz f on f.dokument_id = d.id
                ) s on true
                order by %s""".formatted(
                mitBegriff ? "ts_rank_cd(d.volltext_tsv, " + TSQ + ")" : "cast(0 as real)",
                mitBegriff ? "coalesce(f.belegnummer = :begriff, false)" : "false",
                String.join("\n      and ", bedingungen),
                ordnung,
                mitBegriff ? AUSZUG : "cast(null as text)",
                ordnung.replaceAll("(^|, )", "$1s."));
    }

    private static void filter(List<String> bedingungen, Map<String, Object> parameter, String bedingung, String name,
                               Object wert) {
        if (wert != null) {
            bedingungen.add(bedingung);
            parameter.put(name, wert);
        }
    }

    private Suchtreffer treffer(Object[] z) {
        return new Suchtreffer((UUID) z[1], (String) z[2], (String) z[3], datum(z[4]), zeitpunkt(z[5]), (String) z[6],
                (String) z[7], texte((String) z[8]), z[9] == null ? null : ((Number) z[9]).intValue(), (UUID) z[10],
                (String) z[11], (String) z[12], (BigDecimal) z[13], (String) z[14], (String) z[15],
                Auszug.zerlegen((String) z[16]));
    }

    private List<String> texte(String jsonText) {
        try {
            return jsonText == null ? List.of() : json.readValue(jsonText, TEXTE);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("tags nicht lesbar", e);
        }
    }

    private static LocalDate datum(Object wert) {
        return switch (wert) {
            case null -> null;
            case LocalDate d -> d;
            case Date d -> d.toLocalDate();
            default -> throw new IllegalStateException("Unerwarteter Typ für datum: " + wert.getClass().getName());
        };
    }

    private static Instant zeitpunkt(Object wert) {
        return switch (wert) {
            case Instant i -> i;
            case OffsetDateTime o -> o.toInstant();
            case Timestamp t -> t.toInstant();
            default -> throw new IllegalStateException("Unerwarteter Typ für eingang: " + wert.getClass().getName());
        };
    }
}
