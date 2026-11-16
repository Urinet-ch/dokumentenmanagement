package ch.urinet.archiv.sicherung.adapter.in.kommando;

import ch.urinet.archiv.ablage.application.AblagePort;
import ch.urinet.archiv.ablage.domain.Kopie;
import ch.urinet.archiv.ablage.domain.Objektversion;
import ch.urinet.archiv.ablage.domain.SicherungsSperre;
import ch.urinet.archiv.ablage.domain.Versionsseite;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.sicherung.application.SicherungBucketExport;
import ch.urinet.archiv.sicherung.application.SicherungBucketExport.InventarZeile;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.StringReader;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** JSONL-Inventar, Delta-Abgleich und konkatenierbarer Bucket-Strom ohne Objektpuffer. */
class SicherungBucketExportTest {

    private static final MandantCode MANDANT = new MandantCode("probe");
    private static final String HEADER = SicherungBucketExport.BUCKET_FORMAT;
    private static final int OBJEKT = 0x55424a31;
    private static final int SHA256 = 0x53484131;

    @Test
    void inventarEnthaeltVersionenLoeschmarkenUndSperrenStromweiseUeberSeiten() throws Exception {
        List<Eintrag> eintraege = List.of(
                eintrag("archiv/a", "a2", false, "neu", "GOVERNANCE"),
                eintrag("archiv/a", "a1", false, "alt", null),
                eintrag("archiv/geloescht", "l1", true, "", null),
                eintrag("eingang/b", "b1", false, "eingang", null),
                eintrag("manifest/c.json", "c1", false, "manifest", "COMPLIANCE"),
                eintrag("vorschau/d.png", "d1", false, "vorschau", null),
                eintrag("importe/e.csv", "e1", false, "import", null));
        SicherungBucketExport export = new SicherungBucketExport(ablage(eintraege));
        List<InventarZeile> inventar = new ArrayList<>();

        export.inventar(MANDANT, inventar::add);

        assertThat(inventar).extracting(InventarZeile::schluessel).containsExactly(
                "archiv/a", "archiv/a", "archiv/geloescht", "eingang/b", "manifest/c.json");
        assertThat(inventar).filteredOn(InventarZeile::loeschmarke).singleElement()
                .satisfies(z -> assertThat(z.groesse()).isZero());
        assertThat(inventar).filteredOn(z -> z.versionId().equals("a2")).singleElement()
                .satisfies(z -> assertThat(z.sperrmodus()).isEqualTo("GOVERNANCE"));
        assertThat(inventar).filteredOn(z -> z.versionId().equals("c1")).singleElement()
                .satisfies(z -> assertThat(z.sperrmodus()).isEqualTo("COMPLIANCE"));
        assertThat(inventar).allSatisfy(z -> {
            assertThat(z.format()).isEqualTo(SicherungBucketExport.INVENTAR_FORMAT);
            assertThat(z.sha256()).isEmpty();
        });
    }

    @Test
    void vorherigesInventarFiltertNurNeueVersionenUndLehntUnsorierteZeilenAb() throws Exception {
        Eintrag alt = eintrag("archiv/a", "a1", false, "alt", null);
        Eintrag neu = eintrag("archiv/a", "a2", false, "neu", null);
        SicherungBucketExport export = new SicherungBucketExport(ablage(List.of(neu, alt)));
        InventarZeile vorherZeile = new InventarZeile(SicherungBucketExport.INVENTAR_FORMAT, "archiv/a", "a1", false,
                3, alt.version().lastModified().toString(), "", null, null);
        ObjectMapper json = new ObjectMapper();
        String vorher = json.writeValueAsString(vorherZeile) + "\n";
        ByteArrayOutputStream ausgabe = new ByteArrayOutputStream();

        export.zuwachs(MANDANT, new InventarAbgleich(new StringReader(vorher), json), ausgabe);

        assertThat(leseObjekte(ausgabe.toByteArray())).containsExactly("archiv/a@a2:" + neu.sha256());
        String doppelt = vorher + json.writeValueAsString(vorherZeile) + "\n";
        InventarAbgleich unsortiert = new InventarAbgleich(new StringReader(doppelt), json);
        assertThatThrownBy(() -> unsortiert.test(alt.version()))
                .isInstanceOf(InventarDateiFehler.class).hasMessageContaining("nicht streng sortiert");
    }

