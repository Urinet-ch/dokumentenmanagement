package ch.urinet.archiv.wissen.application;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.gemeinsam.domain.PflichtfelderFehlen;
import ch.urinet.archiv.sicherheit.domain.Benutzer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.function.IntSupplier;

@ApplicationScoped
public class FreigabeDienst {
    private static final int GRUND_MAX = 1000;

    private final FreigabeRepository repository;
    private final FreigabeSperre sperre;
    private final WissenIngestSpeichern ingest;
    private final FreigabeProtokoll protokoll;
    private final FreigabeTransaktion annahme;

    public FreigabeDienst(FreigabeRepository repository, FreigabeSperre sperre,
                          WissenIngestSpeichern ingest, FreigabeProtokoll protokoll, FreigabeTransaktion annahme) {
        this.repository = repository;
        this.sperre = sperre;
        this.ingest = ingest;
        this.protokoll = protokoll;
        this.annahme = annahme;
    }

    public void annehmen(Benutzer benutzer, UUID entwurfId, int nummer, IntSupplier ifMatch,
                         FreigabeAnnahme.Bearbeitung bearbeitung, String stufe, String grund, String realIp) {
        if (!annahme.annehmen(benutzer, entwurfId, nummer, ifMatch, bearbeitung, stufe, grund, realIp)) {
            throw new FachFehler(Fehlercode.VERSION_KONFLIKT);
        }
    }

    @Transactional
    public void verwerfen(Benutzer benutzer, UUID entwurfId, int nummer, IntSupplier ifMatch, String grund,
                          String realIp) {
        FreigabeRepository.Aenderung aenderung = sperre.laden(benutzer, entwurfId, nummer, ifMatch);
        String text = grund == null ? "" : grund.strip();
        if (text.isEmpty()) {
            throw new PflichtfelderFehlen(List.of("grund"));
        }
        if (text.length() > GRUND_MAX) {
            throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
        }
        protokoll.verworfen(benutzer, entwurfId, nummer, aenderung.seiteId(), aenderung.basisVersion(), text, realIp);
        repository.verwerfen(aenderung.id(), aenderung.etag(), benutzer.id(), text);
        if (repository.entwurfAbschliessen(entwurfId)) {
            repository.abschlussProtokollieren(entwurfId);
            if ("INGEST".equals(aenderung.anlass())) {
                ingest.wartendenIngestStarten();
            }
        }
    }
}
