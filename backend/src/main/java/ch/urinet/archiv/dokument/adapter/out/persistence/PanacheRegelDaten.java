package ch.urinet.archiv.dokument.adapter.out.persistence;

import ch.urinet.archiv.konfiguration.application.RegelDatenPort;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
@Transactional(Transactional.TxType.MANDATORY)
public class PanacheRegelDaten implements RegelDatenPort {

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

    private static String ausdruck(String feld, int index) {
        if (feld.startsWith("zusatz.")) {
            return "d.zusatz -> :feld" + index;
        }
        if (feld.startsWith("klassifikation.")) {
            return "(select coalesce(jsonb_agg(k.wert order by k.wert collate \"C\"), '[]'::jsonb) "
                    + "from dokument_klassifikation k where k.dokument_id = d.id "
                    + "and k.klassifikation = :feld" + index + ")";
        }
        String ausdruck = FELDER.get(feld);
        if (ausdruck == null) {
            throw new IllegalArgumentException("Unbekanntes Regel-Feld");
        }
        return ausdruck;
    }

    private String serialisieren(List<Object> werte) {
        try {
            return json.writeValueAsString(werte);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Regelwerte nicht serialisierbar", e);
        }
    }
}
