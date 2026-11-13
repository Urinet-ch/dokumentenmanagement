package ch.urinet.archiv.verarbeitung;

import ch.urinet.archiv.ablage.application.AblagePort;
import ch.urinet.archiv.ablage.domain.AbgelegteDatei;
import ch.urinet.archiv.ablage.domain.Ablageort;
import ch.urinet.archiv.ablage.domain.DateiRolle;
import ch.urinet.archiv.ablage.domain.DateiSchluessel;
import ch.urinet.archiv.ablage.domain.Vorschau;
import ch.urinet.archiv.dokument.Testdokumente;
import ch.urinet.archiv.mandant.application.MandantBucket;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.verarbeitung.adapter.out.ocr.WerkzeugImage;
import ch.urinet.archiv.verarbeitung.application.AuftragArbeiter;
import ch.urinet.archiv.verarbeitung.application.VerarbeitenHandler;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ObjectVersion;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Testdokumente für {@code VERARBEITEN} (Aufgabe 2.6, Teil b): legt Dokument, Dateien in {@code eingang/} und Zeilen in
 * {@code dokument_datei} so an, wie es der Upload (Aufgabe 3.1) tut, und liest Ergebnis, Aufträge und Objektversionen.
 * Nur synthetische Inhalte (Req 16.15).
 */
@ApplicationScoped
public class VerarbeitungTestHilfe {

    /** Eingang aller Testdokumente: Jahr 2026 in Europe/Zurich. */
    public static final Instant EINGANG = Instant.parse("2026-03-15T09:00:00Z");
    public static final int JAHR = 2026;
    private static final String ERSTELLER = "test-verarbeiten";
    private static final List<String> MANDANTEN = List.of("urinet", "vhf-gsk");

    /** Eine Datei des Testdokuments; {@code endung} nur für den Schlüssel des Originals. */
    public record Quelle(DateiRolle rolle, int nr, String endung, String mime, byte[] inhalt) {
    }

    @Inject
    Instance<AgroalDataSource> datenbank;

    @Inject
    AuftragTestHilfe auftraege;

    @Inject
    AuftragArbeiter arbeiter;

    @Inject
    AblagePort ablage;

    @Inject
    MandantBucket buckets;

    @Inject
    S3Client s3;

    @ConfigProperty(name = "archiv.ablage.bucket-praefix")
    String bucketPraefix;

    /** Werkzeug-Image, Mandanten und Buckets bereit; Aufträge und Testdokumente früherer Läufe weg. */
    public void vorbereiten() throws Exception {
        WerkzeugImage.sicherstellen();
        auftraege.vorbereiten();
        auftraege.ausfuehren("delete from public.auftrag where typ in ('VERARBEITEN', 'KI_VORSCHLAG')");
        for (String mandant : MANDANTEN) {
            buckets.einrichten(bucket(mandant));
            Testdokumente.loeschen(datenbank.get(), mandant, testdokumente(mandant));
        }
    }

    public UUID anlegen(String mandant, String quelle, List<Quelle> dateien) throws SQLException {
        return anlegen(mandant, quelle, "NEU", dateien);
    }

    public UUID anlegen(String mandant, String quelle, String status, List<Quelle> dateien) throws SQLException {
        UUID id = UUID.randomUUID();
        String schema = schema(mandant);
        try (Connection verbindung = datenbank.get().getConnection()) {
            try (PreparedStatement dokument = verbindung.prepareStatement("insert into " + schema
                    + ".dokument (id, eingang, quelle, erstellt_von, status) values (?, ?, ?, ?, ?)")) {
                dokument.setObject(1, id);
                dokument.setTimestamp(2, Timestamp.from(EINGANG));
                dokument.setString(3, quelle);
                dokument.setString(4, ERSTELLER);
                dokument.setString(5, status);
                dokument.executeUpdate();
            }
            for (Quelle datei : dateien) {
                DateiSchluessel schluessel = datei.rolle() == DateiRolle.ORIGINAL
                        ? DateiSchluessel.original(JAHR, id, datei.nr(), datei.endung())
                        : DateiSchluessel.arbeitsfassung(JAHR, id, datei.nr());
                AbgelegteDatei abgelegt = ablage.ablegenEingang(new MandantCode(mandant), schluessel,
                        new ByteArrayInputStream(datei.inhalt()), datei.inhalt().length);
                try (PreparedStatement zeile = verbindung.prepareStatement("insert into " + schema
                        + ".dokument_datei (dokument_id, nr, rolle, sha256, s3_schluessel, s3_version, mime, groesse) "
                        + "values (?, ?, ?, ?, ?, ?, ?, ?)")) {
                    zeile.setObject(1, id);
                    zeile.setInt(2, datei.nr());
                    zeile.setString(3, datei.rolle().name());
                    zeile.setString(4, abgelegt.sha256());
                    zeile.setString(5, schluessel.pfad(Ablageort.EINGANG));
                    zeile.setString(6, abgelegt.versionId());
                    zeile.setString(7, datei.mime());
                    zeile.setLong(8, abgelegt.groesse());
                    zeile.executeUpdate();
                }
            }
        }
        return id;
    }

