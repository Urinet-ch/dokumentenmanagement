package ch.urinet.archiv.importe.adapter.out;

import ch.urinet.archiv.importe.application.ImportEinstellungen;
import io.quarkus.runtime.configuration.MemorySize;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Liest die Grenzen des CSV-Imports aus der Konfiguration ({@code MemorySize}: {@code 5M} = 5 MiB). */
@ApplicationScoped
public class ImportKonfiguration {

    @Produces
    @Singleton
    public ImportEinstellungen einstellungen(
            @ConfigProperty(name = "archiv.import.max-groesse", defaultValue = "5M") MemorySize maxGroesse,
            @ConfigProperty(name = "archiv.import.feld-grenze", defaultValue = "10000") int feldGrenze) {
        return new ImportEinstellungen(maxGroesse.asLongValue(), feldGrenze);
    }
}
