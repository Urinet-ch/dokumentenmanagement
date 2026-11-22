package ch.urinet.archiv.benutzer.application;

import ch.urinet.archiv.benutzer.domain.BenutzerStatus;
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
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

@ApplicationScoped
public class BenutzerAntraege {
    private final VerwaltungAntragSpeicher antraege;
    private final IdentitaetsDienst identitaet;
    private final BenutzerStatusSpeicher status;
    private final MandantenRollen rollen;
    private final VerwaltungVorgang vorgang;
    private final Berechtigung berechtigung;
    private final StepUp stepUp;
    private final TokenWiderruf widerruf;
    private final Event<BenutzerRolleAendern.InhaberVergabe> alarm;

    public BenutzerAntraege(VerwaltungAntragSpeicher antraege, IdentitaetsDienst identitaet,
                            BenutzerStatusSpeicher status, MandantenRollen rollen, VerwaltungVorgang vorgang,
                            Berechtigung berechtigung, StepUp stepUp, TokenWiderruf widerruf,
                            Event<BenutzerRolleAendern.InhaberVergabe> alarm) {
        this.antraege = antraege;
        this.identitaet = identitaet;
        this.status = status;
        this.rollen = rollen;
        this.vorgang = vorgang;
        this.berechtigung = berechtigung;
        this.stepUp = stepUp;
        this.widerruf = widerruf;
        this.alarm = alarm;
    }

    public record Ansicht(UUID id, VerwaltungAntragSpeicher.Art art, String ziel, Rolle rolle,
                          String grund, String beantragtVon, Instant beantragtAm, Instant gueltigBis) {
    }

    public List<Ansicht> offene() {
        return antraege.offene(Instant.now()).stream().map(antrag -> new Ansicht(antrag.id(), antrag.art(),
                antrag.ziel(), antrag.rolle(), kategorie(antrag.grund()), antrag.beantragtVon(),
                antrag.beantragtAm(), antrag.gueltigBis())).toList();
    }

