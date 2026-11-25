package ch.urinet.archiv.benutzer.application;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.protokoll.domain.IpAdresse;
import ch.urinet.archiv.protokoll.domain.ProtokollAktion;
import ch.urinet.archiv.protokoll.domain.ProtokollDetails;
import ch.urinet.archiv.protokoll.domain.ZugriffEintrag;
import ch.urinet.archiv.sicherheit.domain.Benutzer;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

@ApplicationScoped
public class BenutzerEinladen {
    private final IdentitaetsDienst identitaet;
    private final BenutzerStatusSpeicher status;
    private final BenutzerEinladungSpeicher einladungen;
    private final VerwaltungVorgang vorgang;
    private final Duration gueltigkeit;

    public BenutzerEinladen(IdentitaetsDienst identitaet, BenutzerStatusSpeicher status,
            BenutzerEinladungSpeicher einladungen, VerwaltungVorgang vorgang,
            @ConfigProperty(name = "archiv.verwaltung.einladung-gueltigkeit", defaultValue = "PT24H")
            Duration gueltigkeit) {
        if (gueltigkeit.isZero() || gueltigkeit.isNegative() || gueltigkeit.compareTo(Duration.ofDays(7)) > 0) {
            throw new IllegalArgumentException("Ungültige Einladungsgültigkeit");
        }
        this.identitaet = identitaet;
        this.status = status;
        this.einladungen = einladungen;
        this.vorgang = vorgang;
        this.gueltigkeit = gueltigkeit;
    }

    public void erneut(MandantCode mandant, Benutzer akteur, String id, Optional<IpAdresse> ip) {
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
        ZugriffEintrag absicht = eintrag(akteur, ip, vorgangId, ziel, ProtokollDetails.Verwaltungsphase.ANGEFRAGT);
        AtomicReference<IdentitaetsDienst.Benutzer> gefunden = new AtomicReference<>();
        vorgang.ausfuehren(mandant, absicht, () -> {
            IdentitaetsDienst.Benutzer benutzer = identitaet.benutzerLesen(mandant.wert(), id);
            if (!benutzer.aktiv() || benutzer.aktionen().isEmpty() || benutzer.rollen().size() > 1
                    || status.lesen(id).isPresent()) {
                throw new FachFehler(Fehlercode.STATUS_KONFLIKT);
            }
            gefunden.set(benutzer);
        }, () -> {
            identitaet.einladen(mandant.wert(), id, gefunden.get().aktionen(), gueltigkeit);
            return ziel;
        }, fehler -> VerwaltungVorgang.Kompensation.UNKLAR,
                ignoriert -> einladungen.speichern(id, gueltigkeit),
                ignoriert -> eintrag(akteur, ip, vorgangId, ziel,
                        ProtokollDetails.Verwaltungsphase.ABGESCHLOSSEN));
    }

    private static ZugriffEintrag eintrag(Benutzer akteur, Optional<IpAdresse> ip, UUID vorgangId, UUID ziel,
                                          ProtokollDetails.Verwaltungsphase phase) {
        var details = new ProtokollDetails.Verwaltung(vorgangId, phase, ziel,
                ProtokollDetails.Verwaltungsvorgang.EINLADUNG_ERNEUT, null, null, List.of());
        return new ZugriffEintrag(Optional.of(akteur.id()), Optional.of(akteur.rolle().name()),
                ProtokollAktion.BENUTZER_AENDERN, Optional.empty(), ip, ProtokollDetails.verwaltung(details));
    }
}
