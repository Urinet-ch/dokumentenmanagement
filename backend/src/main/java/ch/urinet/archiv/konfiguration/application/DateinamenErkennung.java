package ch.urinet.archiv.konfiguration.application;

import ch.urinet.archiv.gemeinsam.domain.RegexZeitgrenze;
import ch.urinet.archiv.konfiguration.domain.Konfiguration.DateinamenRegel;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

@ApplicationScoped
public class DateinamenErkennung {

    private static final Logger LOG = Logger.getLogger(DateinamenErkennung.class);
    private final KonfigurationLesen konfiguration;
    private final Duration zeitgrenze;

    public DateinamenErkennung(KonfigurationLesen konfiguration,
                               @ConfigProperty(name = "archiv.dokumentart.muster-zeitgrenze") Duration zeitgrenze) {
        this.konfiguration = konfiguration;
        this.zeitgrenze = zeitgrenze;
    }

    public Optional<DateinamenTreffer> erkennen(String dateiname) {
        if (dateiname == null || dateiname.isBlank()) {
            return Optional.empty();
        }
        List<DateinamenRegel> regeln = konfiguration.aktuell().dateinamenRegeln();
        for (DateinamenRegel regel : regeln) {
            Pattern muster = Pattern.compile(regel.muster(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
            List<String> gruppen = regel.felder().values().stream().distinct().toList();
            RegexZeitgrenze.Ergebnis auswertung = RegexZeitgrenze.pruefen(muster, dateiname, zeitgrenze, gruppen);
            if (auswertung.abgebrochen()) {
                LOG.warnf("Dateinamen-Regel %d wegen Zeitgrenze übersprungen", regel.nr());
                continue;
            }
            if (!auswertung.trifft()) {
                continue;
            }
            Map<String, String> felder = new LinkedHashMap<>();
            regel.felder().forEach((feld, gruppe) -> Optional.ofNullable(auswertung.gruppen().get(gruppe))
                    .map(String::strip).filter(wert -> !wert.isEmpty()).ifPresent(wert ->
                            felder.put(feld, "partner".equals(feld) ? partnerNormalisieren(wert) : wert)));
            return Optional.of(new DateinamenTreffer(regel.nr(), regel.art(), felder));
        }
        return Optional.empty();
    }

    private static String partnerNormalisieren(String partner) {
        return partner.replace('_', ' ').replace('-', ' ').replaceAll("\\s+", " ").strip();
    }
}
