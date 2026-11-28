package ch.urinet.archiv.sftp;

import java.io.PrintStream;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Log als eine JSON-Zeile je Ereignis auf stdout (Spec 09, Requirement 1.7). Nimmt nur Metadaten auf (Zeit, Benutzer,
 * Adresse, Pfad, Grösse, Verfahren), nie Inhalte von Dateien oder Schlüssel.
 */
public final class JsonLog {

    private final Consumer<String> ausgabe;

    public JsonLog(Consumer<String> ausgabe) {
        this.ausgabe = ausgabe;
    }

    static JsonLog auf(PrintStream strom) {
        return new JsonLog(zeile -> {
            synchronized (strom) {
                strom.println(zeile);
                strom.flush();
            }
        });
    }

    /** Schreibt ein Ereignis; {@code felder} abwechselnd Name und Wert, {@code null}-Werte entfallen. */
    void ereignis(String ereignis, Object... felder) {
        Map<String, Object> zeile = new LinkedHashMap<>();
        zeile.put("zeit", Instant.now().toString());
        zeile.put("ereignis", ereignis);
        for (int i = 0; i + 1 < felder.length; i += 2) {
            if (felder[i + 1] != null) {
                zeile.put(String.valueOf(felder[i]), felder[i + 1]);
            }
        }
        ausgabe.accept(json(zeile));
    }

    static String json(Object wert) {
        StringBuilder sb = new StringBuilder();
        schreibe(sb, wert);
        return sb.toString();
    }

    private static void schreibe(StringBuilder sb, Object wert) {
        switch (wert) {
            case null -> sb.append("null");
            case Number n -> sb.append(n);
            case Boolean b -> sb.append(b);
            case Map<?, ?> m -> {
                sb.append('{');
                boolean erstes = true;
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    if (!erstes) {
                        sb.append(',');
                    }
                    erstes = false;
                    zeichenkette(sb, String.valueOf(e.getKey()));
                    sb.append(':');
                    schreibe(sb, e.getValue());
                }
                sb.append('}');
            }
            case Collection<?> c -> {
                sb.append('[');
                boolean erstes = true;
                for (Object o : c) {
                    if (!erstes) {
                        sb.append(',');
                    }
                    erstes = false;
                    schreibe(sb, o);
                }
                sb.append(']');
            }
            default -> zeichenkette(sb, wert.toString());
        }
    }

    private static void zeichenkette(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}
