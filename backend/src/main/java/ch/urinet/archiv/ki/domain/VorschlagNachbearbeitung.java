package ch.urinet.archiv.ki.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Nachbearbeitung des KI-Vorschlags (design.md «KI», Nachbearbeitung), Fassung {@value #FASSUNG} aus dem Golden-Lauf
 * ({@code spikes/golden-lauf}, {@code Nachbearbeitung}) mit R3, E1, E2, E3 und der Produkt-Regel A1:
 * Swiss QR Code vor KI (Req 7.11), Belegnummer-Muster nur bei eigenem Beleg (Req 7.5, 7.12), Vertraulichkeit mit dem
 * Standard der Art (Req 7.2), Sicherheiten zwischen 0 und 1 (Req 7.6). Abweichend vom Spike gilt die
 * Standard-Vertraulichkeit der End-Art immer (der Spike nahm den Wert des Modells, ausser bei Umstufung), und eine Art
 * ausserhalb der Liste des Mandanten wird verworfen; beide betreffen keine im Gate gemessenen Felder.
 */
public final class VorschlagNachbearbeitung {

    /** Fassung der Regeln wie im Gate-Lauf nach Aufgabe 3.8; A1 senkt nur Sicherheiten und zählt nicht dazu. */
    public static final String FASSUNG = "nb-2026-11-12";
    public static final String GRUND_QR = "QR";
    public static final String GRUND_E2 = "E2";
    /** A1 (Auflage Gate 0): unter der Schwelle (Startwert 0.8), also immer «bitte prüfen». */
    public static final BigDecimal BELEGNUMMER_HOECHSTENS = new BigDecimal("0.7");

    /** structure.md «Belegnummern» (Werte des Mandanten urinet, bis Spec 05 fest). */
    private static final Map<Pattern, String> MUSTER = muster();
    static final int KOPF_FUSS_ZEILEN = 10;
    /** Eigene Arten, die bei fremdem QR-Zahlungsempfänger zu LIEFERANTENBELEG werden; LOHNABRECHNUNG bewusst nicht. */
    static final Set<String> UMSTUFBAR = Set.of("AUSGANGSRECHNUNG", "OFFERTE");
    static final String UMSTUFUNG_ZIEL = "LIEFERANTENBELEG";
    static final String QUITTUNG = "QUITTUNG";
    static final BigDecimal UMSTUFUNG_SICHERHEIT = new BigDecimal("0.5");
    /** {@code NUMERIC(12,2)} der Spalte {@code dokument_finanz.betrag}. */
    static final int BETRAG_STELLEN_VOR_KOMMA = 10;

    private VorschlagNachbearbeitung() {
    }

    private static Map<Pattern, String> muster() {
        Map<Pattern, String> m = new LinkedHashMap<>();
        m.put(Pattern.compile("^\\d{4}-\\d{3}$"), "AUSGANGSRECHNUNG");
        m.put(Pattern.compile("^R\\d{4}$"), "AUSGANGSRECHNUNG");
        m.put(Pattern.compile("^O-\\d{4}-\\d{3}$"), "OFFERTE");
        m.put(Pattern.compile("^F\\d{4}$"), "LIEFERANTENBELEG");
        return m;
    }

    /**
     * @param text    Volltext des Belegs, Seiten durch Seitenvorschub getrennt (für Kopf und Fuss)
     * @param arten   Dokumentarten des Mandanten mit Standard-Vertraulichkeit
     * @param eingang Eingangsdatum (Europe/Zurich), für R3
     */
    public static Vorschlag bearbeite(Rohvorschlag v, Optional<QrDaten> qr, String text, ErfassungMandant mandant,
                                      List<ArtEintrag> arten, LocalDate eingang) {
        Map<Feld, BigDecimal> sicherheit = new EnumMap<>(Feld.class);
        for (Feld f : Feld.values()) {
            sicherheit.put(f, wert(v.sicherheit().get(f)));
        }

        String art = v.art();
        String artRoh = art;
        LocalDate datum = datum(v.datum());
        // R3: ein Beleg kann nicht nach seinem Eingang datiert sein; der gleiche Tag ist zulässig
        if (datum == null || datum.isAfter(eingang)) {
            sicherheit.put(Feld.DATUM, BigDecimal.ZERO);
        }
        BigDecimal betrag = betrag(v.betrag());
        if (v.betrag() != null && betrag == null) {
            sicherheit.put(Feld.BETRAG, BigDecimal.ZERO);
        }
        // E1: Betrag 0 des Modells heisst «kein Betrag»; ein QR-Betrag (auch 0.00) setzt ihn unten wieder (Req 7.11)
        if (betrag != null && betrag.signum() == 0) {
            betrag = null;
        }
        String waehrung = v.waehrung();
        String partner = v.partnerName();
        String partnerIban = v.partnerIban();
        String qrReferenz = v.qrReferenz();
        // E3: eine Belegnummer nur aus Nullen («0», «000») ist keine Belegnummer
        String belegnummer = leer(v.belegnummer()) || v.belegnummer().strip().matches("0+") ? null
                : v.belegnummer().strip();

        boolean qrVomMandant = qr.isPresent() && enthaeltIban(mandant, qr.get().iban());
        if (qr.isPresent()) {
            QrDaten q = qr.get();
            waehrung = q.waehrung();
            sicherheit.put(Feld.WAEHRUNG, BigDecimal.ONE);
            if (q.betragUngueltig()) {
                betrag = null;
                sicherheit.put(Feld.BETRAG, BigDecimal.ZERO);
            } else if (betrag(q.betrag()) != null) {
                betrag = betrag(q.betrag());
                sicherheit.put(Feld.BETRAG, BigDecimal.ONE);
            }
            if (q.referenz() != null) {
                qrReferenz = q.referenz();
                sicherheit.put(Feld.QR_REFERENZ, BigDecimal.ONE);
            }
            // Zahlungsempfänger ist nur bei fremden Belegen die Gegenpartei
            if (!qrVomMandant) {
                partner = q.zahlungsempfaenger();
                partnerIban = q.iban();
                sicherheit.put(Feld.PARTNER, BigDecimal.ONE);
            }
        }

        // Vorrang: Ein QR-Code mit fremder IBAN schliesst den Mandanten als Aussteller aus, auch bei eigener UID im Kopf
        boolean qrFremd = qr.isPresent() && !qrVomMandant;
        boolean aussteller = !qrFremd && (qrVomMandant || kopfOderFussEnthaeltKennung(text, mandant));
        boolean musterGesetzt = false;
        if (aussteller && belegnummer != null) {
            for (Map.Entry<Pattern, String> m : MUSTER.entrySet()) {
                if (m.getKey().matcher(belegnummer).matches() && aktiv(arten, m.getValue())) {
                    art = m.getValue();
                    sicherheit.put(Feld.ART, BigDecimal.ONE);
                    musterGesetzt = true;
                }
            }
        }
        // Eigene Art ohne Kennung des Mandanten: bei fremdem QR umstufen, sonst unverändert (Req 7.12) und prüfen lassen
        String umstufung = null;
        if (!aussteller && art != null && mandant.eigeneArten().contains(art)) {
            if (qrFremd && UMSTUFBAR.contains(art) && aktiv(arten, UMSTUFUNG_ZIEL)) {
                art = UMSTUFUNG_ZIEL;
                sicherheit.put(Feld.ART, UMSTUFUNG_SICHERHEIT);
                umstufung = GRUND_QR;
            } else {
                sicherheit.put(Feld.ART, BigDecimal.ZERO);
            }
        }
        // E2: Lieferantenbeleg ohne QR, ohne IBAN und ohne Fälligkeit ist meist ein bezahlter Beleg
        LocalDate faelligkeit = datum(v.faelligkeit());
        if (faelligkeit == null) {
            sicherheit.put(Feld.FAELLIGKEIT, leer(v.faelligkeit()) ? sicherheit.get(Feld.FAELLIGKEIT) : BigDecimal.ZERO);
        }
        if (!musterGesetzt && UMSTUFUNG_ZIEL.equals(artRoh) && UMSTUFUNG_ZIEL.equals(art) && qr.isEmpty()
                && leer(partnerIban) && faelligkeit == null && aktiv(arten, QUITTUNG)) {
            art = QUITTUNG;
            sicherheit.put(Feld.ART, UMSTUFUNG_SICHERHEIT);
            umstufung = GRUND_E2;
        }
        // A1: Belegnummern ohne Muster des ausstellenden Mandanten immer prüfen lassen
        if (!musterGesetzt && sicherheit.get(Feld.BELEGNUMMER).compareTo(BELEGNUMMER_HOECHSTENS) > 0) {
            sicherheit.put(Feld.BELEGNUMMER, BELEGNUMMER_HOECHSTENS);
        }

        String endArt = art;
        Optional<String> standard = arten.stream().filter(a -> a.code().equals(endArt))
                .map(ArtEintrag::vertraulichkeit).findFirst();
        // Eine Art ausserhalb der Liste des Mandanten (z. B. inaktiv) ist kein Vorschlag
        if (art != null && standard.isEmpty()) {
            art = null;
            sicherheit.put(Feld.ART, BigDecimal.ZERO);
        }
        // Req 7.2: vorbelegt mit der Standard-Vertraulichkeit der vorgeschlagenen Art, nie mit dem Wert des Modells;
        // ohne Art keine Vorbelegung (das Dokument gilt bis zur Bestätigung als PERSONAL)
        String vertraulichkeit = standard.orElse(null);
        sicherheit.put(Feld.VERTRAULICHKEIT, sicherheit.get(Feld.ART));
        return new Vorschlag(art, leer(v.titel()) ? null : v.titel().strip(), datum, partner, v.partnerUid(),
                partnerIban, v.partnerEmail(), belegnummer, v.richtung(), betrag, waehrung, faelligkeit, qrReferenz,
                vertraulichkeit, v.tags(), sicherheit, aussteller, umstufung);
    }

    /** Eine Regel setzt nur eine Art, die beim Mandanten aktiv ist; sonst bleibt die bisherige Art. */
    private static boolean aktiv(List<ArtEintrag> arten, String code) {
        return arten.stream().anyMatch(a -> a.code().equals(code));
    }

    static String kompakt(String s) {
        return s == null ? "" : s.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
    }

    static boolean enthaeltIban(ErfassungMandant m, String iban) {
        String gesucht = kompakt(iban);
        return m.ibans().stream().map(VorschlagNachbearbeitung::kompakt).anyMatch(gesucht::equals);
    }

    /**
     * Eigene UID oder IBAN im Kopf- oder Fussbereich einer Seite (Seiten durch Seitenvorschub getrennt): erste und
     * letzte 10 nicht leere Zeilen; Seiten mit bis zu 20 nicht leeren Zeilen zählen ganz.
     */
    static boolean kopfOderFussEnthaeltKennung(String text, ErfassungMandant m) {
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

    private static boolean enthaeltKennung(String text, ErfassungMandant m) {
        String kompakt = kompakt(text);
        boolean iban = m.ibans().stream().map(VorschlagNachbearbeitung::kompakt)
                .anyMatch(i -> !i.isEmpty() && kompakt.contains(i));
        boolean uid = m.uid() != null && !m.uid().isBlank() && kompakt.contains(kompakt(m.uid()));
        return iban || uid;
    }

    /**
     * Betrag auf zwei Stellen, nur innerhalb von {@code NUMERIC(12,2)}; sonst {@code null}. Geprüft wird über Stellenzahl
     * und Exponent, bevor {@code setScale} rechnet: {@code 1E300000000} oder {@code 1E-300000000} würden sonst riesige
     * Zahlen erzeugen (OutOfMemoryError). Ein Betrag unter einem halben Rappen ergibt 0 (E1 macht ihn leer).
     */
    static BigDecimal betrag(BigDecimal d) {
        if (d == null || d.signum() == 0) {
            return d == null ? null : d.setScale(2, RoundingMode.HALF_UP);
        }
        long stellenVorKomma = (long) d.precision() - d.scale();
        if (stellenVorKomma > BETRAG_STELLEN_VOR_KOMMA) {
            return null;
        }
        if (stellenVorKomma < -2) {
            return BigDecimal.ZERO.setScale(2);
        }
        BigDecimal b = d.setScale(2, RoundingMode.HALF_UP);
        return b.precision() - b.scale() > BETRAG_STELLEN_VOR_KOMMA ? null : b;
    }

    /** Sicherheit zwischen 0 und 1, auf 4 Stellen abgeschnitten (ändert keinen Vergleich mit den Schwellen). */
    private static BigDecimal wert(BigDecimal d) {
        if (d == null || d.signum() < 0 || (d.signum() > 0 && (long) d.precision() - d.scale() < -4)) {
            return BigDecimal.ZERO;
        }
        if (d.compareTo(BigDecimal.ONE) > 0) {
            return BigDecimal.ONE;
        }
        return d.scale() > 4 ? d.setScale(4, RoundingMode.DOWN) : d;
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
