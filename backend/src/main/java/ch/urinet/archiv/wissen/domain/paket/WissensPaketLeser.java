package ch.urinet.archiv.wissen.domain.paket;

import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;
import org.snakeyaml.engine.v2.api.lowlevel.Parse;
import org.snakeyaml.engine.v2.events.*;
import org.snakeyaml.engine.v2.schema.CoreSchema;
import org.snakeyaml.engine.v2.schema.FailsafeSchema;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static ch.urinet.archiv.wissen.domain.paket.WissensPaketException.Code;
import static ch.urinet.archiv.wissen.domain.paket.WissensPaket.HinweisCode;

public final class WissensPaketLeser {
    private final PaketGrenzen grenzen;

    public WissensPaketLeser() { this(PaketGrenzen.standard()); }
    public WissensPaketLeser(PaketGrenzen grenzen) { this.grenzen = Objects.requireNonNull(grenzen); }

    public WissensPaket lesen(byte[] zip) {
        return lesen(zip, System.nanoTime() + grenzen.zeit().toNanos());
    }

    /** deadlineNanos ist eine absolute Frist auf der System.nanoTime-Zeitachse. */
    public WissensPaket lesen(byte[] zip, long deadlineNanos) {
        Objects.requireNonNull(zip);
        long jetzt = System.nanoTime();
        long uebrig = deadlineNanos - jetzt;
        if (uebrig <= 0) throw fehler(Code.ZEIT, "");
        deadlineNanos = jetzt + Math.min(uebrig, grenzen.zeit().toNanos());
        if (zip.length > grenzen.zipBytes()) throw fehler(Code.DATEI_ZU_GROSS, "");
        if (zip.length < 4 || u32(zip, 0) != 0x04034b50L && u32(zip, 0) != 0x06054b50L)
            throw fehler(Code.DATEITYP_UNBEKANNT, "");
        try {
            List<Metadaten> meta = metadaten(zip, deadlineNanos);
            List<WissensPaket.Hinweis> hinweise = new ArrayList<>();
            List<PaketSeite> seiten = new ArrayList<>();
            PaketKopf kopf = null;
            int gesamt = 0;
            try (ZipInputStream strom = new ZipInputStream(new ByteArrayInputStream(zip), StandardCharsets.UTF_8)) {
                for (Metadaten m : meta) {
                    zeit(deadlineNanos);
                    ZipEntry e = strom.getNextEntry();
                    if (e == null || !e.getName().equals(m.name())) throw fehler(Code.ZIP_UNGUELTIG, "");
                    ByteArrayOutputStream inhalt = new ByteArrayOutputStream();
                    byte[] puffer = new byte[8192];
                    int n;
                    while ((n = strom.read(puffer)) != -1) {
                        zeit(deadlineNanos);
                        gesamt += n;
                        if (gesamt > grenzen.entpacktBytes() || inhalt.size() + n > grenzen.dateiBytes())
                            throw fehler(Code.GRENZE, m.pfad());
                        inhalt.write(puffer, 0, n);
                    }
                    strom.closeEntry();
                    if (inhalt.size() != m.groesse() || e.getCrc() != m.crc()
                            || e.getCompressedSize() != m.komprimiert())
                        throw fehler(Code.ZIP_UNGUELTIG, m.pfad());
                    if ((long) inhalt.size() > m.komprimiert() * grenzen.verhaeltnis())
                        throw fehler(Code.ZIP_BOMBE, m.pfad());
                    if (m.verzeichnis()) {
                        if (inhalt.size() != 0) throw fehler(Code.ZIP_UNGUELTIG, m.pfad());
                    } else if (m.pfad().equals("paket.yaml")) {
                        kopf = kopf(yaml(text(inhalt.toByteArray(), m.pfad()), m.pfad(), deadlineNanos), hinweise);
                    } else if (m.pfad().equals("index.md") || m.pfad().equals("log.md")) {
                        hinweise.add(new WissensPaket.Hinweis(HinweisCode.DATEI_IGNORIERT, m.pfad()));
                    } else {
                        if (seiten.size() >= grenzen.seiten()) throw fehler(Code.GRENZE, m.pfad());
                        seiten.add(seite(m.pfad(), inhalt.toByteArray(), hinweise, deadlineNanos));
                    }
                }
                if (strom.getNextEntry() != null) throw fehler(Code.ZIP_UNGUELTIG, "");
            }
            if (kopf == null) throw fehler(Code.FORMAT, "paket.yaml");
            zeit(deadlineNanos);
            return new WissensPaket(kopf, seiten, hinweise);
        } catch (WissensPaketException ex) {
            throw ex;
        } catch (IOException | RuntimeException ex) {
            throw fehler(Code.ZIP_UNGUELTIG, "");
        }
    }

