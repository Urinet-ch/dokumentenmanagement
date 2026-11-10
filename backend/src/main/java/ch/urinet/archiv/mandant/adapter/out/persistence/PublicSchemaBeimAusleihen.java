package ch.urinet.archiv.mandant.adapter.out.persistence;

import io.agroal.api.AgroalPoolInterceptor;
import jakarta.enterprise.context.ApplicationScoped;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Jede Verbindung kommt mit dem Schema {@code public} aus dem Pool (fail closed, design.md «Mandant»). Quarkus setzt den
 * Mandanten per {@code Connection.setSchema} und Agroal setzt das Schema bei der Rückgabe nicht zurück; ohne diesen
 * Interceptor könnte rohes JDBC sonst unbemerkt im Schema des vorherigen Mandanten lesen. Hibernate setzt den Mandanten
 * erst nach dem Ausleihen.
 */
@ApplicationScoped
public class PublicSchemaBeimAusleihen implements AgroalPoolInterceptor {

    static final String SCHEMA = "public";

    @Override
    public void onConnectionAcquire(Connection verbindung) {
        try {
            if (!SCHEMA.equals(verbindung.getSchema())) {
                verbindung.setSchema(SCHEMA);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Schema der Pool-Verbindung lässt sich nicht auf public setzen", e);
        }
    }
}
