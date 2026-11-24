package ch.urinet.archiv.benutzer.application;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.protokoll.domain.ProtokollAktion;
import ch.urinet.archiv.protokoll.domain.ProtokollDetails;
import ch.urinet.archiv.protokoll.domain.ZugriffEintrag;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@ApplicationScoped
public class BestehendenInhaberDokumentieren {
    public enum Ergebnis { GEPLANT, DOKUMENTIERT, BEREITS_DOKUMENTIERT }

    private final IdentitaetsDienst identitaet;
    private final AbgleichStand stand;
    private final VerwaltungVorgang vorgang;

    public BestehendenInhaberDokumentieren(IdentitaetsDienst identitaet, AbgleichStand stand,
                                          VerwaltungVorgang vorgang) {
        this.identitaet = identitaet;
        this.stand = stand;
        this.vorgang = vorgang;
    }

    public Ergebnis ausfuehren(MandantCode mandant, UUID ziel, UUID betreiber, boolean schreiben) {
        if (dokumentiert(mandant, ziel)) {
            return Ergebnis.BEREITS_DOKUMENTIERT;
        }
        if (!schreiben) {
            return Ergebnis.GEPLANT;
        }
        UUID vorgangId = UUID.randomUUID();
        try {
            vorgang.ausfuehren(mandant, eintrag(betreiber, ziel, vorgangId, ProtokollDetails.Verwaltungsphase.ANGEFRAGT),
                    () -> {
                        if (dokumentiert(mandant, ziel)) {
                            throw new BereitsDokumentiert();
                        }
                    }, () -> Boolean.TRUE, fehler -> VerwaltungVorgang.Kompensation.GEKLAERT, ergebnis -> {},
                    ergebnis -> eintrag(betreiber, ziel, vorgangId, ProtokollDetails.Verwaltungsphase.ABGESCHLOSSEN));
            return Ergebnis.DOKUMENTIERT;
        } catch (BereitsDokumentiert e) {
            return Ergebnis.BEREITS_DOKUMENTIERT;
        }
    }

    private boolean dokumentiert(MandantCode mandant, UUID ziel) {
        var benutzer = identitaet.benutzerLesen(mandant.wert(), ziel.toString());
        if (!benutzer.rollen().equals(Set.of(Rolle.INHABER))) {
            throw new FachFehler(Fehlercode.STATUS_KONFLIKT);
        }
        return stand.lesen(mandant).dokumentierteInhaber().contains(ziel);
    }

    private static ZugriffEintrag eintrag(UUID betreiber, UUID ziel, UUID id,
                                          ProtokollDetails.Verwaltungsphase phase) {
        var details = new ProtokollDetails.Verwaltung(id, phase, ziel,
                ProtokollDetails.Verwaltungsvorgang.ROLLE_ZUWEISEN,
                ProtokollDetails.Verwaltungsrolle.INHABER, ProtokollDetails.Verwaltungsrolle.INHABER,
                List.of(), null, null, ProtokollDetails.Verwaltungsanlass.UEBERNAHME, null);
        return new ZugriffEintrag(Optional.of("betreiber:" + betreiber), Optional.empty(),
                ProtokollAktion.ROLLE_ZUWEISEN, Optional.empty(), Optional.empty(),
                ProtokollDetails.verwaltung(details));
    }

    private static final class BereitsDokumentiert extends RuntimeException {
    }
}
