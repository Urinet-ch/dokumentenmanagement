package ch.urinet.archiv.benutzer.application;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.gemeinsam.domain.KonfigurationUngueltig;
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
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

@ApplicationScoped
public class BenutzerRolleAendern {
    public record Ergebnis(UUID antragId) {
    }

    public record InhaberVergabe(MandantCode mandant, UUID ziel) {
    }

    private final IdentitaetsDienst identitaet;
    private final BenutzerStatusSpeicher status;
    private final MandantenRollen rollen;
    private final VerwaltungAntragSpeicher antraege;
    private final VerwaltungVorgang vorgang;
    private final Berechtigung berechtigung;
    private final StepUp stepUp;
    private final TokenWiderruf widerruf;
    private final Event<InhaberVergabe> alarm;
    private final Duration antragGueltigkeit;

    public BenutzerRolleAendern(IdentitaetsDienst identitaet, BenutzerStatusSpeicher status, MandantenRollen rollen,
                                VerwaltungAntragSpeicher antraege, VerwaltungVorgang vorgang,
                                Berechtigung berechtigung, StepUp stepUp, TokenWiderruf widerruf,
                                Event<InhaberVergabe> alarm,
                                @ConfigProperty(name = "archiv.verwaltung.antrag-gueltigkeit", defaultValue = "PT72H")
                                Duration antragGueltigkeit) {
        if (antragGueltigkeit.isNegative() || antragGueltigkeit.isZero()) {
            throw new IllegalArgumentException("Ungültige Antragsgültigkeit");
        }
        this.identitaet = identitaet;
        this.status = status;
        this.rollen = rollen;
        this.antraege = antraege;
        this.vorgang = vorgang;
        this.berechtigung = berechtigung;
        this.stepUp = stepUp;
        this.widerruf = widerruf;
        this.alarm = alarm;
        this.antragGueltigkeit = antragGueltigkeit;
    }

    public Ergebnis aendern(MandantCode mandant, Benutzer akteur, String id, String ifMatch, String rollenCode,
                            Optional<IpAdresse> ip) {
        berechtigung.aktion(akteur, Aktion.A23);
        stepUp.pruefen();
        BenutzerAendern.ifMatchPruefen(ifMatch);
        Rolle rolle = Rolle.ausRealmRollen(rollenCode == null ? List.of() : List.of(rollenCode))
                .orElseThrow(() -> new KonfigurationUngueltig(List.of(new KonfigurationUngueltig.Befund(
                        "rolle", "ROLLE_UNGUELTIG", "Bitte wählen Sie eine gültige Archiv-Rolle."))));
        UUID ziel;
        try {
            ziel = UUID.fromString(id);
            if (!ziel.toString().equalsIgnoreCase(id)) {
                throw new IllegalArgumentException("Ungültige Kennung");
            }
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new FachFehler(Fehlercode.NICHT_GEFUNDEN);
        }
        boolean antragNoetig = rolle == Rolle.INHABER && identitaet.inhaberZaehlen(mandant.wert()) >= 2;
        ProtokollAktion aktion = antragNoetig ? ProtokollAktion.BENUTZER_ANTRAG : ProtokollAktion.ROLLE_ZUWEISEN;
        ProtokollDetails.Verwaltungsvorgang art = antragNoetig ? ProtokollDetails.Verwaltungsvorgang.ANTRAG
                : ProtokollDetails.Verwaltungsvorgang.ROLLE_ZUWEISEN;
        UUID vorgangId = UUID.randomUUID();
        AtomicReference<IdentitaetsDienst.Benutzer> gefunden = new AtomicReference<>();
        Ergebnis ergebnis = vorgang.ausfuehren(mandant, eintrag(akteur, ip, vorgangId, ziel, aktion, art,
                        null, rolle, ProtokollDetails.Verwaltungsphase.ANGEFRAGT),
                () -> {
                    var benutzer = identitaet.benutzerLesen(mandant.wert(), id);
                    if (!ifMatch.equals("\"" + BenutzerEtag.berechnen(benutzer, BenutzerLesen.status(benutzer, status))
                            + "\"")) {
                        throw new FachFehler(Fehlercode.VERSION_KONFLIKT);
                    }
                    if (akteur.id().equals(id) || !rollen.lesen().contains(rolle)
                            || !akteur.rolle().darfVergeben(rolle)) {
                        berechtigung.eskalationVerweigern(akteur, Aktion.A23);
                    }
                    if (benutzer.rollen().size() == 1 && benutzer.rollen().contains(rolle)) {
                        throw new FachFehler(Fehlercode.STATUS_KONFLIKT);
                    }
                    long inhaber = identitaet.inhaberZaehlen(mandant.wert());
                    if ((rolle == Rolle.INHABER && inhaber >= 2) != antragNoetig) {
                        throw new FachFehler(Fehlercode.STATUS_KONFLIKT);
                    }
                    if (rolle != Rolle.INHABER && benutzer.rollen().contains(Rolle.INHABER)
                            && benutzer.aktiv() && benutzer.aktionen().isEmpty() && inhaber <= 1) {
                        throw new FachFehler(Fehlercode.LETZTER_INHABER);
                    }
                    gefunden.set(benutzer);
                }, () -> {
                    if (!antragNoetig) {
                        identitaet.rolleSetzen(mandant.wert(), id, rolle);
                    }
                    return new Ergebnis(antragNoetig ? UUID.randomUUID() : null);
                }, fehler -> {
                    if (antragNoetig) {
                        return VerwaltungVorgang.Kompensation.GEKLAERT;
                    }
                    widerruf.setzen(id, Instant.now());
                    return VerwaltungVorgang.Kompensation.UNKLAR;
                }, resultat -> {
                    if (antragNoetig) {
                        Instant jetzt = Instant.now();
                        antraege.anlegen(new VerwaltungAntragSpeicher.Antrag(resultat.antragId(),
                                VerwaltungAntragSpeicher.Art.INHABER_ROLLE, id, Rolle.INHABER, null,
                                akteur.id(), jetzt, jetzt.plus(antragGueltigkeit),
                                VerwaltungAntragSpeicher.Status.OFFEN, null, null));
                    } else {
                        widerruf.setzen(id, Instant.now());
                    }
                }, resultat -> eintrag(akteur, ip, vorgangId, ziel, aktion, art,
                        gefunden.get().rollen().size() == 1 ? gefunden.get().rollen().iterator().next() : null,
                        rolle, ProtokollDetails.Verwaltungsphase.ABGESCHLOSSEN));
        if (!antragNoetig && rolle == Rolle.INHABER) {
            alarm.fire(new InhaberVergabe(mandant, ziel));
        }
        return ergebnis;
    }

    private static ZugriffEintrag eintrag(Benutzer akteur, Optional<IpAdresse> ip, UUID vorgangId, UUID ziel,
                                          ProtokollAktion aktion, ProtokollDetails.Verwaltungsvorgang art,
                                          Rolle alt, Rolle neu, ProtokollDetails.Verwaltungsphase phase) {
        var details = new ProtokollDetails.Verwaltung(vorgangId, phase, ziel, art,
                alt == null ? null : ProtokollDetails.Verwaltungsrolle.valueOf(alt.name()),
                ProtokollDetails.Verwaltungsrolle.valueOf(neu.name()), List.of());
        return new ZugriffEintrag(Optional.of(akteur.id()), Optional.of(akteur.rolle().name()), aktion,
                Optional.empty(), ip, ProtokollDetails.verwaltung(details));
    }
}
