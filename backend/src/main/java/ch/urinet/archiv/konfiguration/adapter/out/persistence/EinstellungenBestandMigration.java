package ch.urinet.archiv.konfiguration.adapter.out.persistence;

import ch.urinet.archiv.konfiguration.domain.Einstellungskatalog;
import ch.urinet.archiv.konfiguration.domain.Konfiguration;
import ch.urinet.archiv.konfiguration.domain.Wissenszeitplan;
import ch.urinet.archiv.mandant.application.BrandingVerzeichnis;
import ch.urinet.archiv.mandant.domain.MandantCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.arc.Arc;
import liquibase.change.custom.CustomTaskChange;
import liquibase.database.Database;
import liquibase.database.jvm.JdbcConnection;
import liquibase.exception.CustomChangeException;
import liquibase.exception.ValidationErrors;
import liquibase.resource.ResourceAccessor;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.jboss.logging.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Period;

/** Übernimmt die bisher wirksamen Eigenschaften beim erstmaligen Upgrade bestehender Mandanten. */
public class EinstellungenBestandMigration implements CustomTaskChange {
    private static final Logger LOG = Logger.getLogger(EinstellungenBestandMigration.class);

    @Override
    public void execute(Database database) throws CustomChangeException {
        Connection verbindung = ((JdbcConnection) database.getConnection()).getWrappedConnection();
        try (PreparedStatement mandantLesen = verbindung.prepareStatement(
                "select code from public.mandant where schema_name = current_schema()");
             ResultSet register = mandantLesen.executeQuery()) {
            // Bei einem neuen Mandanten wird das Schema vor dem Registereintrag angelegt.
            if (!register.next()) {
                return;
            }
            MandantCode mandant = new MandantCode(register.getString(1));
            if (!mandant.schema().equals(database.getDefaultSchemaName())) {
                throw new CustomChangeException("Ungültiges Mandantenschema bei der Einstellungsmigration");
            }
            Config config = ConfigProvider.getConfig();
            Period frist = Period.parse(config.getValue("archiv.chat.aufbewahrung", String.class));
            if (frist.isNegative() || frist.isZero()) {
                throw new CustomChangeException("Ungültige bisherige Chat-Aufbewahrung");
            }
            Integer chat = darstellbareMonate(frist);
            if (chat == null) {
                LOG.warnf("Mandant %s: Chat-Aufbewahrung bleibt als bisherige Eigenschaft geerbt", mandant.wert());
            }
            String standardTage = config.getValue("archiv.webseite.chat.aufbewahrung-tage", String.class);
            int tage = Integer.parseInt(config.getOptionalValue(
                    "archiv.mandant." + mandant.wert() + ".chat-aufbewahrung-tage", String.class)
                    .orElse(standardTage).strip());
            if (tage < 1 || tage > 365) {
                throw new CustomChangeException("Ungültige bisherige Webseiten-Chat-Aufbewahrung");
            }
            var branding = Arc.container().instance(BrandingVerzeichnis.class).get()
                    .fuerMandant(mandant).orElse(ch.urinet.archiv.mandant.domain.Branding.STANDARD);
            String name = Einstellungskatalog.brandingNameGueltig(branding.name()) ? branding.name() : null;
            String farbe = Einstellungskatalog.primaerfarbeGueltig(branding.primaerfarbe())
                    ? branding.primaerfarbe() : null;
            if (name == null || farbe == null) {
                LOG.warnf("Mandant %s: Nicht darstellbares Branding bleibt aus der Eigenschaft geerbt", mandant.wert());
            }
            Integer budget = startwert(config, "archiv.wissen.budget-tokens", 600000,
                    Einstellungskatalog.Zahl.WISSEN_BUDGET);
            Integer lintBudget = startwert(config, "archiv.wissen.lint-budget-tokens", 100000,
                    Einstellungskatalog.Zahl.WISSEN_LINT_BUDGET);
            Integer monatsbudget = startwert(config, "archiv.wissen.monatsbudget-tokens", 1000000,
                    Einstellungskatalog.Zahl.WISSEN_MONATSBUDGET);
            String cron = config.getOptionalValue("archiv.wissen.lint-zeitplan", String.class)
                    .orElse("0 0 4 ? * SUN");
            Konfiguration.LintZeitplan plan = Wissenszeitplan.lesen(cron).orElse(null);
            if (plan == null) {
                LOG.warnf("Mandant %s: Wissenszeitplan bleibt als bisherige Eigenschaft geerbt", mandant.wert());
            }
            uebernehmen(verbindung, name, farbe, chat, tage, budget, lintBudget, monatsbudget, plan, cron);
        } catch (SQLException | IllegalArgumentException e) {
            throw new CustomChangeException("Migration der bisherigen Einstellungen fehlgeschlagen", e);
        }
    }