    /** Reiht {@code VERARBEITEN} wie der Upload ein (Nutzlast nur mit der Dokument-ID). */
    public long einreihen(String mandant, UUID id) throws SQLException {
        return auftraege.rohEinreihen(mandant, VerarbeitenHandler.TYP,
                "{\"" + VerarbeitenHandler.DOKUMENT_ID + "\":\"" + id + "\"}");
    }

    /** Holt und bearbeitet, bis kein fälliger Auftrag mehr da ist; OCR braucht länger als die Test-Handler. */
    public void abarbeiten() throws Exception {
        for (int runde = 0; runde < 20; runde++) {
            List<CompletableFuture<Void>> laeufe = arbeiter.abholen();
            if (laeufe.isEmpty()) {
                return;
            }
            CompletableFuture.allOf(laeufe.toArray(CompletableFuture[]::new)).get(5, TimeUnit.MINUTES);
        }
        throw new IllegalStateException("Auftragsschlange wird nicht leer");
    }

    /** Zeile des Dokuments als Spaltenname → Wert. */
    public Map<String, Object> dokument(String mandant, UUID id) throws SQLException {
        return zeile("select status, fehler, pdfa_fehlt, sha256_pdfa, s3_pdfa, s3_version_pdfa, seiten, volltext, "
                + "version from " + schema(mandant) + ".dokument where id = ?", id);
    }

    /** KI-Vorschlag des Dokuments (Aufgabe 3.2): {@code ki_vorschlag} und {@code ki_sicherheit} als JSON-Text. */
    public Map<String, Object> kiVorschlag(String mandant, UUID id) throws SQLException {
        return zeile("select status, fehler, ki_vorschlag::text as ki_vorschlag, ki_sicherheit::text as ki_sicherheit, "
                + "partner_id::text as partner_id, partner_name, ki_partner_id::text as ki_partner_id, titel, "
                + "bestaetigt, version from " + schema(mandant)
                + ".dokument where id = ?", id);
    }

    /** Zeilen aus {@code ki_verbrauch} mit einer ID über {@code abId}: Zweck, Modell, Tokens ein und aus. */
    public List<List<Object>> kiVerbrauch(String mandant, long abId) throws SQLException {
        List<List<Object>> zeilen = new ArrayList<>();
        try (Connection verbindung = datenbank.get().getConnection(); PreparedStatement abfrage = verbindung.prepareStatement(
                "select zweck, modell, tokens_ein, tokens_aus from " + schema(mandant)
                        + ".ki_verbrauch where id > ? order by id")) {
            abfrage.setLong(1, abId);
            try (ResultSet z = abfrage.executeQuery()) {
                while (z.next()) {
                    zeilen.add(List.of(z.getString(1), z.getString(2), z.getInt(3), z.getInt(4)));
                }
            }
        }
        return zeilen;
    }

