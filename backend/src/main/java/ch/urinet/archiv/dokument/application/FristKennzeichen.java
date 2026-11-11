package ch.urinet.archiv.dokument.application;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.util.UUID;

/**
 * Setzt «Frist abgelaufen» in einer eigenen Transaktion (Req 9.8): Die Bestätigung endet danach mit 409 und rollt
 * zurück, das Kennzeichen im Eingangskorb soll aber bleiben. Eigene Klasse, weil {@code @Transactional} nur über den
 * Proxy wirkt. Der Aufrufer darf die Zeile des Dokuments noch nicht gesperrt haben.
 */
@ApplicationScoped
public class FristKennzeichen {

    private final DokumentStatusPort status;

    public FristKennzeichen(DokumentStatusPort status) {
        this.status = status;
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void markieren(UUID id) {
        status.fristAbgelaufenMarkieren(id);
    }
}
