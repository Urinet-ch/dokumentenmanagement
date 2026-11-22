package ch.urinet.archiv.konfiguration.domain;

import ch.urinet.archiv.gemeinsam.domain.FarbKontrast;

import java.time.DayOfWeek;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/** Katalog der mandantenbezogenen Einstellungen. */
public final class Einstellungskatalog {

    private static final Pattern FARBE = Pattern.compile("^#[0-9a-fA-F]{6}$");
    private static final Pattern BRANDING_NAME =
            Pattern.compile("^[\\p{L}\\p{N}][\\p{L}\\p{N} &'’().,/-]{0,59}$");
    public static final String UID_MUSTER = "(?i)^CHE[-. ]?\\d{3}[. ]?\\d{3}[. ]?\\d{3}(?: *(?:MWST|TVA|IVA))?$";
    public static final String GESCHAEFTSJAHR_STANDARD = "12-31";
    public static final int IBAN_ANZAHL_MAX = 20;
    /** Längste IBAN-Schreibweise (34 Zeichen); je Eintrag begrenzt, damit kein Freitext in Konfiguration und Export gelangt. */
    public static final int IBAN_LAENGE_MAX = 34;
    private static final Pattern UID = Pattern.compile(UID_MUSTER);

    public enum Gruppe { GESCHAEFTSJAHR, KENNUNGEN, BRANDING, CHAT, WEBSEITEN_CHAT, WISSEN }
    public enum Typ { DATUM, UID, IBANS, TEXT, FARBE, GANZZAHL, WOCHENTAG }
    public enum Risiko { KEINES, IMMER, WENN_KUERZER, WENN_UEBER_DOPPELTEM_STANDARD }
    public enum Recht { A19, A20 }
    public enum Standard { INSTANZ }

    public record Einstellung(String schluessel, Gruppe gruppe, Typ typ, Object standard, Risiko risiko,
                              Recht recht, Zahl zahl) {
        public Object standardFuer(String mandant) {
            return zahl == null ? standard : zahl.standardFuer(mandant);
        }
    }

    private static final List<Einstellung> EINSTELLUNGEN = List.of(
            new Einstellung("geschaeftsjahrEnde", Gruppe.GESCHAEFTSJAHR, Typ.DATUM, GESCHAEFTSJAHR_STANDARD, Risiko.IMMER,
                    Recht.A20, null),
            new Einstellung("kennungen.uid", Gruppe.KENNUNGEN, Typ.UID, null, Risiko.KEINES, Recht.A20, null),
            new Einstellung("kennungen.ibans", Gruppe.KENNUNGEN, Typ.IBANS, List.of(), Risiko.KEINES, Recht.A20, null),
            new Einstellung("branding.name", Gruppe.BRANDING, Typ.TEXT, Standard.INSTANZ, Risiko.KEINES,
                    Recht.A20, null),
            new Einstellung("branding.primaerfarbe", Gruppe.BRANDING, Typ.FARBE, "#eeb111", Risiko.KEINES,
                    Recht.A20, null),
            zahl(Zahl.CHAT_AUFBEWAHRUNG, Gruppe.CHAT, Risiko.WENN_KUERZER, Recht.A20),
            zahl(Zahl.WEBSEITEN_CHAT_AUFBEWAHRUNG, Gruppe.WEBSEITEN_CHAT, Risiko.WENN_KUERZER, Recht.A20),
            zahl(Zahl.WISSEN_BUDGET, Gruppe.WISSEN, Risiko.KEINES, Recht.A19),
            zahl(Zahl.WISSEN_LINT_BUDGET, Gruppe.WISSEN, Risiko.KEINES, Recht.A19),
            zahl(Zahl.WISSEN_MONATSBUDGET, Gruppe.WISSEN, Risiko.WENN_UEBER_DOPPELTEM_STANDARD, Recht.A19),
            new Einstellung("wissen.lintZeitplan.wochentag", Gruppe.WISSEN, Typ.WOCHENTAG, DayOfWeek.SUNDAY,
                    Risiko.KEINES, Recht.A19, null),
            zahl(Zahl.WISSEN_LINT_STUNDE, Gruppe.WISSEN, Risiko.KEINES, Recht.A19));

