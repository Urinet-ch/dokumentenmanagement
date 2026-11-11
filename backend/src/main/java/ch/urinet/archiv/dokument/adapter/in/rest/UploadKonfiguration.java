package ch.urinet.archiv.dokument.adapter.in.rest;

import ch.urinet.archiv.dokument.application.UploadGrenzen;
import io.quarkus.runtime.configuration.MemorySize;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** {@code archiv.upload.max-datei} als {@link UploadGrenzen} (Req 3.6, Startwert 50 MB). */
@ApplicationScoped
public class UploadKonfiguration implements UploadGrenzen {

    private final long maxDatei;

    public UploadKonfiguration(@ConfigProperty(name = "archiv.upload.max-datei") MemorySize maxDatei) {
        this.maxDatei = maxDatei.asLongValue();
    }

    @Override
    public long maxDatei() {
        return maxDatei;
    }
}
