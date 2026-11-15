package ch.urinet.archiv.sicherung.adapter.in.kommando;

import ch.urinet.archiv.ablage.application.AblagePort;
import ch.urinet.archiv.ablage.domain.Pruefsumme;
import ch.urinet.archiv.ablage.domain.Sperrmodus;
import ch.urinet.archiv.ablage.domain.Versionsseite;
import ch.urinet.archiv.mandant.application.MandantRegister;
import ch.urinet.archiv.mandant.domain.Geschaeftsjahresende;
import ch.urinet.archiv.mandant.domain.Mandant;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.sicherung.application.SicherungBucketExport;
import ch.urinet.archiv.sicherung.application.SicherungExportSpeicher;
import ch.urinet.archiv.sicherung.application.SicherungExportVerwaltung;
import ch.urinet.archiv.sicherung.application.WiederherstellungsZiel;
import ch.urinet.archiv.sicherung.application.WiederherstellungsZiel.Quittung;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class SicherungWiederherstellungKommandoTest {

    private static final MandantCode CODE = new MandantCode("probe");
    private static final Instant LAST_MODIFIED = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void liestPflichtargumenteUndOptionaleKappung() {
        var aufruf = SicherungKommando.lesen(List.of("wiederherstellen", "--mandant", "probe", "--inventar",
                "inventar.jsonl", "--strom", "strom.bin", "--kappen"));

        assertThat(aufruf).isPresent();
        assertThat(aufruf.orElseThrow().kappen()).isTrue();
        assertThat(SicherungKommando.lesen(List.of("wiederherstellen", "--mandant", "probe", "--strom", "strom.bin")))
                .isEmpty();
        assertThat(SicherungKommando.lesen(List.of("wiederherstellen", "--mandant", "probe", "--inventar",
                "inventar.jsonl", "--strom", "strom.bin", "--kappen", "--kappen"))).isEmpty();
    }

    @Test
    void kommandoRuftAnwendungUeberZielPortAufUndGibtNurZaehlerAus(@org.junit.jupiter.api.io.TempDir Path temp)
            throws Exception {
        byte[] inhalt = "synthetisch".getBytes(StandardCharsets.UTF_8);
        Path strom = temp.resolve("strom.bin");
        Files.write(strom, frame("archiv/a", "v1", inhalt));
        Path inventar = temp.resolve("inventar.jsonl");
        var zeile = new SicherungBucketExport.InventarZeile(SicherungBucketExport.INVENTAR_FORMAT, "archiv/a", "v1",
                false, inhalt.length, LAST_MODIFIED.toString(), "", null, null);
        Files.writeString(inventar, new ObjectMapper().writeValueAsString(zeile) + "\n", StandardCharsets.UTF_8);
        InMemoryZiel ziel = new InMemoryZiel();
        SicherungKommando kommando = kommando(ziel);
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();

        int exit = kommando.ausfuehren(List.of("wiederherstellen", "--mandant", "probe", "--inventar",
                        inventar.toString(), "--strom", strom.toString()),
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8));

        assertThat(exit).isZero();
        JsonNode status = new ObjectMapper().readTree(stdout.toByteArray());
        assertThat(status.size()).isEqualTo(6);
        assertThat(status.path("versionen").asLong()).isEqualTo(1);
        assertThat(status.path("fehlendInKette").asLong()).isZero();
        assertThat(status.path("ueberzaehlig").asLong()).isZero();
        assertThat(stdout.toString(StandardCharsets.UTF_8)).doesNotContain("archiv/a", strom.toString(),
                inventar.toString());
        assertThat(stderr.toString(StandardCharsets.UTF_8)).isEmpty();
        assertThat(ziel.geschriebene).containsExactly("archiv/a@v1");
    }

    @Test
    void kappungAusserhalbProbeWirdMitExitZweiUndOhneDateidatenAbgelehnt(
            @org.junit.jupiter.api.io.TempDir Path temp) throws Exception {
        String schluessel = "archiv/synthetische-geheimdatei.pdf";
        Path inventar = temp.resolve("inventar-mit-synthetischem-schluessel.jsonl");
        var zeile = new SicherungBucketExport.InventarZeile(SicherungBucketExport.INVENTAR_FORMAT, schluessel, "v1",
                false, 1, LAST_MODIFIED.toString(), "", null, null);
        Files.writeString(inventar, new ObjectMapper().writeValueAsString(zeile) + "\n", StandardCharsets.UTF_8);
        Path strom = temp.resolve("synthetischer-strom-dateiname.bin");
        InMemoryZiel ziel = new InMemoryZiel("urinet-archiv-a-probe");
        SicherungKommando kommando = kommando(ziel);
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();

        int exit = kommando.ausfuehren(List.of("wiederherstellen", "--mandant", "probe", "--inventar",
                        inventar.toString(), "--strom", strom.toString(), "--kappen"),
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8));

        assertThat(exit).isEqualTo(2);
        assertThat(stdout.toString(StandardCharsets.UTF_8)).doesNotContain(schluessel, inventar.toString(),
                strom.toString());
        assertThat(stderr.toString(StandardCharsets.UTF_8))
                .contains("Wiederherstellung abgebrochen: Ziel nicht leer oder Kappung nicht zulässig.")
                .doesNotContain(schluessel, inventar.toString(), strom.toString());
        assertThat(ziel.geschriebene).isEmpty();
    }

    private static SicherungKommando kommando(InMemoryZiel ziel) {
        return new SicherungKommando(new SicherungBucketExport(ablage()),
                new SicherungExportVerwaltung((SicherungExportSpeicher) (art, mandant, sha256) -> { },
                        mandantRegister()),
                new ObjectMapper(), ziel, Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));
    }

    private static MandantRegister mandantRegister() {
        return new MandantRegister() {
            @Override
            public boolean existiert(MandantCode code) {
                return code.equals(CODE);
            }

            @Override
            public void eintragen(Mandant mandant) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<Mandant> aktive() {
                return List.of(new Mandant(CODE, "Probe", "m_probe", "urinet-archiv-p-probe",
                        Geschaeftsjahresende.STANDARD, true));
            }

            @Override
            public Optional<Instant> erstellt(MandantCode code) {
                return Optional.empty();
            }
        };
    }

    private static AblagePort ablage() {
        return (AblagePort) Proxy.newProxyInstance(AblagePort.class.getClassLoader(),
                new Class<?>[]{AblagePort.class}, (proxy, methode, argumente) -> switch (methode.getName()) {
                    case "versionen" -> new Versionsseite(List.of(), Optional.empty());
                    case "toString" -> "AblageTest";
                    default -> throw new UnsupportedOperationException(methode.getName());
                });
    }

    private static byte[] frame(String schluessel, String versionId, byte[] inhalt) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream aus = new DataOutputStream(bytes)) {
            aus.write(SicherungBucketExport.BUCKET_FORMAT.getBytes(StandardCharsets.US_ASCII));
            aus.writeInt(0x55424a31);
            text(aus, schluessel);
            text(aus, versionId);
            aus.writeLong(inhalt.length);
            aus.writeLong(LAST_MODIFIED.toEpochMilli());
            aus.write(inhalt);
            aus.writeInt(0x53484131);
            aus.write(Pruefsumme.sha256().digest(inhalt));
        }
        return bytes.toByteArray();
    }

    private static void text(DataOutputStream aus, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        aus.writeInt(bytes.length);
        aus.write(bytes);
    }

    private static final class InMemoryZiel implements WiederherstellungsZiel {
        private final List<String> geschriebene = new ArrayList<>();
        private final String praefix;

        private InMemoryZiel() {
            this("urinet-archiv-p-");
        }

        private InMemoryZiel(String praefix) {
            this.praefix = praefix;
        }

        @Override
        public Quittung schreiben(String schluessel, String versionId, Instant lastModified, InputStream inhalt,
                                  long groesse) {
            try {
                byte[] daten = inhalt.readAllBytes();
                geschriebene.add(schluessel + "@" + versionId);
                return new Quittung("restored-" + versionId, HexFormat.of().formatHex(
                        Pruefsumme.sha256().digest(daten)));
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public void sperren(Quittung quittung, Sperrmodus modus, Instant bis) {
            throw new AssertionError("Inventar enthält keine Sperre");
        }

        @Override
        public void verwerfen(Quittung quittung) {
            throw new AssertionError("Der gültige Export muss bleiben");
        }

        @Override
        public String praefix() {
            return praefix;
        }

        @Override
        public boolean leer(MandantCode mandant) {
            return true;
        }
    }
}
