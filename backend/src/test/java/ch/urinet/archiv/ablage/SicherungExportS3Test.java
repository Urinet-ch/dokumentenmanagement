package ch.urinet.archiv.ablage;

import ch.urinet.archiv.ablage.adapter.out.AblageEinstellungen;
import ch.urinet.archiv.ablage.adapter.out.s3.BypassVerbot;
import ch.urinet.archiv.ablage.adapter.out.s3.S3Ablage;
import ch.urinet.archiv.ablage.application.AblagePort;
import ch.urinet.archiv.ablage.domain.Pruefsumme;
import ch.urinet.archiv.ablage.domain.Sperrmodus;
import ch.urinet.archiv.mandant.application.MandantRegister;
import ch.urinet.archiv.mandant.domain.Mandant;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.sicherung.adapter.in.kommando.SicherungKommando;
import ch.urinet.archiv.sicherung.application.SicherungBucketExport;
import ch.urinet.archiv.sicherung.application.SicherungBucketExport.InventarZeile;
import ch.urinet.archiv.sicherung.application.SicherungExportVerwaltung;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.SdkResponse;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectVersionsResponse;
import software.amazon.awssdk.services.s3.model.ObjectVersion;
import software.amazon.awssdk.services.s3.model.ObjectLockRetentionMode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bucket-Export (Aufgabe 5.5b) durch {@link S3Ablage} gegen MinIO (Compose Dev Services) oder Ceph RGW: Inventar mit
 * Sperren und Löschmarken, Ausschluss von {@code vorschau/} und {@code importe/}, Seitenfortsetzung (Seitengrösse 2),
 * Zuwachs gegen ein Vorinventar, mehrere Zuwächse in einem Strom und ein Objekt grösser als der Heap des Testprofils.
 */
@QuarkusTest
class SicherungExportS3Test {

    private static final Path ZWISCHENABLAGE = Path.of("target", "arbeit-s3ablage", "sicherung").toAbsolutePath();
    private static final int OBJEKT = 0x55424a31;
    private static final int SHA256 = 0x53484131;

    @Inject
    S3Client s3;

    private S3Client verwaltung;
    private MandantCode mandant;
    private String bucket;

    @BeforeEach
    void bucketAnlegen() {
        verwaltung = TestBucket.verwaltung();
        mandant = TestBucket.neu(verwaltung, true);
        bucket = TestBucket.bucket(mandant);
    }

    @AfterEach
    void aufraeumen() {
        TestBucket.aufraeumen(verwaltung, mandant);
        verwaltung.close();
    }

    /** Die echte Ablage, aber Versionslisten mit höchstens zwei Einträgen, damit mehrere Seiten entstehen. */
    private AblagePort ablage() {
        return ablage(s3, 2);
    }