    static Integer darstellbareMonate(Period frist) {
        long monate = frist.toTotalMonths();
        return frist.getDays() == 0 && monate >= 1 && monate <= 60 ? (int) monate : null;
    }

    private static Integer startwert(Config config, String schluessel, int standard, Einstellungskatalog.Zahl grenze) {
        long wert = config.getOptionalValue(schluessel, Long.class).orElse((long) standard);
        if (wert < 1) {
            throw new IllegalArgumentException("Ungültiger Wissens-Startwert: " + schluessel);
        }
        if (wert > Integer.MAX_VALUE || !grenze.gueltig((int) wert)) {
            LOG.warnf("Nicht darstellbarer Wissens-Startwert bleibt geerbt: %s", schluessel);
            return null;
        }
        return (int) wert;
    }

    private void uebernehmen(Connection verbindung, String name, String farbe, Integer monate, int tage,
                             Integer budget, Integer lintBudget, Integer monatsbudget,
                             Konfiguration.LintZeitplan plan, String cron)
            throws SQLException, CustomChangeException {
        String sql = """
                with vorher as (
                    select version, inhalt from konfiguration_version order by version desc limit 1
                ), abbild as (
                    select version + 1 as nummer,
                        jsonb_set(inhalt, '{einstellungen}',
                            inhalt -> 'einstellungen' || jsonb_build_object(
                                'branding', jsonb_strip_nulls(jsonb_build_object('name', ?::text, 'primaerfarbe', ?::text))
                                    || case when jsonb_typeof(inhalt #> '{einstellungen,branding}') = 'object'
                                        then jsonb_strip_nulls(inhalt #> '{einstellungen,branding}') else '{}'::jsonb end,
                                'chat', jsonb_strip_nulls(jsonb_build_object('aufbewahrungMonate', ?::int))
                                    || case when jsonb_typeof(inhalt #> '{einstellungen,chat}') = 'object'
                                        then jsonb_strip_nulls(inhalt #> '{einstellungen,chat}') else '{}'::jsonb end,
                                'webseitenChat', jsonb_build_object('aufbewahrungTage', ?::int)
                                    || case when jsonb_typeof(inhalt #> '{einstellungen,webseitenChat}') = 'object'
                                        then jsonb_strip_nulls(inhalt #> '{einstellungen,webseitenChat}') else '{}'::jsonb end,
                                'wissen', jsonb_strip_nulls(jsonb_build_object('budgetTokens', ?::int,
                                        'lintBudgetTokens', ?::int, 'monatsbudgetTokens', ?::int))
                                    || case when jsonb_typeof(inhalt #> '{einstellungen,wissen}') = 'object'
                                        then jsonb_strip_nulls(inhalt #> '{einstellungen,wissen}') - 'lintZeitplan'
                                        else '{}'::jsonb end
                                    || jsonb_build_object('lintZeitplan',
                                        jsonb_strip_nulls(jsonb_build_object('wochentag', ?::text, 'stunde', ?::int))
                                        || case when jsonb_typeof(inhalt #> '{einstellungen,wissen,lintZeitplan}') = 'object'
                                            then jsonb_strip_nulls(inhalt #> '{einstellungen,wissen,lintZeitplan}')
                                            else '{}'::jsonb end)
                            )) as inhalt from vorher
                ), neu as (
                    select nummer, jsonb_set(inhalt, '{version}', to_jsonb(nummer)) as inhalt from abbild
                )
                insert into konfiguration_version (version, wer, grund, quelle, inhalt, sha256)
                select nummer, 'system:migration', 'Übernahme bisheriger Einstellungen', 'MIGRATION', inhalt,
                    encode(sha256(convert_to(inhalt::text, 'UTF8')), 'hex') from neu
                """;
        try (PreparedStatement einfuegen = verbindung.prepareStatement(sql)) {
            einfuegen.setString(1, name);
            einfuegen.setString(2, farbe);
            if (monate == null) {
                einfuegen.setNull(3, Types.INTEGER);
            } else {
                einfuegen.setInt(3, monate);
            }
            einfuegen.setInt(4, tage);
            einfuegen.setObject(5, budget, Types.INTEGER);
            einfuegen.setObject(6, lintBudget, Types.INTEGER);
            einfuegen.setObject(7, monatsbudget, Types.INTEGER);
            einfuegen.setString(8, plan == null ? null : plan.wochentag().name());
            einfuegen.setObject(9, plan == null ? null : plan.stunde(), Types.INTEGER);
            if (einfuegen.executeUpdate() != 1) {
                throw new CustomChangeException("Konfigurationsversion für Einstellungsmigration fehlt");
            }
        }
        wissenSynchronisieren(verbindung, cron);
        try (PreparedStatement protokoll = verbindung.prepareStatement("""
                insert into zugriff_protokoll (benutzer, aktion, details)
                select 'system:migration', 'KONFIGURATION_AENDERN',
                    jsonb_build_object('grund', 'Übernahme bisheriger Einstellungen',
                        'konfiguration', jsonb_build_object('version',
                            neu.version, 'quelle', 'MIGRATION',
                            'aenderungen', coalesce((
                                select jsonb_agg(jsonb_build_object('pfad', 'einstellungen.' || p.name,
                                    'alt', null, 'neu', null, 'ohneWerte', true))
                                from (values
                                    ('branding.name', array['einstellungen','branding','name']),
                                    ('branding.primaerfarbe', array['einstellungen','branding','primaerfarbe']),
                                    ('chat.aufbewahrungMonate', array['einstellungen','chat','aufbewahrungMonate']),
                                    ('webseitenChat.aufbewahrungTage', array['einstellungen','webseitenChat','aufbewahrungTage']),
                                    ('wissen.budgetTokens', array['einstellungen','wissen','budgetTokens']),
                                    ('wissen.lintBudgetTokens', array['einstellungen','wissen','lintBudgetTokens']),
                                    ('wissen.monatsbudgetTokens', array['einstellungen','wissen','monatsbudgetTokens']),
                                    ('wissen.lintZeitplan', array['einstellungen','wissen','lintZeitplan'])
                                ) as p(name, pfad)
                                where vorher.inhalt #> p.pfad is distinct from neu.inhalt #> p.pfad
                            ), '[]'::jsonb)))
                from konfiguration_version neu
                join konfiguration_version vorher on vorher.version = neu.version - 1
                where neu.version = (select max(version) from konfiguration_version)
                """)) {
            if (protokoll.executeUpdate() != 1) {
                throw new CustomChangeException("Einstellungsmigration konnte nicht protokolliert werden");
            }
        }
    }

