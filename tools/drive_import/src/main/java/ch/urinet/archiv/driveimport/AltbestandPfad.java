package ch.urinet.archiv.driveimport;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

record AltbestandPfad(String reihe, String art, int jahr, Integer ausgabe) {

    private static final Pattern JAHR = Pattern.compile("(?<!\\d)(?:19|20)\\d{2}(?!\\d)");
    private static final Pattern AUSGABE = Pattern.compile(
            "(?i)^(?:ausgabe[ _.-]*)?([0-9]{1,5})(?:[ _.-]+((?:19|20)[0-9]{2}))?$");
    private static final Pattern JAHR_AUSGABE = Pattern.compile(
            "(?i)^((?:19|20)[0-9]{2})[ _.-]+([0-9]{1,5})$");

    record Erkennung(AltbestandPfad pfad, String grund) {
    }

    static Erkennung erkennen(Path wurzel, Path datei) {
        if (!datei.startsWith(wurzel)) {
            return new Erkennung(null, "Datei liegt ausserhalb des Startordners");
        }
        Path relativ = wurzel.relativize(datei);
        if (relativ.getNameCount() < 2) {
            return new Erkennung(null, "Reihenordner fehlt");
        }
        String reihe = relativ.getName(0).toString();
        String art = switch (reihe.toLowerCase(Locale.ROOT)) {
            case "bulletin" -> "BULLETIN";
            case "merkblatt" -> "MERKBLATT";
            case "protokoll" -> "PROTOKOLL";
            case "jahresbericht" -> "JAHRESBERICHT";
            default -> null;
        };
        if (art == null) {
            return new Erkennung(null, "Reihe nicht erkannt; erwartet Bulletin, Merkblatt, Protokoll oder Jahresbericht");
        }

        Set<Integer> jahre = new LinkedHashSet<>();
        for (Path teil : relativ) {
            Matcher matcher = JAHR.matcher(teil.toString());
            while (matcher.find()) {
                jahre.add(Integer.valueOf(matcher.group()));
            }
        }
        if (jahre.isEmpty()) {
            return new Erkennung(null, "Jahr nicht erkannt; vierstellige Jahreszahl im Pfad oder Dateinamen ergänzen");
        }
        if (jahre.size() > 1) {
            return new Erkennung(null, "Jahr mehrdeutig; im Pfad und Dateinamen darf nur ein Jahr stehen");
        }

        Integer ausgabe = ausgabe(datei.getFileName().toString(), jahre.iterator().next());
        return new Erkennung(new AltbestandPfad(reihe, art, jahre.iterator().next(), ausgabe), null);
    }

    private static Integer ausgabe(String dateiname, int jahr) {
        int punkt = dateiname.lastIndexOf('.');
        String stamm = (punkt > 0 ? dateiname.substring(0, punkt) : dateiname).strip();
        if (JAHR.matcher(stamm).matches() && Integer.parseInt(stamm) == jahr) {
            return null;
        }
        Matcher jahrAusgabe = JAHR_AUSGABE.matcher(stamm);
        if (jahrAusgabe.matches()) {
            return nummer(jahrAusgabe.group(2));
        }
        Matcher ausgabeJahr = AUSGABE.matcher(stamm);
        if (!ausgabeJahr.matches()) {
            return null;
        }
        return nummer(ausgabeJahr.group(1));
    }

    private static Integer nummer(String text) {
        try {
            int nummer = Integer.parseInt(text);
            return nummer > 0 && nummer <= 10_000 ? nummer : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