    private record Metadaten(String name, String pfad, boolean verzeichnis, long groesse, long komprimiert,
                              long crc, int offset, int flags, int methode) {}

    private List<Metadaten> metadaten(byte[] b, long deadline) {
        int ende = -1;
        for (int i = b.length - 22; i >= Math.max(0, b.length - 65557); i--) {
            if (u32(b, i) == 0x06054b50L && i + 22 + u16(b, i + 20) == b.length) { ende = i; break; }
        }
        if (ende < 0 || u16(b, ende + 4) != 0 || u16(b, ende + 6) != 0
                || u16(b, ende + 8) != u16(b, ende + 10)) throw fehler(Code.ZIP_UNGUELTIG, "");
        int anzahl = u16(b, ende + 10);
        if (anzahl > grenzen.eintraege()) throw fehler(Code.GRENZE, "");
        long start = u32(b, ende + 16), size = u32(b, ende + 12);
        if (start + size != ende) throw fehler(Code.ZIP_UNGUELTIG, "");
        int pos = (int) start;
        List<Metadaten> alle = new ArrayList<>();
        Set<String> namen = new HashSet<>();
        for (int i = 0; i < anzahl; i++) {
            zeit(deadline);
            if (pos + 46 > ende || u32(b, pos) != 0x02014b50L) throw fehler(Code.ZIP_UNGUELTIG, "");
            int flags = u16(b, pos + 8), methode = u16(b, pos + 10);
            int n = u16(b, pos + 28), extra = u16(b, pos + 30), comment = u16(b, pos + 32);
            if ((long) pos + 46 + n + extra + comment > ende || u16(b, pos + 34) != 0)
                throw fehler(Code.ZIP_UNGUELTIG, "");
            if ((flags & (1 | 64 | 8192)) != 0) throw fehler(Code.VERSCHLUESSELT, "");
            if (methode != 0 && methode != 8) throw fehler(Code.ZIP_UNGUELTIG, "");
            String name = text(Arrays.copyOfRange(b, pos + 46, pos + 46 + n), "");
            boolean dir = name.endsWith("/");
            String pfad = Normalizer.normalize(dir ? name.substring(0, name.length() - 1) : name,
                    Normalizer.Form.NFC);
            if (!namen.add(pfad)) throw fehler(Code.DUPLIKAT, "");
            int system = (int) (u16(b, pos + 4) >>> 8);
            long attr = u32(b, pos + 38);
            int typ = (int) ((attr >>> 16) & 0170000);
            if (typ == 0120000 || system == 3 && typ != 0 && typ != 0100000 && typ != 0040000)
                throw fehler(Code.SYMLINK, "");
            if (system == 3 && typ != 0 && (typ == 0040000) != dir) throw fehler(Code.ZIP_UNGUELTIG, "");
            long packed = u32(b, pos + 20), unpacked = u32(b, pos + 24), offset = u32(b, pos + 42);
            if (packed == 0xffffffffL || unpacked == 0xffffffffL || offset >= start)
                throw fehler(Code.ZIP_UNGUELTIG, "");
            alle.add(new Metadaten(name, pfad, dir, unpacked, packed, u32(b, pos + 16), (int) offset, flags, methode));
            pos += 46 + n + extra + comment;
        }
        if (pos != ende) throw fehler(Code.ZIP_UNGUELTIG, "");
        for (Metadaten m : alle) pfad(m.name(), m.verzeichnis());
        alle.sort(Comparator.comparingInt(Metadaten::offset));
        long next = 0;
        for (Metadaten m : alle) {
            int p = m.offset();
            if (p != next || p + 30L > start || u32(b, p) != 0x04034b50L
                    || u16(b, p + 6) != m.flags() || u16(b, p + 8) != m.methode())
                throw fehler(Code.ZIP_UNGUELTIG, "");
            int n = u16(b, p + 26), extra = u16(b, p + 28);
            long daten = (long) p + 30 + n + extra;
            if (daten + m.komprimiert() > start || !text(Arrays.copyOfRange(b, p + 30, p + 30 + n), "").equals(m.name()))
                throw fehler(Code.ZIP_UNGUELTIG, "");
            next = daten + m.komprimiert();
            if ((m.flags() & 8) != 0) {
                int d = (int) next;
                if (d + 12L > start) throw fehler(Code.ZIP_UNGUELTIG, "");
                if (u32(b, d) == 0x08074b50L) d += 4;
                if (d + 12L > start || u32(b, d) != m.crc() || u32(b, d + 4) != m.komprimiert()
                        || u32(b, d + 8) != m.groesse()) throw fehler(Code.ZIP_UNGUELTIG, "");
                next = d + 12L;
            } else if (u32(b, p + 14) != m.crc() || u32(b, p + 18) != m.komprimiert()
                    || u32(b, p + 22) != m.groesse()) throw fehler(Code.ZIP_UNGUELTIG, "");
        }
        if (next != start) throw fehler(Code.ZIP_UNGUELTIG, "");
        return alle;
    }

