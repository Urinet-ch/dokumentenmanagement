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
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Nachbearbeitung nach design.md «KI»: QR vor KI, Belegnummer-Muster nur bei eigenem Beleg (Req 7.5, 7.11, 7.12). */
public final class Nachbearbeitung {

    public record Ergebnis(String art, LocalDate datum, String partner, String partnerIban, BigDecimal betrag,
                           String waehrung, String belegnummer, String richtung, LocalDate faelligkeit,
                           String vertraulichkeit, boolean mandantIstAussteller, Map<Feld, Double> sicherheit,
                           String artRoh, double artRohSicherheit, boolean umgestuft) {
    }

    /** structure.md «Belegnummern»; das Muster setzt die Art nur, wenn der Mandant Aussteller ist. */
    private static final Map<Pattern, String> MUSTER = Map.of(
            Pattern.compile("^\\d{4}-\\d{3}$"), "AUSGANGSRECHNUNG",
            Pattern.compile("^R\\d{4}$"), "AUSGANGSRECHNUNG",
            Pattern.compile("^O-\\d{4}-\\d{3}$"), "OFFERTE",
            Pattern.compile("^F\\d{4}$"), "LIEFERANTENBELEG");

    /** Kopf- bzw. Fussbereich: die ersten und letzten N nicht leeren Zeilen einer Seite (Begründung im README). */
    static final int KOPF_FUSS_ZEILEN = 10;

    /**
     * Eigene Arten, die bei fremdem QR-Zahlungsempfänger zu LIEFERANTENBELEG werden (Entscheid Fabian, 0.5-Nachschärfen):
     * Eine Rechnung oder Offerte mit fremder QR-IBAN ist fast immer eine Eingangsrechnung. LOHNABRECHNUNG fehlt bewusst,
     * sonst sänke die Vertraulichkeit von PERSONAL auf die von LIEFERANTENBELEG (Req 2.22, 7.2).
     */
    static final Set<String> UMSTUFBAR = Set.of("AUSGANGSRECHNUNG", "OFFERTE");
    static final String UMSTUFUNG_ZIEL = "LIEFERANTENBELEG";
    /** Unter der Schwelle (≥ 0.8), also «bitte prüfen». */
    static final double UMSTUFUNG_SICHERHEIT = 0.5;

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

        // Vorrang: Ein QR-Code mit fremder IBAN schliesst den Mandanten als Aussteller aus, auch bei eigener UID im Kopf
        boolean qrFremd = qr.isPresent() && !qrVomMandant;
        boolean aussteller = !qrFremd && (qrVomMandant || kopfOderFussEnthaeltKennung(text, mandant));
        // Partner-Wache (v6): Der Mandant ist nie die Gegenpartei; bei fremden Belegen gilt der Absender im Briefkopf
        if (!leer(partner) && Normalisierung.name(partner).equals(Normalisierung.name(mandant.name()))) {
            String absender = v.absender();
            if (!aussteller && !leer(absender)
                    && !Normalisierung.name(absender).equals(Normalisierung.name(mandant.name()))) {
                partner = absender.strip();
            } else {
                sicherheit.put(Feld.PARTNER, 0.0);
            }
        }
        if (aussteller && belegnummer != null) {
            for (Map.Entry<Pattern, String> m : MUSTER.entrySet()) {
                if (m.getKey().matcher(belegnummer).matches()) {
                    art = m.getValue();
                    sicherheit.put(Feld.ART, 1.0);
                }
            }
        }
        // Eigene Art ohne Kennung des Mandanten: bei fremdem QR umstufen, sonst unverändert (Req 7.12) und prüfen lassen
        boolean umgestuft = false;
        if (!aussteller && art != null && eigeneArten.contains(art)) {
            if (qrFremd && UMSTUFBAR.contains(art)) {
                art = UMSTUFUNG_ZIEL;
                sicherheit.put(Feld.ART, UMSTUFUNG_SICHERHEIT);
                umgestuft = true;
            } else {
                sicherheit.put(Feld.ART, 0.0);
            }
        }

        String endArt = art;
        String standard = arten.stream().filter(a -> a.code().equals(endArt)).map(Dokumentarten.Art::vertraulichkeit)
                .findFirst().orElse(null);
        // Bei Umstufung gehört der Modellwert zur verworfenen Art
        String vertraulichkeit = !umgestuft && v.vertraulichkeit() != null ? v.vertraulichkeit().name() : standard;
        return new Ergebnis(art, datum, partner, partnerIban, betrag, waehrung, belegnummer,
                v.richtung() == null ? null : v.richtung().name(), datum(v.faelligkeit()), vertraulichkeit,
                aussteller, sicherheit, v.art() == null ? null : v.art().name(), wert(s.art()), umgestuft);
    }

    static boolean enthaeltIban(Mandant m, String iban) {
        String gesucht = Normalisierung.iban(iban);
        return m.ibans().stream().map(Normalisierung::iban).anyMatch(gesucht::equals);
    }

    /**
     * Eigene UID oder IBAN im Kopf- oder Fussbereich einer Seite (Seiten durch Seitenvorschub getrennt, wie pdftotext);
     * eine IBAN im Fliesstext, z. B. als Gutschriftskonto, zählt nicht.
     */
    static boolean kopfOderFussEnthaeltKennung(String text, Mandant m) {
        if (text == null) {
            return false;
        }
        for (String seite : text.split("\f")) {
            List<String> zeilen = seite.lines().filter(z -> !z.isBlank()).toList();
            List<String> bereich = zeilen.size() <= 2 * KOPF_FUSS_ZEILEN ? zeilen
                    : Stream.concat(zeilen.subList(0, KOPF_FUSS_ZEILEN).stream(),
                    zeilen.subList(zeilen.size() - KOPF_FUSS_ZEILEN, zeilen.size()).stream()).toList();
            if (enthaeltKennung(String.join("\n", bereich), m)) {
                return true;
            }
        }
        return false;
    }

    private static boolean enthaeltKennung(String text, Mandant m) {
        String kompakt = Normalisierung.iban(text);
        boolean iban = m.ibans().stream().map(Normalisierung::iban).anyMatch(i -> !i.isEmpty() && kompakt.contains(i));
        boolean uid = m.uid() != null && kompakt.contains(Normalisierung.iban(m.uid()));
        return iban || uid;
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
