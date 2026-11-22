package ch.urinet.archiv.mandant.adapter.out.persistence;

import ch.urinet.archiv.mandant.application.MandantAufloesung;
import ch.urinet.archiv.mandant.application.MandantVollexportKonfiguration;
import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.regex.Pattern;

/** Liest die unveränderlichen Konfigurationsabbilder des aktuellen Mandantenschemas. */
@ApplicationScoped
public class JdbcMandantVollexportKonfiguration implements MandantVollexportKonfiguration {

    private static final int BLOCK_ZEICHEN = 16_384;
    public static final int MAX_KONFIGURATION_BYTES = 1024 * 1024;
    private static final Pattern SCHEMA = Pattern.compile("^m_[a-z0-9_]+$");
    private final Instance<AgroalDataSource> datenquelle;
    private final MandantAufloesung aufloesung;

    public JdbcMandantVollexportKonfiguration(Instance<AgroalDataSource> datenquelle, MandantAufloesung aufloesung) {
        this.datenquelle = datenquelle;
        this.aufloesung = aufloesung;
    }

    @Override
    public Version aktuell() {
        String schema = schema();
        String sql = """
                with stand as materialized (
                    select version, inhalt from %s.konfiguration_version order by version desc limit 1
                ), datei as materialized (
                    select s.version, jsonb_build_object(
                        'format', 1, 'basisVersion', coalesce(s.inhalt->'version', '0'::jsonb),
                        'einstellungen', case when s.inhalt->'einstellungen' is null
                                or s.inhalt->'einstellungen' = 'null'::jsonb then 'null'::jsonb
                            else jsonb_build_object(
                                'geschaeftsjahrEnde', s.inhalt #> '{einstellungen,geschaeftsjahrEnde}',
                                'kennungen', jsonb_build_object(
                                    'uid', s.inhalt #> '{einstellungen,kennungen,uid}',
                                    'ibans', coalesce(nullif(s.inhalt #> '{einstellungen,kennungen,ibans}',
                                        'null'::jsonb), '[]'::jsonb)),
                                'branding', jsonb_build_object(
                                    'name', s.inhalt #> '{einstellungen,branding,name}',
                                    'primaerfarbe', s.inhalt #> '{einstellungen,branding,primaerfarbe}'),
                                'chat', jsonb_build_object(
                                    'aufbewahrungMonate', s.inhalt #> '{einstellungen,chat,aufbewahrungMonate}'),
                                'webseitenChat', jsonb_build_object(
                                    'aufbewahrungTage', s.inhalt #> '{einstellungen,webseitenChat,aufbewahrungTage}'),
                                'wissen', jsonb_build_object(
                                    'budgetTokens', s.inhalt #> '{einstellungen,wissen,budgetTokens}',
                                    'lintBudgetTokens', s.inhalt #> '{einstellungen,wissen,lintBudgetTokens}',
                                    'monatsbudgetTokens', s.inhalt #> '{einstellungen,wissen,monatsbudgetTokens}',
                                    'lintZeitplan', jsonb_build_object(
                                        'wochentag', s.inhalt #> '{einstellungen,wissen,lintZeitplan,wochentag}',
                                        'stunde', s.inhalt #> '{einstellungen,wissen,lintZeitplan,stunde}')))
                            end,
                        'kiTexte', jsonb_build_object('rolle', t.rolle, 'feldregeln', t.feldregeln,
                            'kiFreigegeben', t.ki_freigegeben),
                        'klassifikationen', coalesce((
                            select jsonb_agg(
                                jsonb_build_object('code', k->'code', 'name', k->'name',
                                    'beschreibung', k->'beschreibung',
                                    'werte', coalesce((
                                        select jsonb_agg(jsonb_build_object('code', w->'code', 'name', w->'name',
                                            'beschreibung', w->'beschreibung',
                                            'reihenfolge', coalesce(nullif(w->'reihenfolge', 'null'::jsonb), '0'::jsonb),
                                            'aktiv', coalesce(nullif(w->'aktiv', 'null'::jsonb), 'false'::jsonb))
                                            order by ord)
                                        from jsonb_array_elements(coalesce(nullif(k->'werte', 'null'::jsonb),
                                            '[]'::jsonb)) with ordinality as werte(w, ord)), '[]'::jsonb),
                                    'mehrfach', coalesce(nullif(k->'mehrfach', 'null'::jsonb), 'false'::jsonb),
                                    'kiVorschlag', coalesce(nullif(k->'kiVorschlag', 'null'::jsonb), 'false'::jsonb),
                                    'suchfilter', coalesce(nullif(k->'suchfilter', 'null'::jsonb), 'false'::jsonb),
                                    'oeffentlichFilter', coalesce(nullif(k->'oeffentlichFilter', 'null'::jsonb),
                                        'false'::jsonb),
                                    'aktiv', coalesce(nullif(k->'aktiv', 'null'::jsonb), 'false'::jsonb))
                                order by k->>'code' collate "C" nulls first)
                            from jsonb_array_elements(coalesce(nullif(s.inhalt->'klassifikationen', 'null'::jsonb),
                                '[]'::jsonb)) k), '[]'::jsonb),
                        'arten', coalesce((
                            select jsonb_agg(
                                jsonb_build_object(
                                    'code', a->'code', 'name', a->'name', 'beschreibung', a->'beschreibung',
                                    'gruppen', coalesce(nullif(a->'gruppen', 'null'::jsonb), '[]'::jsonb),
                                    'pflichtfelder', coalesce(nullif(a->'pflichtfelder', 'null'::jsonb), '[]'::jsonb),
                                    'zusatzSchema', case
                                        when jsonb_typeof(a->'zusatzSchema') = 'object' then a->'zusatzSchema'
                                        when jsonb_typeof(a->'zusatzSchema') = 'string'
                                            and btrim(a->>'zusatzSchema') <> '' then (a->>'zusatzSchema')::jsonb
                                        else '{"additionalProperties":false,"properties":{},"type":"object"}'::jsonb
                                        end,
                                    'vertraulichkeit', a->'vertraulichkeit',
                                    'aufbewahrung', case when a->'aufbewahrung' is null
                                            or a->'aufbewahrung' = 'null'::jsonb then 'null'::jsonb
                                        else jsonb_build_object('jahre', a #> '{aufbewahrung,jahre}',
                                            'ab', a #> '{aufbewahrung,ab}', 'feld', a #> '{aufbewahrung,feld}')
                                        end,
                                    'gesetzlichePflicht', coalesce(nullif(a->'gesetzlichePflicht', 'null'::jsonb),
                                        'false'::jsonb),
                                    'aktiv', coalesce(nullif(a->'aktiv', 'null'::jsonb), 'false'::jsonb),
                                    'selbstAusgestellt', coalesce(nullif(a->'selbstAusgestellt', 'null'::jsonb),
                                        'false'::jsonb),
                                    'belegnummerMuster', coalesce(nullif(a->'belegnummerMuster', 'null'::jsonb),
                                        '[]'::jsonb),
                                    'fremdbelegArt', a->'fremdbelegArt',
                                    'ohneZahlungsdatenArt', a->'ohneZahlungsdatenArt',
                                    'kiVorschlag', coalesce(nullif(a->'kiVorschlag', 'null'::jsonb), 'false'::jsonb),
                                    'kiFelder', coalesce(nullif(a->'kiFelder', 'null'::jsonb), '[]'::jsonb),
                                    'kiHinweise', a->'kiHinweise',
                                    'reihenfolge', coalesce(nullif(a->'reihenfolge', 'null'::jsonb), '0'::jsonb),
                                    'klassifikationen', coalesce((
                                        select jsonb_agg(jsonb_build_object('code', z->'klassifikation',
                                            'pflicht', coalesce(nullif(z->'pflicht', 'null'::jsonb), 'false'::jsonb),
                                            'standardWert', z->'standardWert') order by ord)
                                        from jsonb_array_elements(coalesce(
                                            nullif(a->'klassifikationen', 'null'::jsonb), '[]'::jsonb))
                                            with ordinality as zuordnung(z, ord)), '[]'::jsonb))
                                order by a->>'code' collate "C")
                            from jsonb_array_elements(coalesce(nullif(s.inhalt->'arten', 'null'::jsonb),
                                '[]'::jsonb)) a), '[]'::jsonb),
                        'regeln', coalesce((
                            select jsonb_agg(jsonb_build_object(
                                'code', r->'code', 'art', r->'art', 'typ', r->'typ', 'parameter', r->'parameter',
                                'schwere', r->'schwere', 'meldung', r->'meldung',
                                'aktiv', coalesce(nullif(r->'aktiv', 'null'::jsonb), 'false'::jsonb))
                                order by r->>'code' collate "C" nulls first)
                            from jsonb_array_elements(coalesce(nullif(s.inhalt->'regeln', 'null'::jsonb),
                                '[]'::jsonb)) r), '[]'::jsonb)
                    )::text as json
                    from stand s join %s.ki_texte t on t.id = 1
                )
                select version, octet_length(json),
                    case when octet_length(json) <= ? then json end from datei
                """.formatted(schema, schema);
        try (Connection verbindung = datenquelle.get().getConnection();
             PreparedStatement abfrage = verbindung.prepareStatement(sql)) {
            abfrage.setQueryTimeout(300);
            abfrage.setInt(1, MAX_KONFIGURATION_BYTES);
            try (ResultSet zeile = abfrage.executeQuery()) {
                if (!zeile.next()) {
                    throw new IllegalStateException("Aktuelle Konfiguration oder KI-Texte fehlen");
                }
                if (zeile.getLong(2) > MAX_KONFIGURATION_BYTES) {
                    throw new ZuGross();
                }
                return new Version(zeile.getInt(1), zeile.getString(3));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Aktuelle Konfiguration für Vollexport nicht gelesen", e);
        }
    }

    @Override
    public void versionen(Versionsausgabe ausgabe) throws IOException {
        String schema = schema();
        String versionSql = "select version from " + schema + ".konfiguration_version "
                + "where version > ? order by version limit 1";
        String blockSql = "select substring(inhalt::text from ? for ?) from " + schema
                + ".konfiguration_version where version = ?";
        try (Connection verbindung = datenquelle.get().getConnection();
             PreparedStatement versionen = verbindung.prepareStatement(versionSql);
             PreparedStatement block = verbindung.prepareStatement(blockSql)) {
            versionen.setQueryTimeout(300);
            block.setQueryTimeout(300);
            int nach = 0;
            while (true) {
                versionen.setInt(1, nach);
                int nummer;
                try (ResultSet zeile = versionen.executeQuery()) {
                    if (!zeile.next()) {
                        return;
                    }

                    nummer = zeile.getInt(1);
                }
                ausgabe.schreiben(nummer, ziel -> abbildSchreiben(block, nummer, ziel));
                nach = nummer;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Konfigurationsabbilder für Vollexport nicht gelesen", e);
        }
    }

    private String schema() {
        String schema = aufloesung.schema();
        if (!SCHEMA.matcher(schema).matches()) {
            throw new IllegalStateException("Vollexport ohne Mandanten-Schema");
        }
        return schema;
    }

    private static void abbildSchreiben(PreparedStatement abfrage, int version, OutputStream ziel) throws IOException {
        try {
            int position = 1;
            while (true) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new IOException("Konfigurationsexport unterbrochen");
                }
                abfrage.setInt(1, position);
                abfrage.setInt(2, BLOCK_ZEICHEN);
                abfrage.setInt(3, version);
                try (ResultSet zeile = abfrage.executeQuery()) {
                    if (!zeile.next()) {
                        throw new IllegalStateException("Konfigurationsversion fehlt beim Export");
                    }
                    String text = zeile.getString(1);
                    int laenge = text.codePointCount(0, text.length());
                    ziel.write(text.getBytes(StandardCharsets.UTF_8));
                    if (laenge < BLOCK_ZEICHEN) {
                        return;
                    }
                    position += laenge;
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Konfigurationsversion nicht gelesen", e);
        }
    }
}
