package ch.urinet.archiv.wissen.application;

import ch.urinet.archiv.ablage.application.AblagePort;
import ch.urinet.archiv.ablage.domain.VorsignierteUrl;
import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.sicherheit.application.Berechtigung;
import ch.urinet.archiv.sicherheit.domain.Aktion;
import ch.urinet.archiv.sicherheit.domain.Benutzer;
import ch.urinet.archiv.verarbeitung.application.AuftragService;
import ch.urinet.archiv.verarbeitung.domain.Nutzlast;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class WissenExportVerwaltung {
    public record Abruf(WissenExportRepository.Export export, Optional<VorsignierteUrl> adresse) {
    }

    private final WissenExportRepository repository;
    private final WissenExportProtokoll protokoll;
    private final AuftragService auftraege;
    private final AblagePort ablage;
    private final Berechtigung berechtigung;
    private final Clock uhr;

    @Inject
    public WissenExportVerwaltung(WissenExportRepository repository, WissenExportProtokoll protokoll,
                                 AuftragService auftraege, AblagePort ablage, Berechtigung berechtigung) {
        this(repository, protokoll, auftraege, ablage, berechtigung, Clock.systemUTC());
    }

    WissenExportVerwaltung(WissenExportRepository repository, WissenExportProtokoll protokoll,
                          AuftragService auftraege, AblagePort ablage, Berechtigung berechtigung, Clock uhr) {
        this.repository = repository;
        this.protokoll = protokoll;
        this.auftraege = auftraege;
        this.ablage = ablage;
        this.berechtigung = berechtigung;
        this.uhr = uhr;
    }

    @Transactional
    public WissenExportRepository.Export anfordern(Benutzer benutzer) {
        berechtigung.aktion(benutzer, Aktion.A19);
        UUID id = UUID.randomUUID();
        repository.anlegen(id, benutzer.id(), benutzer.rolle().name());
        protokoll.angefordert(id);
        auftraege.einreihen(WissenExportHandler.TYP, new Nutzlast(Map.of(WissenExportHandler.EXPORT_ID, id)));
        return repository.laden(id).orElseThrow(() -> new IllegalStateException("Wissensexport nach Anlage fehlt"));
    }

    @Transactional
    public Abruf abrufen(Benutzer benutzer, MandantCode mandant, UUID id) {
        berechtigung.aktion(benutzer, Aktion.A19);
        WissenExportRepository.Export export = repository.laden(id)
                .orElseThrow(() -> new FachFehler(Fehlercode.NICHT_GEFUNDEN));
        if (!export.herunterladbar(uhr.instant())) {
            return new Abruf(export, Optional.empty());
        }
        VorsignierteUrl url = ablage.exportUrl(mandant, export.schluessel(), export.version(),
                export.fertig().plus(Duration.ofHours(24)), "wissensexport-" + mandant.wert() + ".zip");
        protokoll.heruntergeladen(id, export.sha256());
        return new Abruf(export, Optional.of(url));
    }
}
