package ch.urinet.archiv.sicherung.application;

import ch.urinet.archiv.mandant.application.MandantRegister;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.sicherung.domain.SicherungsArt;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Optional;

/** Anwendungsfälle des Sicherungsregisters und Validierung des aktiven Mandanten. */
@ApplicationScoped
public class SicherungExportVerwaltung {

    private final SicherungExportSpeicher speicher;
    private final MandantRegister register;

    public SicherungExportVerwaltung(SicherungExportSpeicher speicher, MandantRegister register) {
        this.speicher = speicher;
        this.register = register;
    }

    public boolean mandantAktiv(MandantCode code) {
        return register.aktive().stream().anyMatch(m -> m.code().equals(code));
    }

    public void festhalten(SicherungsArt art, Optional<MandantCode> mandant, String sha256) {
        if (art.mandantPflicht() != mandant.isPresent()) {
            throw new SicherungExportFehler("Mandant passt nicht zur Exportart");
        }
        mandant.ifPresent(code -> {
            if (!mandantAktiv(code)) {
                throw new SicherungExportFehler("Unbekannter oder inaktiver Mandant");
            }
        });
        speicher.festhalten(art, mandant, sha256);
    }
}
