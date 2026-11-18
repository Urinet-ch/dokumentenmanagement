package ch.urinet.archiv.dokument.adapter.out.persistence;

import ch.urinet.archiv.konfiguration.application.RegelDatenPort;
import ch.urinet.archiv.konfiguration.application.RegelVerletzungsZaehler;
import ch.urinet.archiv.konfiguration.domain.Regel;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.transaction.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
@Transactional(Transactional.TxType.MANDATORY)
public class PanacheRegelDaten implements RegelDatenPort, RegelVerletzungsZaehler {

    private static final Map<String, String> FELDER = Map.ofEntries(
            Map.entry("titel", "to_jsonb(d.titel)"),
            Map.entry("datum", "to_jsonb(d.datum)"),
            Map.entry("partner", "to_jsonb(d.partner_id)"),
            Map.entry("projekt", "to_jsonb(d.projekt)"),
            Map.entry("tags", "(select coalesce(jsonb_agg(t order by t collate \"C\"), '[]'::jsonb) "
                    + "from (select distinct unnest(d.tags) t) tags)"),
            Map.entry("vertraulichkeit", "to_jsonb(d.vertraulichkeit)"),
            Map.entry("person_benutzer", "to_jsonb(d.person_benutzer)"),
            Map.entry("belegnummer", "to_jsonb(f.belegnummer)"),
            Map.entry("richtung", "to_jsonb(f.richtung)"),
            Map.entry("betrag", "to_jsonb(f.betrag)"),
            Map.entry("waehrung", "to_jsonb(f.waehrung)"),
            Map.entry("faelligkeit", "to_jsonb(f.faelligkeit)"),
            Map.entry("qr_referenz", "to_jsonb(f.qr_referenz)"),
            Map.entry("konto", "to_jsonb(f.konto)"),
            Map.entry("gueltig_ab", "to_jsonb(v.gueltig_ab)"),
            Map.entry("gueltig_bis", "to_jsonb(v.gueltig_bis)"),
            Map.entry("kuendigungsfrist", "to_jsonb(v.kuendigungsfrist)"),
            Map.entry("naechste_frist", "to_jsonb(v.naechste_frist)"));

    private static final String SPERRE = "select 1 from pg_advisory_xact_lock(hashtextextended("
            + "'regel:' || current_schema() || ':' || :schluessel, 0))";

    private final Instance<EntityManager> em;
    private final ObjectMapper json;

    @Inject
    public PanacheRegelDaten(Instance<EntityManager> em, ObjectMapper json) {
        this.em = em;
        this.json = json;
    }

    private EntityManager entityManager() {
        return em.get();
    }

    @Override
    public boolean dubletteVorhanden(String art, boolean ueberAlleArten, List<String> felder, List<Object> werte,
                                     UUID dokumentId, boolean sperren) {
        List<String> ausdruecke = new ArrayList<>(felder.size());
        for (int i = 0; i < felder.size(); i++) {
            ausdruecke.add(ausdruck(felder.get(i), i));
        }
        String werteJson = serialisieren(werte);
        if (sperren) {
            // Prüfen und Speichern sind nicht atomar: gleiche Werte derselben Regel werden bis zum Commit serialisiert
            entityManager().createNativeQuery(SPERRE)
                    .setParameter("schluessel", (ueberAlleArten ? "*" : art) + ":" + String.join(",", felder) + ":"
                            + werteJson)
                    .getSingleResult();
        }
        // Grundmenge (Req 5.1): bestätigte Dokumente, die archiviert werden oder sind. Generator-Dokumente sind schon
        // beim Erfassen bestätigt und warten bis zum Worker in NEU/VERARBEITUNG (oder nach Fehlversuchen in FEHLER,
        // «Wiederholen» archiviert sie später); sie zählen mit, sonst ginge eine zweite Erfassung bis dahin durch.
        String sql = "select exists (select 1 from dokument d "
                + "left join dokument_finanz f on f.dokument_id = d.id "
                + "left join dokument_vertrag v on v.dokument_id = d.id "
                + "where d.bestaetigt is not null "
                + "and (d.status in ('ARCHIVIERUNG', 'ARCHIVIERUNG_FEHLER', 'ARCHIVIERT') "
                + "or d.quelle = 'GENERATOR' and d.status in ('NEU', 'VERARBEITUNG', 'FEHLER')) "
                + (ueberAlleArten ? "" : "and d.art = :art ")
                + (dokumentId == null ? "" : "and d.id <> :dokumentId ")
                + "and jsonb_build_array(" + String.join(", ", ausdruecke) + ") = cast(:werte as jsonb))";
        var abfrage = entityManager().createNativeQuery(sql)
                .setParameter("werte", werteJson);
        if (!ueberAlleArten) {
            abfrage.setParameter("art", art);
        }
        if (dokumentId != null) {
            abfrage.setParameter("dokumentId", dokumentId);
        }
        for (int i = 0; i < felder.size(); i++) {
            String feld = felder.get(i);
            if (feld.startsWith("zusatz.")) {
                abfrage.setParameter("feld" + i, feld.substring("zusatz.".length()));
            } else if (feld.startsWith("klassifikation.")) {
                abfrage.setParameter("feld" + i, feld.substring("klassifikation.".length()));
            }
        }
        return (Boolean) abfrage.getSingleResult();
    }

    @Override
    public long anzahlBetroffeneDokumente(Regel regel) {
        Regel.Parameter parameter = regel.typisierteParameter();
        if (parameter instanceof Regel.PflichtWenn pflicht) {
            return pflichtVerletzungen(regel, pflicht);
        }
        if (parameter instanceof Regel.Eindeutig eindeutig) {
            return eindeutigVerletzungen(regel, eindeutig);
        }
        // FOLGE und ERWARTET melden Lücken, keine Verletzung eines einzelnen Dokuments.
        return 0;
    }

