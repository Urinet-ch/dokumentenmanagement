package ch.urinet.archiv.importe.domain;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Zuordnung, Vorschlag und Zeilenprüfung mit synthetischen Zeilen (Req 13.2, 13.10). */
class ZuordnungTest {

    private static final Kopfzeile KOPF_21CRM = new Kopfzeile(List.of("GGUID", "Kontakt ist eine Organisation",
            "Organisation", "Name", "Vorname", "Ist gelöscht", "Standard-E-Mail", "E-Mail (Geschäft)",
            "E-Mail (Privat)", "E-Mail (Zentrale)", "Standard-Telefon 1", "Telefon (Geschäft)", "Telefon (Zentrale)",
            "Mobil (Geschäft)", "Sekretär(in)", "Sekretär(in)"));
    private static final Zuordnung PROFIL = Zuordnungsvorschlag.fuer(ImportArt.KONTAKT, KOPF_21CRM);

    @Test
    void kopfHashIgnoriertGrossschreibungUndRandLeerraum() {
        Kopfzeile kopf = new Kopfzeile(List.of("Name", "PLZ"));

        assertThat(new Kopfzeile(List.of(" name ", "plz")).hash()).isEqualTo(kopf.hash());
        assertThat(new Kopfzeile(List.of("PLZ", "Name")).hash()).isNotEqualTo(kopf.hash());
        assertThat(new Kopfzeile(List.of("Name", "PLZ", "Ort")).hash()).isNotEqualTo(kopf.hash());
        assertThat(new Kopfzeile(List.of("NamePLZ")).hash()).isNotEqualTo(kopf.hash());
    }

    @Test
    void profilNurMitAllenKennspalten() {
        assertThat(PROFIL.profil()).isEqualTo(Zuordnung.PROFIL_21CRM);
        assertThat(Zuordnungsvorschlag.profil21Crm(ImportArt.KONTAKT,
                new Kopfzeile(KOPF_21CRM.namen().subList(1, KOPF_21CRM.spalten())))).isEmpty();
    }

    @Test
    void synonymeNehmenVorrangUndJedeSpalteNurEinmal() {
        Kopfzeile kopf = new Kopfzeile(List.of("Nachname", "Firma", "Vorname", "E-Mail (Geschäft)", "Tel.", "Ort"));

        Zuordnung vorschlag = Zuordnungsvorschlag.fuer(ImportArt.KONTAKT, kopf);

        assertThat(vorschlag.profil()).isNull();
        assertThat(vorschlag.felder()).containsEntry(ImportFeld.NAME, 1).containsEntry(ImportFeld.VORNAME, 2)
                .containsEntry(ImportFeld.EMAIL, 3).containsEntry(ImportFeld.TELEFON, 4)
                .containsEntry(ImportFeld.ORT, 5).doesNotContainValue(0);
    }

    @Test
    void organisationNimmtNameAusOrganisation() {
        List<String> zeile = zeile(Map.of(1, "true", 2, "Muster AG", 3, "Ansprechperson"));

        assertThat(PROFIL.werte(ImportArt.KONTAKT, zeile)).containsEntry(ImportFeld.NAME, "Muster AG")
                .containsEntry(ImportFeld.TYP, "ORGANISATION").doesNotContainKey(ImportFeld.ORGANISATION);
    }

    @Test
    void personOhneNameIstFehlerhaftAuchMitOrganisation() {
        List<String> zeile = zeile(Map.of(1, "false", 2, "Muster AG", 4, "Erika"));

        assertThat(PROFIL.werte(ImportArt.KONTAKT, zeile)).doesNotContainKey(ImportFeld.NAME);
        assertThat(pruefen(zeile).ergebnis()).isEqualTo(ZeilenPruefung.Ergebnis.FEHLER);
        assertThat(pruefen(zeile).meldung()).isEqualTo("Name fehlt");
    }

