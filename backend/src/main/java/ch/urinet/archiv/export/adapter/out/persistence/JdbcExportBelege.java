package ch.urinet.archiv.export.adapter.out.persistence;

import ch.urinet.archiv.ablage.domain.ManifestTeil;
import ch.urinet.archiv.export.application.ExportBelege;
import ch.urinet.archiv.mandant.application.MandantAufloesung;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Inhalt eines Jahresexports aus {@code m_<code>} (Spec 02, Req 7.1): Dokumente im Status {@code ARCHIVIERT} mit
 * {@code geschaeftsjahr}, ihre PDF/A-Fassung oder bei {@code pdfa_fehlt} (bzw. ohne PDF/A) die aktiven Originale, sowie
 * die Dateien der Tages-Manifeste. Nur Dateien unter {@code archiv/} bzw. {@code manifest/} mit Versions-ID; jede
 * Anweisung mit eigener Zeitgrenze.
 */
@ApplicationScoped
public class JdbcExportBelege implements ExportBelege {

    private static final Pattern SCHEMA = Pattern.compile("^m_[a-z0-9_]+$");
    private static final UUID VOR_ALLEN = new UUID(0L, 0L);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<Map<String, String>>> PRUEFDATEN = new TypeReference<>() {
    };

    private final Instance<AgroalDataSource> datenquelle;
    private final MandantAufloesung aufloesung;

    public JdbcExportBelege(Instance<AgroalDataSource> datenquelle, MandantAufloesung aufloesung) {
        this.datenquelle = datenquelle;
        this.aufloesung = aufloesung;
    }

    private record Kopf(UUID id, String art, LocalDate datum, Instant eingang, Instant archiviert, String partner,
                        String belegnummer, String richtung, BigDecimal betrag, String waehrung,
                        boolean pdfaFehlt, String pdfa, String pdfaVersion, String pdfaSha256) {
    }

    @Override
    public List<Beleg> seite(int geschaeftsjahr, Optional<UUID> nach, int anzahl) {
        String s = schema();
        try (Connection v = datenquelle.get().getConnection()) {
            List<Kopf> koepfe = new ArrayList<>();
            // UUID-Ordnung von PostgreSQL ist bytweise; dieselbe Ordnung nutzt «id > ?»
            try (PreparedStatement p = v.prepareStatement("select d.id, d.art, d.datum, d.eingang, d.archiviert, "
                    + "d.partner_name, f.belegnummer, f.richtung, f.betrag, f.waehrung, d.pdfa_fehlt, d.s3_pdfa, "
                    + "d.s3_version_pdfa, d.sha256_pdfa from " + s + ".dokument d left join " + s
                    + ".dokument_finanz f on f.dokument_id = d.id where d.status = 'ARCHIVIERT' "
                    + "and d.geschaeftsjahr = ? and d.id > ? order by d.id limit ?")) {
                p.setQueryTimeout(JdbcExportRepository.ZEITGRENZE_S);
                p.setInt(1, geschaeftsjahr);
                p.setObject(2, nach.orElse(VOR_ALLEN), Types.OTHER);
                p.setInt(3, anzahl);
                try (ResultSet z = p.executeQuery()) {
                    while (z.next()) {
                        koepfe.add(new Kopf(z.getObject(1, UUID.class), z.getString(2),
                                z.getObject(3, LocalDate.class), zeit(z.getTimestamp(4)), zeit(z.getTimestamp(5)),
                                z.getString(6), z.getString(7), z.getString(8), z.getBigDecimal(9), z.getString(10),
                                z.getBoolean(11), z.getString(12), z.getString(13), z.getString(14)));
                    }
                }
            }
            Map<UUID, List<Datei>> originale = originale(v, s, koepfe.stream()
                    .filter(k -> k.pdfaFehlt() || k.pdfa() == null).map(Kopf::id).toList());
            List<Beleg> belege = new ArrayList<>();
            for (Kopf k : koepfe) {
                List<Datei> dateien = !k.pdfaFehlt() && k.pdfa() != null
                        ? List.of(new Datei(k.pdfa(), version(k.pdfaVersion(), k.pdfa()), k.pdfaSha256(), "pdf"))
                        : originale.getOrDefault(k.id(), List.of());
                belege.add(new Beleg(k.id(), k.art(), k.datum(), k.eingang(), k.archiviert(), k.partner(),
                        k.belegnummer(), k.richtung(), k.betrag(), k.waehrung(), dateien));
            }
            return belege;
        } catch (SQLException e) {
            throw new IllegalStateException("Belege des Exports nicht gelesen", e);
        }
    }