    private long pflichtVerletzungen(Regel regel, Regel.PflichtWenn pflicht) {
        List<String> gebundeneFelder = new ArrayList<>();
        String wennTyp = ausdruck(pflicht.wennFeld(), gebundeneFelder);
        String wennArray = ausdruck(pflicht.wennFeld(), gebundeneFelder);
        String wennEinzelwert = ausdruck(pflicht.wennFeld(), gebundeneFelder);
        List<String> fehlende = new ArrayList<>();
        for (String feld : pflicht.dann()) {
            fehlende.add(leer(feld, gebundeneFelder));
        }
        String bedingung = "case when jsonb_typeof(" + wennTyp + ") = 'array' then "
                + "exists (select 1 from jsonb_array_elements(" + wennArray + ") as items(value) "
                + "where value = cast(:gleich as jsonb)) else " + wennEinzelwert
                + " = cast(:gleich as jsonb) end";
        String sql = "select count(*) " + dokumenteVonRegel(regel)
                + " and (" + bedingung + ") and (" + String.join(" or ", fehlende) + ")";
        Query abfrage = entityManager().createNativeQuery(sql)
                .setParameter("gleich", serialisieren(pflicht.gleich()));
        felderBinden(abfrage, gebundeneFelder);
        if (regel.art() != null) {
            abfrage.setParameter("art", regel.art());
        }
        return ((Number) abfrage.getSingleResult()).longValue();
    }

    private long eindeutigVerletzungen(Regel regel, Regel.Eindeutig eindeutig) {
        List<String> gebundeneFelder = new ArrayList<>();
        List<String> ausdruecke = new ArrayList<>(eindeutig.felder().size());
        List<String> vorhanden = new ArrayList<>(eindeutig.felder().size());
        for (String feld : eindeutig.felder()) {
            ausdruecke.add(ausdruck(feld, gebundeneFelder));
            vorhanden.add("not " + leer(feld, gebundeneFelder));
        }
        String artFilter = regel.art() == null || eindeutig.ueberAlleArten() ? ""
                : " and d.art = :art";
        String sql = "select coalesce(sum(anzahl), 0) from (select count(*) as anzahl "
                + dokumenteVonRegel(regel, artFilter) + " and " + String.join(" and ", vorhanden)
                + " group by jsonb_build_array(" + String.join(", ", ausdruecke) + ") having count(*) > 1) "
                + "doppelte";
        Query abfrage = entityManager().createNativeQuery(sql);
        felderBinden(abfrage, gebundeneFelder);
        if (!artFilter.isEmpty()) {
            abfrage.setParameter("art", regel.art());
        }
        return ((Number) abfrage.getSingleResult()).longValue();
    }

    private static String dokumenteVonRegel(Regel regel) {
        return dokumenteVonRegel(regel, regel.art() == null ? "" : " and d.art = :art");
    }

    private static String dokumenteVonRegel(Regel regel, String artFilter) {
        return """
                from dokument d
                left join dokument_finanz f on f.dokument_id = d.id
                left join dokument_vertrag v on v.dokument_id = d.id
                where d.bestaetigt is not null
                and (d.status in ('ARCHIVIERUNG', 'ARCHIVIERUNG_FEHLER', 'ARCHIVIERT')
                    or d.quelle = 'GENERATOR' and d.status in ('NEU', 'VERARBEITUNG', 'FEHLER'))"""
                + artFilter;
    }

    private static String leer(String feld, List<String> gebundeneFelder) {
        return "(" + ausdruck(feld, gebundeneFelder) + " is null or "
                + ausdruck(feld, gebundeneFelder) + " = 'null'::jsonb or "
                + ausdruck(feld, gebundeneFelder) + " in ('\"\"'::jsonb, '[]'::jsonb, '{}'::jsonb) or "
                + "(jsonb_typeof(" + ausdruck(feld, gebundeneFelder) + ") = 'string' and "
                + ausdruck(feld, gebundeneFelder) + " #>> '{}' ~ '^[[:space:]]*$'))";
    }

    private static void felderBinden(Query abfrage, List<String> felder) {
        for (int i = 0; i < felder.size(); i++) {
            String feld = felder.get(i);
            if (feld.startsWith("zusatz.")) {
                abfrage.setParameter("feld" + i, feld.substring("zusatz.".length()));
            } else if (feld.startsWith("klassifikation.")) {
                abfrage.setParameter("feld" + i, feld.substring("klassifikation.".length()));
            }
        }
    }

    private static String ausdruck(String feld, int index) {
        if (feld.startsWith("zusatz.")) {
            return "d.zusatz -> cast(:feld" + index + " as text)";
        }
        if (feld.startsWith("klassifikation.")) {
            return "(select coalesce(jsonb_agg(k.wert order by k.wert collate \"C\"), '[]'::jsonb) "
                    + "from dokument_klassifikation k where k.dokument_id = d.id "
                    + "and k.klassifikation = cast(:feld" + index + " as text))";
        }
        String ausdruck = FELDER.get(feld);
        if (ausdruck == null) {
            throw new IllegalArgumentException("Unbekanntes Regel-Feld");
        }
        return ausdruck;
    }

    private static String ausdruck(String feld, List<String> gebundeneFelder) {
        int index = gebundeneFelder.size();
        gebundeneFelder.add(feld);
        return ausdruck(feld, index);
    }

    private String serialisieren(Object werte) {
        try {
            return json.writeValueAsString(werte);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Regelwerte nicht serialisierbar", e);
        }
    }
}
