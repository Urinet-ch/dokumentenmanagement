package ch.urinet.archiv.mandant.adapter.out.persistence;

import org.hibernate.boot.model.relational.Namespace;
import org.hibernate.boot.model.relational.Sequence;
import org.hibernate.mapping.Table;
import org.hibernate.tool.schema.spi.SchemaFilter;
import org.hibernate.tool.schema.spi.SchemaFilterProvider;

/**
 * Trennt die Prüfung des Mappings ({@code strategy=validate}, Req 1.7) nach Schema: Beim Start prüft Hibernate nur die
 * ausdrücklich qualifizierten Tabellen in {@code public} (Session ohne Mandant); die unqualifizierten
 * Mandanten-Tabellen prüft {@link HibernateMandantSchemaPruefung} je Mandanten-Schema nach dessen Migration.
 * Hibernate erzeugt die Klasse selbst (Einstellung {@code hibernate.hbm2ddl.schema_filter_provider}), darum der
 * Schalter als ThreadLocal.
 */
public class MandantSchemaFilter implements SchemaFilterProvider {

    private static final ThreadLocal<String> MANDANTEN_SCHEMA = new ThreadLocal<>();

    static void mitMandantenSchema(String schema, Runnable pruefung) {
        MANDANTEN_SCHEMA.set(schema);
        try {
            pruefung.run();
        } finally {
            MANDANTEN_SCHEMA.remove();
        }
    }

    static boolean einbeziehen(String namespaceSchema, String mandantenSchema) {
        if (mandantenSchema == null) {
            return namespaceSchema != null;
        }
        return namespaceSchema == null || namespaceSchema.equalsIgnoreCase(mandantenSchema);
    }

    private static final SchemaFilter PRUEFUNG = new SchemaFilter() {
        @Override
        public boolean includeNamespace(Namespace namespace) {
            var schema = namespace.getName().schema();
            return einbeziehen(schema == null ? null : schema.getText(), MANDANTEN_SCHEMA.get());
        }

        @Override
        public boolean includeTable(Table table) {
            return true;
        }

        @Override
        public boolean includeSequence(Sequence sequence) {
            return true;
        }
    };

    @Override
    public SchemaFilter getValidateFilter() {
        return PRUEFUNG;
    }

    @Override
    public SchemaFilter getCreateFilter() {
        return SchemaFilter.ALL;
    }

    @Override
    public SchemaFilter getDropFilter() {
        return SchemaFilter.ALL;
    }

    @Override
    public SchemaFilter getTruncatorFilter() {
        return SchemaFilter.ALL;
    }

    @Override
    public SchemaFilter getMigrateFilter() {
        return SchemaFilter.ALL;
    }
}
