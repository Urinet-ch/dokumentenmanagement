package ch.urinet.archiv.dokument.adapter.out.persistence;

import ch.urinet.archiv.ki.application.chat.WerkzeugDaten;
import ch.urinet.archiv.sicherheit.domain.Sichtregel;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.transaction.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
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
    public Optional<String> inhalt(UUID id, Sichtregel regel, int zeichen) {
        VertraulichkeitFilter sicht = VertraulichkeitFilter.sql(regel, "d");
        Query abfrage = em().createNativeQuery("select left(d.volltext, :zeichen) from dokument d where d.id = :id and "
                + sicht.bedingung());
        sicht.parameter().forEach(abfrage::setParameter);
        @SuppressWarnings("unchecked")
        List<String> zeilen = abfrage.setParameter("id", id).setParameter("zeichen", zeichen).getResultList();
        return zeilen.stream().findFirst().filter(t -> !t.isBlank());
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

    private static String quelle(String spalte, Quelle quelle, String verbindung, String basis) {
        return "select to_char(" + spalte + ", 'YYYY-MM-DD') as datum, " + quelle.ordinal() + " as quelle, d.id "
                + "from dokument d " + verbindung + " where " + basis + " and " + spalte + " between :von and :bis";
    }

    private static EntityManager em() {
        return DokumentEintrag.getEntityManager();
    }
}
