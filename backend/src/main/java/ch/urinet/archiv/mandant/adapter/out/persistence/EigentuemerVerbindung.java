package ch.urinet.archiv.mandant.adapter.out.persistence;

import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Optional;

/**
 * Verbindung mit der DB-Rolle für Schemaänderungen ({@code archiv_owner}, design.md «Secrets und Konfiguration»):
 * dieselbe wie für Liquibase ({@code quarkus.liquibase.username}). Ohne eigene Rolle (Dev Services) die Datasource.
 */
@ApplicationScoped
public class EigentuemerVerbindung {

    private final Instance<AgroalDataSource> datenquelle;
    private final Optional<String> url;
    private final Optional<String> benutzer;
    private final Optional<String> passwort;

    public EigentuemerVerbindung(Instance<AgroalDataSource> datenquelle,
                                 @ConfigProperty(name = "quarkus.datasource.jdbc.url") Optional<String> url,
                                 @ConfigProperty(name = "quarkus.liquibase.username") Optional<String> benutzer,
                                 @ConfigProperty(name = "quarkus.liquibase.password") Optional<String> passwort) {
        this.datenquelle = datenquelle;
        this.url = url;
        this.benutzer = benutzer;
        this.passwort = passwort;
    }

    Connection oeffnen() throws SQLException {
        if (url.isPresent() && benutzer.isPresent()) {
            return DriverManager.getConnection(url.get(), benutzer.get(), passwort.orElse(""));
        }
        return datenquelle.get().getConnection();
    }
}