    /** {@code public.mandant.kennungen} als JSON-Text ({@code null}, wenn nicht gesetzt). */
    public String kennungen(String mandant) throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection(); PreparedStatement abfrage = verbindung.prepareStatement(
                "select kennungen::text from public.mandant where code = ?")) {
            abfrage.setString(1, mandant);
            try (ResultSet z = abfrage.executeQuery()) {
                return z.next() ? z.getString(1) : null;
            }
        }
    }

    /** Legt einen Kontakt mit IBAN an (Partner-Abgleich, Req 7.4) und gibt seine ID zurück. */
    public UUID kontaktAnlegen(String mandant, String name, String iban) throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection verbindung = datenbank.get().getConnection(); PreparedStatement anlegen = verbindung.prepareStatement(
                "insert into " + schema(mandant) + ".kontakt (id, typ, name, name_normalisiert, iban, quelle) "
                        + "values (?, 'ORGANISATION', ?, ?, ?, 'TEST')")) {
            anlegen.setObject(1, id);
            anlegen.setString(2, name);
            anlegen.setString(3, name.toLowerCase(java.util.Locale.ROOT));
            anlegen.setString(4, iban);
            anlegen.executeUpdate();
        }
        return id;
    }

    /** Löst den Kontakt von allen Dokumenten und löscht ihn (Aufräumen nach dem Szenario). */
    public void kontaktLoeschen(String mandant, UUID id) throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection()) {
            try (PreparedStatement loesen = verbindung.prepareStatement("update " + schema(mandant)
                    + ".dokument set partner_id = null where partner_id = ?")) {
                loesen.setObject(1, id);
                loesen.executeUpdate();
            }
            try (PreparedStatement loeschen = verbindung.prepareStatement("delete from " + schema(mandant)
                    + ".kontakt where id = ?")) {
                loeschen.setObject(1, id);
                loeschen.executeUpdate();
            }
        }
    }

    /** Setzt {@code public.mandant.kennungen} (JSON-Text oder {@code null}). */
    public void kennungenSetzen(String mandant, String json) throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection(); PreparedStatement setzen = verbindung.prepareStatement(
                "update public.mandant set kennungen = cast(? as jsonb) where code = ?")) {
            setzen.setString(1, json);
            setzen.setString(2, mandant);
            setzen.executeUpdate();
        }
    }

    /** Höchste ID in {@code ki_verbrauch} (0 ohne Zeilen). */
    public long kiVerbrauchStand(String mandant) throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection(); PreparedStatement abfrage = verbindung.prepareStatement(
                "select coalesce(max(id), 0) from " + schema(mandant) + ".ki_verbrauch");
             ResultSet z = abfrage.executeQuery()) {
            z.next();
            return z.getLong(1);
        }
    }

    /** Findet die deutsche Volltextsuche (Req 5.3) das Dokument mit diesem Wort? */
    public boolean volltextFindet(String mandant, UUID id, String wort) throws SQLException {
        return !zeile("select 1 as treffer from " + schema(mandant) + ".dokument where id = ? "
                + "and volltext_tsv @@ plainto_tsquery('german', '" + wort.replace("'", "") + "')", id).isEmpty();
    }

    /** Zeilen aus {@code dokument_datei}: Rolle und Nummer → SHA-256 und Versions-ID. */
    public Map<String, String[]> dateien(String mandant, UUID id) throws SQLException {
        Map<String, String[]> dateien = new HashMap<>();
        try (Connection verbindung = datenbank.get().getConnection(); PreparedStatement abfrage = verbindung.prepareStatement(
                "select rolle, nr, sha256, s3_schluessel, s3_version from " + schema(mandant)
                        + ".dokument_datei where dokument_id = ?")) {
            abfrage.setObject(1, id);
            try (ResultSet z = abfrage.executeQuery()) {
                while (z.next()) {
                    dateien.put(z.getString(1) + "-" + z.getInt(2),
                            new String[]{z.getString(3), z.getString(4), z.getString(5)});
                }
            }
        }
        return dateien;
    }

    /** Ändert die SHA-256 einer Datei in {@code dokument_datei} (der Object Storage bleibt, wie er ist). */
    public void pruefsummeVerfaelschen(String mandant, UUID id) throws SQLException {
        auftraege.ausfuehren("update " + schema(mandant) + ".dokument_datei set sha256 = '"
                + HexFormat.of().formatHex(zufall(32)) + "' where dokument_id = '" + id + "'");
    }

    /**
     * Ändert das Dokument wie ein Benutzer im Eingangskorb: {@code setzen} (fester SQL-Ausdruck aus dem Test), dazu
     * {@code version + 1}.
     */
    public void dokumentAendern(String mandant, UUID id, String setzen) throws SQLException {
        auftraege.ausfuehren("update " + schema(mandant) + ".dokument set " + setzen
                + ", version = version + 1, geaendert = clock_timestamp() where id = '" + id + "'");
    }

    /** IDs der Aufträge eines Typs für dieses Dokument. */
    /** Status der Aufträge {@code typ} zum Dokument, nach ID sortiert. */
    public List<String> auftragStatus(String typ, UUID id) throws SQLException {
        List<String> status = new ArrayList<>();
        try (Connection verbindung = datenbank.get().getConnection(); PreparedStatement abfrage = verbindung.prepareStatement(
                "select status from public.auftrag where typ = ? and nutzlast ->> 'dokumentId' = ? order by id")) {
            abfrage.setString(1, typ);
            abfrage.setString(2, id.toString());
            try (ResultSet z = abfrage.executeQuery()) {
                while (z.next()) {
                    status.add(z.getString(1));
                }
            }
        }
        return status;
    }

    public List<Long> auftraege(String typ, UUID id) throws SQLException {
        List<Long> ids = new ArrayList<>();
        try (Connection verbindung = datenbank.get().getConnection(); PreparedStatement abfrage = verbindung.prepareStatement(
                "select id from public.auftrag where typ = ? and nutzlast ->> 'dokumentId' = ? order by id")) {
            abfrage.setString(1, typ);
            abfrage.setString(2, id.toString());
            try (ResultSet z = abfrage.executeQuery()) {
                while (z.next()) {
                    ids.add(z.getLong(1));
                }
            }
        }
        return ids;
    }

    /** Alle Versionen und Löschmarken unter einem Präfix, je als {@code <key>@<version>}. */
    public List<String> versionen(String mandant, String praefix) {
        List<String> versionen = new ArrayList<>();
        for (var seite : s3.listObjectVersionsPaginator(b -> b.bucket(bucket(mandant)).prefix(praefix))) {
            seite.versions().stream().map(v -> v.key() + "@" + v.versionId()).forEach(versionen::add);
            seite.deleteMarkers().forEach(m -> versionen.add(m.key() + "@" + m.versionId() + " (Löschmarke)"));
        }
        return versionen;
    }

    public List<ObjectVersion> objektversionen(String mandant, String praefix) {
        List<ObjectVersion> versionen = new ArrayList<>();
        for (var seite : s3.listObjectVersionsPaginator(b -> b.bucket(bucket(mandant)).prefix(praefix))) {
            versionen.addAll(seite.versions());
        }
        return versionen;
    }

    /** Lädt eine Objektversion in eine Datei unter {@code target/}. */
    public Path herunterladen(String mandant, String key, String versionId) throws Exception {
        Path ziel = Files.createDirectories(Path.of("target/verarbeiten-test")).resolve(UUID.randomUUID() + ".bin");
        Files.write(ziel, s3.getObjectAsBytes(b -> b.bucket(bucket(mandant)).key(key).versionId(versionId))
                .asByteArray());
        return ziel;
    }

    public static String pdfaSchluessel(UUID id) {
        return DateiSchluessel.pdfa(JAHR, id).pfad(Ablageort.EINGANG);
    }

    public static String vorschauOrdner(UUID id) {
        return Vorschau.ordner(id);
    }

    public static String eingangOrdner(UUID id) {
        return "eingang/" + JAHR + "/" + id + "/";
    }

    public static String sha256(byte[] inhalt) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(inhalt));
    }

    /** Zufallsbytes, damit gleiche Testbilder keine Dubletten werden (Req 3.4). */
    public static byte[] zufall(int laenge) {
        byte[] bytes = new byte[laenge];
        new java.security.SecureRandom().nextBytes(bytes);
        return bytes;
    }

    private Map<String, Object> zeile(String sql, UUID id) throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement(sql)) {
            abfrage.setObject(1, id);
            try (ResultSet z = abfrage.executeQuery()) {
                Map<String, Object> werte = new HashMap<>();
                if (z.next()) {
                    for (int i = 1; i <= z.getMetaData().getColumnCount(); i++) {
                        werte.put(z.getMetaData().getColumnLabel(i), z.getObject(i));
                    }
                }
                return werte;
            }
        }
    }

    private List<UUID> testdokumente(String mandant) throws SQLException {
        List<UUID> ids = new ArrayList<>();
        try (Connection verbindung = datenbank.get().getConnection(); PreparedStatement abfrage = verbindung.prepareStatement(
                "select id from " + schema(mandant) + ".dokument where erstellt_von = ?")) {
            abfrage.setString(1, ERSTELLER);
            try (ResultSet z = abfrage.executeQuery()) {
                while (z.next()) {
                    ids.add(z.getObject(1, UUID.class));
                }
            }
        }
        return ids;
    }

    private String bucket(String mandant) {
        return new MandantCode(mandant).bucket(bucketPraefix);
    }

    private static String schema(String mandant) {
        return new MandantCode(mandant).schema();
    }
}
