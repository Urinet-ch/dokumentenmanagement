package ch.urinet.archiv.mandant.adapter.out.persistence;

import ch.urinet.archiv.export.application.ExportBelege;
import ch.urinet.archiv.mandant.application.MandantAufloesung;
import ch.urinet.archiv.mandant.application.MandantVollexportDaten;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/** Leseadapter für alle Dokumentmetadaten und Dateien des Mandanten. */
@ApplicationScoped
public class JdbcMandantVollexportDaten implements MandantVollexportDaten {

    private static final int ZEITGRENZE_S = 30;
    private static final Pattern SCHEMA = Pattern.compile("^m_[a-z0-9_]+$");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String METADATEN = """
            jsonb_build_object(
                'dokument', to_jsonb(d),
                'finanz', (select to_jsonb(f) from %s.dokument_finanz f where f.dokument_id = d.id),
                'vertrag', (select to_jsonb(v) from %s.dokument_vertrag v where v.dokument_id = d.id),
                'dateien', coalesce((select jsonb_agg(to_jsonb(x) || jsonb_build_object('zip_pfad',
                    'dateien/' || d.id || '/' || x.rolle || '-' || x.nr || '.bin') order by x.rolle, x.nr)
                    from %s.dokument_datei x where x.dokument_id = d.id), '[]'::jsonb),
                'versionen', coalesce((select jsonb_agg(to_jsonb(v) order by v.version, v.id)
                    from %s.dokument_version v where v.dokument_id = d.id), '[]'::jsonb)
            )::text
            """;

    private final Instance<AgroalDataSource> datenquelle;
    private final MandantAufloesung aufloesung;
    private final ExportBelege exportBelege;

    public JdbcMandantVollexportDaten(Instance<AgroalDataSource> datenquelle, MandantAufloesung aufloesung,
                                      ExportBelege exportBelege) {
        this.datenquelle = datenquelle;
        this.aufloesung = aufloesung;
        this.exportBelege = exportBelege;
    }

    @Override
    public List<Dokument> seite(Optional<UUID> nach, int anzahl) {
        if (anzahl < 1 || anzahl > 1000) {
            throw new IllegalArgumentException("Seitengrösse muss zwischen 1 und 1000 liegen");
        }
        String s = schema();
        String metadaten = METADATEN.formatted(s, s, s, s);
        String sql = "select d.id, d.status, d.art, " + metadaten + " from " + s
                + ".dokument d where d.id > ? order by d.id limit ?";
        try (Connection verbindung = datenquelle.get().getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement(sql)) {
            abfrage.setQueryTimeout(ZEITGRENZE_S);
            abfrage.setObject(1, nach.orElse(new UUID(0L, 0L)), Types.OTHER);
            abfrage.setInt(2, anzahl);
            List<Dokument> dokumente = new ArrayList<>();
            try (ResultSet zeile = abfrage.executeQuery()) {
                while (zeile.next()) {
                    UUID id = zeile.getObject(1, UUID.class);
                    String json = zeile.getString(4);
                    dokumente.add(new Dokument(id, zeile.getString(2), zeile.getString(3), json,
                            dateien(id, json)));
                }
            }
            return dokumente;
        } catch (SQLException e) {
            throw new IllegalStateException("Mandantendaten für Vollexport nicht gelesen", e);
        }
    }

    private static List<Datei> dateien(UUID id, String json) {
        try {
            JsonNode root = JSON.readTree(json);
            List<Datei> dateien = new ArrayList<>();
            for (JsonNode datei : root.path("dateien")) {
                fuegeDateiHinzu(dateien, datei, "dateien/" + id + "/" + datei.path("rolle").asText()
                        + "-" + datei.path("nr").asInt() + ".bin");
            }
            JsonNode dokument = root.path("dokument");
            JsonNode pdfa = dokument.path("s3_pdfa");
            JsonNode pdfaVersion = dokument.path("s3_version_pdfa");
            if (!pdfa.isMissingNode() && !pdfa.isNull() || !pdfaVersion.isMissingNode() && !pdfaVersion.isNull()) {
                if (pdfa.isMissingNode() || pdfa.isNull() || pdfaVersion.isMissingNode() || pdfaVersion.isNull()) {
                    throw new IllegalStateException("PDF/A-Datei ohne vollständige Ablageangaben");
                }
                String sha256 = text(dokument.path("sha256_pdfa"));
                if (sha256 == null) {
                    throw new IllegalStateException("PDF/A-Datei ohne Prüfsumme");
                }
                dateien.add(new Datei(pdfa.asText(), pdfaVersion.asText(), sha256, "dateien/" + id + "/pdfa.pdf"));
            }
            return List.copyOf(dateien);
        } catch (IOException e) {
            throw new IllegalStateException("Dokumentmetadaten für Vollexport nicht lesbar", e);
        }
    }

    private static void fuegeDateiHinzu(List<Datei> dateien, JsonNode datei, String zipPfad) {
        JsonNode key = datei.path("s3_schluessel");
        JsonNode version = datei.path("s3_version");
        boolean keyVorhanden = !key.isMissingNode() && !key.isNull();
        boolean versionVorhanden = !version.isMissingNode() && !version.isNull();
        if (keyVorhanden != versionVorhanden) {
            throw new IllegalStateException("Dokumentdatei ohne vollständige Ablageangaben");
        }
        if (keyVorhanden) {
            String sha256 = text(datei.path("sha256"));
            if (sha256 == null) {
                throw new IllegalStateException("Dokumentdatei ohne Prüfsumme");
            }
            dateien.add(new Datei(key.asText(), version.asText(), sha256, zipPfad));
        }
    }

    private static String text(JsonNode wert) {
        return wert.isMissingNode() || wert.isNull() ? null : wert.asText();
    }

    @Override
    public List<Manifestdatei> manifeste() {
        List<Manifestdatei> dateien = exportBelege.manifeste(LocalDate.of(1, 1, 1),
                LocalDate.of(9999, 12, 31)).stream()
                .map(m -> new Manifestdatei(m.datum(), m.endung(), m.schluessel(), m.versionId(), m.sha256()))
                .toList();
        return dateien;
    }

    private String schema() {
        String schema = aufloesung.schema();
        if (!SCHEMA.matcher(schema).matches()) {
            throw new IllegalStateException("Vollexport ohne Mandanten-Schema");
        }
        return schema;
    }
}
