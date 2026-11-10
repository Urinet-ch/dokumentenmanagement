package ch.urinet.archiv.mandant;

import io.agroal.api.AgroalDataSource;
import io.cucumber.java.de.Dann;
import io.cucumber.java.de.Und;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Table;
import org.eclipse.microprofile.config.ConfigProvider;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Schritte zu Req 1.7: Schema nur über Liquibase, Hibernate prüft nur. */
public class SchemaSchritte {

    @Inject
    Instance<AgroalDataSource> datenbank;

    // lazy, weil Tests ohne Datasource die Persistence Unit abschalten
    @Inject
    Instance<EntityManager> entityManager;

    @Dann("ist die Schema-Strategie von Hibernate «{word}»")
    public void schemaStrategie(String strategie) {
        assertThat(ConfigProvider.getConfig()
                .getValue("quarkus.hibernate-orm.schema-management.strategy", String.class)).isEqualTo(strategie);
    }

    @Und("Liquibase verwendet den Changelog «{word}»")
    public void liquibaseChangelog(String changelog) {
        assertThat(ConfigProvider.getConfig().getValue("quarkus.liquibase.change-log", String.class)).isEqualTo(changelog);
        liegtImArtefakt(changelog);
    }

    @Und("der Changelog «{word}» liegt im Artefakt")
    public void liegtImArtefakt(String changelog) {
        assertThat(Thread.currentThread().getContextClassLoader().getResource(changelog)).isNotNull();
    }

    @Dann("hat Liquibase im Schema «{word}» die Changesets «{word}», «{word}» und «{word}» ausgeführt")
    public void changesets(String schema, String a, String b, String c) throws SQLException {
        assertThat(abfragen("select id from " + schema + ".databasechangelog where exectype = 'EXECUTED'"))
                .contains(a, b, c);
    }

    @Und("das Schema «{word}» enthält nur die Tabellen «{word}», «{word}», «{word}», «{word}» und «{word}»")
    public void nurTabellen(String schema, String a, String b, String c, String d, String e) throws SQLException {
        assertThat(abfragen("select table_name from information_schema.tables where table_schema = ?", schema))
                .containsExactlyInAnyOrder(a, b, c, d, e);
    }

    @Und("das Schema «{word}» enthält keine Sequenz ausser «{word}»")
    public void sequenzen(String schema, String erlaubt) throws SQLException {
        assertThat(abfragen("select c.relname from pg_class c join pg_namespace n on n.oid = c.relnamespace"
                + " where c.relkind = 'S' and n.nspname = ?", schema))
                .containsOnly(erlaubt);
    }

    @Und("Hibernate bildet die Entität «{word}» auf die Tabelle «{word}» ab")
    public void entitaet(String entitaet, String tabelle) {
        var typ = entityManager.get().getMetamodel().getEntities().stream()
                .filter(e -> e.getName().equals(entitaet)).findFirst().orElseThrow();
        assertThat(typ.getJavaType().getAnnotation(Table.class).name()).isEqualTo(tabelle);
    }

    private List<String> abfragen(String sql, String... parameter) throws SQLException {
        try (Connection verbindung = datenbank.get().getConnection(); PreparedStatement abfrage = verbindung.prepareStatement(sql)) {
            for (int i = 0; i < parameter.length; i++) {
                abfrage.setString(i + 1, parameter[i]);
            }
            List<String> werte = new ArrayList<>();
            try (ResultSet ergebnis = abfrage.executeQuery()) {
                while (ergebnis.next()) {
                    werte.add(ergebnis.getString(1));
                }
            }
            return werte;
        }
    }
}