    private AblagePort ablage(S3Client client, int seitenGroesse) {
        var einstellungen = new AblageEinstellungen(TestBucket.PRAEFIX, Optional.of(Duration.ofDays(1)),
                Sperrmodus.GOVERNANCE, Optional.of(Duration.ofDays(1)), Duration.ofMinutes(5), ZWISCHENABLAGE);
        S3Ablage echt = new S3Ablage(client, S3Ablage.presigner(client, true), einstellungen, Clock.systemUTC());
        return (AblagePort) Proxy.newProxyInstance(AblagePort.class.getClassLoader(),
                new Class<?>[]{AblagePort.class}, (proxy, methode, args) -> {
                    Object[] angepasst = args == null ? null : args.clone();
                    if (methode.getName().equals("versionen")) {
                        angepasst[3] = Math.min(seitenGroesse, (Integer) angepasst[3]);
                    }
                    try {
                        return methode.invoke(echt, angepasst);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    private String put(String key, String inhalt) {
        return verwaltung.putObject(r -> r.bucket(bucket).key(key), RequestBody.fromString(inhalt)).versionId();
    }

    private List<InventarZeile> inventar(SicherungBucketExport export) {
        List<InventarZeile> zeilen = new ArrayList<>();
        export.inventar(mandant, zeilen::add);
        return zeilen;
    }

    private static Set<String> ids(List<InventarZeile> zeilen) {
        return zeilen.stream().map(z -> z.schluessel() + "@" + z.versionId()).collect(Collectors.toCollection(TreeSet::new));
    }

    private static Set<String> eintragIds(List<Eintrag> eintraege) {
        return eintraege.stream().map(e -> e.schluessel + "@" + e.versionId).collect(Collectors.toCollection(TreeSet::new));
    }

    private record Eintrag(String schluessel, String versionId, byte[] inhalt, String sha256) {
    }

    /** Liest einen oder mehrere hintereinander gehängte Ströme. */
    private static List<Eintrag> lesen(byte[] strom) {
        ByteBuffer puffer = ByteBuffer.wrap(strom);
        byte[] kennung = SicherungBucketExport.BUCKET_FORMAT.getBytes(StandardCharsets.US_ASCII);
        List<Eintrag> eintraege = new ArrayList<>();
        while (puffer.hasRemaining()) {
            if (puffer.remaining() >= kennung.length && Arrays.equals(Arrays.copyOfRange(strom, puffer.position(),
                    puffer.position() + kennung.length), kennung)) {
                puffer.position(puffer.position() + kennung.length);
                continue;
            }
            assertThat(puffer.getInt()).isEqualTo(OBJEKT);
            String schluessel = text(puffer);
            String versionId = text(puffer);
            long groesse = puffer.getLong();
            puffer.getLong();
            byte[] inhalt = new byte[(int) groesse];
            puffer.get(inhalt);
            assertThat(puffer.getInt()).isEqualTo(SHA256);
            byte[] sha = new byte[32];
            puffer.get(sha);
            assertThat(HexFormat.of().formatHex(sha)).isEqualTo(Pruefsumme.von(inhalt));
            eintraege.add(new Eintrag(schluessel, versionId, inhalt, HexFormat.of().formatHex(sha)));
        }
        return eintraege;
    }

    private static String text(ByteBuffer puffer) {
        byte[] daten = new byte[puffer.getInt()];
        puffer.get(daten);
        return new String(daten, StandardCharsets.UTF_8);
    }

    private byte[] zuwachs(SicherungBucketExport export, java.util.function.Predicate<ch.urinet.archiv.ablage.domain.Objektversion> neu) {
        ByteArrayOutputStream aus = new ByteArrayOutputStream();
        export.zuwachs(mandant, neu, aus);
        return aus.toByteArray();
    }

    @Test
    void inventarUndVollexportMitSperrenLoeschmarkeUndSeitenfortsetzung() {
        String a = "archiv/2026/" + UUID.randomUUID() + "/original.pdf";
        String b = "archiv/2026/" + UUID.randomUUID() + "/geloescht.pdf";
        String m = "manifest/" + UUID.randomUUID() + ".json";
        String e = "eingang/2026/" + UUID.randomUUID() + "/original.pdf";
        String a1 = put(a, "a1");
        String a2 = put(a, "a2");
        put(b, "b1");
        verwaltung.deleteObject(r -> r.bucket(bucket).key(b));
        String mv = put(m, "manifest");
        String ev = put(e, "eingang");
        put("vorschau/2026/x.png", "vorschau");
        put("importe/2026/y.csv", "import");
        Instant bis = Instant.now().plus(Duration.ofHours(1));
        verwaltung.putObjectRetention(r -> r.bucket(bucket).key(a).versionId(a1).retention(s -> s
                .mode(ObjectLockRetentionMode.GOVERNANCE).retainUntilDate(bis)));
        verwaltung.putObjectRetention(r -> r.bucket(bucket).key(m).versionId(mv).retention(s -> s
                .mode(ObjectLockRetentionMode.COMPLIANCE).retainUntilDate(bis)));

        SicherungBucketExport export = new SicherungBucketExport(ablage());
        List<InventarZeile> zeilen = inventar(export);

        assertThat(zeilen).hasSize(6).allSatisfy(z -> assertThat(z.schluessel()).doesNotStartWith("vorschau/")
                .doesNotStartWith("importe/"));
        assertThat(zeilen).filteredOn(z -> z.schluessel().equals(e)).singleElement()
                .satisfies(z -> assertThat(z.versionId()).isEqualTo(ev));
        assertThat(zeilen).filteredOn(InventarZeile::loeschmarke).singleElement()
                .satisfies(z -> assertThat(z.schluessel()).isEqualTo(b));
        Map<String, String> modi = zeilen.stream().filter(z -> z.sperrmodus() != null)
                .collect(Collectors.toMap(InventarZeile::versionId, InventarZeile::sperrmodus));
        assertThat(modi).containsOnly(Map.entry(a1, "GOVERNANCE"), Map.entry(mv, "COMPLIANCE"));
        assertThat(zeilen).filteredOn(z -> z.versionId().equals(a2)).singleElement()
                .satisfies(z -> assertThat(z.sperrmodus()).isNull());

        List<Eintrag> voll = lesen(zuwachs(export, v -> true));
        assertThat(voll).hasSize(5);
        assertThat(eintragIds(voll)).isEqualTo(ids(zeilen.stream().filter(z -> !z.loeschmarke()).toList()));
        assertThat(voll).filteredOn(x -> x.versionId.equals(a1)).singleElement()
                .satisfies(x -> assertThat(new String(x.inhalt, StandardCharsets.UTF_8)).isEqualTo("a1"));
    }

    @Test
    void inventarEnthaeltGleichzeitigeVersionenUeberSeitengrenzeInS3Reihenfolge() {
        String key = "archiv/2026/" + UUID.randomUUID() + "/original.pdf";
        String erste = put(key, "erste synthetische Version");
        String zweite = put(key, "zweite synthetische Version");
        Instant gleicherZeitpunkt = Instant.parse("2026-01-01T00:00:00Z");
        int[] seitenMitVersionen = {0};
        ExecutionInterceptor gleicheZeit = new ExecutionInterceptor() {
            @Override
            public SdkResponse modifyResponse(Context.ModifyResponse kontext, ExecutionAttributes attribute) {
                if (kontext.response() instanceof ListObjectVersionsResponse antwort) {
                    if (!antwort.versions().isEmpty()) {
                        seitenMitVersionen[0]++;
                    }
                    List<ObjectVersion> versionen = antwort.versions().stream()
                            .map(v -> v.toBuilder().lastModified(gleicherZeitpunkt).build()).toList();
                    return antwort.toBuilder().versions(versionen).build();
                }
                return kontext.response();
            }
        };

        try (S3Client zeitgleich = TestBucket.client(new BypassVerbot(), new KeinBypassInterceptor(), gleicheZeit)) {
            SicherungBucketExport export = new SicherungBucketExport(ablage(zeitgleich, 1));
            List<InventarZeile> inventar = inventar(export).stream().filter(z -> z.schluessel().equals(key)).toList();
            List<String> erwartet = verwaltung.listObjectVersions(r -> r.bucket(bucket).prefix(key)).versions().stream()
                    .map(ObjectVersion::versionId).toList();

            assertThat(erwartet).containsExactlyInAnyOrder(erste, zweite);
            assertThat(seitenMitVersionen[0]).isEqualTo(2);
            assertThat(inventar).extracting(InventarZeile::versionId).containsExactlyElementsOf(erwartet);
            assertThat(inventar).extracting(InventarZeile::lastModified).containsOnly(gleicherZeitpunkt.toString());
        }
    }

    @Test
    void zuwachsGegenVorinventarUndZweiZuwaechseInEinemStrom() throws IOException {
        String a = "archiv/2026/" + UUID.randomUUID() + "/original.pdf";
        String b = "archiv/2026/" + UUID.randomUUID() + "/original.pdf";
        put(a, "a1");
        put(b, "b1");
        put("eingang/2026/" + UUID.randomUUID() + "/original.pdf", "e1");
        SicherungBucketExport export = new SicherungBucketExport(ablage());
        MandantRegister register = register();
        SicherungKommando kommando = new SicherungKommando(export,
                new SicherungExportVerwaltung((art, man, sha) -> { }, register), new ObjectMapper(), ZWISCHENABLAGE);

        byte[] strom = zuwachs(export, v -> true);
        Path inventar1 = inventarDatei(kommando);
        put(a, "a2");
        put(a, "a3");
        put("manifest/" + UUID.randomUUID() + ".json", "m1");
        byte[] zuwachs1 = kommandoZuwachs(kommando, inventar1);
        Path inventar2 = inventarDatei(kommando);
        put(b, "b2");
        verwaltung.deleteObject(r -> r.bucket(bucket).key(a));
        byte[] zuwachs2 = kommandoZuwachs(kommando, inventar2);

        assertThat(lesen(zuwachs1)).extracting(x -> new String(x.inhalt, StandardCharsets.UTF_8))
                .containsExactlyInAnyOrder("a2", "a3", "m1");
        assertThat(lesen(zuwachs2)).extracting(x -> new String(x.inhalt, StandardCharsets.UTF_8))
                .containsExactly("b2");

        ByteArrayOutputStream alles = new ByteArrayOutputStream();
        alles.write(strom);
        alles.write(zuwachs1);
        alles.write(zuwachs2);
        List<Eintrag> eintraege = lesen(alles.toByteArray());
        List<InventarZeile> aktuell = inventar(export);
        assertThat(eintraege).hasSize(eintraege.stream().map(x -> x.versionId).distinct().toList().size());
        assertThat(eintragIds(eintraege)).isEqualTo(ids(aktuell.stream().filter(z -> !z.loeschmarke()).toList()));
        assertThat(zuwachsLeer(kommando, inventarDatei(kommando))).isTrue();
    }

    private Path inventarDatei(SicherungKommando kommando) throws IOException {
        ByteArrayOutputStream aus = new ByteArrayOutputStream();
        int exit = kommando.ausfuehren(List.of("inventar", "--mandant", mandant.wert()),
                new PrintStream(aus, true, StandardCharsets.UTF_8), new PrintStream(OutputStream.nullOutputStream()));
        assertThat(exit).isZero();
        Files.createDirectories(ZWISCHENABLAGE);
        Path datei = Files.createTempFile(ZWISCHENABLAGE, "inventar", ".jsonl");
        Files.write(datei, aus.toByteArray());
        datei.toFile().deleteOnExit();
        return datei;
    }

    private byte[] kommandoZuwachs(SicherungKommando kommando, Path inventar) {
        ByteArrayOutputStream aus = new ByteArrayOutputStream();
        int exit = kommando.ausfuehren(List.of("zuwachs", "--mandant", mandant.wert(), "--inventar-vorher",
                inventar.toString()), new PrintStream(aus, true), new PrintStream(OutputStream.nullOutputStream()));
        assertThat(exit).isZero();
        return aus.toByteArray();
    }

    private boolean zuwachsLeer(SicherungKommando kommando, Path inventar) {
        return lesen(kommandoZuwachs(kommando, inventar)).isEmpty();
    }

    private MandantRegister register() {
        return new MandantRegister() {
            @Override
            public boolean existiert(MandantCode code) {
                return code.equals(mandant);
            }

            @Override
            public void eintragen(Mandant m) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<Mandant> aktive() {
                return List.of(new Mandant(mandant, "Test", "m_test", bucket,
                        new ch.urinet.archiv.mandant.domain.Geschaeftsjahresende("12-31"), true));
            }

            @Override
            public Optional<Instant> erstellt(MandantCode code) {
                return Optional.empty();
            }
        };
    }
}