    @Test
    void zweiZuwachseKoennenImGleichenStromGelesenWerdenUndTragenDieSha256() throws Exception {
        byte[] daten = "synthetischer Export".getBytes(StandardCharsets.UTF_8);
        SicherungBucketExport export = new SicherungBucketExport(ablage(List.of(eintrag("archiv/a", "a1", false,
                new String(daten, StandardCharsets.UTF_8), null))));
        ByteArrayOutputStream ausgabe = new ByteArrayOutputStream();

        export.zuwachs(MANDANT, version -> true, ausgabe);
        export.zuwachs(MANDANT, version -> true, ausgabe);

        try (DataInputStream strom = new DataInputStream(new ByteArrayInputStream(ausgabe.toByteArray()))) {
            assertThat(new String(strom.readNBytes(HEADER.length()), StandardCharsets.US_ASCII)).isEqualTo(HEADER);
            assertThat(leseObjekt(strom)).isEqualTo("archiv/a@a1:" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(daten)));
            assertThat(new String(strom.readNBytes(HEADER.length()), StandardCharsets.US_ASCII)).isEqualTo(HEADER);
            assertThat(leseObjekt(strom)).isEqualTo("archiv/a@a1:" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(daten)));
            assertThat(strom.read()).isEqualTo(-1);
        }
    }

    @Test
    void inventarUndStromBewahrenUnicodeLeerzeichenUndZeilenumbruecheImSchluessel() throws Exception {
        String schluessel = "archiv/ü 2026/\n\"synthetisch.pdf";
        Eintrag eintrag = eintrag(schluessel, "u1", false, "synthetischer Inhalt", null);
        SicherungBucketExport export = new SicherungBucketExport(ablage(List.of(eintrag)));
        List<InventarZeile> inventar = new ArrayList<>();

        export.inventar(MANDANT, inventar::add);

        assertThat(inventar).singleElement().satisfies(zeile -> {
            assertThat(zeile.schluessel()).isEqualTo(schluessel);
            String json = new ObjectMapper().valueToTree(zeile).toString();
            assertThat(json).contains("\\n").doesNotContain("\n");
        });
        var abgleich = new InventarAbgleich(new StringReader(new ObjectMapper().writeValueAsString(inventar.getFirst())
                + "\n"), new ObjectMapper());
        assertThat(abgleich.test(eintrag.version())).isFalse();
        abgleich.vollstaendigPruefen();

        ByteArrayOutputStream ausgabe = new ByteArrayOutputStream();
        export.zuwachs(MANDANT, version -> true, ausgabe);

        assertThat(leseObjekte(ausgabe.toByteArray()))
                .containsExactly(schluessel + "@u1:" + eintrag.sha256());
    }

    @Test
    void exportiertEinFuenfzigMiBObjektOhneEsImSpeicherZuPuffern() throws Exception {
        long groesse = 50L * 1024 * 1024;
        Eintrag eintrag = nullBytes("archiv/gross", "v1", groesse);
        SicherungBucketExport export = new SicherungBucketExport(ablage(List.of(eintrag)));
        ZaehlerAusgabe ausgabe = new ZaehlerAusgabe();

        export.zuwachs(MANDANT, version -> true, ausgabe);

        long rahmen = HEADER.length() + Integer.BYTES * 3L + "archiv/gross".getBytes(StandardCharsets.UTF_8).length
                + "v1".length() + 2L * Long.BYTES + Integer.BYTES + 32;
        assertThat(ausgabe.geschrieben()).isEqualTo(rahmen + groesse);
    }

    private static String leseObjekt(DataInputStream strom) throws Exception {
        assertThat(strom.readInt()).isEqualTo(OBJEKT);
        String schluessel = text(strom);
        String version = text(strom);
        int groesse = Math.toIntExact(strom.readLong());
        strom.readLong();
        byte[] daten = strom.readNBytes(groesse);
        assertThat(daten).hasSize(groesse);
        assertThat(strom.readInt()).isEqualTo(SHA256);
        byte[] sha = strom.readNBytes(32);
        assertThat(sha).hasSize(32);
        assertThat(sha).containsExactly(MessageDigest.getInstance("SHA-256").digest(daten));
        return schluessel + "@" + version + ":" + HexFormat.of().formatHex(sha);
    }

    private static String text(DataInputStream strom) throws IOException {
        return new String(strom.readNBytes(strom.readInt()), StandardCharsets.UTF_8);
    }

    private static List<String> leseObjekte(byte[] daten) throws Exception {
        List<String> objekte = new ArrayList<>();
        try (DataInputStream strom = new DataInputStream(new ByteArrayInputStream(daten))) {
            assertThat(new String(strom.readNBytes(HEADER.length()), StandardCharsets.US_ASCII)).isEqualTo(HEADER);
            while (strom.available() > 0) {
                objekte.add(leseObjekt(strom));
            }
        }
        return objekte;
    }

    private static Eintrag eintrag(String schluessel, String version, boolean loeschmarke, String text, String modus)
            throws Exception {
        byte[] daten = text.getBytes(StandardCharsets.UTF_8);
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(daten));
        return new Eintrag(new Objektversion(schluessel, version, loeschmarke,
                Instant.parse(version.endsWith("2") ? "2026-02-02T00:00:00Z" : "2026-02-01T00:00:00Z"),
                daten.length), modus == null ? Optional.empty()
                : Optional.of(new SicherungsSperre(modus, Instant.parse("2027-01-01T00:00:00Z"))),
                daten.length, sha, out -> out.write(daten));
    }

    private static Eintrag nullBytes(String schluessel, String version, long groesse) throws Exception {
        byte[] block = new byte[32 * 1024];
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long rest = groesse;
        while (rest > 0) {
            int laenge = (int) Math.min(rest, block.length);
            digest.update(block, 0, laenge);
            rest -= laenge;
        }
        String sha = HexFormat.of().formatHex(digest.digest());
        return new Eintrag(new Objektversion(schluessel, version, false, Instant.parse("2026-02-01T00:00:00Z"),
                groesse), Optional.empty(), groesse, sha, out -> {
                    long uebrig = groesse;
                    while (uebrig > 0) {
                        int laenge = (int) Math.min(uebrig, block.length);
                        out.write(block, 0, laenge);
                        uebrig -= laenge;
                    }
                });
    }

    private static AblagePort ablage(List<Eintrag> eintraege) {
        return (AblagePort) Proxy.newProxyInstance(AblagePort.class.getClassLoader(),
                new Class<?>[]{AblagePort.class}, (proxy, methode, argumente) -> switch (methode.getName()) {
                    case "versionen" -> seite(eintraege, (String) argumente[1],
                            (Optional<Versionsseite.Marke>) argumente[2]);
                    case "sicherungsSperre" -> finde(eintraege, (Objektversion) argumente[1]).sperre();
                    case "sicherungsKopie" -> {
                        Eintrag eintrag = finde(eintraege, (String) argumente[1], (String) argumente[2]);
                        eintrag.schreiben().auf((OutputStream) argumente[3]);
                        yield new Kopie(eintrag.sha256(), eintrag.groesse());
                    }
                    case "toString" -> "AblageTest";
                    default -> throw new UnsupportedOperationException(methode.getName());
                });
    }

    private static Versionsseite seite(List<Eintrag> eintraege, String praefix, Optional<Versionsseite.Marke> ab) {
        List<Objektversion> alle = eintraege.stream().map(Eintrag::version)
                .filter(v -> v.schluessel().startsWith(praefix))
                .sorted(Comparator.comparing(Objektversion::schluessel)
                        .thenComparing(Objektversion::lastModified, Comparator.reverseOrder())
                        .thenComparing(Objektversion::versionId)).toList();
        int start = ab.map(m -> alle.indexOf(alle.stream().filter(v -> v.schluessel().equals(m.schluessel())
                && v.versionId().equals(m.versionId().orElseThrow())).findFirst().orElseThrow()) + 1).orElse(0);
        int ende = Math.min(start + 2, alle.size());
        Optional<Versionsseite.Marke> weiter = ende < alle.size()
                ? Optional.of(new Versionsseite.Marke(alle.get(ende - 1).schluessel(), alle.get(ende - 1).versionId()))
                : Optional.empty();
        return new Versionsseite(alle.subList(start, ende), weiter);
    }

    private static Eintrag finde(List<Eintrag> eintraege, Objektversion version) {
        return finde(eintraege, version.schluessel(), version.versionId());
    }

    private static Eintrag finde(List<Eintrag> eintraege, String schluessel, String version) {
        return eintraege.stream().filter(e -> e.version().schluessel().equals(schluessel)
                && e.version().versionId().equals(version)).findFirst().orElseThrow();
    }

    private record Eintrag(Objektversion version, Optional<SicherungsSperre> sperre, long groesse, String sha256,
                           Schreibe schreiben) {
    }

    @FunctionalInterface
    private interface Schreibe {
        void auf(OutputStream ziel) throws IOException;
    }

    private static final class ZaehlerAusgabe extends OutputStream {
        private long geschrieben;

        @Override
        public void write(int b) {
            geschrieben++;
        }

        @Override
        public void write(byte[] b, int off, int len) {
            geschrieben += len;
        }

        long geschrieben() {
            return geschrieben;
        }
    }
}
