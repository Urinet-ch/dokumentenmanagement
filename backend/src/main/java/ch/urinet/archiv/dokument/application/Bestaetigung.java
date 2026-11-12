package ch.urinet.archiv.dokument.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Bestätigte Metadaten aus {@code POST /dokumente/{id}/bestaetigen} (Req 8.2). Was fehlt, ist leer ({@code null});
 * gespeichert wird genau dieser Stand. {@code vertraulichkeit} ohne Wert übernimmt die Standard-Vertraulichkeit der
 * Art (Req 2.22), {@code geschaeftsjahr} ohne Wert den Vorschlag aus Belegdatum bzw. Eingang (Req 9.6).
 *
 * @param ohnePdfa                  bestätigt bei {@code pdfa_fehlt}, dass nur Original und Arbeitsfassung archiviert
 *                                  werden (Req 5.9)
 * @param fristAbgelaufenAkzeptiert Entscheid des Inhabers, ein Dokument mit Frist in der Vergangenheit ohne Sperre zu
 *                                  archivieren (Req 9.8)
 */
public record Bestaetigung(String art, String titel, LocalDate datum, UUID partnerId, String projekt,
                           List<String> tags, String vertraulichkeit, String personBenutzer, Integer geschaeftsjahr,
                           String belegnummer, String richtung, BigDecimal betrag, String waehrung,
                           LocalDate faelligkeit, String qrReferenz, String konto, LocalDate gueltigAb,
                           LocalDate gueltigBis, String kuendigungsfrist, LocalDate naechsteFrist, boolean ohnePdfa,
                           boolean fristAbgelaufenAkzeptiert) {

    public Bestaetigung {
        art = leer(art);
        titel = leer(titel);
        projekt = leer(projekt);
        tags = tags == null ? List.of() : tags.stream().map(Bestaetigung::leer).filter(t -> t != null).distinct()
                .toList();
        vertraulichkeit = leer(vertraulichkeit);
        personBenutzer = leer(personBenutzer);
        belegnummer = leer(belegnummer);
        richtung = leer(richtung);
        waehrung = leer(waehrung);
        qrReferenz = leer(qrReferenz);
        konto = leer(konto);
        kuendigungsfrist = leer(kuendigungsfrist);
    }

    private static String leer(String text) {
        return text == null || text.isBlank() ? null : text.strip();
    }
}
