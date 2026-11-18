package ch.urinet.archiv.dokument.application;

import ch.urinet.archiv.dokument.application.DokumentAenderungPort.Metadaten;
import ch.urinet.archiv.dokument.domain.Dokument;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Collections;

final class RegelMetadaten {

    private RegelMetadaten() {
    }

    static Map<String, Object> aus(Bestaetigung bestaetigung, Map<String, Object> zusatz) {
        Map<String, Object> werte = new LinkedHashMap<>();
        grundfelder(werte, bestaetigung.art(), bestaetigung.titel(), bestaetigung.datum(),
                bestaetigung.partnerId() == null ? null : bestaetigung.partnerId().toString(),
                bestaetigung.projekt(), bestaetigung.tags(), bestaetigung.vertraulichkeit(),
                bestaetigung.personBenutzer(), bestaetigung.klassifikationen());
        finanz(werte, bestaetigung.belegnummer(), bestaetigung.richtung(), bestaetigung.betrag(),
                bestaetigung.waehrung(), bestaetigung.faelligkeit(), bestaetigung.qrReferenz(), bestaetigung.konto());
        vertrag(werte, bestaetigung.gueltigAb(), bestaetigung.gueltigBis(), bestaetigung.kuendigungsfrist(),
                bestaetigung.naechsteFrist());
        zusatz(werte, zusatz);
        return Collections.unmodifiableMap(werte);
    }

    static Map<String, Object> aus(Metadaten metadaten, Map<String, Object> zusatz) {
        Map<String, Object> werte = new LinkedHashMap<>();
        grundfelder(werte, metadaten.art(), metadaten.titel(), metadaten.datum(),
                metadaten.partnerId() == null ? null : metadaten.partnerId().toString(), metadaten.projekt(),
                metadaten.tags(), metadaten.vertraulichkeit(), metadaten.personBenutzer(),
                metadaten.klassifikationen());
        metadaten.finanz().ifPresent(f -> finanz(werte, f.belegnummer(), f.richtung(), f.betrag(), f.waehrung(),
                f.faelligkeit(), f.qrReferenz(), f.konto()));
        metadaten.vertrag().ifPresent(v -> vertrag(werte, v.gueltigAb(), v.gueltigBis(), v.kuendigungsfrist(),
                v.naechsteFrist()));
        zusatz(werte, zusatz);
        return Collections.unmodifiableMap(werte);
    }

    static Map<String, Object> aus(Dokument dokument, Map<String, List<String>> klassifikationen,
                                   Map<String, Object> zusatz) {
        Map<String, Object> werte = new LinkedHashMap<>();
        grundfelder(werte, dokument.art(), dokument.titel(), dokument.datum(),
                dokument.partnerId() == null ? null : dokument.partnerId().toString(), dokument.projekt(),
                dokument.tags(), dokument.vertraulichkeit(), dokument.personBenutzer(), klassifikationen);
        if (dokument.finanz() != null) {
            var f = dokument.finanz();
            finanz(werte, f.belegnummer(), f.richtung(), f.betrag(), f.waehrung(), f.faelligkeit(),
                    f.qrReferenz(), f.konto());
        }
        if (dokument.vertrag() != null) {
            var v = dokument.vertrag();
            vertrag(werte, v.gueltigAb(), v.gueltigBis(), v.kuendigungsfrist(), v.naechsteFrist());
        }
        zusatz(werte, zusatz);
        return Collections.unmodifiableMap(werte);
    }

    private static void grundfelder(Map<String, Object> werte, String art, String titel,
                                    java.time.LocalDate datum, String partner, String projekt, List<String> tags,
                                    String vertraulichkeit, String personBenutzer,
                                    Map<String, List<String>> klassifikationen) {
        werte.put("art", art);
        werte.put("titel", titel);
        werte.put("datum", datum == null ? null : datum.toString());
        werte.put("partner", partner);
        werte.put("projekt", projekt);
        werte.put("tags", tags);
        werte.put("vertraulichkeit", vertraulichkeit);
        werte.put("person_benutzer", personBenutzer);
        klassifikationen.forEach((code, wert) -> werte.put("klassifikation." + code, wert));
    }

    private static void finanz(Map<String, Object> werte, String belegnummer, String richtung,
                               java.math.BigDecimal betrag, String waehrung, java.time.LocalDate faelligkeit,
                               String qrReferenz, String konto) {
        werte.put("belegnummer", belegnummer);
        werte.put("richtung", richtung);
        werte.put("betrag", betrag);
        werte.put("waehrung", waehrung);
        werte.put("faelligkeit", faelligkeit == null ? null : faelligkeit.toString());
        werte.put("qr_referenz", qrReferenz);
        werte.put("konto", konto);
    }

    private static void vertrag(Map<String, Object> werte, java.time.LocalDate gueltigAb,
                                java.time.LocalDate gueltigBis, String kuendigungsfrist,
                                java.time.LocalDate naechsteFrist) {
        werte.put("gueltig_ab", gueltigAb == null ? null : gueltigAb.toString());
        werte.put("gueltig_bis", gueltigBis == null ? null : gueltigBis.toString());
        werte.put("kuendigungsfrist", kuendigungsfrist);
        werte.put("naechste_frist", naechsteFrist == null ? null : naechsteFrist.toString());
    }

    private static void zusatz(Map<String, Object> werte, Map<String, Object> zusatz) {
        zusatz.forEach((feld, wert) -> werte.put("zusatz." + feld, wert));
    }
}
