package ch.urinet.archiv.mandant;

import io.agroal.api.AgroalDataSource;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;

/**
 * Testvorbereitung für {@link MigrationBeimStartTest}: trägt nach {@code db/public}, aber vor {@code MigrationBeimStart}
 * einen aktiven Mandanten mit leerem Schema ein (Changesets fehlen). Nur mit {@code archiv.test.start-mandant}.
 */
@ApplicationScoped
public class StartMandantVorbereitung {

    void vorbereiten(@Observes @Priority(1) StartupEvent start,
                     @ConfigProperty(name = "archiv.test.start-mandant") Optional<String> code,
                     Instance<AgroalDataSource> datenbank) throws SQLException {
        if (code.isEmpty()) {
            return;
        }
        String schema = "m_" + code.get().replace('-', '_');
        try (Connection verbindung = datenbank.get().getConnection(); PreparedStatement einfuegen = verbindung.prepareStatement(
                "insert into public.mandant (code, name, schema_name, bucket) values (?, ?, ?, ?) on conflict do nothing");
             Statement befehl = verbindung.createStatement()) {
            einfuegen.setString(1, code.get());
            einfuegen.setString(2, code.get());
            einfuegen.setString(3, schema);
            einfuegen.setString(4, "urinet-archiv-" + code.get());
            einfuegen.executeUpdate();
            befehl.execute("drop schema if exists " + schema + " cascade");
            befehl.execute("create schema " + schema);
        }
    }
}
