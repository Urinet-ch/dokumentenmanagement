package ch.urinet.archiv.konfiguration.application;

import ch.urinet.archiv.dokumentart.application.ZusatzSchemaPruefer;
import ch.urinet.archiv.gemeinsam.domain.RegelVerletzt;
import ch.urinet.archiv.konfiguration.domain.Regel;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@ApplicationScoped
public class RegelPruefung {

    private final KonfigurationVerwaltung konfiguration;
    private final RegelDatenPort daten;
    private final ZusatzSchemaPruefer zusatzSchema;

    public RegelPruefung(KonfigurationVerwaltung konfiguration, RegelDatenPort daten,
                         ZusatzSchemaPruefer zusatzSchema) {
        this.konfiguration = konfiguration;
        this.daten = daten;
        this.zusatzSchema = zusatzSchema;
    }

    public Map<String, Object> zusatzWerte(String json) {
        return zusatzSchema.werteLesen(json);
    }

    public List<Warnung> pruefen(String art, Map<String, Object> werte, Map<String, Object> vorher,
                                 UUID dokumentId) {
        List<RegelVerletzt.Befund> fehler = new ArrayList<>();
        List<Warnung> warnungen = new ArrayList<>();
        for (Regel regel : konfiguration.aktuell().regeln()) {
            if (!regel.aktiv() || regel.art() != null && !regel.art().equals(art)) {
                continue;
            }
            Regel.Parameter parameter = regel.typisierteParameter();
            if (!(parameter instanceof Regel.PflichtWenn || parameter instanceof Regel.Eindeutig)) {
                continue;
            }
            if (!verletzt(regel, parameter, werte, dokumentId)) {
                continue;
            }
            boolean geaendert = vorher == null || parameter.felder().stream()
                    .anyMatch(feld -> !Objects.deepEquals(vorher.get(feld), werte.get(feld)));
            String schwere = geaendert ? regel.schwere() : "WARNUNG";
            if ("FEHLER".equals(schwere)) {
                fehler.add(new RegelVerletzt.Befund(regel.code(), regel.meldung()));
            } else {
                warnungen.add(new Warnung(regel.code(), regel.meldung()));
            }
        }
        if (!fehler.isEmpty()) {
            throw new RegelVerletzt(fehler);
        }
        return List.copyOf(warnungen);
    }

    private boolean verletzt(Regel regel, Regel.Parameter parameter, Map<String, Object> werte, UUID dokumentId) {
        if (parameter instanceof Regel.PflichtWenn pflicht) {
            Object bedingung = werte.get(pflicht.wennFeld());
            boolean gilt = bedingung instanceof List<?> liste
                    ? liste.contains(pflicht.gleich()) : Objects.equals(bedingung, pflicht.gleich());
            return gilt && pflicht.dann().stream().anyMatch(feld -> !vorhanden(werte.get(feld)));
        }
        Regel.Eindeutig eindeutig = (Regel.Eindeutig) parameter;
        List<Object> werteDerFelder = eindeutig.felder().stream().map(werte::get).toList();
        if (werteDerFelder.stream().anyMatch(wert -> !vorhanden(wert))) {
            return false;
        }
        return daten.eindeutig(regel.art(), regel.art() == null || eindeutig.ueberAlleArten(),
                eindeutig.felder(), werteDerFelder,
                dokumentId);
    }

    private static boolean vorhanden(Object wert) {
        if (wert == null) {
            return false;
        }
        if (wert instanceof String text) {
            return !text.isBlank();
        }
        if (wert instanceof List<?> liste) {
            return !liste.isEmpty();
        }
        if (wert instanceof Map<?, ?> map) {
            return !map.isEmpty();
        }
        return true;
    }

    public record Warnung(String code, String meldung) {
    }
}
