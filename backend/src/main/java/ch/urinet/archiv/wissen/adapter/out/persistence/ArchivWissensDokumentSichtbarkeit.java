package ch.urinet.archiv.wissen.adapter.out.persistence;

import ch.urinet.archiv.dokument.application.DokumentRepository;
import ch.urinet.archiv.sicherheit.domain.Benutzer;
import ch.urinet.archiv.sicherheit.domain.Sichtregel;
import ch.urinet.archiv.wissen.application.WissensDokumentSichtbarkeit;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.UUID;

@ApplicationScoped
public class ArchivWissensDokumentSichtbarkeit implements WissensDokumentSichtbarkeit {

    private final DokumentRepository dokumente;

    public ArchivWissensDokumentSichtbarkeit(DokumentRepository dokumente) {
        this.dokumente = dokumente;
    }

    @Override
    public boolean sichtbar(Benutzer benutzer, UUID dokumentId) {
        return dokumente.sichtbar(dokumentId, Sichtregel.fuer(benutzer));
    }
}
