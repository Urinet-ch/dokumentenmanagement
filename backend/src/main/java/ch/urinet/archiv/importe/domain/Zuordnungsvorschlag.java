package ch.urinet.archiv.importe.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Vorschlag für den Zuordnungsdialog (Req 13.2): das Profil «21.CRM», wenn die Kopfzeile die kennzeichnenden Spalten des
 * 21.CRM-Exports hat (design.md, {@code docs/spikes/21crm-export.md}), sonst die Synonymliste. Ein Vorschlag gilt erst,
 * wenn der Benutzer ihn bestätigt.
 */
public final class Zuordnungsvorschlag {

    private static final Map<String, String> KONTAKT_21CRM = geordnet(
            ImportFeld.CRM_ID, "GGUID",
            ImportFeld.TYP, "Kontakt ist eine Organisation",
            ImportFeld.NAME, "Name",
            ImportFeld.ORGANISATION, "Organisation",
            ImportFeld.VORNAME, "Vorname",
            ImportFeld.STRASSE, "Straße (Geschäft)",
            ImportFeld.PLZ, "PLZ (Geschäft)",
            ImportFeld.ORT, "Ort (Geschäft)",
            ImportFeld.GELOESCHT, "Ist gelöscht",
            ImportFeld.STANDARD_EMAIL, "Standard-E-Mail",
            ImportFeld.EMAIL_GESCHAEFT, "E-Mail (Geschäft)",
            ImportFeld.EMAIL_GESCHAEFT_2, "E-Mail (Geschäft 2)",
            ImportFeld.EMAIL_PRIVAT, "E-Mail (Privat)",
            ImportFeld.EMAIL_ZENTRALE, "E-Mail (Zentrale)",
            ImportFeld.STANDARD_TELEFON, "Standard-Telefon 1",
            ImportFeld.TELEFON_GESCHAEFT, "Telefon (Geschäft)",
            ImportFeld.TELEFON_ZENTRALE, "Telefon (Zentrale)",
            ImportFeld.MOBIL_GESCHAEFT, "Mobil (Geschäft)");
    private static final List<String> KENNSPALTEN_KONTAKT = List.of("GGUID", "Kontakt ist eine Organisation",
            "Organisation", "Name", "Vorname", "Ist gelöscht", "Standard-E-Mail", "Standard-Telefon 1");

    // design.md: [offen: Zuordnung der Verkaufschancen aus 21.CRM mit einem Nachexport mit Testdaten bestätigen]
    // – bis dahin gilt das Profil nur als Vorschlag, den der Benutzer bestätigt
    private static final Map<String, String> VERKAUFSCHANCE_21CRM = geordnet(
            ImportFeld.CRM_ID, "GGUID",
            ImportFeld.KONTAKT_CRM_ID, "ACCOUNTGUID",
            ImportFeld.TITEL, "Name",
            ImportFeld.PHASE, "Phase",
            ImportFeld.BETRAG, "Gesamt",
            ImportFeld.DATUM, "Beginn",
            ImportFeld.GELOESCHT, "Ist gelöscht");
    private static final List<String> KENNSPALTEN_VERKAUFSCHANCE = List.of("GGUID", "ACCOUNTGUID", "Name", "Phase",
            "Gesamt", "Beginn", "Ist gelöscht");

    /** Synonyme je Feld, in Vorrangreihenfolge (design.md: {@code Firma|Organisation|Name|Nachname → name} …). */
    private static final Map<String, List<String>> SYNONYME_KONTAKT = geordnetListe(
            ImportFeld.CRM_ID, List.of("ID", "GGUID", "Nummer", "Kontakt-Nr", "Kontakt-Nr.", "Kontakt-ID"),
            ImportFeld.NAME, List.of("Firma", "Organisation", "Name", "Nachname"),
            ImportFeld.VORNAME, List.of("Vorname"),
            ImportFeld.STRASSE, List.of("Strasse", "Straße", "Adresse"),
            ImportFeld.PLZ, List.of("PLZ", "Postleitzahl"),
            ImportFeld.ORT, List.of("Ort", "Stadt"),
            ImportFeld.EMAIL, List.of("E-Mail", "Mail", "Email"),
            ImportFeld.TELEFON, List.of("Telefon", "Tel", "Tel.", "Telefonnummer"),
            "uid", List.of("UID", "UID-Nummer"),
            "iban", List.of("IBAN"),
            "kategorie", List.of("Kategorie"),
            ImportFeld.GELOESCHT, List.of("Ist gelöscht", "Gelöscht"));
    private static final Map<String, List<String>> SYNONYME_VERKAUFSCHANCE = geordnetListe(
            ImportFeld.CRM_ID, List.of("ID", "GGUID", "Nummer"),
            ImportFeld.KONTAKT_CRM_ID, List.of("ACCOUNTGUID", "Kontakt-ID", "Kontakt-Nr", "Kontakt-Nr."),
            ImportFeld.TITEL, List.of("Titel", "Bezeichnung", "Name"),
            ImportFeld.PHASE, List.of("Phase"),
            ImportFeld.BETRAG, List.of("Betrag", "Gesamt", "Summe"),
            ImportFeld.DATUM, List.of("Datum", "Beginn"),
            ImportFeld.GELOESCHT, List.of("Ist gelöscht", "Gelöscht"));

