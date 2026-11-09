package ch.urinet.archiv.gemeinsam.adapter.in.health;

import io.agroal.api.AgroalDataSource;
import io.quarkus.arc.InjectableInstance;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

/**
 * Readiness DOWN, wenn keine Datasource aktiv ist (z. B. ohne JDBC-URL). Der Agroal-Check übergeht inaktive
 * Datasources und meldete dann UP; ist die Datasource aktiv, aber die Datenbank weg, meldet er selbst DOWN.
 */
@Readiness
@ApplicationScoped
public class DatenbankBereitschaft implements HealthCheck {

    @Inject
    InjectableInstance<AgroalDataSource> datenbank;

    @Override
    public HealthCheckResponse call() {
        return HealthCheckResponse.named("Datenbank konfiguriert").status(!datenbank.listActive().isEmpty()).build();
    }
}
