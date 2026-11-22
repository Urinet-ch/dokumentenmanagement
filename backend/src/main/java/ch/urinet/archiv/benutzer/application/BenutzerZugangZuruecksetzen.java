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
import ch.urinet.archiv.sicherheit.domain.Rolle;
import ch.urinet.archiv.sicherheit.domain.Vergaberegeln;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

@ApplicationScoped
public class BenutzerZugangZuruecksetzen {
    private static final Set<IdentitaetsDienst.ErforderlicheAktion> AKTIONEN = Set.of(
            IdentitaetsDienst.ErforderlicheAktion.UPDATE_PASSWORD, IdentitaetsDienst.ErforderlicheAktion.CONFIGURE_TOTP);

    private final IdentitaetsDienst identitaet;
    private final BenutzerStatusSpeicher status;
    private final VerwaltungVorgang vorgang;
    private final Berechtigung berechtigung;
    private final StepUp stepUp;
    private final TokenWiderruf tokenWiderruf;
    private final Duration gueltigkeit;

    public BenutzerZugangZuruecksetzen(IdentitaetsDienst identitaet, BenutzerStatusSpeicher status,
                                      VerwaltungVorgang vorgang, Berechtigung berechtigung, StepUp stepUp,
                                      TokenWiderruf tokenWiderruf,
                                      @ConfigProperty(name = "archiv.verwaltung.einladung-gueltigkeit",
                                              defaultValue = "PT24H") Duration gueltigkeit) {
        if (gueltigkeit.isZero() || gueltigkeit.isNegative() || gueltigkeit.compareTo(Duration.ofDays(7)) > 0) {
            throw new IllegalArgumentException("Ungültige Einladungsgültigkeit");
        }
        this.identitaet = identitaet;
        this.status = status;
        this.vorgang = vorgang;
        this.berechtigung = berechtigung;
        this.stepUp = stepUp;
        this.tokenWiderruf = tokenWiderruf;
        this.gueltigkeit = gueltigkeit;
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
        AtomicReference<IdentitaetsDienst.Benutzer> gefunden = new AtomicReference<>();
        vorgang.ausfuehren(mandant, eintrag(akteur, ip, vorgangId, ziel, ProtokollDetails.Verwaltungsphase.ANGEFRAGT),
                () -> {
                    var benutzer = identitaet.benutzerLesen(mandant.wert(), id);
                    if (benutzer.rollen().size() > 1) {
                        throw new FachFehler(Fehlercode.STATUS_KONFLIKT);
                    }
                    Rolle rolle = benutzer.rollen().stream().findFirst().orElse(null);
                    if (Vergaberegeln.pruefeSelbstzugriff(akteur, id,
                            Vergaberegeln.Selbstaktion.ZUGANG_ZURUECKSETZEN)
                            != Vergaberegeln.Pruefung.ERLAUBT) {
                        berechtigung.eskalationVerweigern(akteur, Aktion.A22);
                    }
                    if (!benutzer.aktiv() || status.lesen(id).isPresent()) {
                        throw new FachFehler(Fehlercode.STATUS_KONFLIKT);
                    }
                    if (rolle == Rolle.INHABER && benutzer.aktionen().isEmpty()
                            && identitaet.inhaberZaehlen(mandant.wert()) <= 1) {
                        throw new FachFehler(Fehlercode.LETZTER_INHABER);
                    }
                    gefunden.set(benutzer);
                }, () -> {
                    var aktionen = EnumSet.noneOf(IdentitaetsDienst.ErforderlicheAktion.class);
                    aktionen.addAll(gefunden.get().aktionen());
                    aktionen.addAll(AKTIONEN);
                    identitaet.aktionenHinzufuegen(mandant.wert(), id, AKTIONEN);
                    identitaet.otpEntfernen(mandant.wert(), id);
                    identitaet.sitzungenBeenden(mandant.wert(), id);
                    identitaet.einladen(mandant.wert(), id, aktionen, gueltigkeit);
                    tokenWiderruf.setzen(id, Instant.now());
                    return ziel;
                }, fehler -> VerwaltungVorgang.Kompensation.UNKLAR, ignoriert -> { },
                ignoriert -> eintrag(akteur, ip, vorgangId, ziel, ProtokollDetails.Verwaltungsphase.ABGESCHLOSSEN));
    }

    private static ZugriffEintrag eintrag(Benutzer akteur, Optional<IpAdresse> ip, UUID vorgangId, UUID ziel,
                                          ProtokollDetails.Verwaltungsphase phase) {
        var details = new ProtokollDetails.Verwaltung(vorgangId, phase, ziel,
                ProtokollDetails.Verwaltungsvorgang.ZUGANG_ZURUECKSETZEN, null, null, List.of());
        return new ZugriffEintrag(Optional.of(akteur.id()), Optional.of(akteur.rolle().name()),
                ProtokollAktion.BENUTZER_AENDERN, Optional.empty(), ip, ProtokollDetails.verwaltung(details));
    }
}
