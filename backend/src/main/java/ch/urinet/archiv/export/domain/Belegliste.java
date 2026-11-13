package ch.urinet.archiv.export.domain;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * {@code belegliste.csv} im Jahresexport (Spec 02, Req 7.1; design.md «Jahresexport»): UTF-8 mit BOM, Semikolon,
 * Zeilenende CRLF (Excel CH), eine Zeile je Datei im ZIP. Spalten ID, Art, Datum, Partner, Belegnummer, Richtung,
 * Betrag, Währung, SHA-256, Dateiname. Datum als {@code yyyy-mm-dd}, Betrag mit Punkt ohne Tausendertrenner.
 * <p>
 * Felder mit Semikolon, Anführungszeichen oder Zeilenumbruch stehen in Anführungszeichen (RFC 4180). Gegen
 * Formel-Injection (Spec 01, design.md «CSV-Import») erhalten Textfelder, die mit {@code =}, {@code +}, {@code -},
 * {@code @}, Tab oder CR beginnen, ein vorangestelltes {@code '}; Betrag und Datum erzeugt das Archiv selbst, sie
 * bleiben Zahlen.
 */
public final class Belegliste {

    public static final String DATEINAME = "belegliste.csv";
    public static final byte[] BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    public static final List<String> KOPF = List.of("ID", "Art", "Datum", "Partner", "Belegnummer", "Richtung",
            "Betrag", "Währung", "SHA-256", "Dateiname");
    private static final String ZEILENENDE = "\r\n";

    private Belegliste() {
    }

    /** Eine Zeile der Liste: eine Datei im ZIP mit den Metadaten ihres Dokuments. */
    public record Zeile(UUID id, String art, LocalDate datum, String partner, String belegnummer, String richtung,
                        BigDecimal betrag, String waehrung, String sha256, String dateiname) {

        public Zeile {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(sha256, "sha256");
            Objects.requireNonNull(dateiname, "dateiname");
        }
    }

    public static byte[] kopf() {
        return (KOPF.stream().map(Belegliste::feld).collect(Collectors.joining(";")) + ZEILENENDE)
                .getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] zeile(Zeile z) {
        String text = String.join(";", feld(z.id().toString()), text(z.art()),
                z.datum() == null ? "" : z.datum().toString(), text(z.partner()), text(z.belegnummer()),
                text(z.richtung()), z.betrag() == null ? "" : z.betrag().toPlainString(), text(z.waehrung()),
                feld(z.sha256()), text(z.dateiname()));
        return (text + ZEILENENDE).getBytes(StandardCharsets.UTF_8);
    }

    /** Textfeld mit Schutz gegen Formel-Injection. */
    static String text(String wert) {
        if (wert == null || wert.isEmpty()) {
            return "";
        }
        char erstes = wert.charAt(0);
        boolean formel = erstes == '=' || erstes == '+' || erstes == '-' || erstes == '@' || erstes == '\t'
                || erstes == '\r';
        return feld(formel ? "'" + wert : wert);
    }

    static String feld(String wert) {
        if (wert.indexOf(';') < 0 && wert.indexOf('"') < 0 && wert.indexOf('\n') < 0 && wert.indexOf('\r') < 0) {
            return wert;
        }
        return '"' + wert.replace("\"", "\"\"") + '"';
    }
}
