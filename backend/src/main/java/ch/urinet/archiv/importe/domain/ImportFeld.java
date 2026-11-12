package ch.urinet.archiv.importe.domain;

import java.util.List;
import java.util.Set;

/**
 * Zielfelder der Spaltenzuordnung je Importart. Neben den Feldern von {@code kontakt} bzw. {@code verkaufschance}
 * (design.md «Data Models») gibt es Hilfsfelder für die Regeln des Profils «21.CRM»: {@code organisation} und
 * {@code typ} für den Namen, die Index- und Einzelspalten für E-Mail und Telefon, {@code geloescht} zum Überspringen.
 */
public final class ImportFeld {

    public static final String CRM_ID = "crm_id";
    public static final String NAME = "name";
    public static final String VORNAME = "vorname";
    public static final String ORGANISATION = "organisation";
    public static final String TYP = "typ";
    public static final String STRASSE = "strasse";
    public static final String PLZ = "plz";
    public static final String ORT = "ort";
    public static final String GELOESCHT = "geloescht";
    public static final String EMAIL = "email";
    public static final String TELEFON = "telefon";
    public static final String STANDARD_EMAIL = "standard_email";
    public static final String EMAIL_GESCHAEFT = "email_geschaeft";
    public static final String EMAIL_GESCHAEFT_2 = "email_geschaeft_2";
    public static final String EMAIL_PRIVAT = "email_privat";
    public static final String EMAIL_ZENTRALE = "email_zentrale";
    public static final String STANDARD_TELEFON = "standard_telefon";
    public static final String TELEFON_GESCHAEFT = "telefon_geschaeft";
    public static final String TELEFON_ZENTRALE = "telefon_zentrale";
    public static final String MOBIL_GESCHAEFT = "mobil_geschaeft";
    public static final String KONTAKT_CRM_ID = "kontakt_crm_id";
    public static final String TITEL = "titel";
    public static final String PHASE = "phase";
    public static final String BETRAG = "betrag";
    public static final String DATUM = "datum";

    /** Hilfsfelder: steuern die Regeln, erscheinen aber nicht selbst unter den Feldwerten einer Zeile. */
    static final Set<String> HILFSFELDER = Set.of(ORGANISATION, GELOESCHT, STANDARD_EMAIL, EMAIL_GESCHAEFT,
            EMAIL_GESCHAEFT_2, EMAIL_PRIVAT, EMAIL_ZENTRALE, STANDARD_TELEFON, TELEFON_GESCHAEFT, TELEFON_ZENTRALE,
            MOBIL_GESCHAEFT);

    private static final List<String> KONTAKT = List.of(CRM_ID, TYP, NAME, ORGANISATION, VORNAME, STRASSE, PLZ, ORT,
            EMAIL, TELEFON, "uid", "iban", "kategorie", GELOESCHT, STANDARD_EMAIL, EMAIL_GESCHAEFT, EMAIL_GESCHAEFT_2,
            EMAIL_PRIVAT, EMAIL_ZENTRALE, STANDARD_TELEFON, TELEFON_GESCHAEFT, TELEFON_ZENTRALE, MOBIL_GESCHAEFT);
    private static final List<String> VERKAUFSCHANCE = List.of(CRM_ID, KONTAKT_CRM_ID, TITEL, PHASE, BETRAG, DATUM,
            GELOESCHT);

    private ImportFeld() {
    }

    /** Erlaubte Zielfelder in fester Reihenfolge (für den Zuordnungsdialog). */
    public static List<String> erlaubt(ImportArt art) {
        return art == ImportArt.KONTAKT ? KONTAKT : VERKAUFSCHANCE;
    }
}
