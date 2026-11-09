package ch.urinet.archiv.spike.golden;

import ch.urinet.archiv.spike.golden.SwissQrLeser.QrDaten;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

/** Prompt nach design.md «KI»: Rolle, Dokumentarten, QR-Werte, Text (12'000 Zeichen, Anfang + Ende); ohne Beispiele. */
public final class Prompt {

    static final String SYSTEM = """
            Du bist Archivassistent für ein Schweizer KMU. Mandant (eigene Firma): {mandant}.
            Erfasse die Metadaten des Belegs für das Archiv des Mandanten.

            Dokumentarten (Code: Name, Standard-Vertraulichkeit):
            {dokumentarten}
            Regeln:
            - art: genau ein Code aus der Liste. Arten mit «stellt der Mandant selbst aus» nur, wenn der Mandant der \
            Aussteller ist (sein Name steht als Absender im Briefkopf, nicht nur im Adressfeld). Steht der Mandant im \
            Adressfeld oder ist der Zahlungsempfänger im Swiss QR Code ein anderer, ist es ein fremder Beleg, auch wenn \
            die Nummer wie eine eigene aussieht. Belege von Dritten sind nie eine solche Art: Kassenbons und Quittungen sind QUITTUNG, \
            Rechnungen und Gutschriften von Lieferanten LIEFERANTENBELEG, Schreiben, Verfügungen und Rechnungen von \
            Ämtern und Ausgleichskassen BEHOERDE, Policen, Prämienrechnungen und Mahnungen von Versicherungen \
            VERSICHERUNG.
            - partnerName: die Gegenpartei aus Sicht des Mandanten (bei fremden Belegen der Aussteller, bei eigenen \
            Belegen der Empfänger, bei Quittungen wer das Geld erhalten hat), nie der Mandant selbst und nie eine \
            Zahlungsart; so geschrieben wie auf dem Beleg.
            - datum: Belegdatum (Ausstellungsdatum) als JJJJ-MM-TT; zweistellige Jahre wie in 8.11.26 sind 20xx. \
            Erfinde kein Datum: steht keines auf dem Beleg, null.
            - betrag: zu zahlender bzw. bezahlter Endbetrag; Gutschriften negativ; ohne Betrag null (nicht 0).
            - belegnummer: Nummer des Belegs ohne Beschriftung, z. B. nach «Rechnung», «Nr.», «Bon», «Beleg», \
            «Quittung», «Ticket», «Bestellung», «Referenz», «Police» oder «Verfügung»; nicht die QR-Referenz \
            und nicht die Kundennummer; fehlt sie, null.
            - richtung: EINNAHME oder AUSGABE aus Sicht des Mandanten; ohne Betrag null.
            - faelligkeit: Zahlungsfrist als JJJJ-MM-TT; bei «zahlbar innert N Tagen» Belegdatum plus N Tage; sonst null.
            - Werte aus dem Swiss QR Code sind verlässlich und haben Vorrang vor dem Text.
            - sicherheit: je Feld eine Zahl zwischen 0 und 1, wie sicher der Wert stimmt.
            """;

    static final String BENUTZER = """
            Swiss QR Code: {qr}

            Belegtext (OCR):
            ---
            {text}
            ---
            """;

    static final String NACHFRAGE = """

            Die letzte Antwort entsprach nicht dem verlangten JSON-Schema ({fehler}). Antworte nur mit gültigem JSON.
            """;

    static final String BENUTZER_K1 = """
            Swiss QR Code: {qr}

            Der Beleg liegt als Bild bei (Foto oder gerenderte Seiten untereinander). Lies die Werte direkt aus dem Bild.
            """;

    static final int MAX_ZEICHEN = 12_000;

    private Prompt() {
    }

    public static String gekuerzt(String text) {
        if (text == null) {
            return "";
        }
        String t = text.strip();
        if (t.length() <= MAX_ZEICHEN) {
            return t;
        }
        int haelfte = MAX_ZEICHEN / 2;
        return t.substring(0, haelfte) + "\n[…]\n" + t.substring(t.length() - haelfte);
    }

    public static String qr(Optional<QrDaten> qr) {
        if (qr.isEmpty()) {
            return "keiner gefunden";
        }
        QrDaten q = qr.get();
        return "IBAN " + q.iban() + ", Betrag " + (q.betrag() == null ? "keiner" : q.betrag().toPlainString())
                + ", Währung " + q.waehrung() + ", Referenz " + (q.referenz() == null ? "keine" : q.referenz())
                + ", Zahlungsempfänger " + q.zahlungsempfaenger();
    }

    /** Kennung der Prompt-Fassung für den Bericht (Version + Hash der Vorlagen). */
    public static String hash() {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256")
                    .digest((SYSTEM + BENUTZER + NACHFRAGE + BENUTZER_K1).getBytes(StandardCharsets.UTF_8));
            return MetadatenExtraktor.PROMPT_VERSION + "-" + HexFormat.of().formatHex(h).substring(0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
