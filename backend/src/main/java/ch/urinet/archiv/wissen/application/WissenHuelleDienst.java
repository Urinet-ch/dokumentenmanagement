package ch.urinet.archiv.wissen.application;

import ch.urinet.archiv.wissen.domain.WissensGraph;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.util.Set;
import java.util.UUID;

@ApplicationScoped
public class WissenHuelleDienst {

    private final WissenHuelleRepository repository;

    public WissenHuelleDienst(WissenHuelleRepository repository) {
        this.repository = repository;
    }

    /**
     * Sperrt die Neuberechnung der Hüllen im Mandanten bis zum Ende der laufenden Transaktion. Die Freigabe (14.2)
     * ruft das vor dem Schreiben von Seite und Version auf, damit parallele Freigaben die Sperren in derselben
     * Reihenfolge nehmen; {@link #versionFreigeben} folgt in derselben Transaktion.
     */
    @Transactional(Transactional.TxType.MANDATORY)
    public void sperren() {
        repository.sperren();
    }

    @Transactional
    public Set<UUID> versionFreigeben(UUID seiteId, int version) {
        if (version < 1) {
            throw new IllegalArgumentException("Ungültige Wissensseitenversion");
        }
        repository.sperren();
        WissensGraph graph = repository.graphLaden();
        if (!graph.kenntSeite(seiteId)) {
            throw new IllegalArgumentException("Wissensseite nicht vorhanden");
        }

        Set<UUID> versionHuelle = graph.dokumente(seiteId);
        aktualisieren(graph, graph.abhaengigeSeiten(seiteId));
        repository.versionsHuelleEinfuegen(seiteId, version, versionHuelle);
        return versionHuelle;
    }

    @Transactional
    public Set<UUID> dokumentSichtbarkeitGeaendert(UUID dokumentId) {
        repository.sperren();
        Set<UUID> betroffene = repository.seitenMitDokument(dokumentId);
        if (betroffene.isEmpty()) {
            return Set.of();
        }
        WissensGraph graph = repository.graphLaden();
        aktualisieren(graph, betroffene);
        return betroffene;
    }

    private void aktualisieren(WissensGraph graph, Set<UUID> seiten) {
        for (UUID seiteId : seiten) {
            Set<UUID> dokumente = graph.dokumente(seiteId);
            repository.aktuelleHuelleSetzen(seiteId, dokumente);
            repository.mindeststufeAnheben(seiteId, graph.mindeststufeMitQuellen(seiteId));
            repository.vertraulichkeitSetzen(seiteId, graph.wirksameStufe(seiteId, dokumente));
        }
    }
}
