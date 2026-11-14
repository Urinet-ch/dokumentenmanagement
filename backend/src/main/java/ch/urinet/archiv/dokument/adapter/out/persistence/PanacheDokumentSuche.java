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

    static final String TSQ = "websearch_to_tsquery('german', :begriff)";
    /** Steuerzeichen der Markierung; die Abfrage entfernt sie vorher aus dem Text ({@link Auszug}). */
    static final String MARKEN = "chr(2) || chr(3) || chr(31)";
    /** Optionen von {@code ts_headline} (Req 14.4), auch für den Auszug reiner Vektortreffer der Hybridsuche. */
    static final String AUSZUG_OPTIONEN = "'StartSel=' || chr(2) || ', StopSel=' || chr(3) || ', FragmentDelimiter=' "
            + "|| chr(31) || ', MaxFragments=2, MaxWords=20, MinWords=8'";
    // der Dateiname in der Form des Index, sonst bildete ts_headline andere Wörter (Req 14.8)
    private static final String AUSZUG_DATEINAME = "nullif(archiv_dateiname_text(d.dateiname), ''), ";
    /*
     * Ohne Dateinamen (Req 3.13) treffen und ranken nur die Teile A–C. Bewusst ohne zusätzliches
     * "volltext_tsv @@ q" als Vorfilter: Bei einer Negation («-Wort») entschiede sonst der Dateiname mit. Den Ausdruck
     * trägt der GIN-Index dokument_volltext_ohne_dateiname_ix (Changeset 028, Spec 02, Aufgabe 7.1a); er muss
     * wörtlich gleich bleiben, sonst greift der Index nicht.
     */
    private static final String OHNE_DATEINAME = "ts_filter(d.volltext_tsv, '{a,b,c}')";
    private static final String AUSZUG = "ts_headline('german', translate(concat_ws(' ', d.titel, d.partner_name, "
            + "archiv_tags_text(d.tags), %sleft(d.volltext, " + AUSZUG_ZEICHEN + ")), " + MARKEN + ", ''), " + TSQ
            + ", " + AUSZUG_OPTIONEN + ")";
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
    public Suchergebnis suchen(Suchanfrage anfrage, Sichtregel regel, boolean mitDateiname) {
        Map<String, Object> parameter = new HashMap<>();
        String sql = sql(anfrage, regel, mitDateiname, parameter);
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
    static String sql(Suchanfrage a, Sichtregel regel, boolean mitDateiname, Map<String, Object> parameter) {
        List<String> bedingungen = SuchFilter.bedingungen(a, regel, parameter);
        boolean mitBegriff = a.begriff() != null;
        String vektor = mitDateiname ? "d.volltext_tsv" : OHNE_DATEINAME;
        if (mitBegriff) {
            // exakte Belegnummer zählt als Treffer, auch ohne Volltext (Req 14.7); nach der Sicht wie bisher
            bedingungen.add(1, "(" + vektor + " @@ " + TSQ + " or d.id = any (array(select b.dokument_id "
                    + "from dokument_finanz b where b.belegnummer = :begriff)))");
            parameter.put("begriff", a.begriff());
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
                    s.auszug, s.dateiname
                from (select count(*) as gesamt from treffer) g
                left join (
                    select d.id, d.art, d.titel, d.datum, d.eingang, d.status,
                        case when d.bestaetigt is null then 'PERSONAL' else d.vertraulichkeit end as vertraulichkeit,
                        cast(array_to_json(d.tags) as text) as tags, d.geschaeftsjahr, d.partner_id, d.partner_name,
                        f.belegnummer, f.betrag, cast(f.waehrung as text) as waehrung, f.richtung, %s as auszug,
                        %s as dateiname, p.rang, p.belegtreffer
                    from seite p join dokument d on d.id = p.id
                    left join dokument_finanz f on f.dokument_id = d.id
                ) s on true
                order by %s""".formatted(
                mitBegriff ? "ts_rank_cd(" + vektor + ", " + TSQ + ")" : "cast(0 as real)",
                mitBegriff ? "coalesce(f.belegnummer = :begriff, false)" : "false",
                String.join("\n      and ", bedingungen),
                ordnung,
                mitBegriff ? AUSZUG.formatted(mitDateiname ? AUSZUG_DATEINAME : "") : "cast(null as text)",
                mitDateiname ? "d.dateiname" : "cast(null as text)",
                ordnung.replaceAll("(^|, )", "$1s."));
    }

    private Suchtreffer treffer(Object[] z) {
        return treffer(z, 1, json);
    }

    /**
     * Treffer aus einer Zeile ab Spalte {@code ab}: id, art, titel, datum, eingang, status, vertraulichkeit, tags (JSON),
     * geschaeftsjahr, partner_id, partner_name, belegnummer, betrag, waehrung, richtung, auszug, dateiname.
     */
    static Suchtreffer treffer(Object[] z, int ab, ObjectMapper json) {
        int i = ab;
        return new Suchtreffer((UUID) z[i], (String) z[i + 1], (String) z[i + 2], datum(z[i + 3]),
                zeitpunkt(z[i + 4]), (String) z[i + 5], (String) z[i + 6], texte((String) z[i + 7], json),
                z[i + 8] == null ? null : ((Number) z[i + 8]).intValue(), (UUID) z[i + 9], (String) z[i + 10],
                (String) z[i + 11], (BigDecimal) z[i + 12], (String) z[i + 13], (String) z[i + 14],
                Auszug.zerlegen((String) z[i + 15]), (String) z[i + 16]);
    }

    private static List<String> texte(String jsonText, ObjectMapper json) {
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
