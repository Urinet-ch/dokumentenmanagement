package ch.urinet.archiv.wissen.application;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.sicherheit.application.Berechtigung;
import ch.urinet.archiv.sicherheit.domain.Aktion;
import ch.urinet.archiv.sicherheit.domain.Benutzer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.IntSupplier;

@ApplicationScoped
public class FreigabeSperre {
    private final FreigabeRepository repository;
    private final WissenHuelleDienst huelle;
    private final WissenSichtbarkeit sichtbarkeit;
    private final Berechtigung berechtigung;

    public FreigabeSperre(FreigabeRepository repository, WissenHuelleDienst huelle, WissenSichtbarkeit sichtbarkeit,
                          Berechtigung berechtigung) {
        this.repository = repository;
        this.huelle = huelle;
        this.sichtbarkeit = sichtbarkeit;
        this.berechtigung = berechtigung;
    }

    @Transactional(Transactional.TxType.MANDATORY)
    public FreigabeRepository.Aenderung laden(Benutzer benutzer, UUID entwurfId, int nummer, IntSupplier ifMatch) {
        return laden(benutzer, entwurfId, nummer, ifMatch, false);
    }

    @Transactional(Transactional.TxType.MANDATORY)
    public FreigabeRepository.Aenderung zumVerwerfenLaden(Benutzer benutzer, UUID entwurfId, int nummer,
                                                           IntSupplier ifMatch) {
        return laden(benutzer, entwurfId, nummer, ifMatch, true);
    }

    private FreigabeRepository.Aenderung laden(Benutzer benutzer, UUID entwurfId, int nummer, IntSupplier ifMatch,
                                                boolean veraltetErlaubt) {
        huelle.sperren();
        Optional<FreigabeRepository.Aenderung> gefunden = repository.sperren(entwurfId, nummer);
        boolean sichtbar = gefunden.filter(a -> a.seiteId() == null
                || sichtbarkeit.aktuelleSeiteSichtbar(benutzer, a.seiteId())).isPresent();
        berechtigung.wissensseitenAktion(benutzer, Aktion.A17, sichtbar);
        FreigabeRepository.Aenderung aenderung = gefunden.orElseThrow();
        if (ifMatch.getAsInt() != aenderung.etag()) {
            throw new FachFehler(Fehlercode.VERSION_KONFLIKT);
        }
        if (!"OFFEN".equals(aenderung.entwurfStatus()) || !("OFFEN".equals(aenderung.status())
                || veraltetErlaubt && "VERALTET".equals(aenderung.status()))) {
            throw new FachFehler(Fehlercode.STATUS_KONFLIKT);
        }
        return aenderung;
    }

    @Transactional(Transactional.TxType.MANDATORY)
    public boolean basisAktuell(FreigabeRepository.Aenderung aenderung) {
        if (aenderung.seiteId() == null) {
            if (!"NEU".equals(aenderung.aktion()) || aenderung.basisVersion() != null) {
                throw new FachFehler(Fehlercode.STATUS_KONFLIKT);
            }
            return true;
        }
        if ("NEU".equals(aenderung.aktion())) {
            throw new FachFehler(Fehlercode.STATUS_KONFLIKT);
        }
        Integer aktuell = repository.seitenVersionSperren(aenderung.seiteId())
                .orElseThrow(() -> new FachFehler(Fehlercode.NICHT_GEFUNDEN));
        if (Objects.equals(aenderung.basisVersion(), aktuell)) {
            return true;
        }
        repository.alsVeraltetMarkieren(aenderung.id(), aenderung.etag());
        return false;
    }
}
