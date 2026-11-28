package ch.urinet.archiv.ki.application;

import ch.urinet.archiv.konfiguration.application.DateinamenTreffer;
import ch.urinet.archiv.ki.domain.Feld;
import ch.urinet.archiv.ki.domain.Vorschlag;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

final class DateinamenAbgleich {

    private DateinamenAbgleich() {
    }

    static Optional<Vorschlag> anwenden(Optional<Vorschlag> bisher, Optional<DateinamenTreffer> treffer,
                                         String volltext) {
        if (treffer.isEmpty()) {
            return bisher;
        }
        DateinamenTreffer dateiname = treffer.get();
        Vorschlag alt = bisher.orElseGet(() -> leer(dateiname.art()));
        Map<Feld, BigDecimal> sicherheit = new EnumMap<>(Feld.class);
        sicherheit.putAll(alt.sicherheit());
        Map<String, Object> dynamischeWerte = new LinkedHashMap<>(alt.dynamischeWerte());
        Map<String, BigDecimal> dynamischeSicherheit = new LinkedHashMap<>(alt.dynamischeSicherheit());
        Map<String, String> herkunft = new LinkedHashMap<>(alt.herkunft());
        List<String> bittePruefen = new ArrayList<>(alt.bittePruefen());

        String belegnummer = dateiname.felder().get("belegnummer");
        if (belegnummer != null) {
            sicherheit.put(Feld.BELEGNUMMER, BigDecimal.ONE);
            herkunft.put(Feld.BELEGNUMMER.schluessel(), "DATEINAME");
            if (volltext == null || !volltext.toLowerCase(Locale.ROOT)
                    .contains(belegnummer.toLowerCase(Locale.ROOT))) {
                bittePruefen.add(Feld.BELEGNUMMER.schluessel());
            }
        }
        String partner = dateiname.felder().get("partner");
        if (partner != null) {
            sicherheit.put(Feld.PARTNER, BigDecimal.ONE);
            herkunft.put(Feld.PARTNER.schluessel(), "DATEINAME");
        }
        for (String feld : List.of("periode", "person")) {
            String wert = dateiname.felder().get(feld);
            if (wert != null) {
                dynamischeWerte.put(feld, wert);
                dynamischeSicherheit.put(feld, BigDecimal.ONE);
                herkunft.put(feld, "DATEINAME");
            }
        }
        herkunft.put(Feld.ART.schluessel(), "DATEINAME");
        sicherheit.put(Feld.ART, BigDecimal.ONE);
        return Optional.of(new Vorschlag(dateiname.art(), alt.titel(), alt.datum(),
                partner == null ? alt.partnerName() : partner, alt.partnerUid(), alt.partnerIban(),
                alt.partnerEmail(), belegnummer == null ? alt.belegnummer() : belegnummer, alt.richtung(),
                alt.betrag(), alt.waehrung(), alt.faelligkeit(), alt.qrReferenz(), alt.vertraulichkeit(), alt.tags(),
                sicherheit, alt.mandantIstAussteller(), alt.umstufung(), dynamischeWerte, dynamischeSicherheit,
                herkunft, bittePruefen.stream().distinct().toList()));
    }

    private static Vorschlag leer(String art) {
        return new Vorschlag(art, null, null, null, null, null, null, null, null, null, null, null, null, null,
                List.of(), Map.of(), false, null, Map.of(), Map.of(), Map.of(), List.of());
    }
}
