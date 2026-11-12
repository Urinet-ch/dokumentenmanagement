package ch.urinet.archiv.ki.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Prompt der Erfassung, Fassung {@value #VERSION} aus dem Golden-Lauf (Gate 0 nach Aufgabe 3.8; design.md «KI»): Texte,
 * Kürzung, QR-Zeile, Liste der Dokumentarten und Kennung der Prompt-Fassung 1:1 aus
 * {@code spikes/golden-lauf} ({@code Prompt}, {@code Dokumentarten}). Jede Änderung an Text, Reihenfolge oder Schema
 * ändert die Kennung und braucht einen neuen Golden-Lauf; {@code ErfassungPromptTest} hält den Stand des Spikes fest.
 * Ohne Korrekturbeispiele, die folgen mit Aufgabe 3.3 (Req 7.9).
 */
public final class ErfassungPrompt {

    public static final String VERSION = "v6";
    /** Kennung des Bildwegs im Gate-Lauf: Originale, die Bilder sind, gehen als Seitenbild an das Modell (Req 7.1). */
    public static final String BILDWEG = "fotos";

    /** Vorlage; {mandant} und {dokumentarten} setzt {@link #system} ein, damit gehashter und gesendeter Text gleich sind. */
    public static final String SYSTEM = """
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
            Ämtern und Steuerverwaltungen BEHOERDE, Policen, Nachträge und Kündigungen von Versicherungen VERSICHERUNG. \
            Prämienrechnungen von Versicherern und Mahnungen mit Mahngebühr oder Verzugszins sind LIEFERANTENBELEG, \
            auch wenn sie eine Prämie betreffen; Mahnungen ohne Gebühr und ohne Zins sind KORRESPONDENZ.
            - Sozialversicherungen (AHV-Ausgleichskassen, IV, EO, FAK; frz. caisse de compensation, ital. cassa di \
            compensazione) sind BEHOERDE, auch bei Rückerstattungen und Gutschriften; Policen von UVG-, Krankentaggeld- und \
            BVG-Versicherern sind VERSICHERUNG, ihre Prämienrechnungen LIEFERANTENBELEG.
            - partnerName: die Gegenpartei aus Sicht des Mandanten (bei fremden Belegen der Aussteller, bei eigenen \
            Belegen der Empfänger, bei Quittungen wer das Geld erhalten hat), nie der Mandant selbst und nie eine \
            Zahlungsart; so geschrieben wie auf dem Beleg.
            - datum: Belegdatum (Ausstellungsdatum) als JJJJ-MM-TT; zweistellige Jahre wie in 8.11.26 sind 20xx. \
            Erfinde kein Datum: steht keines auf dem Beleg, null.
            - betrag: zu zahlender bzw. bezahlter Endbetrag; Gutschriften negativ; ohne Betrag null (nicht 0). Bei \
            Offerten das einmalige Total; wiederkehrende Beträge (pro Monat oder Jahr) zählen nicht.
            - belegnummer: Nummer des Belegs ohne Beschriftung, z. B. nach «Rechnung», «Nr.», «Bon», «Beleg», \
            «Quittung», «Ticket», «Bestellung», «Referenz», «Police», «Verfügung» oder «Antrag»; nicht die QR-Referenz \
            und nicht die Kundennummer; fehlt sie, null.
            - richtung: EINNAHME oder AUSGABE aus Sicht des Mandanten; ohne Betrag null.
            - faelligkeit: Zahlungsfrist als JJJJ-MM-TT; bei «zahlbar innert N Tagen» Belegdatum plus N Tage; sonst null.
            - Werte aus dem Swiss QR Code sind verlässlich und haben Vorrang vor dem Text.
            - sicherheit: je Feld eine Zahl zwischen 0 und 1, wie sicher der Wert stimmt.
            """;

    public static final String BENUTZER = """
            Swiss QR Code: {qr}

            Belegtext (OCR):
            ---
            {text}
            ---
            """;

    public static final String NACHFRAGE = """

            Die letzte Antwort entsprach nicht dem verlangten JSON-Schema ({fehler}). Antworte nur mit gültigem JSON.
            """;

    public static final String BENUTZER_K1 = """
            Swiss QR Code: {qr}

            Der Beleg liegt als Bild bei (Foto oder gerenderte Seiten untereinander). Lies die Werte direkt aus dem Bild.
            """;

    public static final int MAX_ZEICHEN = 12_000;

    /**
     * Anzeigetexte nur im Prompt (v5); die Namen der Dokumentarten bleiben. %s ist der Mandantenname, eingesetzt in
     * Java, weil die Liste selbst der Wert von {dokumentarten} ist und nicht nochmals ersetzt würde.
     */
    static final Map<String, String> ANZEIGE = Map.of(
            "LIEFERANTENBELEG", "Lieferantenbeleg – Rechnung, Facture, Fattura oder Gutschrift eines Lieferanten an %s (Eingangsrechnung)",
            "QUITTUNG", "Quittung / Spesen – Kassenbon, Zahlungsbestätigung, Billett, Parkticket; bereits bezahlt",
            "AUSGANGSRECHNUNG", "Ausgangsrechnung – nur wenn %s im Briefkopf als Absender steht");

    private ErfassungPrompt() {
    }

    /** Text bis {@value #MAX_ZEICHEN} Zeichen; längere Texte als Anfang und Ende, getrennt durch «[…]». */
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

    /** Fertig gerenderter System-Prompt, wie ihn das Modell erhält. */
    public static String system(String mandant, String dokumentarten) {
        return SYSTEM.replace("{mandant}", mandant).replace("{dokumentarten}", dokumentarten);
    }

    /**
     * Liste der Dokumentarten für {dokumentarten}: fremde Arten zuerst, eigene zuletzt, je in der Reihenfolge von
     * {@code arten} (bei Unsicherheit fällt das Modell auf frühe Werte zurück).
     */
    public static String artListe(List<ArtEintrag> arten, Set<String> eigeneArten, String mandant) {
        List<ArtEintrag> sortiert = new ArrayList<>(arten.stream().filter(a -> !eigeneArten.contains(a.code())).toList());
        sortiert.addAll(arten.stream().filter(a -> eigeneArten.contains(a.code())).toList());
        StringBuilder sb = new StringBuilder();
        for (ArtEintrag a : sortiert) {
            String name = ANZEIGE.containsKey(a.code()) ? ANZEIGE.get(a.code()).replace("%s", mandant) : a.name();
            sb.append("- ").append(a.code()).append(": ").append(name).append(", ").append(a.vertraulichkeit());
            if (eigeneArten.contains(a.code())) {
                sb.append(" (stellt der Mandant selbst aus)");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    /** Kennung der Prompt-Fassung: gerenderter System-Prompt, Vorlagen, Schema, Reihenfolge der Art-Codes, Bildweg. */
    public static String hash(String system, String schema, List<String> artCodes, String bildweg) {
        StringBuilder sb = new StringBuilder();
        for (String teil : List.of(system, BENUTZER, NACHFRAGE, BENUTZER_K1, schema, String.join(",", artCodes),
                bildweg)) {
            sb.append(teil.length()).append(':').append(teil).append('\n');
        }
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return VERSION + "-" + HexFormat.of().formatHex(h).substring(0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
