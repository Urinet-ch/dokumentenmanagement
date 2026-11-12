package ch.urinet.archiv.protokoll.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Inhalt des Tages-Manifests (Req 15.1, 15.2; design.md «Manifest»). {@link #kanonisch()} liefert die signierte Form
 * nach RFC 8785; {@link #aus(Map)} liest sie zurück (Prüfwerkzeug). {@code sicherung} bleibt leer, bis Aufgabe 5.5 die
 * Exporte von {@code archiv-sicherung} anbindet.
 */
public record ManifestInhalt(String mandant, LocalDate datum, Instant von, Instant bis, String vorherSha256,
                             Vorher vorher, List<ManifestEintrag> eintraege, SortedMap<String, ProtokollHash> protokolle,
                             List<String> sicherung) {

    public ManifestInhalt {
        Objects.requireNonNull(mandant, "mandant");
        ManifestFenster fenster = new ManifestFenster(datum, von, bis);
        Sha256.gueltig(vorherSha256, "vorher_sha256");
        if (fenster.erstes() != (vorherSha256 == null) || fenster.erstes() != (vorher == null)) {
            throw new IllegalArgumentException("Nur das erste Manifest hat weder von noch vorher_sha256 noch vorher");
        }
        eintraege = eintraege.stream().sorted(ManifestEintrag.REIHENFOLGE).toList();
        protokolle = new TreeMap<>(protokolle);
        sicherung = List.copyOf(sicherung);
        sicherung.forEach(s -> Sha256.gueltig(s, "sicherung"));
    }

    public byte[] kanonisch() {
        return Jcs.utf8(alsBaum());
    }

    public Map<String, Object> alsBaum() {
        Map<String, Object> baum = new LinkedHashMap<>();
        baum.put("mandant", mandant);
        baum.put("datum", datum.toString());
        baum.put("von", von == null ? null : von.toString());
        baum.put("bis", bis.toString());
        baum.put("vorher_sha256", vorherSha256);
        baum.put("vorher", vorher == null ? null : vorherBaum(vorher));
        List<Object> liste = new ArrayList<>();
        for (ManifestEintrag e : eintraege) {
            Map<String, Object> eintrag = new LinkedHashMap<>();
            eintrag.put("dokument", e.dokument().toString());
            eintrag.put("variante", e.variante());
            eintrag.put("nr", e.nr());
            eintrag.put("sha256", e.sha256());
            eintrag.put("s3_version", e.s3Version());
            liste.add(eintrag);
        }
        baum.put("eintraege", liste);
        baum.put("anzahl", eintraege.size());
        Map<String, Object> prot = new LinkedHashMap<>();
        protokolle.forEach((tabelle, hash) -> prot.put(tabelle, Map.of("anzahl", hash.anzahl(), "sha256", hash.sha256())));
        baum.put("protokolle", prot);
        baum.put("sicherung", sicherung);
        return baum;
    }

    private static Map<String, Object> vorherBaum(Vorher vorher) {
        List<Object> stempel = new ArrayList<>();
        for (ZeitstempelVerweis z : vorher.zeitstempel()) {
            stempel.add(Map.of("dienst", z.dienst(), "tsr_sha256", z.tsrSha256(),
                    "pruefdaten_sha256", z.pruefdatenSha256()));
        }
        return Map.of("sig_sha256", vorher.sigSha256(), "zeitstempel", stempel);
    }

    /**
     * Liest ein Manifest aus seinem JSON-Baum; unbekannte Felder werden übergangen (Erweiterungen wie Spec 04,
     * Req 4.6), die Signatur deckt ohnehin die Bytes.
     *
     * @throws IllegalArgumentException wenn ein Pflichtfeld fehlt oder ungültig ist
     */
    public static ManifestInhalt aus(Map<String, Object> baum) {
        try {
            List<ManifestEintrag> eintraege = new ArrayList<>();
            for (Object o : liste(baum, "eintraege")) {
                Map<String, Object> e = objekt(o, "eintraege[]");
                eintraege.add(new ManifestEintrag(UUID.fromString(text(e, "dokument")), text(e, "variante"),
                        Math.toIntExact(ganz(e.get("nr"), "nr")), text(e, "sha256"), (String) e.get("s3_version")));
            }
            if (ganz(baum.get("anzahl"), "anzahl") != eintraege.size()) {
                throw new IllegalArgumentException("anzahl stimmt nicht mit eintraege überein");
            }
            SortedMap<String, ProtokollHash> protokolle = new TreeMap<>();
            objekt(baum.get("protokolle"), "protokolle").forEach((tabelle, wert) -> {
                Map<String, Object> p = objekt(wert, "protokolle." + tabelle);
                protokolle.put(tabelle, new ProtokollHash(ganz(p.get("anzahl"), "anzahl"), text(p, "sha256")));
            });
            Vorher vorher = null;
            if (baum.get("vorher") != null) {
                Map<String, Object> v = objekt(baum.get("vorher"), "vorher");
                List<ZeitstempelVerweis> stempel = new ArrayList<>();
                for (Object o : liste(v, "zeitstempel")) {
                    Map<String, Object> z = objekt(o, "zeitstempel[]");
                    stempel.add(new ZeitstempelVerweis(text(z, "dienst"), text(z, "tsr_sha256"),
                            liste(z, "pruefdaten_sha256").stream().map(s -> (String) s).toList()));
                }
                vorher = new Vorher(text(v, "sig_sha256"), stempel);
            }
            List<String> sicherung = baum.get("sicherung") == null ? List.of()
                    : liste(baum, "sicherung").stream().map(s -> (String) s).toList();
            return new ManifestInhalt(text(baum, "mandant"), LocalDate.parse(text(baum, "datum")),
                    baum.get("von") == null ? null : Instant.parse((String) baum.get("von")),
                    Instant.parse(text(baum, "bis")), (String) baum.get("vorher_sha256"), vorher, eintraege,
                    protokolle, sicherung);
        } catch (ClassCastException | DateTimeParseException | ArithmeticException e) {
            throw new IllegalArgumentException("Manifest ungültig: " + e.getClass().getSimpleName(), e);
        }
    }

    private static String text(Map<String, Object> m, String feld) {
        if (!(m.get(feld) instanceof String s)) {
            throw new IllegalArgumentException("Feld " + feld + " fehlt oder ist kein Text");
        }
        return s;
    }

    private static long ganz(Object wert, String feld) {
        if (!(wert instanceof Number n)) {
            throw new IllegalArgumentException("Feld " + feld + " fehlt oder ist keine Zahl");
        }
        return new java.math.BigDecimal(n.toString()).longValueExact();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> objekt(Object wert, String feld) {
        if (!(wert instanceof Map<?, ?> m)) {
            throw new IllegalArgumentException("Feld " + feld + " fehlt oder ist kein Objekt");
        }
        return (Map<String, Object>) m;
    }

    private static List<?> liste(Map<String, Object> m, String feld) {
        if (!(m.get(feld) instanceof List<?> l)) {
            throw new IllegalArgumentException("Feld " + feld + " fehlt oder ist keine Liste");
        }
        return l;
    }
}
