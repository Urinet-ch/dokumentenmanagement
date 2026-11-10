package ch.urinet.archiv.dokumentart.domain;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Dokumentart eines Mandanten (Req 9.1). Das Schema der Zusatzfelder ({@code zusatzSchema}, JSON Schema Draft 2020-12)
 * steht als Text; geprüft wird es ausserhalb der Domain ({@code ZusatzSchemaPruefer}). Verletzte Invarianten ergeben
 * {@code ANFRAGE_UNGUELTIG}.
 * <p>
 * Pflichtfelder sind die Felder des Dokuments (design.md «Data Model») und {@code zusatz.<feld>}; ein Feld der Gruppe
 * {@code finanz}, {@code vertrag} bzw. ein Zusatzfeld ({@code frei}) verlangt die passende Gruppe.
 */
public record Dokumentart(String code, String name, String beschreibung, List<String> gruppen,
                          List<String> pflichtfelder, String zusatzSchema, Vertraulichkeit vertraulichkeit,
                          Aufbewahrungsregel aufbewahrung, boolean gesetzlichePflicht, boolean aktiv) {

    public static final int NAME_MAX = 100;
    public static final int BESCHREIBUNG_MAX = 1000;
    public static final String ZUSATZ = "zusatz.";
    /** Schema ohne Zusatzfelder; gilt, wenn beim Anlegen keines angegeben ist. */
    public static final String OHNE_ZUSATZ = "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}";

    private static final Pattern CODE = Pattern.compile("^[A-Z][A-Z0-9_]{1,39}$");
    private static final Pattern ZUSATZFELD = Pattern.compile("^zusatz\\.[a-z][a-z0-9_]{0,62}$");
    private static final Set<String> GRUPPEN = Set.of("finanz", "vertrag", "frei");
    private static final Set<String> GRUNDFELDER =
            Set.of("titel", "datum", "partner", "projekt", "tags", "vertraulichkeit", "person_benutzer");
    private static final Map<String, String> GRUPPE_DES_FELDES = Map.ofEntries(
            Map.entry("belegnummer", "finanz"), Map.entry("richtung", "finanz"), Map.entry("betrag", "finanz"),
            Map.entry("waehrung", "finanz"), Map.entry("faelligkeit", "finanz"), Map.entry("qr_referenz", "finanz"),
            Map.entry("konto", "finanz"), Map.entry("gueltig_ab", "vertrag"), Map.entry("gueltig_bis", "vertrag"),
            Map.entry("kuendigungsfrist", "vertrag"), Map.entry("naechste_frist", "vertrag"));

    public Dokumentart {
        if (!gueltigerCode(code) || !text(name, NAME_MAX) || beschreibung != null && !text(beschreibung, BESCHREIBUNG_MAX)
                || gruppen == null || pflichtfelder == null || zusatzSchema == null || vertraulichkeit == null
                || aufbewahrung == null) {
            throw ungueltig();
        }
        name = name.strip();
        beschreibung = beschreibung == null ? null : beschreibung.strip();
        gruppen = List.copyOf(gruppen);
        pflichtfelder = List.copyOf(pflichtfelder);
        if (!GRUPPEN.containsAll(gruppen) || new HashSet<>(gruppen).size() != gruppen.size()
                || new HashSet<>(pflichtfelder).size() != pflichtfelder.size()) {
            throw ungueltig();
        }
        for (String feld : pflichtfelder) {
            String gruppe = gruppe(feld);
            if (gruppe == null && !GRUNDFELDER.contains(feld) || gruppe != null && !gruppen.contains(gruppe)) {
                throw ungueltig();
            }
        }
    }

    public static boolean gueltigerCode(String code) {
        return code != null && CODE.matcher(code).matches();
    }

    /** Namen der Zusatzfelder unter den Pflichtfeldern (ohne {@code zusatz.}). */
    public Set<String> zusatzPflichtfelder() {
        Set<String> felder = new HashSet<>();
        pflichtfelder.stream().filter(f -> f.startsWith(ZUSATZ)).forEach(f -> felder.add(f.substring(ZUSATZ.length())));
        return Set.copyOf(felder);
    }

    private static String gruppe(String feld) {
        if (feld == null) {
            return null;
        }
        if (ZUSATZFELD.matcher(feld).matches()) {
            return "frei";
        }
        return GRUPPE_DES_FELDES.get(feld);
    }

    private static boolean text(String wert, int max) {
        if (wert == null) {
            return false;
        }
        String bereinigt = wert.strip();
        return !bereinigt.isEmpty() && bereinigt.length() <= max && bereinigt.chars().noneMatch(Character::isISOControl);
    }

    private static FachFehler ungueltig() {
        return new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
    }
}