    private static Map<UUID, List<Datei>> originale(Connection v, String s, List<UUID> ids) throws SQLException {
        Map<UUID, List<Datei>> dateien = new LinkedHashMap<>();
        if (ids.isEmpty()) {
            return dateien;
        }
        try (PreparedStatement p = v.prepareStatement("select dokument_id, s3_schluessel, s3_version, sha256 from " + s
                + ".dokument_datei where dokument_id = any (?) and rolle = 'ORIGINAL' and status = 'AKTIV' "
                + "order by dokument_id, nr")) {
            p.setQueryTimeout(JdbcExportRepository.ZEITGRENZE_S);
            p.setArray(1, v.createArrayOf("uuid", ids.toArray()));
            try (ResultSet z = p.executeQuery()) {
                while (z.next()) {
                    String schluessel = z.getString(2);
                    dateien.computeIfAbsent(z.getObject(1, UUID.class), id -> new ArrayList<>())
                            .add(new Datei(schluessel, version(z.getString(3), schluessel), z.getString(4),
                                    endung(schluessel)));
                }
            }
        }
        return dateien;
    }

    @Override
    public List<Manifestdatei> manifeste(LocalDate von, LocalDate bis) {
        String s = schema();
        List<Manifestdatei> dateien = new ArrayList<>();
        try (Connection v = datenquelle.get().getConnection()) {
            Map<LocalDate, List<Manifestdatei>> stempel = new LinkedHashMap<>();
            try (PreparedStatement p = v.prepareStatement("select datum, dienst, s3_schluessel, s3_version, tsr_sha256, "
                    + "pruefdaten::text from " + s + ".manifest_zeitstempel where datum between ? and ? "
                    + "order by datum, dienst")) {
                p.setQueryTimeout(JdbcExportRepository.ZEITGRENZE_S);
                p.setObject(1, von);
                p.setObject(2, bis);
                try (ResultSet z = p.executeQuery()) {
                    while (z.next()) {
                        LocalDate datum = z.getObject(1, LocalDate.class);
                        String dienst = z.getString(2);
                        List<Manifestdatei> tag = stempel.computeIfAbsent(datum, d -> new ArrayList<>());
                        tag.add(new Manifestdatei(datum, ManifestTeil.tsr(dienst).endung(), z.getString(3),
                                z.getString(4), z.getString(5)));
                        for (Map<String, String> d : JSON.readValue(z.getString(6), PRUEFDATEN)) {
                            ManifestTeil teil = switch (d.get("art")) {
                                case "kette" -> ManifestTeil.kette(dienst);
                                case "ocsp" -> ManifestTeil.ocsp(dienst);
                                case "crl" -> ManifestTeil.crl(dienst);
                                default -> throw new IllegalStateException("Unbekannte Prüfdaten im Manifest " + datum);
                            };
                            tag.add(new Manifestdatei(datum, teil.endung(), teil.pfad(datum), d.get("s3_version"),
                                    d.get("sha256")));
                        }
                    }
                }
            }
            try (PreparedStatement p = v.prepareStatement("select datum, s3_schluessel, s3_version, sha256, sig_sha256, "
                    + "sig_s3_version from " + s + ".manifest where datum between ? and ? order by datum")) {
                p.setQueryTimeout(JdbcExportRepository.ZEITGRENZE_S);
                p.setObject(1, von);
                p.setObject(2, bis);
                try (ResultSet z = p.executeQuery()) {
                    while (z.next()) {
                        LocalDate datum = z.getObject(1, LocalDate.class);
                        dateien.add(new Manifestdatei(datum, ManifestTeil.JSON.endung(), z.getString(2), z.getString(3),
                                z.getString(4)));
                        dateien.add(new Manifestdatei(datum, ManifestTeil.SIG.endung(), ManifestTeil.SIG.pfad(datum),
                                z.getString(6), z.getString(5)));
                        dateien.addAll(stempel.getOrDefault(datum, List.of()));
                    }
                }
            }
            return dateien;
        } catch (SQLException e) {
            throw new IllegalStateException("Manifeste des Exports nicht gelesen", e);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Prüfdaten eines Manifests nicht lesbar", e);
        }
    }

    private String schema() {
        String schema = aufloesung.schema();
        if (!SCHEMA.matcher(schema).matches()) {
            throw new IllegalStateException("Export ohne Mandanten-Schema");
        }
        return schema;
    }

    private static String version(String version, String schluessel) {
        if (version == null || version.isBlank()) {
            throw new IllegalStateException("Archivierte Datei ohne Versions-ID: " + schluessel);
        }
        return version;
    }

    /** Endung aus dem Schlüssel, z. B. {@code archiv/2026/<id>/1-original.jpg} → {@code jpg}. */
    static String endung(String schluessel) {
        int punkt = schluessel.lastIndexOf('.');
        return punkt < 0 || punkt < schluessel.lastIndexOf('/') ? "bin" : schluessel.substring(punkt + 1);
    }

    private static Instant zeit(Timestamp zeit) {
        return zeit == null ? null : zeit.toInstant();
    }
}
