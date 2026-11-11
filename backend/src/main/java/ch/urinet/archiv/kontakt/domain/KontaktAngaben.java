package ch.urinet.archiv.kontakt.domain;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.gemeinsam.domain.Normalisierer;

/**
 * Pflegbare Felder eines Kontakts. {@link #bereinigt()} trimmt, normalisiert E-Mail, UID, IBAN und PLZ (design.md
 * «Normalisierung») und macht leere Felder zu {@code null}; der Name ist Pflicht.
 */
public record KontaktAngaben(KontaktTyp typ, String name, String vorname, String strasse, String plz, String ort,
                             String email, String telefon, String uid, String iban, String kategorie) {

    public KontaktAngaben bereinigt() {
        if (typ == null || leer(name)) {
            throw new FachFehler(Fehlercode.PFLICHTFELD_FEHLT);
        }
        return new KontaktAngaben(typ, name.strip(), text(vorname), text(strasse), wert(Normalisierer.plz(plz)),
                text(ort), wert(Normalisierer.email(email)), text(telefon), wert(Normalisierer.uid(uid)),
                wert(Normalisierer.iban(iban)), text(kategorie));
    }

    /** Vergleichsname: bei Personen aus Vorname und Name (Profil «21.CRM»), sonst aus dem Namen. */
    public String nameNormalisiert() {
        return typ == KontaktTyp.PERSON ? Normalisierer.personName(vorname, name) : Normalisierer.name(name);
    }

    /** Übernimmt jedes Feld aus {@code neu}, das nicht {@code null} ist; ein leerer Text löscht das Feld. */
    public KontaktAngaben mit(KontaktAngaben neu) {
        return new KontaktAngaben(neu.typ != null ? neu.typ : typ, neu.name != null ? neu.name : name,
                neu.vorname != null ? neu.vorname : vorname, neu.strasse != null ? neu.strasse : strasse,
                neu.plz != null ? neu.plz : plz, neu.ort != null ? neu.ort : ort,
                neu.email != null ? neu.email : email, neu.telefon != null ? neu.telefon : telefon,
                neu.uid != null ? neu.uid : uid, neu.iban != null ? neu.iban : iban,
                neu.kategorie != null ? neu.kategorie : kategorie);
    }

    private static boolean leer(String s) {
        return s == null || s.isBlank();
    }

    private static String text(String s) {
        return leer(s) ? null : s.strip();
    }

    private static String wert(String normalisiert) {
        return normalisiert.isEmpty() ? null : normalisiert;
    }
}
