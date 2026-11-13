package ch.urinet.archiv.protokoll.application;

import ch.urinet.archiv.mandant.application.MandantKontext;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.protokoll.application.ManifestDaten.ManifestStand;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.LocalDate;
import java.util.Optional;

/** Liest {@link FestgehalteneManifeste} aus dem Schema des Mandanten, je Datum in einer eigenen Transaktion. */
@ApplicationScoped
public class DbFestgehalteneManifeste implements FestgehalteneManifeste {

    private final MandantKontext kontext;
    private final ManifestSpeichern speichern;

    public DbFestgehalteneManifeste(MandantKontext kontext, ManifestSpeichern speichern) {
        this.kontext = kontext;
        this.speichern = speichern;
    }

    @Override
    public Optional<ManifestStand> stand(MandantCode mandant, LocalDate datum) {
        return kontext.mit(mandant, () -> speichern.stand(datum));
    }
}
