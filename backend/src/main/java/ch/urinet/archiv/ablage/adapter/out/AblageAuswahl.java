package ch.urinet.archiv.ablage.adapter.out;

import ch.urinet.archiv.ablage.adapter.out.s3.S3Ablage;
import ch.urinet.archiv.ablage.adapter.out.stub.AblageStub;
import ch.urinet.archiv.ablage.application.AblagePort;
import ch.urinet.archiv.ablage.domain.Sperrmodus;
import ch.urinet.archiv.gemeinsam.domain.PortAuswahl;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

/** Wählt zur Laufzeit zwischen Dev-Stub und S3-Adapter (Schalter per Konfiguration, nicht per Build-Profil). */
@ApplicationScoped
public class AblageAuswahl {

    @Produces
    @ApplicationScoped
    public AblagePort ablage(@ConfigProperty(name = "archiv.ablage.stub") boolean stub, AblageStub dev,
                             Instance<S3Ablage> s3) {
        return stub ? dev : s3.get();
    }

    /** Für die Startprüfung der Port-Auswahl: Der S3-Adapter ist der echte Adapter (Aufgabe 2.3). */
    @Produces
    @Dependent
    public PortAuswahl auswahl(@ConfigProperty(name = "archiv.ablage.stub") boolean stub) {
        return new PortAuswahl("AblagePort", stub, true, Optional.empty());
    }

    @Produces
    @Singleton
    public AblageEinstellungen einstellungen(
            @ConfigProperty(name = "archiv.ablage.bucket-praefix") String bucketPraefix,
            @ConfigProperty(name = "archiv.ablage.max-sperrfrist") Optional<Duration> archivHoechstfrist,
            @ConfigProperty(name = "archiv.ablage.manifest-sperrmodus", defaultValue = "COMPLIANCE")
            Sperrmodus manifestSperrmodus,
            @ConfigProperty(name = "archiv.ablage.manifest-max-sperrfrist") Optional<Duration> manifestHoechstfrist,
            @ConfigProperty(name = "archiv.ablage.url-gueltigkeit", defaultValue = "PT5M") Duration urlGueltigkeit,
            @ConfigProperty(name = "archiv.ablage.zwischenablage") Path zwischenablage) {
        return new AblageEinstellungen(bucketPraefix, archivHoechstfrist, manifestSperrmodus, manifestHoechstfrist,
                urlGueltigkeit, zwischenablage);
    }
}
