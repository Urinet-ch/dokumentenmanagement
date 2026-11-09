package ch.urinet.archiv.mandant;

import io.cucumber.java.de.Dann;
import io.cucumber.java.de.Und;
import org.eclipse.microprofile.config.ConfigProvider;

import static org.assertj.core.api.Assertions.assertThat;

/** Schritte zu Req 1.7: Schema nur über Liquibase, Hibernate prüft nur. */
public class SchemaSchritte {

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
}