    private Einstellungskatalog() {
    }

    private static Einstellung zahl(Zahl zahl, Gruppe gruppe, Risiko risiko, Recht recht) {
        return new Einstellung(zahl.schluessel(), gruppe, Typ.GANZZAHL, zahl.standard(), risiko, recht, zahl);
    }

    public static List<Einstellung> alle() {
        return EINSTELLUNGEN;
    }

    public enum Zahl {
        CHAT_AUFBEWAHRUNG("chat.aufbewahrungMonate", 1, 60, 12),
        WEBSEITEN_CHAT_AUFBEWAHRUNG("webseitenChat.aufbewahrungTage", 1, 365, 90),
        WISSEN_BUDGET("wissen.budgetTokens", 10_000, 5_000_000, 600_000),
        WISSEN_LINT_BUDGET("wissen.lintBudgetTokens", 10_000, 1_000_000, 100_000),
        WISSEN_MONATSBUDGET("wissen.monatsbudgetTokens", 100_000, 20_000_000, 1_000_000),
        WISSEN_LINT_STUNDE("wissen.lintZeitplan.stunde", 0, 23, 4);

        private final String schluessel;
        private final int minimum;
        private final int maximum;
        private final int standard;

        Zahl(String schluessel, int minimum, int maximum, int standard) {
            this.schluessel = schluessel;
            this.minimum = minimum;
            this.maximum = maximum;
            this.standard = standard;
        }

        public String schluessel() {
            return schluessel;
        }

        public int minimum() {
            return minimum;
        }

        public int maximum() {
            return maximum;
        }

        public int standard() {
            return standard;
        }

        public int standardFuer(String mandant) {
            return this == WEBSEITEN_CHAT_AUFBEWAHRUNG && "vhf-gsk".equals(mandant) ? 30 : standard;
        }

        /** Ein fehlender Wert verwendet den jeweiligen Standard. */
        public boolean gueltig(Integer wert) {
            return wert == null || wert >= minimum && wert <= maximum;
        }

        /** Bis zur Migration hat der bisherige Startwert Vorrang vor dem Katalogstandard. */
        public int wirksamerWert(Integer eigenerWert, Integer bisherigerStartwert, String mandant) {
            if (!gueltig(eigenerWert) || !gueltig(bisherigerStartwert)) {
                throw new IllegalArgumentException("Einstellungswert ausserhalb der Grenzen: " + schluessel);
            }
            if (eigenerWert != null) {
                return eigenerWert;
            }
            if (bisherigerStartwert != null) {
                return bisherigerStartwert;
            }
            return standardFuer(mandant);
        }
    }

    public static Optional<Zahl> zahl(String schluessel) {
        return Arrays.stream(Zahl.values()).filter(e -> e.schluessel().equals(schluessel)).findFirst();
    }

    public static boolean brandingNameGueltig(String name) {
        return name == null || BRANDING_NAME.matcher(name).matches();
    }

    public static boolean primaerfarbeGueltig(String farbe) {
        return farbe == null || FARBE.matcher(farbe).matches()
                && FarbKontrast.zwischen(farbe, "#05070c") >= 4.5;
    }

    public static boolean uidGueltig(String uid) {
        return uid == null || UID.matcher(uid).matches();
    }

    public static boolean ibansGueltig(List<String> ibans) {
        return ibans.size() <= IBAN_ANZAHL_MAX && ibans.stream()
                .allMatch(wert -> !wert.isBlank() && wert.codePointCount(0, wert.length()) <= IBAN_LAENGE_MAX && wert.codePoints().noneMatch(Character::isISOControl));
    }
}
