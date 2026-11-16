package ch.urinet.archiv.dokument.adapter.out.persistence;

import ch.urinet.archiv.ki.application.chat.WerkzeugDaten;
import ch.urinet.archiv.sicherheit.domain.Sichtregel;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.transaction.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Abfragen der Chat-Werkzeuge (Spec 02, Aufgabe 7.1a) im Schema des Mandanten der laufenden Transaktion, jede mit dem
 * {@link VertraulichkeitFilter} der {@link Sichtregel} (Req 5.2), auch in Gesamtzahl und Zahl der Dokumente. Das SQL
 * entsteht nur aus festen Stücken; jeder Wert wird gebunden. Fristdaten gehen als Text {@code JJJJ-MM-TT}, damit ein
 * ungültiges Datum in einem Zusatzfeld die Abfrage nicht abbricht (ISO-Text sortiert wie das Datum).
 */
@ApplicationScoped
@Transactional(Transactional.TxType.MANDATORY)
public class PanacheWerkzeugDaten implements WerkzeugDaten {

    private static final String ISO = "'^[0-9]{4}-[0-9]{2}-[0-9]{2}$'";
    private static final String DATUM = "'^[0-9]{4}-(0[1-9]|1[0-2])-[0-9]{2}$'";
    private static final String MONAT = "'^[0-9]{4}-(0[1-9]|1[0-2])$'";
    private static final String BESTAETIGT = "d.status = 'ARCHIVIERT' and d.bestaetigt is not null";
    private static final String ZUSATZ = "zusatz.";
    private static final java.util.regex.Pattern FELD = java.util.regex.Pattern.compile("^[a-z][a-z0-9_]{0,62}$");

    @Override
    public void nurLesend() {
        // In PostgreSQL darf eine Transaktion jederzeit auf «nur lesend» wechseln, nur nicht zurück
        em().createNativeQuery("set transaction read only").executeUpdate();
    }

