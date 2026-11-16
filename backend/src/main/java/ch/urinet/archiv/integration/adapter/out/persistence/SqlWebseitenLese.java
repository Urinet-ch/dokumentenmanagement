package ch.urinet.archiv.integration.adapter.out.persistence;

import ch.urinet.archiv.integration.application.webseite.FesterFilter;
import ch.urinet.archiv.integration.application.webseite.WebseitenLesePort;
import ch.urinet.archiv.integration.domain.webseite.ListenFilter;
import ch.urinet.archiv.integration.domain.webseite.Lesezugriff;
import ch.urinet.archiv.integration.domain.webseite.WebseiteDokument;
import ch.urinet.archiv.integration.domain.webseite.WebseiteReihe;
import ch.urinet.archiv.integration.domain.webseite.WebseiteSeite;
import ch.urinet.archiv.integration.domain.webseite.WebseiteSortierung;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.sql.Date;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Lesende Abfragen der Webseiten-Schnittstelle als natives SQL im Schema des Mandanten der Anfrage (Spec 03,
 * Aufgabe 11.2). Jede Abfrage beginnt mit {@link FesterFilter#bedingung(String)} und bindet {@code :stufen} aus dem
 * {@link Lesezugriff}; Werte der Anfrage (Reihe, Jahr, Ausgabe, Suchbegriff, URL) werden nur gebunden, nie ins SQL
 * gesetzt. Die Felder {@code ausgabe} und {@code jahr} sind Zusatzfelder ({@code dokument.zusatz}). Ausgegeben wird
 * nie ein Feld ausser den Antwortfeldern (kein Dateiname, keine Vertraulichkeit, kein Status).
 * {@code statement_timeout} begrenzt die Kosten wie in der Suche ({@code archiv.suche.zeitgrenze}).
 */
@ApplicationScoped
@Transactional
public class SqlWebseitenLese implements WebseitenLesePort {

    /** Wie die Volltextsuche: Steuerzeichen der Markierung, die der Text selbst nicht tragen darf. */
    private static final String MARKEN = "chr(2) || chr(3) || chr(31)";
    private static final String BLANK = "chr(12) || chr(13) || chr(10) || chr(9)";
    private static final String ZEICHEN = "left(coalesce(d.volltext, ''), 20000)";
    private static final String SAUBER = "translate(translate(" + ZEICHEN + ", " + MARKEN + ", ''), " + BLANK
            + ", '    ')";
    /** Textstelle zum Suchbegriff, ohne Markierung (die Webseite hebt nicht hervor); aus dem Volltext allein. */
    private static final String AUSZUG_SUCHE = "translate(ts_headline('german', " + SAUBER
            + ", websearch_to_tsquery('german', :begriff), 'StartSel=' || chr(2) || ', StopSel=' || chr(3) "
            + "|| ', MaxFragments=1, MaxWords=35, MinWords=15'), " + MARKEN + ", '')";
    /** Anfang des Textes für die Details. */
    private static final String AUSZUG_ANFANG = "left(" + SAUBER + ", 600)";
    private static final String FELDER = "d.id, d.titel, d.art, a.name, d.zusatz ->> 'ausgabe', d.zusatz ->> 'jahr', "
            + "d.datum, d.zusatz ->> 'zugriff', d.seiten";
    private static final String VON = " from dokument d left join dokumentart a on a.code = d.art ";
    /**
     * Der feste Filter und dazu die bestätigte Art: eine Art ohne Bestätigung wirkt als {@code PERSONAL} (Spec 01,
     * Req 2.21), wie in der Sicht der Suche ({@code Sichtregel#fuerWebseite}).
     */
    private static final String FEST = FesterFilter.bedingung("d") + " and d.bestaetigt is not null";
    private static final Pattern JAHR = Pattern.compile("^[0-9]{4}$");

    // lazy: ohne Datenquelle (Start ohne Datenbank) ist der EntityManager nicht aktiv, der Start darf daran nicht scheitern
    private final Instance<EntityManager> em;
    private final Duration zeitgrenze;

    public SqlWebseitenLese(Instance<EntityManager> em, @ConfigProperty(name = "archiv.suche.zeitgrenze") Duration zeitgrenze) {
        this.em = em;
        this.zeitgrenze = zeitgrenze;
    }

    @Override
    public WebseiteSeite liste(Lesezugriff zugriff, ListenFilter filter, WebseiteSortierung sortierung, int seite,
                               int proSeite) {
        Map<String, Object> parameter = new HashMap<>();
        String bedingung = bedingung(zugriff, filter, parameter);
        String ordnung = switch (sortierung) {
            case DATUM_AB -> "d.datum desc nulls last, d.id";
            case DATUM_AUF -> "d.datum asc nulls last, d.id";
            case TITEL -> "lower(d.titel) nulls last, d.id";
        };
        vorbereiten();
        long gesamt = ((Number) abfrage("select count(*)" + VON + "where " + bedingung, parameter).getSingleResult())
                .longValue();
        Query q = abfrage("select " + FELDER + ", cast(null as text)" + VON + "where " + bedingung + " order by "
                + ordnung + " limit :proSeite offset :start", parameter);
        q.setParameter("proSeite", proSeite).setParameter("start", (long) (seite - 1) * proSeite);
        return new WebseiteSeite(dokumente(q), seite, proSeite, gesamt);
    }

    @Override
    public List<WebseiteDokument> treffer(Lesezugriff zugriff, List<UUID> ids, String begriff) {
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<String, Object> parameter = new HashMap<>();
        parameter.put("ids", ids.toArray(UUID[]::new));
        parameter.put("begriff", begriff);
        parameter.put(FesterFilter.PARAMETER, FesterFilter.stufen(zugriff));
        vorbereiten();
        List<WebseiteDokument> geladen = dokumente(abfrage("select " + FELDER + ", " + AUSZUG_SUCHE + VON
                + "where d.id = any(:ids) and " + FEST, parameter));
        Map<UUID, WebseiteDokument> nachId = new LinkedHashMap<>();
        geladen.forEach(d -> nachId.put(d.id(), d));
        List<WebseiteDokument> geordnet = new ArrayList<>();
        for (UUID id : ids) {
            WebseiteDokument d = nachId.get(id);
            if (d != null) {
                geordnet.add(d);
            }
        }
        return geordnet;
    }

    @Override
    public Optional<WebseiteDokument> dokument(Lesezugriff zugriff, UUID id) {
        Map<String, Object> parameter = new HashMap<>();
        parameter.put("id", id);
        parameter.put(FesterFilter.PARAMETER, FesterFilter.stufen(zugriff));
        vorbereiten();
        return dokumente(abfrage("select " + FELDER + ", " + AUSZUG_ANFANG + VON + "where d.id = :id and "
                + FEST, parameter)).stream().findFirst();
    }

    @Override
    public List<WebseiteReihe> reihen(Lesezugriff zugriff) {
        Map<String, Object> parameter = new HashMap<>();
        parameter.put(FesterFilter.PARAMETER, FesterFilter.stufen(zugriff));
        vorbereiten();
        @SuppressWarnings("unchecked")
        List<Object[]> zeilen = abfrage("select a.code, a.name from dokumentart a where a.aktiv and exists "
                + "(select 1 from dokument d where d.art = a.code and " + FEST
                + ") order by lower(a.name), a.code", parameter).getResultList();
        return zeilen.stream().map(z -> new WebseiteReihe((String) z[0], (String) z[1])).toList();
    }

    @Override
    public Optional<UUID> umleitung(Lesezugriff zugriff, String alteUrl) {
        Map<String, Object> parameter = new HashMap<>();
        parameter.put("url", alteUrl);
        parameter.put(FesterFilter.PARAMETER, FesterFilter.stufen(zugriff));
        vorbereiten();
        @SuppressWarnings("unchecked")
        List<Object> zeilen = abfrage("select d.id from alte_url u join dokument d on d.id = u.dokument_id "
                + "where u.url = :url and " + FEST, parameter).getResultList();
        return zeilen.stream().findFirst().map(o -> (UUID) o);
    }

    private static String bedingung(Lesezugriff zugriff, ListenFilter filter, Map<String, Object> parameter) {
        StringBuilder bedingung = new StringBuilder(FEST);
        parameter.put(FesterFilter.PARAMETER, FesterFilter.stufen(zugriff));
        if (filter.reihe() != null) {
            bedingung.append(" and d.art = :reihe");
            parameter.put("reihe", filter.reihe());
        }
        if (filter.jahr() != null) {
            bedingung.append(" and d.zusatz ->> 'jahr' = :jahr");
            parameter.put("jahr", filter.jahr());
        }
        if (filter.ausgabe() != null) {
            bedingung.append(" and d.zusatz ->> 'ausgabe' = :ausgabe");
            parameter.put("ausgabe", filter.ausgabe());
        }
        return bedingung.toString();
    }

    private void vorbereiten() {
        em.get().createNativeQuery("select set_config('statement_timeout', :ms, true)")
                .setParameter("ms", Long.toString(zeitgrenze.toMillis())).getSingleResult();
    }

    private Query abfrage(String sql, Map<String, Object> parameter) {
        Query q = em.get().createNativeQuery(sql);
        parameter.forEach(q::setParameter);
        return q;
    }

    private static List<WebseiteDokument> dokumente(Query q) {
        @SuppressWarnings("unchecked")
        List<Object[]> zeilen = q.getResultList();
        List<WebseiteDokument> ergebnis = new ArrayList<>();
        for (Object[] z : zeilen) {
            String code = (String) z[2];
            String jahr = (String) z[5];
            ergebnis.add(new WebseiteDokument((UUID) z[0], (String) z[1],
                    code == null || z[3] == null ? null : new WebseiteReihe(code, (String) z[3]), (String) z[4],
                    jahr != null && JAHR.matcher(jahr).matches() ? Integer.valueOf(jahr) : null, datum(z[6]),
                    (String) z[7], z[8] == null ? null : ((Number) z[8]).intValue(), (String) z[9]));
        }
        return ergebnis;
    }

    private static LocalDate datum(Object wert) {
        return switch (wert) {
            case null -> null;
            case LocalDate d -> d;
            case Date d -> d.toLocalDate();
            default -> throw new IllegalStateException("Unerwarteter Typ für datum: " + wert.getClass().getName());
        };
    }
}