    @Test
    void emailUndTelefonNachIndexMitAusweichreihenfolge() {
        List<String> nachIndex = zeile(Map.of(3, "Muster", 6, "3", 7, "g@example.test", 8, "p@example.test",
                10, "10", 11, "041 000 00 01", 12, "041 000 00 02"));
        List<String> ohneIndex = zeile(Map.of(3, "Muster", 9, "z@example.test", 13, "079 000 00 03"));

        assertThat(PROFIL.werte(ImportArt.KONTAKT, nachIndex)).containsEntry(ImportFeld.EMAIL, "p@example.test")
                .containsEntry(ImportFeld.TELEFON, "041 000 00 02");
        assertThat(PROFIL.werte(ImportArt.KONTAKT, ohneIndex)).containsEntry(ImportFeld.EMAIL, "z@example.test")
                .containsEntry(ImportFeld.TELEFON, "079 000 00 03");
    }

    @Test
    void geloeschteZeileWirdUebersprungen() {
        assertThat(pruefen(zeile(Map.of(3, "Muster", 5, "True"))))
                .isEqualTo(ZeilenPruefung.Befund.UEBERSPRUNGEN);
        assertThat(pruefen(zeile(Map.of(3, "Muster", 5, "false"))))
                .isEqualTo(ZeilenPruefung.Befund.OFFEN);
    }

    @Test
    void strukturMeldetNurSpaltenUndZahlen() {
        ZeilenPruefung.Befund kurz = ZeilenPruefung.struktur(List.of("geheim"), 2, 10);
        ZeilenPruefung.Befund lang = ZeilenPruefung.struktur(List.of("a", "geheim-geheim"), 2, 10);

        assertThat(kurz.meldung()).isEqualTo("Spaltenzahl 1 statt 2 wie in der Kopfzeile");
        assertThat(kurz.werteVerwerfen()).isFalse();
        assertThat(lang.meldung()).isEqualTo("Feld in Spalte 2 länger als 10 Zeichen");
        assertThat(lang.werteVerwerfen()).isTrue();
    }

    @Test
    void formelwerteBleibenUnveraendert() {
        List<String> zeile = zeile(Map.of(3, "=HYPERLINK(\"x\")", 10, "4", 11, "+41 41 000 00 00"));

        assertThat(PROFIL.werte(ImportArt.KONTAKT, zeile)).containsEntry(ImportFeld.NAME, "=HYPERLINK(\"x\")")
                .containsEntry(ImportFeld.TELEFON, "+41 41 000 00 00");
    }

    @Test
    void mangelDerZuordnung() {
        assertThat(new Zuordnung(null, Map.of(ImportFeld.VORNAME, 0)).mangel(ImportArt.KONTAKT, 2))
                .contains(Zuordnung.Mangel.PFLICHTFELD_FEHLT);
        assertThat(new Zuordnung(null, Map.of(ImportFeld.ORGANISATION, 0)).mangel(ImportArt.KONTAKT, 2)).isEmpty();
        assertThat(new Zuordnung(null, Map.of(ImportFeld.NAME, 2)).mangel(ImportArt.KONTAKT, 2))
                .contains(Zuordnung.Mangel.POSITION_UNGUELTIG);
        assertThat(new Zuordnung(null, Map.of(ImportFeld.NAME, 0, "passwort", 1)).mangel(ImportArt.KONTAKT, 2))
                .contains(Zuordnung.Mangel.UNBEKANNTES_FELD);
        assertThat(new Zuordnung(null, Map.of(ImportFeld.NAME, 0)).mangel(ImportArt.VERKAUFSCHANCE, 2))
                .contains(Zuordnung.Mangel.UNBEKANNTES_FELD);
    }

    private static ZeilenPruefung.Befund pruefen(List<String> zeile) {
        return ZeilenPruefung.pruefen(ImportArt.KONTAKT, PROFIL, zeile, KOPF_21CRM.spalten(), 10_000);
    }

    private static List<String> zeile(Map<Integer, String> werte) {
        List<String> zeile = new ArrayList<>(Collections.nCopies(KOPF_21CRM.spalten(), ""));
        werte.forEach(zeile::set);
        return zeile;
    }
}