    @Override
    public Set<UUID> bestaetigte(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return Set.of();
        }
        @SuppressWarnings("unchecked")
        List<UUID> zeilen = em().createNativeQuery("select d.id from dokument d where d.id in (:ids) "
                + "and d.bestaetigt is not null").setParameter("ids", List.copyOf(ids)).getResultList();
        return new HashSet<>(zeilen);
    }

    @Override
    public Map<UUID, Integer> seiten(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        @SuppressWarnings("unchecked")
        List<Object[]> zeilen = em().createNativeQuery("select d.id, d.seiten from dokument d where d.id in (:ids) "
                + "and d.seiten is not null").setParameter("ids", List.copyOf(ids)).getResultList();
        Map<UUID, Integer> seiten = new HashMap<>();
        zeilen.forEach(z -> seiten.put((UUID) z[0], ((Number) z[1]).intValue()));
        return seiten;
    }

    @Override
    public Optional<String> inhalt(UUID id, Sichtregel regel, int zeichen) {
        VertraulichkeitFilter sicht = VertraulichkeitFilter.sql(regel, "d");
        Query abfrage = em().createNativeQuery("select left(d.volltext, :zeichen) from dokument d where d.id = :id and "
                + sicht.bedingung());
        sicht.parameter().forEach(abfrage::setParameter);
        @SuppressWarnings("unchecked")
        List<String> zeilen = abfrage.setParameter("id", id).setParameter("zeichen", zeichen).getResultList();
        // ein Dokument ohne Volltext (z. B. im Eingangskorb) liefert eine Zeile mit null: findFirst() würde daran scheitern
        return zeilen.stream().filter(java.util.Objects::nonNull).findFirst().filter(t -> !t.isBlank());
    }

    @Override
    public List<String> belegnummern(String art, int geschaeftsjahr, Sichtregel regel) {
        VertraulichkeitFilter sicht = VertraulichkeitFilter.sql(regel, "d");
        Query abfrage = em().createNativeQuery("select f.belegnummer from dokument d "
                + "join dokument_finanz f on f.dokument_id = d.id where " + BESTAETIGT + " and d.art = :art "
                + "and d.geschaeftsjahr = :gj and f.belegnummer is not null and " + sicht.bedingung());
        sicht.parameter().forEach(abfrage::setParameter);
        @SuppressWarnings("unchecked")
        List<String> zeilen = abfrage.setParameter("art", art).setParameter("gj", geschaeftsjahr).getResultList();
        return zeilen;
    }

    @Override
    public List<Monatszeile> monate(Monatsquelle quelle, Sichtregel regel) {
        VertraulichkeitFilter sicht = VertraulichkeitFilter.sql(regel, "d");
        Map<String, Object> parameter = new HashMap<>(sicht.parameter());
        parameter.put("art", quelle.art());
        String monat;
        String bedingung = BESTAETIGT + " and d.art = :art and " + sicht.bedingung();
        if (quelle.bezug().equals(BELEGDATUM)) {
            monat = "to_char(d.datum, 'YYYY-MM')";
            bedingung += " and d.datum is not null";
        } else {
            monat = "substr(d.zusatz ->> :bezug, 1, 7)";
            bedingung += " and (d.zusatz ->> :bezug) ~ " + DATUM;
            parameter.put("bezug", feld(quelle.bezug()));
        }
        if (quelle.nurMonat() != null) {
            bedingung += " and (d.zusatz ->> :nurMonat) ~ " + MONAT;
            parameter.put("nurMonat", feld(quelle.nurMonat()));
        }
        String schluessel;
        String anzeige;
        if (quelle.gruppe() == null) {
            schluessel = "''";
            anzeige = "''";
        } else if (quelle.gruppe().equals(PARTNER)) {
            schluessel = "coalesce(cast(d.partner_id as text), lower(nullif(btrim(d.partner_name), '')))";
            anzeige = "min(nullif(btrim(d.partner_name), ''))";
        } else {
            schluessel = "lower(nullif(btrim(d.zusatz ->> :gruppe), ''))";
            anzeige = "min(nullif(btrim(d.zusatz ->> :gruppe), ''))";
            parameter.put("gruppe", feld(quelle.gruppe()));
        }
        Query abfrage = em().createNativeQuery("select " + schluessel + ", " + anzeige + ", " + monat + " as monat, "
                + "count(*) from dokument d where " + bedingung + " group by 1, 3"
                + " order by 3, 2, 1");
        parameter.forEach(abfrage::setParameter);
        @SuppressWarnings("unchecked")
        List<Object[]> zeilen = abfrage.getResultList();
        List<Monatszeile> ergebnis = new ArrayList<>();
        for (Object[] z : zeilen) {
            ergebnis.add(new Monatszeile((String) z[0], (String) z[1], YearMonth.parse((String) z[2]),
                    ((Number) z[3]).longValue()));
        }
        return ergebnis;
    }

    @Override
    public long belege(Collection<String> arten, Sichtregel regel) {
        if (arten.isEmpty()) {
            return 0;
        }
        VertraulichkeitFilter sicht = VertraulichkeitFilter.sql(regel, "d");
        Query abfrage = em().createNativeQuery("select count(*) from dokument d where " + BESTAETIGT
                + " and d.art in (:arten) and " + sicht.bedingung());
        sicht.parameter().forEach(abfrage::setParameter);
        return ((Number) abfrage.setParameter("arten", List.copyOf(arten)).getSingleResult()).longValue();
    }

    /** {@code zusatz.<feld>} → {@code <feld>}. */
    private static String feld(String name) {
        if (!name.startsWith(ZUSATZ) || !FELD.matcher(name.substring(ZUSATZ.length())).matches()) {
            throw new IllegalArgumentException("ungültiges Feld");
        }
        return name.substring(ZUSATZ.length());
    }

    @Override
    public Fristseite fristen(LocalDate von, LocalDate bis, Sichtregel regel, List<Zusatzfrist> zusatz,
                              int hoechstens) {
        VertraulichkeitFilter sicht = VertraulichkeitFilter.sql(regel, "d");
        Map<String, Object> parameter = new HashMap<>(sicht.parameter());
        parameter.put("von", von);
        parameter.put("bis", bis);
        parameter.put("hoechstens", hoechstens);
        String basis = "d.status = 'ARCHIVIERT' and " + sicht.bedingung();
        List<String> teile = new ArrayList<>(List.of(
                quelle("v.naechste_frist", Quelle.KUENDIGUNGSFRIST, "join dokument_vertrag v on v.dokument_id = d.id",
                        basis),
                quelle("v.gueltig_bis", Quelle.GUELTIG_BIS, "join dokument_vertrag v on v.dokument_id = d.id", basis),
                quelle("f.faelligkeit", Quelle.FAELLIGKEIT, "join dokument_finanz f on f.dokument_id = d.id", basis)));
        for (int i = 0; i < zusatz.size(); i++) {
            String feld = "d.zusatz ->> :feld" + i;
            teile.add("select " + feld + " as datum, " + Quelle.ZUSATZ.ordinal() + " as quelle, d.id from dokument d "
                    + "where " + basis + " and d.art = :art" + i + " and " + feld + " ~ " + ISO + " and " + feld
                    + " between :vonText and :bisText");
            parameter.put("feld" + i, zusatz.get(i).feld());
            parameter.put("art" + i, zusatz.get(i).art());
        }
        if (!zusatz.isEmpty()) {
            parameter.put("vonText", von.toString());
            parameter.put("bisText", bis.toString());
        }
        String sql = """
                with frist as (
                    %s
                ), zahl as (
                    select count(*) as gesamt, count(distinct id) as dokumente from frist
                ), seite as (
                    select * from frist order by datum, quelle, id limit :hoechstens
                )
                select z.gesamt, z.dokumente, s.datum, s.quelle, d.id, d.titel, d.art, a.name, d.partner_name,
                    f.betrag, cast(f.waehrung as text), v.kuendigungsfrist
                from zahl z
                left join seite s on true
                left join dokument d on d.id = s.id
                left join dokumentart a on a.code = d.art
                left join dokument_finanz f on f.dokument_id = d.id
                left join dokument_vertrag v on v.dokument_id = d.id
                order by s.datum, s.quelle, s.id""".formatted(String.join("\n    union all\n    ", teile));
        Query abfrage = em().createNativeQuery(sql);
        parameter.forEach(abfrage::setParameter);
        @SuppressWarnings("unchecked")
        List<Object[]> zeilen = abfrage.getResultList();
        Object[] erste = zeilen.getFirst();
        List<Frist> fristen = new ArrayList<>();
        for (Object[] z : zeilen) {
            if (z[2] != null) {
                fristen.add(new Frist((String) z[2], Quelle.values()[((Number) z[3]).intValue()], (UUID) z[4],
                        (String) z[5], (String) z[6], (String) z[7], (String) z[8], (BigDecimal) z[9],
                        (String) z[10], (String) z[11]));
            }
        }
        return new Fristseite(fristen, ((Number) erste[0]).longValue(), ((Number) erste[1]).longValue());
    }

    @Override
    public KennzahlSeite kennzahl(KennzahlAnfrage anfrage, Sichtregel regel) {
        KennzahlSql sql = KennzahlSql.kennzahl(anfrage, regel);
        List<Object[]> gruppen = abfragen(sql.zeilen(), sql.parameter());
        List<KennzahlZeile> zeilen = new ArrayList<>();
        for (Object[] z : gruppen) {
            if (((Number) z[2]).longValue() > 0) {
                zeilen.add(new KennzahlZeile((String) z[0], (BigDecimal) z[1], ((Number) z[2]).longValue()));
            }
        }
        boolean gekuerzt = zeilen.size() > KennzahlSql.ZEILEN_MAX;
        Object[] summe = abfragen(sql.summe(), sql.parameter()).getFirst();
        List<UUID> belege = abfragen(sql.belege(), sql.parameter()).stream().map(r -> (UUID) r[0]).toList();
        return new KennzahlSeite(gekuerzt ? zeilen.subList(0, KennzahlSql.ZEILEN_MAX) : zeilen, (BigDecimal) summe[0],
                ((Number) summe[1]).longValue(), belege, ((Number) summe[2]).longValue(), gekuerzt);
    }

    @Override
    public OffenePostenSeite offenePosten(Richtung richtung, List<ArtRegel> arten, LocalDate von, LocalDate bis,
                                          Sichtregel regel, int hoechstens) {
        KennzahlSql sql = KennzahlSql.offenePosten(richtung, arten, von, bis, regel);
        Map<String, Object> parameter = new HashMap<>(sql.parameter());
        parameter.put("hoechstens", hoechstens);
        List<Object[]> zeilen = abfragen(sql.offen(), parameter);
        List<OffenerPosten> posten = new ArrayList<>();
        for (Object[] z : zeilen) {
            posten.add(new OffenerPosten((UUID) z[2], (String) z[3], (String) z[4], (BigDecimal) z[5], (String) z[6],
                    datum(z[1]), (String) z[7]));
        }
        return new OffenePostenSeite(posten, zeilen.isEmpty() ? 0 : ((Number) zeilen.getFirst()[0]).longValue());
    }

    private static LocalDate datum(Object wert) {
        return wert instanceof java.sql.Date d ? d.toLocalDate() : (LocalDate) wert;
    }

    @SuppressWarnings("unchecked")
    private static List<Object[]> abfragen(String sql, Map<String, Object> parameter) {
        Query abfrage = em().createNativeQuery(sql);
        parameter.forEach(abfrage::setParameter);
        List<?> zeilen = abfrage.getResultList();
        return zeilen.isEmpty() || zeilen.getFirst() instanceof Object[] ? (List<Object[]>) zeilen
                : zeilen.stream().map(w -> new Object[]{w}).toList();
    }

    private static String quelle(String spalte, Quelle quelle, String verbindung, String basis) {
        return "select to_char(" + spalte + ", 'YYYY-MM-DD') as datum, " + quelle.ordinal() + " as quelle, d.id "
                + "from dokument d " + verbindung + " where " + basis + " and " + spalte + " between :von and :bis";
    }

    private static EntityManager em() {
        return DokumentEintrag.getEntityManager();
    }
}
