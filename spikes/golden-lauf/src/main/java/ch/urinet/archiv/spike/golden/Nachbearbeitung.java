package ch.urinet.archiv.spike.golden;

import ch.urinet.archiv.spike.golden.Erwartung.Mandant;
import ch.urinet.archiv.spike.golden.MetadatenVorschlag.Sicherheit;
import ch.urinet.archiv.spike.golden.SwissQrLeser.QrDaten;
import ch.urinet.archiv.spike.golden.Vergleich.Feld;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/** Nachbearbeitung nach design.md «KI»: QR vor KI, Belegnummer-Muster nur bei eigenem Beleg (Req 7.5, 7.11, 7.12). */
public final class Nachbearbeitung {

    public record Ergebnis(String art, LocalDate datum, String partner, String partnerIban, BigDecimal betrag,
                           String waehrung, String belegnummer, String richtung, LocalDate faelligkeit,
                           String vertraulichkeit, boolean mandantIstAussteller, Map<Feld, Double> sicherheit) {
    }

    /** structure.md «Belegnummern»; das Muster setzt die Art nur, wenn der Mandant Aussteller ist. */
    private static final Map<Pattern, String> MUSTER = Map.of(
            Pattern.compile("^\\d{4}-\\d{3}$"), "AUSGANGSRECHNUNG",
            Pattern.compile("^R\\d{4}$"), "AUSGANGSRECHNUNG",
            Pattern.compile("^O-\\d{4}-\\d{3}$"), "OFFERTE",
            Pattern.compile("^F\\d{4}$"), "LIEFERANTENBELEG");

    private Nachbearbeitung() {
    }

    public static Ergebnis bearbeite(MetadatenVorschlag v, Optional<QrDaten> qr, String text, Mandant mandant,
                                     List<Dokumentarten.Art> arten, List<String> eigeneArten, LocalDate heute) {
        Sicherheit s = v.sicherheit() == null ? new Sicherheit(null, null, null, null, null, null, null, null, null, null)
                : v.sicherheit();
        Map<Feld, Double> sicherheit = new EnumMap<>(Feld.class);
        sicherheit.put(Feld.ART, wert(s.art()));
        sicherheit.put(Feld.DATUM, wert(s.datum()));
        sicherheit.put(Feld.PARTNER, wert(s.partnerName()));
        sicherheit.put(Feld.BETRAG, wert(s.betrag()));
        sicherheit.put(Feld.BELEGNUMMER, wert(s.belegnummer()));

        String art = v.art() == null ? null : v.art().name();
        LocalDate datum = datum(v.datum());
        if (datum == null || datum.isAfter(heute.plusYears(1))) {
            sicherheit.put(Feld.DATUM, 0.0);
        }
        BigDecimal betrag = v.betrag() == null ? null : v.betrag().setScale(2, RoundingMode.HALF_UP);
        String waehrung = v.waehrung();
        String partner = v.partnerName();
        String partnerIban = v.partnerIban();
        String belegnummer = leer(v.belegnummer()) ? null : v.belegnummer().strip();

        boolean qrVomMandant = qr.isPresent() && enthaeltIban(mandant, qr.get().iban());
        if (qr.isPresent()) {
            QrDaten q = qr.get();
            waehrung = q.waehrung();
            if (q.betrag() != null) {
                betrag = q.betrag().setScale(2, RoundingMode.HALF_UP);
                sicherheit.put(Feld.BETRAG, 1.0);
            }
            // Zahlungsempfänger ist nur bei fremden Belegen die Gegenpartei
            if (!qrVomMandant) {
                partner = q.zahlungsempfaenger();
                partnerIban = q.iban();
                sicherheit.put(Feld.PARTNER, 1.0);
            }
        }

        boolean aussteller = qrVomMandant || textEnthaeltKennung(text, mandant);
        if (aussteller && belegnummer != null) {
            for (Map.Entry<Pattern, String> m : MUSTER.entrySet()) {
                if (m.getKey().matcher(belegnummer).matches()) {
                    art = m.getValue();
                    sicherheit.put(Feld.ART, 1.0);
                }
            }
        }
        // Eigene Art, obwohl ein QR-Code einen anderen Zahlungsempfänger nennt: Art nicht ändern (Req 7.12), aber prüfen lassen
        if (!aussteller && qr.isPresent() && art != null && eigeneArten.contains(art)) {
            sicherheit.put(Feld.ART, 0.0);
        }

        String vertraulichkeit = v.vertraulichkeit() != null ? v.vertraulichkeit().name()
                : arten.stream().filter(a -> a.code().equals(finalArt(v))).map(Dokumentarten.Art::vertraulichkeit)
                .findFirst().orElse(null);
        return new Ergebnis(art, datum, partner, partnerIban, betrag, waehrung, belegnummer,
                v.richtung() == null ? null : v.richtung().name(), datum(v.faelligkeit()), vertraulichkeit,
                aussteller, sicherheit);
    }

    static boolean enthaeltIban(Mandant m, String iban) {
        String gesucht = Normalisierung.iban(iban);
        return m.ibans().stream().map(Normalisierung::iban).anyMatch(gesucht::equals);
    }

    /** Eigene UID oder IBAN im Belegtext (Briefkopf/Fuss) zeigt den Mandanten als Aussteller an. */
    static boolean textEnthaeltKennung(String text, Mandant m) {
        if (text == null) {
            return false;
        }
        String kompakt = Normalisierung.iban(text);
        boolean iban = m.ibans().stream().map(Normalisierung::iban).anyMatch(i -> !i.isEmpty() && kompakt.contains(i));
        boolean uid = m.uid() != null && kompakt.contains(Normalisierung.iban(m.uid()));
        return iban || uid;
    }

    private static String finalArt(MetadatenVorschlag v) {
        return v.art() == null ? null : v.art().name();
    }

    private static double wert(Double d) {
        return d == null ? 0.0 : Math.max(0.0, Math.min(1.0, d));
    }

    private static boolean leer(String s) {
        return s == null || s.isBlank() || "null".equalsIgnoreCase(s.strip());
    }

    private static LocalDate datum(String s) {
        if (leer(s)) {
            return null;
        }
        try {
            return LocalDate.parse(s.strip());
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
