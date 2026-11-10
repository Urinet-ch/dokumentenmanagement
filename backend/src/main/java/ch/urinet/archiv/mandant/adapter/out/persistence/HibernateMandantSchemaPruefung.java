package ch.urinet.archiv.mandant.adapter.out.persistence;

import ch.urinet.archiv.mandant.application.MandantFehler;
import ch.urinet.archiv.mandant.application.MandantSchemaPruefung;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import org.hibernate.SessionFactory;
import org.hibernate.tool.schema.spi.SchemaManagementException;

/** Prüft ein Mandanten-Schema mit dem {@code SchemaManager} von Hibernate gegen das Mapping (Req 1.7). */
@ApplicationScoped
public class HibernateMandantSchemaPruefung implements MandantSchemaPruefung {

    // lazy: ohne Datasource ist die SessionFactory inaktiv, und die Prüfung läuft dann nie
    private final Instance<SessionFactory> sessionFactory;

    public HibernateMandantSchemaPruefung(Instance<SessionFactory> sessionFactory) {
        this.sessionFactory = sessionFactory;
    }

    @Override
    public void pruefen(String schema) {
        try {
            MandantSchemaFilter.mitMandantenSchema(schema,
                    () -> sessionFactory.get().getSchemaManager().forSchema(schema).validateMappedObjects());
        } catch (SchemaManagementException e) {
            throw new MandantFehler(MandantFehler.Grund.SCHEMA_ABWEICHUNG,
                    "Schema " + schema + " weicht vom Mapping ab: " + e.getMessage(), e);
        }
    }
}
