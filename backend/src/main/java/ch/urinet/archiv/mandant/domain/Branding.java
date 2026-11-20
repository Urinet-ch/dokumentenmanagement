package ch.urinet.archiv.mandant.domain;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.FarbKontrast;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;

import java.net.IDN;
import java.net.URI;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/** Öffentliches, mandantenspezifisches Erscheinungsbild; enthält keine Datenzugriffsrechte. */
public record Branding(String name, String logoUrl, String primaerfarbe, String textfarbe, String sprache) {

    private static final Pattern HOST = Pattern.compile(
            "(?=.{1,253}$)(?![0-9.]+$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)*[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");
    private static final Pattern FARBE = Pattern.compile("^#[0-9a-fA-F]{6}$");
    private static final Pattern NAME = Pattern.compile("^[\\p{L}\\p{N}][\\p{L}\\p{N} &'’().,/-]{0,79}$");
    private static final Pattern DATEN_URI = Pattern.compile(
            "^data:image/(png|jpeg|webp);base64,([A-Za-z0-9+/]+={0,2})$");
    private static final Pattern BILD_PFAD = Pattern.compile("(?i).+\\.(png|jpe?g|webp)$");

    private static final String TEXT_DUNKEL = "#05070c";
    private static final String TEXT_HELL = "#f2efe7";
    /** WCAG 2.1 AA für normalen Text. */
    private static final double MIN_KONTRAST = 4.5;

    public static final Branding STANDARD = new Branding("Archiv", null, "#eeb111", TEXT_DUNKEL, "de-CH");

    public static Optional<String> domain(String wert) {
        if (wert == null || wert.isBlank() || wert.length() > 253) {
            return Optional.empty();
        }
        // Ein einzelner abschliessender Punkt (FQDN-Schreibweise) bezeichnet denselben Host.
        String name = wert.endsWith(".") ? wert.substring(0, wert.length() - 1) : wert;
        if (name.isEmpty()) {
            return Optional.empty();
        }
        try {
            String ascii = IDN.toASCII(name, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
            return HOST.matcher(ascii).matches() ? Optional.of(ascii) : Optional.empty();
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public static Branding ausKonfiguration(String name, String logoUrl, String primaerfarbe, String sprache) {
        String sicherName = name != null && NAME.matcher(name.strip()).matches() ? name.strip() : STANDARD.name;
        String sicherFarbe = primaerfarbe != null && FARBE.matcher(primaerfarbe).matches()
                ? primaerfarbe.toLowerCase(Locale.ROOT) : STANDARD.primaerfarbe;
        if (bestesKontrastverhaeltnis(sicherFarbe) < MIN_KONTRAST) {
            sicherFarbe = STANDARD.primaerfarbe;
        }
        String sicherSprache = "de-CH".equals(sprache) ? sprache : STANDARD.sprache;
        return new Branding(sicherName, logo(logoUrl), sicherFarbe, textfarbe(sicherFarbe), sicherSprache);
    }

    public static String validierteDomain(String domain) {
        return domain(domain).orElseThrow(() -> new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG));
    }

    private static String logo(String wert) {
        if (wert == null || wert.isBlank()) {
            return null;
        }
        var datenUri = DATEN_URI.matcher(wert);
        if (datenUri.matches()) {
            try {
                if (wert.length() > 140_000) {
                    return null;
                }
                byte[] daten = Base64.getDecoder().decode(datenUri.group(2));
                if (daten.length > 100_000 || !bildSignatur(datenUri.group(1), daten)) {
                    return null;
                }
                return wert;
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        if (wert.length() > 2_048) {
            return null;
        }
        try {
            URI uri = URI.create(wert);
            boolean pfadBild = BILD_PFAD.matcher(uri.getPath() == null ? "" : uri.getPath()).matches();
            if (!pfadBild || uri.getRawQuery() != null || uri.getRawFragment() != null || uri.getUserInfo() != null) {
                return null;
            }
            // Keine fremden Hosts: Besucher des öffentlichen Archivs würden sonst Dritte kontaktieren.
            return !uri.isAbsolute() && wert.startsWith("/") && !wert.startsWith("//") ? wert : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean bildSignatur(String typ, byte[] daten) {
        return switch (typ) {
            case "png" -> daten.length >= 8 && daten[0] == (byte) 0x89 && daten[1] == 'P'
                    && daten[2] == 'N' && daten[3] == 'G';
            case "jpeg" -> daten.length >= 3 && daten[0] == (byte) 0xff && daten[1] == (byte) 0xd8
                    && daten[2] == (byte) 0xff;
            case "webp" -> daten.length >= 12 && daten[0] == 'R' && daten[1] == 'I' && daten[2] == 'F'
                    && daten[3] == 'F' && daten[8] == 'W' && daten[9] == 'E' && daten[10] == 'B' && daten[11] == 'P';
            default -> false;
        };
    }

    private static String textfarbe(String farbe) {
        return kontrast(farbe, TEXT_DUNKEL) >= kontrast(farbe, TEXT_HELL) ? TEXT_DUNKEL : TEXT_HELL;
    }

    private static double bestesKontrastverhaeltnis(String farbe) {
        return Math.max(kontrast(farbe, TEXT_DUNKEL), kontrast(farbe, TEXT_HELL));
    }

    private static double kontrast(String farbeA, String farbeB) {
        return FarbKontrast.zwischen(farbeA, farbeB);
    }
}