    private Zuordnungsvorschlag() {
    }

    /** Profil «21.CRM», wenn die Kopfzeile es trägt, sonst die Treffer der Synonymliste (kann leer sein). */
    public static Zuordnung fuer(ImportArt art, Kopfzeile kopf) {
        return profil21Crm(art, kopf).orElseGet(() -> synonyme(art, kopf));
    }

    /** Profil «21.CRM» mit den Positionen dieser Kopfzeile, wenn alle kennzeichnenden Spalten vorhanden sind. */
    public static Optional<Zuordnung> profil21Crm(ImportArt art, Kopfzeile kopf) {
        List<String> kennspalten = art == ImportArt.KONTAKT ? KENNSPALTEN_KONTAKT : KENNSPALTEN_VERKAUFSCHANCE;
        if (kennspalten.stream().anyMatch(name -> kopf.position(name) < 0)) {
            return Optional.empty();
        }
        Map<String, Integer> felder = new LinkedHashMap<>();
        (art == ImportArt.KONTAKT ? KONTAKT_21CRM : VERKAUFSCHANCE_21CRM).forEach((feld, spalte) -> {
            int position = kopf.position(spalte);
            if (position >= 0) {
                felder.put(feld, position);
            }
        });
        return Optional.of(new Zuordnung(Zuordnung.PROFIL_21CRM, felder));
    }

    /**
     * Synonymliste: je Feld die erste Spalte zum vorrangigsten Synonym; erst exakt (normalisiert), dann ohne Zusatz in
     * Klammern («E-Mail (Geschäft)» → «E-Mail»). Eine Spalte erhält höchstens ein Feld.
     */
    static Zuordnung synonyme(ImportArt art, Kopfzeile kopf) {
        Map<String, Integer> felder = new LinkedHashMap<>();
        List<String> ohneKlammer = kopf.namen().stream()
                .map(name -> name == null ? "" : name.replaceFirst("\\s*\\([^)]*\\)\\s*$", "")).toList();
        Kopfzeile basis = new Kopfzeile(ohneKlammer);
        (art == ImportArt.KONTAKT ? SYNONYME_KONTAKT : SYNONYME_VERKAUFSCHANCE).forEach((feld, synonyme) -> {
            for (Kopfzeile k : List.of(kopf, basis)) {
                for (String synonym : synonyme) {
                    int position = freiePosition(k, synonym, felder);
                    if (position >= 0) {
                        felder.put(feld, position);
                        return;
                    }
                }
            }
        });
        return new Zuordnung(null, felder);
    }

    private static int freiePosition(Kopfzeile kopf, String name, Map<String, Integer> belegt) {
        String gesucht = Kopfzeile.normalisiert(name);
        for (int i = 0; i < kopf.spalten(); i++) {
            if (Kopfzeile.normalisiert(kopf.namen().get(i)).equals(gesucht) && !belegt.containsValue(i)) {
                return i;
            }
        }
        return -1;
    }

    private static Map<String, String> geordnet(String... paare) {
        Map<String, String> ergebnis = new LinkedHashMap<>();
        for (int i = 0; i < paare.length; i += 2) {
            ergebnis.put(paare[i], paare[i + 1]);
        }
        return ergebnis;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, List<String>> geordnetListe(Object... paare) {
        Map<String, List<String>> ergebnis = new LinkedHashMap<>();
        for (int i = 0; i < paare.length; i += 2) {
            ergebnis.put((String) paare[i], (List<String>) paare[i + 1]);
        }
        return ergebnis;
    }
}