    private void wissenSynchronisieren(Connection verbindung, String cron) throws SQLException, CustomChangeException {
        try (PreparedStatement lesen = verbindung.prepareStatement(
                "select inhalt::text from konfiguration_version order by version desc limit 1");
             ResultSet zeile = lesen.executeQuery()) {
            if (!zeile.next()) {
                throw new CustomChangeException("Wissenseinstellungen der Migration fehlen");
            }
            var repository = new PanacheKonfigurationVersionsRepository(Arc.container().instance(ObjectMapper.class).get());
            var wissen = repository.lesen(zeile.getString(1)).einstellungen().wissen();
            try (PreparedStatement schreiben = verbindung.prepareStatement("""
                    insert into wissens_einstellung
                        (id, budget_tokens, lint_budget_tokens, monatsbudget_tokens, lint_zeitplan)
                    values (1, ?, ?, ?, ?)
                    on conflict (id) do update set budget_tokens = excluded.budget_tokens,
                        lint_budget_tokens = excluded.lint_budget_tokens,
                        monatsbudget_tokens = excluded.monatsbudget_tokens, lint_zeitplan = excluded.lint_zeitplan
                    """)) {
                schreiben.setObject(1, wissen.budgetTokens(), Types.INTEGER);
                schreiben.setObject(2, wissen.lintBudgetTokens(), Types.INTEGER);
                schreiben.setObject(3, wissen.monatsbudgetTokens(), Types.INTEGER);
                schreiben.setString(4, Wissenszeitplan.cron(wissen.lintZeitplan(), cron));
                schreiben.executeUpdate();
            }
        } catch (JsonProcessingException e) {
            throw new CustomChangeException("Wissenseinstellungen der Migration nicht lesbar", e);
        }
    }

    @Override
    public String getConfirmationMessage() {
        return "Bisherige Mandanteneinstellungen übernommen";
    }

    @Override
    public void setUp() {
    }

    @Override
    public void setFileOpener(ResourceAccessor resourceAccessor) {
    }

    @Override
    public ValidationErrors validate(Database database) {
        return new ValidationErrors();
    }
}
