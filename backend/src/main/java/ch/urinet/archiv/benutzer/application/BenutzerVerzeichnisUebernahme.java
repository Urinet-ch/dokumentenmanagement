package ch.urinet.archiv.benutzer.application;

import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.protokoll.application.BenutzerVerzeichnis;
import ch.urinet.archiv.protokoll.domain.VerzeichnisEintrag;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Übernimmt bestehende Keycloak-Benutzer in das append-only Benutzerverzeichnis. */
@ApplicationScoped
public class BenutzerVerzeichnisUebernahme {

    private final BenutzerVerzeichnis verzeichnis;

    public BenutzerVerzeichnisUebernahme(BenutzerVerzeichnis verzeichnis) {
        this.verzeichnis = verzeichnis;
    }

    public List<IdentitaetsDienst.Benutzer> fehlende(MandantCode mandant,
                                                      List<IdentitaetsDienst.Benutzer> benutzer) {
        Optional<MandantCode> code = Optional.of(mandant);
        return benutzer.stream().filter(eintrag ->
                verzeichnis.letzter(code, UUID.fromString(eintrag.id()).toString()).isEmpty()).toList();
    }

    @Transactional
    public int uebernehmen(MandantCode mandant, List<IdentitaetsDienst.Benutzer> benutzer) {
        int uebernommen = 0;
        for (IdentitaetsDienst.Benutzer eintrag : benutzer) {
            UUID id = UUID.fromString(eintrag.id());
            String kennung = id.toString();
            Optional<MandantCode> code = Optional.of(mandant);
            verzeichnis.sperren(code, kennung, BenutzerVerzeichnis.Sperre.AKTION);
            if (verzeichnis.letzter(code, kennung).isPresent()) {
                continue;
            }
            verzeichnis.sperren(code, kennung, BenutzerVerzeichnis.Sperre.EINFUEGEN);
            if (verzeichnis.letzter(code, kennung).isPresent()) {
                continue;
            }
            Optional<String> rolle = eintrag.rollen().size() == 1
                    ? eintrag.rollen().stream().findFirst().map(Enum::name)
                    : Optional.empty();
            verzeichnis.einfuegen(code, new BenutzerVerzeichnis.Neu(kennung,
                    Optional.ofNullable(eintrag.benutzername()), rolle, Optional.empty(), false,
                    VerzeichnisEintrag.Anlass.UEBERNAHME, Optional.empty()));
            uebernommen++;
        }
        return uebernommen;
    }
}