    private String pfad(String name, boolean dir) {
        String s = Normalizer.normalize(name, Normalizer.Form.NFC);
        if (dir) s = s.substring(0, s.length() - 1);
        if (s.isEmpty() || s.split("/", -1).length > grenzen.pfadEbenen()
                || !(dir ? s.matches("[a-z0-9-]+(?:/[a-z0-9-]+)*")
                        : s.equals("paket.yaml") || s.matches("[a-z0-9-]+(?:/[a-z0-9-]+)*\\.md")))
            throw fehler(Code.PFAD, "");
        if (!s.equals("paket.yaml")) {
            String ohneEndung = dir ? s : s.substring(0, s.length() - 3);
            for (String segment : ohneEndung.split("/"))
                if (!segment.matches("[a-z0-9]+(?:-[a-z0-9]+)*")) throw fehler(Code.PFAD, "");
        }
        if (!dir && !s.equals("paket.yaml") && !s.equals("index.md") && !s.equals("log.md")
                && !gueltigerSeitenverweis("/" + s)) throw fehler(Code.PFAD, "");
        return s;
    }

    private static int u16(byte[] b, int p) { return (b[p] & 255) | ((b[p + 1] & 255) << 8); }
    private static long u32(byte[] b, int p) { return (u16(b, p) | ((long) u16(b, p + 2) << 16)); }
    private static WissensPaketException fehler(Code code, String pfad) { return new WissensPaketException(code, pfad); }
    private static void zeit(long deadline) {
        if (System.nanoTime() - deadline >= 0 || Thread.currentThread().isInterrupted()) throw fehler(Code.ZEIT, "");
    }
    private static String text(byte[] bytes, String pfad) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException ex) { throw fehler(Code.FORMAT, pfad); }
    }

    private Map<String, Object> yaml(String text, String pfad, long deadline) {
        LoadSettings settings = LoadSettings.builder().setSchema(new CoreSchema())
                .setAllowDuplicateKeys(false).setAllowNonScalarKeys(false)
                .setCodePointLimit(grenzen.dateiBytes()).build();
        try {
            int tiefe = 0, dokumente = 0;
            for (Event event : new Parse(settings).parseString(text)) {
                zeit(deadline);
                if (event instanceof DocumentStartEvent && ++dokumente > 1) throw fehler(Code.YAML, pfad);
                if (event instanceof AliasEvent || event instanceof NodeEvent node && node.getAnchor().isPresent()
                        || event instanceof ScalarEvent scalar && (scalar.getTag().isPresent()
                        || scalar.isPlain() && scalar.getValue().equals("<<"))
                        || event instanceof CollectionStartEvent collection && collection.getTag().isPresent())
                    throw fehler(Code.YAML, pfad);
                if (event instanceof CollectionStartEvent && ++tiefe > grenzen.yamlTiefe())
                    throw fehler(Code.YAML, pfad);
                if (event instanceof CollectionEndEvent) tiefe--;
            }
            if (dokumente != 1) throw fehler(Code.YAML, pfad);
            Map<String, Object> daten = mapping(new Load(settings).loadFromString(text), pfad);
            LoadSettings rohSettings = LoadSettings.builder().setSchema(new FailsafeSchema())
                    .setAllowDuplicateKeys(false).setAllowNonScalarKeys(false)
                    .setCodePointLimit(grenzen.dateiBytes()).build();
            Map<String, Object> roh = mapping(new Load(rohSettings).loadFromString(text), pfad);
            if (daten.get("sources") instanceof List<?> quellen && roh.get("sources") instanceof List<?> rohQuellen
                    && quellen.size() == rohQuellen.size()) {
                List<Object> angepasst = new ArrayList<>(quellen);
                for (int i = 0; i < quellen.size(); i++) {
                    if (quellen.get(i) instanceof Map<?, ?> && rohQuellen.get(i) instanceof Map<?, ?>) {
                        Map<String, Object> quelle = mapping(quellen.get(i), pfad);
                        Map<String, Object> rohQuelle = mapping(rohQuellen.get(i), pfad);
                        for (String feld : List.of("sha256", "seite")) {
                            if (quelle.get(feld) instanceof Number && rohQuelle.get(feld) instanceof String)
                                quelle.put(feld, rohQuelle.get(feld));
                        }
                        angepasst.set(i, quelle);
                    }
                }
                daten.put("sources", angepasst);
            }
            zeit(deadline);
            return daten;
        } catch (WissensPaketException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw fehler(Code.YAML, pfad);
        }
    }

    private PaketKopf kopf(Map<String, Object> map, List<WissensPaket.Hinweis> hinweise) {
        String p = "paket.yaml";
        unbekannt(map, Set.of("paket_format", "mandant", "erzeugt_von", "erzeugt_mit", "kontext_stufe",
                "erzeugt_am", "herkunft"), Set.of(), p, hinweise);
        Long format = ganzzahl(map.get("paket_format"), p);
        if (format == null || format != 1) throw fehler(Code.FORMAT, p);
        String mandant = string(map.get("mandant"), p, true);
        if (mandant.length() > 40 || !mandant.matches("[a-z0-9]+(?:-[a-z0-9]+)*")
                || mandant.startsWith("a-") || mandant.startsWith("p-")) throw fehler(Code.FORMAT, p);
        String von = erzeuger(map.get("erzeugt_von"), p);
        String mit = string(map.get("erzeugt_mit"), p, true);
        String stufe = string(map.get("kontext_stufe"), p, true);
        if (!Set.of("lokal", "cloud").contains(mit) || !Set.of("NORMAL", "INTERN").contains(stufe))
            throw fehler(Code.FORMAT, p);
        if (mit.equals("cloud") && stufe.equals("INTERN")) throw fehler(Code.CLOUD_STUFE, p);
        Instant am;
        try { am = OffsetDateTime.parse(string(map.get("erzeugt_am"), p, true)).toInstant(); }
        catch (RuntimeException ex) { throw fehler(Code.FORMAT, p); }
        String instanz = null;
        if (map.containsKey("herkunft")) {
            Map<String, Object> herkunft = mapping(map.get("herkunft"), p);
            unbekannt(herkunft, Set.of("instanz"), Set.of(), p, hinweise);
            instanz = string(herkunft.get("instanz"), p, true);
            if (instanz.isBlank()) throw fehler(Code.FORMAT, p);
        }
        return new PaketKopf(1, mandant, von, mit, stufe, am, instanz);
    }

    private PaketSeite seite(String name, byte[] bytes, List<WissensPaket.Hinweis> hinweise, long deadline) {
        String text = text(bytes, name);
        if (!text.startsWith("---\n") && !text.startsWith("---\r\n")) throw fehler(Code.FORMAT, name);
        int beginn = text.indexOf('\n') + 1, ende = -1, nach = -1;
        for (int p = beginn; p <= text.length();) {
            int lf = text.indexOf('\n', p);
            int bis = lf < 0 ? text.length() : lf;
            String zeile = text.substring(p, bis);
            if (zeile.equals("---") || zeile.equals("---\r")) {
                ende = p;
                nach = lf < 0 ? bis : lf + 1;
                break;
            }
            if (lf < 0) break;
            p = lf + 1;
        }
        if (ende < 0 || text.substring(beginn, ende).getBytes(StandardCharsets.UTF_8).length > grenzen.frontmatterBytes())
            throw fehler(Code.FORMAT, name);
        Map<String, Object> map = yaml(text.substring(beginn, ende), name, deadline);
        unbekannt(map, Set.of("type", "title", "description", "tags", "stale_after", "basis_version",
                "erzeugt_von", "sources"), Set.of("status", "generated", "verified"), name, hinweise);
        String typ = string(map.get("type"), name, true);
        if (!Set.of("Synthese", "Quelle", "Konzept", "Akteur", "Projekt", "Analyse").contains(typ))
            throw fehler(Code.FORMAT, name);
        String titel = string(map.get("title"), name, true);
        String beschreibung = string(map.get("description"), name, true);
        List<String> tags = new ArrayList<>();
        for (Object tag : liste(map.get("tags"), name)) tags.add(string(tag, name, true));
        if (!map.containsKey("stale_after")) throw fehler(Code.FORMAT, name);
        LocalDate stale = null;
        if (map.get("stale_after") != null) {
            try { stale = LocalDate.parse(string(map.get("stale_after"), name, true)); }
            catch (RuntimeException ex) { throw fehler(Code.FORMAT, name); }
        }
        Long version = ganzzahl(map.get("basis_version"), name);
        String von = map.containsKey("erzeugt_von") ? erzeuger(map.get("erzeugt_von"), name) : null;
        List<PaketQuelle> quellen = new ArrayList<>();
        for (Object objekt : liste(map.get("sources"), name)) {
            Map<String, Object> quelle = mapping(objekt, name);
            unbekannt(quelle, Set.of("id", "resource", "sha256", "seite", "auszug", "wissensquelle",
                    "title", "last_modified"), Set.of(), name, hinweise);
            Object seite = quelle.get("seite");
            String seitenangabe = seite instanceof Number ? seite.toString() : string(seite, name, false);
            Object wissen = quelle.get("wissensquelle");
            if (wissen != null && !(wissen instanceof Boolean)) throw fehler(Code.FORMAT, name);
            String resource = string(quelle.get("resource"), name, false);
            String hash = string(quelle.get("sha256"), name, false);
            if (hash != null) {
                if (!hash.matches("[0-9a-fA-F]{64}") || resource != null
                        && !resource.startsWith("urn:urinet-archiv:")) throw fehler(Code.FORMAT, name);
                hash = hash.toLowerCase(Locale.ROOT);
                resource = null;
            } else if (!gueltigerSeitenverweis(resource) || seitenangabe != null
                    || quelle.get("auszug") != null || Boolean.TRUE.equals(wissen)) {
                throw fehler(Code.FORMAT, name);
            }
            if (seitenangabe != null && !gueltigeSeitenangabe(seitenangabe))
                throw fehler(Code.FORMAT, name);
            quellen.add(new PaketQuelle(string(quelle.get("id"), name, true),
                    resource, hash,
                    seitenangabe, string(quelle.get("auszug"), name, false), Boolean.TRUE.equals(wissen)));
        }
        return new PaketSeite(name.substring(0, name.length() - 3), typ, titel, beschreibung,
                tags, stale, version, von, quellen, text.substring(nach));
    }

    private static void unbekannt(Map<String, Object> map, Set<String> bekannt, Set<String> ignoriert,
                                  String pfad, List<WissensPaket.Hinweis> hinweise) {
        for (String key : map.keySet()) {
            if (ignoriert.contains(key)) hinweise.add(new WissensPaket.Hinweis(HinweisCode.FELD_IGNORIERT, pfad));
            else if (!bekannt.contains(key)) hinweise.add(new WissensPaket.Hinweis(HinweisCode.FELD_UNBEKANNT, pfad));
        }
    }

    private static Map<String, Object> mapping(Object objekt, String pfad) {
        if (!(objekt instanceof Map<?, ?> map)) throw fehler(Code.FORMAT, pfad);
        Map<String, Object> ergebnis = new LinkedHashMap<>();
        for (var entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key)) throw fehler(Code.FORMAT, pfad);
            ergebnis.put(key, entry.getValue());
        }
        return ergebnis;
    }
    private static List<?> liste(Object objekt, String pfad) {
        if (!(objekt instanceof List<?> list)) throw fehler(Code.FORMAT, pfad);
        return list;
    }
    private static String string(Object objekt, String pfad, boolean pflicht) {
        if (objekt == null && !pflicht) return null;
        if (!(objekt instanceof String text)) throw fehler(Code.FORMAT, pfad);
        return text;
    }
    private static String erzeuger(Object objekt, String pfad) {
        String wert = string(objekt, pfad, true);
        if (wert.isBlank() || !wert.matches("[A-Za-z0-9._/:+ -]{1,100}")) throw fehler(Code.FORMAT, pfad);
        return wert;
    }
    private static Long ganzzahl(Object objekt, String pfad) {
        if (objekt == null) return null;
        if (objekt instanceof Integer n) return n.longValue();
        if (objekt instanceof Long n) return n;
        throw fehler(Code.FORMAT, pfad);
    }

    private static boolean gueltigerSeitenverweis(String resource) {
        if (resource == null || !resource.startsWith("/") || !resource.endsWith(".md")) return false;
        String pfad = resource.substring(1, resource.length() - 3);
        if (pfad.equals("ueberblick")) return true;
        String[] teile = pfad.split("/", -1);
        return teile.length == 2 && Set.of("quellen", "konzepte", "akteure", "projekte", "analysen")
                .contains(teile[0]) && teile[1].matches("[a-z0-9]+(?:-[a-z0-9]+)*")
                && !Set.of("index", "log").contains(teile[1]);
    }

    private static boolean gueltigeSeitenangabe(String seite) {
        if (!seite.matches("[0-9]+(?:-[0-9]+)?")) return false;
        String[] teile = seite.split("-");
        String von = teile[0].replaceFirst("^0+", "");
        if (von.isEmpty()) return false;
        if (teile.length == 1) return true;
        String bis = teile[1].replaceFirst("^0+", "");
        return !bis.isEmpty() && (bis.length() > von.length()
                || bis.length() == von.length() && bis.compareTo(von) >= 0);
    }
}
