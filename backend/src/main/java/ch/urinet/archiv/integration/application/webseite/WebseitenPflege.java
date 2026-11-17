package ch.urinet.archiv.integration.application.webseite;

import ch.urinet.archiv.dokument.application.Aenderung;
import ch.urinet.archiv.dokument.application.Bestaetigung;
import ch.urinet.archiv.dokument.application.DokumentAendern;
import ch.urinet.archiv.dokument.application.DokumentBestaetigen;
import ch.urinet.archiv.dokument.application.DokumentLesen;
import ch.urinet.archiv.dokumentart.application.DokumentartVerwaltung;
import ch.urinet.archiv.dokument.domain.Dokument;
import ch.urinet.archiv.dokument.domain.DokumentStatus;
import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.sicherheit.domain.Benutzer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.util.UUID;
import java.util.function.Supplier;

@ApplicationScoped
@Transactional
public class WebseitenPflege {
    private final DokumentLesen lesen;
    private final DokumentAendern aendern;
    private final DokumentBestaetigen bestaetigen;
    private final DokumentartVerwaltung arten;

    public WebseitenPflege(DokumentLesen lesen, DokumentAendern aendern, DokumentBestaetigen bestaetigen,
                            DokumentartVerwaltung arten) {
        this.lesen = lesen;
        this.aendern = aendern;
        this.bestaetigen = bestaetigen;
        this.arten = arten;
    }

    public record Bestaetigungsangaben(Bestaetigung daten, Aenderung zusatz) {
    }

    public Dokument bestaetigen(Benutzer benutzer, UUID id, int version, Supplier<Bestaetigungsangaben> eingabe) {
        Dokument d = lesen.lesen(benutzer, id);
        if (d.version() != version) {
            throw new FachFehler(Fehlercode.VERSION_KONFLIKT);
        }
        if (d.status() != DokumentStatus.EINGANGSKORB) {
            throw new FachFehler(Fehlercode.STATUS_KONFLIKT);
        }
        Bestaetigungsangaben angaben = eingabe.get();
        if (angaben.zusatz() != null) {
            version = aendern.aendern(benutzer, id, version, angaben::zusatz);
        }
        if (angaben.daten().art() != null) {
            Dokument aktuell = lesen.lesen(benutzer, id);
            arten.zusatzPruefen(angaben.daten().art(), aktuell.zusatz() == null ? "{}" : aktuell.zusatz());
        }
        bestaetigen.bestaetigen(benutzer, id, version, angaben.daten());
        return lesen.lesen(benutzer, id);
    }

    public Dokument aendern(Benutzer benutzer, UUID id, int version, boolean nurArchiviert,
                            Supplier<Aenderung> eingabe) {
        aendern.aendern(benutzer, id, version, () -> {
            // Der gemeinsame Dienst hat hier bereits die Dokumentzeile gesperrt und die Version erneut geprüft.
            if (nurArchiviert && lesen.lesen(benutzer, id).status() != DokumentStatus.ARCHIVIERT) {
                throw new FachFehler(Fehlercode.STATUS_KONFLIKT);
            }
            return eingabe.get();
        });
        return lesen.lesen(benutzer, id);
    }
}