    public void entscheiden(MandantCode mandant, Benutzer akteur, UUID id, VerwaltungAntragSpeicher.Status entscheid,
                            Optional<IpAdresse> ip) {
        berechtigung.aktion(akteur, Aktion.A23);
        if (entscheid == VerwaltungAntragSpeicher.Status.BESTAETIGT) {
            stepUp.pruefen();
        }
        if (entscheid != VerwaltungAntragSpeicher.Status.BESTAETIGT
                && entscheid != VerwaltungAntragSpeicher.Status.ABGELEHNT
                && entscheid != VerwaltungAntragSpeicher.Status.ZURUECKGEZOGEN) {
            throw new IllegalArgumentException("Ungültiger Antragsentscheid");
        }
        var initial = antraege.lesen(id).orElseThrow(() -> new FachFehler(Fehlercode.NICHT_GEFUNDEN));
        UUID ziel;
        try {
            ziel = UUID.fromString(initial.ziel());
            if (!ziel.toString().equalsIgnoreCase(initial.ziel())) {
                throw new IllegalArgumentException("Ungültige Kennung");
            }
        } catch (IllegalArgumentException e) {
            throw new FachFehler(Fehlercode.STATUS_KONFLIKT);
        }
        boolean bestaetigen = entscheid == VerwaltungAntragSpeicher.Status.BESTAETIGT;
        ProtokollAktion aktion = bestaetigen ? ProtokollAktion.ROLLE_ZUWEISEN : ProtokollAktion.BENUTZER_ANTRAG;
        ProtokollDetails.Verwaltungsvorgang art = bestaetigen ? ProtokollDetails.Verwaltungsvorgang.ROLLE_ZUWEISEN
                : ProtokollDetails.Verwaltungsvorgang.ANTRAG;
        Rolle zielRolle = initial.art() == VerwaltungAntragSpeicher.Art.INHABER_ROLLE ? Rolle.INHABER : null;
        UUID vorgangId = UUID.randomUUID();
        AtomicReference<IdentitaetsDienst.Benutzer> gefunden = new AtomicReference<>();
        vorgang.ausfuehren(mandant, eintrag(akteur, ip, vorgangId, ziel, aktion, art, null, zielRolle,
                        ProtokollDetails.Verwaltungsphase.ANGEFRAGT),
                () -> {
                    var antrag = antraege.lesen(id).orElseThrow(() -> new FachFehler(Fehlercode.NICHT_GEFUNDEN));
                    if (antrag.status() != VerwaltungAntragSpeicher.Status.OFFEN
                            || !antrag.gueltigBis().isAfter(Instant.now()) || !antrag.ziel().equals(initial.ziel())) {
                        throw new FachFehler(Fehlercode.STATUS_KONFLIKT);
                    }
                    var ausfuehrender = identitaet.benutzerLesen(mandant.wert(), akteur.id());
                    if (!ausfuehrender.rollen().contains(Rolle.INHABER)
                            || BenutzerLesen.status(ausfuehrender, status) != BenutzerStatus.AKTIV) {
                        berechtigung.eskalationVerweigern(akteur, Aktion.A23);
                    }
                    boolean antragsteller = antrag.beantragtVon().equals(akteur.id());
                    if ((entscheid == VerwaltungAntragSpeicher.Status.ZURUECKGEZOGEN) != antragsteller) {
                        berechtigung.eskalationVerweigern(akteur, Aktion.A23);
                    }
                    var benutzer = identitaet.benutzerLesen(mandant.wert(), antrag.ziel());
                    if (bestaetigen) {
                        if (antrag.art() != VerwaltungAntragSpeicher.Art.INHABER_ROLLE
                                || !rollen.lesen().contains(Rolle.INHABER)) {
                            throw new FachFehler(Fehlercode.STATUS_KONFLIKT);
                        }
                        if (akteur.id().equals(antrag.ziel())) {
                            berechtigung.eskalationVerweigern(akteur, Aktion.A23);
                        }
                        if (benutzer.rollen().size() == 1 && benutzer.rollen().contains(Rolle.INHABER)) {
                            throw new FachFehler(Fehlercode.STATUS_KONFLIKT);
                        }
                        gefunden.set(benutzer);
                    }
                }, () -> {
                    if (bestaetigen) {
                        identitaet.rolleSetzen(mandant.wert(), initial.ziel(), Rolle.INHABER);
                    }
                    return ziel;
                }, fehler -> {
                    if (!bestaetigen) {
                        return VerwaltungVorgang.Kompensation.GEKLAERT;
                    }
                    widerruf.setzen(initial.ziel(), Instant.now());
                    return VerwaltungVorgang.Kompensation.UNKLAR;
                }, ignoriert -> {
                    if (bestaetigen) {
                        widerruf.setzen(initial.ziel(), Instant.now());
                    }
                    if (!antraege.entscheiden(id, entscheid, akteur.id(), Instant.now())) {
                        throw new FachFehler(Fehlercode.STATUS_KONFLIKT);
                    }
                }, ignoriert -> eintrag(akteur, ip, vorgangId, ziel, aktion, art,
                        gefunden.get() != null && gefunden.get().rollen().size() == 1
                                ? gefunden.get().rollen().iterator().next() : null,
                        zielRolle,
                        ProtokollDetails.Verwaltungsphase.ABGESCHLOSSEN));
        if (bestaetigen) {
            alarm.fire(new BenutzerRolleAendern.InhaberVergabe(mandant, ziel));
        }
    }

    private static String kategorie(String grund) {
        if (grund == null) {
            return null;
        }
        try {
            return ProtokollDetails.Verwaltungsgrund.valueOf(grund).name();
        } catch (IllegalArgumentException e) {
            return "NICHT_ANZEIGBAR";
        }
    }

    private static ZugriffEintrag eintrag(Benutzer akteur, Optional<IpAdresse> ip, UUID vorgangId, UUID ziel,
                                          ProtokollAktion aktion, ProtokollDetails.Verwaltungsvorgang art,
                                          Rolle alt, Rolle neu, ProtokollDetails.Verwaltungsphase phase) {
        var details = new ProtokollDetails.Verwaltung(vorgangId, phase, ziel, art,
                alt == null ? null : ProtokollDetails.Verwaltungsrolle.valueOf(alt.name()),
                neu == null ? null : ProtokollDetails.Verwaltungsrolle.valueOf(neu.name()), List.of());
        return new ZugriffEintrag(Optional.of(akteur.id()), Optional.of(akteur.rolle().name()), aktion,
                Optional.empty(), ip, ProtokollDetails.verwaltung(details));
    }
}
