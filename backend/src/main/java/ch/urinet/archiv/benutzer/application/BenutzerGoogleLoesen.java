package ch.urinet.archiv.benutzer.application;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.protokoll.domain.IpAdresse;
import ch.urinet.archiv.protokoll.domain.ProtokollAktion;
import ch.urinet.archiv.protokoll.domain.ProtokollDetails;
import ch.urinet.archiv.protokoll.domain.ZugriffEintrag;
import ch.urinet.archiv.sicherheit.application.Berechtigung;
import ch.urinet.archiv.sicherheit.application.TokenWiderruf;
import ch.urinet.archiv.sicherheit.domain.Aktion;
import ch.urinet.archiv.sicherheit.domain.Benutzer;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class BenutzerGoogleLoesen {
    private final IdentitaetsDienst identitaet;
    private final VerwaltungVorgang vorgang;
    private final Berechtigung berechtigung;
    private final StepUp stepUp;
    private final TokenWiderruf tokenWiderruf;

    public BenutzerGoogleLoesen(IdentitaetsDienst identitaet, VerwaltungVorgang vorgang,
                                Berechtigung berechtigung, StepUp stepUp, TokenWiderruf tokenWiderruf) {
        this.identitaet = identitaet;
        this.vorgang = vorgang;
        this.berechtigung = berechtigung;
        this.stepUp = stepUp;
        this.tokenWiderruf = tokenWiderruf;
    }

    public void ausfuehren(MandantCode mandant, Benutzer akteur, String id, Optional<IpAdresse> ip) {
        berechtigung.aktion(akteur, Aktion.A22);
        stepUp.pruefen();
        UUID ziel;
        try {
            ziel = UUID.fromString(id);
            if (!ziel.toString().equalsIgnoreCase(id)) {
                throw new IllegalArgumentException("Ungültige Kennung");
            }
        } catch (IllegalArgumentException e) {
            throw new FachFehler(Fehlercode.NICHT_GEFUNDEN);
        }
        UUID vorgangId = UUID.randomUUID();
        vorgang.ausfuehren(mandant, eintrag(akteur, ip, vorgangId, ziel, ProtokollDetails.Verwaltungsphase.ANGEFRAGT),
                () -> {
                    if (!identitaet.benutzerLesen(mandant.wert(), id).google()) {
                        throw new FachFehler(Fehlercode.STATUS_KONFLIKT);
                    }
                }, () -> {
                    identitaet.googleLoesen(mandant.wert(), id);
                    identitaet.sitzungenBeenden(mandant.wert(), id);
                    tokenWiderruf.setzen(id, Instant.now());
                    return ziel;
                }, fehler -> VerwaltungVorgang.Kompensation.UNKLAR, ignoriert -> { },
                ignoriert -> eintrag(akteur, ip, vorgangId, ziel, ProtokollDetails.Verwaltungsphase.ABGESCHLOSSEN));
    }

    private static ZugriffEintrag eintrag(Benutzer akteur, Optional<IpAdresse> ip, UUID vorgangId, UUID ziel,
                                          ProtokollDetails.Verwaltungsphase phase) {
        var details = new ProtokollDetails.Verwaltung(vorgangId, phase, ziel,
                ProtokollDetails.Verwaltungsvorgang.GOOGLE_LOESEN, null, null, List.of());
        return new ZugriffEintrag(Optional.of(akteur.id()), Optional.of(akteur.rolle().name()),
                ProtokollAktion.BENUTZER_AENDERN, Optional.empty(), ip, ProtokollDetails.verwaltung(details));
    }
}
