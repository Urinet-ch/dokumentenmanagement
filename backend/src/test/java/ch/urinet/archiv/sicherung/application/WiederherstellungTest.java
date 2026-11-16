package ch.urinet.archiv.sicherung.application;

import ch.urinet.archiv.ablage.application.AblagePort;
import ch.urinet.archiv.ablage.domain.Kopie;
import ch.urinet.archiv.ablage.domain.Objektversion;
import ch.urinet.archiv.ablage.domain.Pruefsumme;
import ch.urinet.archiv.ablage.domain.SicherungsSperre;
import ch.urinet.archiv.ablage.domain.Sperrmodus;
import ch.urinet.archiv.ablage.domain.Versionsseite;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.sicherung.application.SicherungBucketExport.InventarZeile;
import ch.urinet.archiv.sicherung.application.WiederherstellungsZiel.Quittung;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class WiederherstellungTest {

    private static final MandantCode MANDANT = new MandantCode("probe");
    private static final Instant JETZT = Instant.parse("2026-11-15T12:00:00Z");
    private static final Clock UHR = Clock.fixed(JETZT, ZoneOffset.UTC);

    @Test
    void rundreiseAusVollexportUndZweiZuwaechsenStelltVersionenAufsteigendWiederHer(@TempDir Path temp)
            throws Exception {
        List<Eintrag> ablageInhalt = new ArrayList<>();
        SicherungBucketExport export = new SicherungBucketExport(ablage(ablageInhalt));
        ByteArrayOutputStream ausgabe = new ByteArrayOutputStream();
        ablageInhalt.add(eintrag("archiv/dokument/a", "v1", "eins", "GOVERNANCE",
                Instant.parse("2036-11-15T12:00:00Z"), "2026-01-01T00:00:00Z"));
        export.zuwachs(MANDANT, version -> true, ausgabe);
        ablageInhalt.add(eintrag("archiv/dokument/a", "v2", "zwei", null, null, "2026-01-01T00:00:00Z"));
        export.zuwachs(MANDANT, version -> version.versionId().equals("v2"), ausgabe);
        ablageInhalt.add(eintrag("archiv/dokument/a", "v3", "drei", "COMPLIANCE",
                Instant.parse("2027-11-15T12:00:00Z"), "2026-03-01T00:00:00Z"));
        export.zuwachs(MANDANT, version -> version.versionId().equals("v3"), ausgabe);
        ausgabe.writeBytes(frame("archiv/dokument/a", "v1", "eins".getBytes(StandardCharsets.UTF_8),
                Instant.parse("2026-01-01T00:00:00Z")));
        List<InventarZeile> inventar = new ArrayList<>();
        export.inventar(MANDANT, inventar::add);
        Path datei = temp.resolve("strom.bin");
        Files.write(datei, ausgabe.toByteArray());
        InMemoryZiel ziel = new InMemoryZiel("urinet-archiv-p-", true);

        Wiederherstellung.Ergebnis ergebnis = new Wiederherstellung(ziel, UHR)
                .wiederherstellen(MANDANT, inventar, datei, false);

        assertThat(ergebnis.exitCode()).isZero();
        assertThat(ergebnis.zaehler()).isEqualTo(new Wiederherstellung.Zaehler(3, 1, 0, 0, 0, 0));
        assertThat(ziel.reihenfolge).containsExactly("v1", "v2", "v3");
        assertThat(ziel.inhalt.get("v1")).containsExactly("eins".getBytes(StandardCharsets.UTF_8));
        assertThat(ziel.inhalt.get("v2")).containsExactly("zwei".getBytes(StandardCharsets.UTF_8));
        assertThat(ziel.inhalt.get("v3")).containsExactly("drei".getBytes(StandardCharsets.UTF_8));
        assertThat(ziel.sperren.get("v1")).isEqualTo(new Sperrung(Sperrmodus.GOVERNANCE,
                Instant.parse("2036-11-15T12:00:00Z")));
        assertThat(ziel.sperren.get("v3")).isEqualTo(new Sperrung(Sperrmodus.COMPLIANCE,
                Instant.parse("2027-11-15T12:00:00Z")));
        assertThat(ziel.sperren).doesNotContainKey("v2");
    }

    @Test
    void unvollstaendigeOderUeberzaehligeKetteSchreibtNichts(@TempDir Path temp) throws Exception {
        InventarZeile vorhanden = zeile("archiv/a", "v1", false, 4, "2026-01-01T00:00:00Z", null, null);
        InventarZeile fehlt = zeile("archiv/b", "v2", false, 2, "2026-02-01T00:00:00Z", null, null);
        Path nurVorhanden = temp.resolve("vorhanden.bin");
        Files.write(nurVorhanden, frame("archiv/a", "v1", "eins".getBytes(StandardCharsets.UTF_8),
                Instant.parse("2026-01-01T00:00:00Z")));
        InMemoryZiel zielFehlt = new InMemoryZiel("urinet-archiv-p-", true);

        Wiederherstellung.Ergebnis fehlend = new Wiederherstellung(zielFehlt, UHR)
                .wiederherstellen(MANDANT, List.of(vorhanden, fehlt), nurVorhanden, false);

        assertThat(fehlend.exitCode()).isEqualTo(4);
        assertThat(fehlend.zaehler().fehlendInKette()).isEqualTo(1);
        assertThat(zielFehlt.reihenfolge).isEmpty();

        Path mitExtra = temp.resolve("extra.bin");
        Files.write(mitExtra, frame("archiv/c", "v3", "extra".getBytes(StandardCharsets.UTF_8),
                Instant.parse("2026-03-01T00:00:00Z")));
        InMemoryZiel zielExtra = new InMemoryZiel("urinet-archiv-p-", true);
        Wiederherstellung.Ergebnis extra = new Wiederherstellung(zielExtra, UHR)
                .wiederherstellen(MANDANT, List.of(), mitExtra, false);

        assertThat(extra.exitCode()).isEqualTo(4);
        assertThat(extra.zaehler().ueberzaehlig()).isEqualTo(1);
        assertThat(zielExtra.reihenfolge).isEmpty();
    }

    @Test
    void abweichendePruefsummeVerwirftUngesperrteVersionUndSperrtNie(@TempDir Path temp) throws Exception {
        Path datei = temp.resolve("hash.bin");
        byte[] daten = frame("archiv/a", "v1", "inhalt".getBytes(StandardCharsets.UTF_8), JETZT);
        daten[daten.length - 1] ^= 0xff;
        Files.write(datei, daten);
        InMemoryZiel ziel = new InMemoryZiel("urinet-archiv-p-", true);

        Wiederherstellung.Ergebnis ergebnis = new Wiederherstellung(ziel, UHR)
                .wiederherstellen(MANDANT, List.of(zeile("archiv/a", "v1", false, 6, JETZT.toString(), null, null)),
                        datei, false);

        assertThat(ergebnis.exitCode()).isEqualTo(3);
        assertThat(ergebnis.zaehler().abweichend()).isEqualTo(1);
        assertThat(ziel.verworfen).isEqualTo(1);
        assertThat(ziel.sperren).isEmpty();
    }

    @Test
    void fehlerBeimSperrenVerwirftDieUngesperrteVersionUndMeldetDenZwischenstand(@TempDir Path temp)
            throws Exception {
        Path datei = temp.resolve("sperre.bin");
        Files.write(datei, frame("archiv/a", "v1", "inhalt".getBytes(StandardCharsets.UTF_8), JETZT));
        InMemoryZiel ziel = new InMemoryZiel("urinet-archiv-p-", true);
        ziel.sperrFehler = true;

        Wiederherstellung.Ergebnis ergebnis = new Wiederherstellung(ziel, UHR)
                .wiederherstellen(MANDANT, List.of(zeile("archiv/a", "v1", false, 6, JETZT.toString(),
                        "GOVERNANCE", "2036-11-15T12:00:00Z")), datei, false);

        assertThat(ergebnis.exitCode()).isEqualTo(3);
        assertThat(ergebnis.zaehler().abweichend()).isEqualTo(1);
        assertThat(ziel.verworfen).isEqualTo(1);
    }

    @Test
    void ungueltigesLastModifiedImInventarLiefertExit3StattAusnahme(@TempDir Path temp) throws Exception {
        Path datei = temp.resolve("leer.bin");
        Files.write(datei, new byte[0]);
        InMemoryZiel ziel = new InMemoryZiel("urinet-archiv-p-", true);

        for (String wert : new String[]{"gestern", null}) {
            Wiederherstellung.Ergebnis ergebnis = new Wiederherstellung(ziel, UHR)
                    .wiederherstellen(MANDANT, List.of(zeile("archiv/a", "v1", false, 6, wert, null, null)),
                            datei, false);
            assertThat(ergebnis.exitCode()).isEqualTo(3);
        }
        assertThat(ziel.reihenfolge).isEmpty();
    }

    @Test
    void abgelaufeneOriginalSperreWirdAlsOhneSperreGezaehltUndNichtGesetzt(@TempDir Path temp) throws Exception {
        Path datei = temp.resolve("abgelaufen.bin");
        Files.write(datei, frame("archiv/a", "v1", "inhalt".getBytes(StandardCharsets.UTF_8), JETZT));
        InMemoryZiel ziel = new InMemoryZiel("urinet-archiv-p-", true);

        Wiederherstellung.Ergebnis ergebnis = new Wiederherstellung(ziel, UHR)
                .wiederherstellen(MANDANT, List.of(zeile("archiv/a", "v1", false, 6, JETZT.toString(),
                        "GOVERNANCE", "2026-01-01T00:00:00Z")), datei, false);

        assertThat(ergebnis.exitCode()).isZero();
        assertThat(ergebnis.zaehler().ohneSperre()).isEqualTo(1);
        assertThat(ziel.sperren).isEmpty();
    }

    @Test
    @Tag("req-16.27")
    void kappungIstNurImProbeBucketZulaessigUndVerwendetDenFrueherenTermin(@TempDir Path temp) throws Exception {
        Path datei = temp.resolve("kappen.bin");
        Files.write(datei, frame("archiv/a", "v1", "inhalt".getBytes(StandardCharsets.UTF_8), JETZT));
        InventarZeile inventar = zeile("archiv/a", "v1", false, 6, JETZT.toString(), "GOVERNANCE",
                "2036-11-15T12:00:00Z");
        for (String praefix : List.of("urinet-archiv-", "urinet-archiv-a-")) {
            InMemoryZiel falschesZiel = new InMemoryZiel(praefix, true);
            Wiederherstellung.Ergebnis abgelehnt = new Wiederherstellung(falschesZiel, UHR)
                    .wiederherstellen(MANDANT, List.of(inventar), datei, true);
            assertThat(abgelehnt.exitCode()).isEqualTo(2);
            assertThat(falschesZiel.reihenfolge).isEmpty();
            assertThat(falschesZiel.leerAufrufe).isZero();
        }
        for (String praefix : List.of("Urinet-archiv-p-", " urinet-archiv-p-", "xurinet-archiv-p-",
                "urinet-archiv-p- ")) {
            InMemoryZiel falschesZiel = new InMemoryZiel(praefix, true);
            Wiederherstellung.Ergebnis abgelehnt = new Wiederherstellung(falschesZiel, UHR)
                    .wiederherstellen(MANDANT, List.of(inventar), datei, true);
            assertThat(abgelehnt.exitCode()).isEqualTo(2);
            assertThat(falschesZiel.leerAufrufe).isZero();
        }

        InMemoryZiel probeZiel = new InMemoryZiel("urinet-archiv-p-", true);
        Wiederherstellung.Ergebnis gekappt = new Wiederherstellung(probeZiel, UHR)
                .wiederherstellen(MANDANT, List.of(inventar), datei, true);

        assertThat(gekappt.exitCode()).isZero();
        assertThat(gekappt.zaehler().gekappt()).isEqualTo(1);
        assertThat(probeZiel.sperren.get("v1").bis()).isEqualTo(JETZT.plusSeconds(86_400));
    }

    @Test
    void nichtLeeresZielWirdVorDemErstenSchreibenAbgelehnt(@TempDir Path temp) throws Exception {
        Path datei = temp.resolve("strom.bin");
        Files.write(datei, frame("archiv/a", "v1", new byte[]{1}, JETZT));
        InMemoryZiel ziel = new InMemoryZiel("urinet-archiv-p-", false);

        Wiederherstellung.Ergebnis ergebnis = new Wiederherstellung(ziel, UHR)
                .wiederherstellen(MANDANT, List.of(zeile("archiv/a", "v1", false, 1, JETZT.toString(), null, null)),
                        datei, false);

        assertThat(ergebnis.exitCode()).isEqualTo(2);
        assertThat(ziel.reihenfolge).isEmpty();
    }

    @Test
    void zweiteAusfuehrungIstIdempotentUndSchreibtKeineWeitereVersion(@TempDir Path temp) throws Exception {
        Path datei = temp.resolve("strom.bin");
        Files.write(datei, frame("archiv/a", "v1", new byte[]{1}, JETZT));
        InMemoryZiel ziel = new InMemoryZiel("urinet-archiv-p-", true);
        var wiederherstellung = new Wiederherstellung(ziel, UHR);
        List<InventarZeile> inventar = List.of(zeile("archiv/a", "v1", false, 1, JETZT.toString(), null, null));

        Wiederherstellung.Ergebnis erste = wiederherstellung.wiederherstellen(MANDANT, inventar, datei, false);
        Wiederherstellung.Ergebnis zweite = wiederherstellung.wiederherstellen(MANDANT, inventar, datei, false);

        assertThat(erste.exitCode()).isZero();
        assertThat(zweite.exitCode()).isEqualTo(2);
        assertThat(ziel.reihenfolge).containsExactly("v1");
    }

    @Test
    void mittenImObjektAbgeschnittenerStromSchreibtKeineVersion(@TempDir Path temp) throws Exception {
        byte[] vollstaendig = frame("archiv/a", "v1", "inhalt".getBytes(StandardCharsets.UTF_8), JETZT);
        Path datei = temp.resolve("abgeschnitten.bin");
        Files.write(datei, Arrays.copyOf(vollstaendig, vollstaendig.length - 10));
        InMemoryZiel ziel = new InMemoryZiel("urinet-archiv-p-", true);

        Wiederherstellung.Ergebnis ergebnis = new Wiederherstellung(ziel, UHR)
                .wiederherstellen(MANDANT, List.of(zeile("archiv/a", "v1", false, 6, JETZT.toString(), null, null)),
                        datei, false);

        assertThat(ergebnis.exitCode()).isEqualTo(3);
        assertThat(ziel.reihenfolge).isEmpty();
        assertThat(ziel.sperren).isEmpty();
    }

    @Test
    void unvollstaendigeSperrfristImInventarBrichtVorDemSchreibenAb(@TempDir Path temp) throws Exception {
        Path datei = temp.resolve("strom.bin");
        Files.write(datei, frame("archiv/a", "v1", new byte[]{1}, JETZT));
        InMemoryZiel ziel = new InMemoryZiel("urinet-archiv-p-", true);
        List<InventarZeile> inventar = List.of(
                zeile("archiv/a", "v1", false, 1, JETZT.toString(), "GOVERNANCE", null),
                zeile("archiv/a", "v1", false, 1, JETZT.toString(), null, "2036-11-15T12:00:00Z"));

        for (InventarZeile zeile : inventar) {
            Wiederherstellung.Ergebnis ergebnis = new Wiederherstellung(ziel, UHR)
                    .wiederherstellen(MANDANT, List.of(zeile), datei, false);
            assertThat(ergebnis.exitCode()).isEqualTo(3);
        }

        assertThat(ziel.reihenfolge).isEmpty();
    }

    @Test
    void fuenfzigMiBObjektWirdMitBegrenztemPufferWiederhergestellt(@TempDir Path temp) throws Exception {
        long groesse = 50L * 1024 * 1024;
        Path datei = temp.resolve("gross.bin");
        schreibeGrossesObjekt(datei, groesse);
        InventarZeile inventar = new InventarZeile(SicherungBucketExport.INVENTAR_FORMAT, "archiv/gross", "v1",
                false, groesse, JETZT.toString(), "", null, null);
        InMemoryZiel ziel = new InMemoryZiel("urinet-archiv-p-", true, false);

        Wiederherstellung.Ergebnis ergebnis = new Wiederherstellung(ziel, UHR)
                .wiederherstellen(MANDANT, List.of(inventar), datei, false);

        assertThat(ergebnis.exitCode()).isZero();
        assertThat(ergebnis.zaehler().versionen()).isEqualTo(1);
        assertThat(ziel.groessterLeseaufruf).isLessThanOrEqualTo(64 * 1024);
    }

    private static AblagePort ablage(List<Eintrag> eintraege) {
        return (AblagePort) Proxy.newProxyInstance(AblagePort.class.getClassLoader(),
                new Class<?>[]{AblagePort.class}, (proxy, methode, argumente) -> switch (methode.getName()) {
                    case "versionen" -> {
                        String praefix = (String) argumente[1];
                        List<Objektversion> versionen = eintraege.stream().map(Eintrag::version)
                                .filter(v -> v.schluessel().startsWith(praefix))
                                .sorted(Comparator.comparing(Objektversion::schluessel)
                                        .thenComparing(Objektversion::lastModified, Comparator.reverseOrder()))
                                .toList();
                        yield new Versionsseite(versionen, Optional.empty());
                    }
                    case "sicherungsSperre" -> eintraege.stream()
                            .filter(e -> e.version().equals(argumente[1]))
                            .findFirst().orElseThrow().sperre();
                    case "sicherungsKopie" -> {
                        Eintrag eintrag = eintraege.stream()
                                .filter(e -> e.version().schluessel().equals(argumente[1])
                                        && e.version().versionId().equals(argumente[2]))
                                .findFirst().orElseThrow();
                        ((OutputStream) argumente[3]).write(eintrag.inhalt());
                        yield new Kopie(Pruefsumme.von(eintrag.inhalt()), eintrag.inhalt().length);
                    }
                    case "toString" -> "AblageTest";
                    default -> throw new UnsupportedOperationException(methode.getName());
                });
    }

    private static Eintrag eintrag(String schluessel, String versionId, String text, String modus, Instant retainUntil,
                                   String lastModified) {
        byte[] inhalt = text.getBytes(StandardCharsets.UTF_8);
        Optional<SicherungsSperre> sperre = modus == null ? Optional.empty()
                : Optional.of(new SicherungsSperre(modus, retainUntil));
        return new Eintrag(new Objektversion(schluessel, versionId, false, Instant.parse(lastModified), inhalt.length),
                sperre, inhalt);
    }

    private static InventarZeile zeile(String schluessel, String versionId, boolean loeschmarke, long groesse,
                                       String lastModified, String modus, String retainUntil) {
        return new InventarZeile(SicherungBucketExport.INVENTAR_FORMAT, schluessel, versionId, loeschmarke, groesse,
                lastModified, "", modus, retainUntil);
    }

    private static byte[] frame(String schluessel, String versionId, byte[] daten, Instant lastModified)
            throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream aus = new DataOutputStream(bytes)) {
            aus.write(SicherungBucketExport.BUCKET_FORMAT.getBytes(StandardCharsets.US_ASCII));
            schreibeFrame(aus, schluessel, versionId, daten.length, lastModified, daten, true);
        }
        return bytes.toByteArray();
    }

    private static void schreibeFrame(DataOutputStream aus, String schluessel, String versionId, long groesse,
                                      Instant lastModified, byte[] daten, boolean trailer) throws IOException {
        aus.writeInt(0x55424a31);
        text(aus, schluessel);
        text(aus, versionId);
        aus.writeLong(groesse);
        aus.writeLong(lastModified.toEpochMilli());
        aus.write(daten);
        if (trailer) {
            aus.writeInt(0x53484131);
            aus.write(Pruefsumme.sha256().digest(daten));
        }
    }

    private static void schreibeGrossesObjekt(Path datei, long groesse) throws IOException {
        byte[] puffer = new byte[32 * 1024];
        MessageDigest digest = Pruefsumme.sha256();
        try (DataOutputStream aus = new DataOutputStream(Files.newOutputStream(datei))) {
            aus.write(SicherungBucketExport.BUCKET_FORMAT.getBytes(StandardCharsets.US_ASCII));
            aus.writeInt(0x55424a31);
            text(aus, "archiv/gross");
            text(aus, "v1");
            aus.writeLong(groesse);
            aus.writeLong(JETZT.toEpochMilli());
            long rest = groesse;
            while (rest > 0) {
                int n = (int) Math.min(rest, puffer.length);
                aus.write(puffer, 0, n);
                digest.update(puffer, 0, n);
                rest -= n;
            }
            aus.writeInt(0x53484131);
            aus.write(digest.digest());
        }
    }

    private static void text(DataOutputStream aus, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        aus.writeInt(bytes.length);
        aus.write(bytes);
    }

    private record Eintrag(Objektversion version, Optional<SicherungsSperre> sperre, byte[] inhalt) {
    }

    private record Sperrung(Sperrmodus modus, Instant bis) {
    }

    private static final class InMemoryZiel implements WiederherstellungsZiel {
        private final String praefix;
        private final boolean zielLeer;
        private final boolean inhaltBehalten;
        private final List<String> reihenfolge = new ArrayList<>();
        private final Map<String, byte[]> inhalt = new HashMap<>();
        private final Map<String, Sperrung> sperren = new HashMap<>();
        private int verworfen;
        private boolean sperrFehler;
        private int groessterLeseaufruf;
        private int leerAufrufe;

        private InMemoryZiel(String praefix, boolean zielLeer) {
            this(praefix, zielLeer, true);
        }

        private InMemoryZiel(String praefix, boolean zielLeer, boolean inhaltBehalten) {
            this.praefix = praefix;
            this.zielLeer = zielLeer;
            this.inhaltBehalten = inhaltBehalten;
        }

        @Override
        public Quittung schreiben(String schluessel, String versionId, Instant lastModified, InputStream inhalt,
                                  long groesse) {
            MessageDigest digest = Pruefsumme.sha256();
            ByteArrayOutputStream bytes = inhaltBehalten ? new ByteArrayOutputStream() : null;
            byte[] puffer = new byte[64 * 1024];
            try {
                for (int n; (n = inhalt.read(puffer)) != -1; ) {
                    groessterLeseaufruf = Math.max(groessterLeseaufruf, n);
                    digest.update(puffer, 0, n);
                    if (bytes != null) {
                        bytes.write(puffer, 0, n);
                    }
                }
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            reihenfolge.add(versionId);
            if (bytes != null) {
                this.inhalt.put(versionId, bytes.toByteArray());
            }
            return new Quittung(versionId, java.util.HexFormat.of().formatHex(digest.digest()));
        }

        @Override
        public void sperren(Quittung quittung, Sperrmodus modus, Instant bis) {
            if (sperrFehler) {
                throw new IllegalStateException("Sperre abgelehnt");
            }
            sperren.put(quittung.versionId(), new Sperrung(modus, bis));
        }

        @Override
        public void verwerfen(Quittung quittung) {
            verworfen++;
            reihenfolge.remove(quittung.versionId());
            inhalt.remove(quittung.versionId());
        }

        @Override
        public String praefix() {
            return praefix;
        }

        @Override
        public boolean leer(MandantCode mandant) {
            leerAufrufe++;
            return zielLeer && reihenfolge.isEmpty();
        }
    }
}
