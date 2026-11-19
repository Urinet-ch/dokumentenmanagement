package ch.urinet.archiv.ki.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Prompt der Erfassung (design.md «KI»): Texte aus der Mandantenkonfiguration, Kürzung und QR-Zeile. Jede Änderung an
 * Text, Reihenfolge oder Schema ändert die Kennung des Prompts.
 * Die Korrekturbeispiele (Aufgabe 3.3, Req 7.9) stehen in {@link #BEISPIELE} vor der Benutzernachricht; sie zählen
 * nicht zur Kennung, gehen aber nur mit {@code archiv.ki.beispiele.aktiv=true} an das Modell (der Gate-Lauf kannte sie
 * nicht, design.md «Umsetzung (Aufgabe 3.3)»).
 */
public final class ErfassungPrompt {

    public static final String VERSION = "v6";
    /** Kennung des bisherigen Gates; die Snapshot-Prüfung hält die Kompatibilität für {@code urinet} fest. */
    public static final String GATE_KENNUNG = "v6-7a39224f2f5e";
    /** Kennung des Bildwegs im Gate-Lauf: Originale, die Bilder sind, gehen als Seitenbild an das Modell (Req 7.1). */
    public static final String BILDWEG = "fotos";

    /** Vorlage; Konfiguration, Mandantenname und Artenliste werden je Mandant eingesetzt. */
    public static final String SYSTEM = """
            Du bist {rolle}. Mandant (eigene Firma): {mandant}.
            Erfasse die Metadaten des Belegs für das Archiv des Mandanten.

            Dokumentarten (Code: Name, Standard-Vertraulichkeit):
            {dokumentarten}
            Regeln:
            {feldregeln}
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

    /**
     * Korrekturbeispiele vor {@link #BENUTZER} bzw. {@link #BENUTZER_K1} (Req 7.9); {beispiele} aus
     * {@link #beispiele(List, boolean)} mit der Einleitung je nach Auswahl. Nicht Teil von {@link #hash}: Ohne Beispiele
     * bleibt die Nachricht die des Gate-Laufs.
     */
    public static final String BEISPIELE = """
            Korrekturbeispiele: {beispiele}

            """;
    static final String EINLEITUNG_MERKMALE = "Bei früheren Belegen mit gleichen Merkmalen (IBAN, UID, E-Mail, "
            + "Belegnummer-Muster) hat der Benutzer diese Werte bestätigt. Übernimm sie nur, wenn der Beleg dazu passt.";
    static final String EINLEITUNG_NEUESTE = "Bei den neuesten früheren Belegen, ohne gleiche Merkmale wie dieser "
            + "Beleg, hat der Benutzer diese Werte bestätigt. Sie zeigen nur, worauf er achtet; übernimm keinen Wert, der "
            + "nicht aus diesem Beleg hervorgeht.";

    public static final int MAX_ZEICHEN = 12_000;
    /** Höchstlänge eines Werts in {@link #beispiele(List)}. */
    static final int MAX_WERT = 200;

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

    /**
     * Wert für {beispiele}: die Einleitung (Auswahl nach Merkmalen oder Rückfall auf die neuesten), dann je Beispiel
     * eine Zeile mit der bestätigten Art und nur den korrigierten Feldern in der Reihenfolge von {@link Feld},
     * Leerraum zusammengefasst, Werte höchstens {@value #MAX_WERT} Zeichen; leer ohne Beispiele.
     */
    public static String beispiele(List<Beispiel> beispiele, boolean nachMerkmalen) {
        if (beispiele.isEmpty()) {
            return "";
        }
        return (nachMerkmalen ? EINLEITUNG_MERKMALE : EINLEITUNG_NEUESTE) + "\n" + zeilen(beispiele);
    }

    /** Je Beispiel eine Zeile (ohne Einleitung). */
    static String zeilen(List<Beispiel> beispiele) {
        List<String> zeilen = new ArrayList<>();
        for (Beispiel b : beispiele) {
            StringBuilder sb = new StringBuilder("- art=").append(wert(b.art()));
            for (Feld feld : Feld.values()) {
                if (feld != Feld.ART && b.korrektur().containsKey(feld)) {
                    sb.append("; ").append(feld.schluessel()).append('=').append(wert(b.korrektur().get(feld)));
                }
            }
            zeilen.add(sb.toString());
        }
        return String.join("\n", zeilen);
    }

    private static String wert(String wert) {
        if (wert == null) {
            return "null";
        }
        String w = wert.replaceAll("\\s+", " ").strip();
        return w.length() <= MAX_WERT ? w : w.substring(0, MAX_WERT) + "…";
    }

    /** Fertig gerenderter System-Prompt, wie ihn das Modell erhält. */
    public static String system(String mandant, String dokumentarten, String rolle, String feldregeln,
                                List<KiKlassifikation> klassifikationen) {
        return SYSTEM.replace("{rolle}", rolle).replace("{mandant}", mandant)
                .replace("{dokumentarten}", dokumentarten)
                .replace("{feldregeln}", feldregeln + klassifikationen(klassifikationen));
    }

    /** Für Tests ohne Konfiguration. Produktaufrufe verwenden die vierteilige Variante. */
    public static String system(String mandant, String dokumentarten) {
        return system(mandant, dokumentarten, "Archivassistent für ein Schweizer KMU", "", List.of());
    }

    private static String klassifikationen(List<KiKlassifikation> klassifikationen) {
        List<String> zeilen = new ArrayList<>();
        for (KiKlassifikation klassifikation : klassifikationen) {
            List<KiKlassifikation.Wert> werte = klassifikation.werte().stream()
                    .filter(KiKlassifikation.Wert::aktiv).toList();
            if (werte.isEmpty()) {
                continue;
            }
            String werteText = werte.stream().map(wert -> wert.code() + ": " + wert.name()
                    + (wert.beschreibung() == null || wert.beschreibung().isBlank()
                    ? "" : " – " + wert.beschreibung())).collect(java.util.stream.Collectors.joining("; "));
            zeilen.add("- " + klassifikation.code() + ": " + klassifikation.name()
                    + (klassifikation.beschreibung() == null || klassifikation.beschreibung().isBlank()
                    ? "" : " – " + klassifikation.beschreibung())
                    + (klassifikation.mehrfach() ? " (mehrere Werte)" : "") + ": " + werteText);
        }
        return zeilen.isEmpty() ? "" : "\n\nKlassifikationen:\n" + String.join("\n", zeilen);
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
            sb.append("- ").append(a.code()).append(": ").append(a.name());
            if (a.beschreibung() != null && !a.beschreibung().isBlank()) {
                sb.append(" – ").append(a.beschreibung());
            }
            if (a.kiHinweise() != null && !a.kiHinweise().isBlank()) {
                sb.append(" KI-Hinweis: ").append(a.kiHinweise().replace("{mandant}", mandant));
            }
            sb.append(", ").append(a.vertraulichkeit());
            if (eigeneArten.contains(a.code())) {
                sb.append(" (stellt der Mandant selbst aus)");
            }
            sb.append("\n");
            if (!a.kiFelder().isEmpty()) {
                for (String feld : a.kiFelder()) {
                    if (!feld.equals("zugriff") && !feld.equals("veroeffentlicht")) {
                        sb.append("  KI-Feld: zusatz.").append(feld).append(" (")
                                .append(a.zusatzFeldTypen().get(feld)).append(")");
                        String beschreibung = a.zusatzFeldBeschreibungen().get(feld);
                        if (beschreibung != null && !beschreibung.isBlank()) {
                            sb.append(" – ").append(beschreibung);
                        }
                        sb.append("\n");
                    }
                }
            }
        }
        return sb.toString();
    }

    /** Arten, die das Antwortschema kennt, in dessen Reihenfolge (Reihenfolge der Seed-Tabelle im Golden-Lauf). */
    public static List<ArtEintrag> schemaArten(List<ArtEintrag> arten, List<String> codes) {
        return arten.stream().filter(a -> codes.contains(a.code()))
                .sorted(Comparator.comparingInt(a -> codes.indexOf(a.code()))).toList();
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
