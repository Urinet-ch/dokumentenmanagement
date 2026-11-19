package ch.urinet.archiv.wissen.application;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.gemeinsam.domain.PflichtfelderFehlen;
import ch.urinet.archiv.sicherheit.application.Berechtigung;
import ch.urinet.archiv.sicherheit.domain.Aktion;
import ch.urinet.archiv.sicherheit.domain.Benutzer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.IntSupplier;

@ApplicationScoped
public class FreigabeDienst {
    private static final int GRUND_MAX = 1000;

    private final FreigabeRepository repository;
    private final WissenHuelleDienst huelle;
    private final WissenSichtbarkeit sichtbarkeit;
    private final Berechtigung berechtigung;
    private final WissenIngestSpeichern ingest;
    private final FreigabeProtokoll protokoll;

    public FreigabeDienst(FreigabeRepository repository, WissenHuelleDienst huelle, WissenSichtbarkeit sichtbarkeit,
                          Berechtigung berechtigung, WissenIngestSpeichern ingest, FreigabeProtokoll protokoll) {
        this.repository = repository;
        this.huelle = huelle;
        this.sichtbarkeit = sichtbarkeit;
        this.berechtigung = berechtigung;
        this.ingest = ingest;
        this.protokoll = protokoll;
    }

    @Transactional
    public void verwerfen(Benutzer benutzer, UUID entwurfId, int nummer, IntSupplier ifMatch, String grund,
                          String realIp) {
        huelle.sperren();
        Optional<FreigabeRepository.Aenderung> gefunden = repository.sperren(entwurfId, nummer);
        boolean sichtbar = gefunden.filter(a -> a.seiteId() == null
                || sichtbarkeit.aktuelleSeiteSichtbar(benutzer, a.seiteId())).isPresent();
        berechtigung.wissensseitenAktion(benutzer, Aktion.A17, sichtbar);
        FreigabeRepository.Aenderung aenderung = gefunden.orElseThrow();
        int version = ifMatch.getAsInt();
        if (version != aenderung.etag()) {
            throw new FachFehler(Fehlercode.VERSION_KONFLIKT);
        }
        if (!"OFFEN".equals(aenderung.entwurfStatus()) || !"OFFEN".equals(aenderung.status())) {
            throw new FachFehler(Fehlercode.STATUS_KONFLIKT);
        }
        String text = grund == null ? "" : grund.strip();
        if (text.isEmpty()) {
            throw new PflichtfelderFehlen(List.of("grund"));
        }
        if (text.length() > GRUND_MAX) {
            throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
        }
        protokoll.verworfen(benutzer, entwurfId, text, realIp);
        repository.verwerfen(aenderung.id(), version, benutzer.id(), text);
        if (repository.entwurfAbschliessen(entwurfId)) {
            repository.abschlussProtokollieren(entwurfId);
            if ("INGEST".equals(aenderung.anlass())) {
                ingest.wartendenIngestStarten();
            }
        }
    }
}
