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
import ch.urinet.archiv.sicherheit.domain.Vergaberegeln;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class BenutzerLoeschen {
    public record Ergebnis(UUID antragId) {
    }

    private final IdentitaetsDienst identitaet;
    private final BenutzerStatusSpeicher status;
    private final BenutzerVerweise verweise;
    private final VerwaltungAntragSpeicher antraege;
    private final VerwaltungVorgang vorgang;
    private final Berechtigung berechtigung;
    private final StepUp stepUp;
    private final TokenWiderruf widerruf;
    private final Duration frist;
    private final Duration antragGueltigkeit;

    public BenutzerLoeschen(IdentitaetsDienst identitaet, BenutzerStatusSpeicher status, BenutzerVerweise verweise,
                            VerwaltungAntragSpeicher antraege, VerwaltungVorgang vorgang, Berechtigung berechtigung,
                            StepUp stepUp, TokenWiderruf widerruf,
                            @ConfigProperty(name = "archiv.verwaltung.loeschfrist", defaultValue = "P30D")
                            Duration frist,
                            @ConfigProperty(name = "archiv.verwaltung.antrag-gueltigkeit", defaultValue = "PT72H")
                            Duration antragGueltigkeit) {
        if (frist.compareTo(Duration.ofDays(7)) < 0 || antragGueltigkeit.isNegative() || antragGueltigkeit.isZero()) {
            throw new IllegalArgumentException("Ungültige Löschfrist oder Antragsgültigkeit");
        }
        this.identitaet = identitaet;
        this.status = status;
        this.verweise = verweise;
        this.antraege = antraege;
        this.vorgang = vorgang;
        this.berechtigung = berechtigung;
        this.stepUp = stepUp;
        this.widerruf = widerruf;
        this.frist = frist;
        this.antragGueltigkeit = antragGueltigkeit;
    }

    public BenutzerVerweise.Loeschvorschau vorschau(MandantCode mandant, Benutzer akteur, String id) {
        berechtigung.aktion(akteur, Aktion.A22);
        kennung(id);
        identitaet.benutzerLesen(mandant.wert(), id);
        return verweise.zaehlen(id);
    }

    public Ergebnis loeschen(MandantCode mandant, Benutzer akteur, String id, String grund, Optional<IpAdresse> ip) {
        berechtigung.aktion(akteur, Aktion.A22);
        stepUp.pruefen();
        ProtokollDetails.Verwaltungsgrund kategorie = VerwaltungsgrundPruefung.pruefen(grund);
        UUID ziel = kennung(id);
        boolean antragNoetig = identitaet.inhaberZaehlen(mandant.wert()) >= 2;
        ProtokollAktion aktion = antragNoetig ? ProtokollAktion.BENUTZER_ANTRAG : ProtokollAktion.BENUTZER_LOESCHEN;
        ProtokollDetails.Verwaltungsvorgang art = antragNoetig ? ProtokollDetails.Verwaltungsvorgang.ANTRAG
                : ProtokollDetails.Verwaltungsvorgang.LOESCHEN;
        UUID vorgangId = UUID.randomUUID();
        return vorgang.ausfuehren(mandant, eintrag(akteur, ip, vorgangId, ziel, aktion, art, kategorie,
                        ProtokollDetails.Verwaltungsphase.ANGEFRAGT),
                () -> {
                    pruefen(mandant, akteur, id);
                    if ((identitaet.inhaberZaehlen(mandant.wert()) >= 2) != antragNoetig) {
                        throw new FachFehler(Fehlercode.STATUS_KONFLIKT);
                    }
                }, () -> {
                    if (!antragNoetig) {
                        externLoeschen(mandant, id);
                    }
                    return new Ergebnis(antragNoetig ? UUID.randomUUID() : null);
                }, fehler -> {
                    if (antragNoetig) {
                        return VerwaltungVorgang.Kompensation.GEKLAERT;
                    }
                    widerruf.setzen(id, Instant.now());
                    return VerwaltungVorgang.Kompensation.UNKLAR;
                }, ergebnis -> {
                    if (antragNoetig) {
                        Instant jetzt = Instant.now();
                        antraege.anlegen(new VerwaltungAntragSpeicher.Antrag(ergebnis.antragId(),
                                VerwaltungAntragSpeicher.Art.LOESCHEN, id, null, kategorie.name(), akteur.id(),
                                jetzt, jetzt.plus(antragGueltigkeit), VerwaltungAntragSpeicher.Status.OFFEN,
                                null, null));
                    } else {
                        abschluss(id);
                    }
                }, ergebnis -> eintrag(akteur, ip, vorgangId, ziel, aktion, art, kategorie,
                        ProtokollDetails.Verwaltungsphase.ABGESCHLOSSEN));
    }

    void pruefen(MandantCode mandant, Benutzer akteur, String id) {
        var benutzer = identitaet.benutzerLesen(mandant.wert(), id);
        if (Vergaberegeln.pruefeSelbstzugriff(akteur, id, Vergaberegeln.Selbstaktion.LOESCHEN)
                != Vergaberegeln.Pruefung.ERLAUBT) {
            berechtigung.eskalationVerweigern(akteur, Aktion.A22);
        }
        var eintrag = status.lesen(id).orElseThrow(() -> new FachFehler(Fehlercode.STATUS_KONFLIKT));
        if (eintrag.status() != BenutzerStatus.DEAKTIVIERT || benutzer.aktiv()
                || eintrag.seit().plus(frist).isAfter(Instant.now()) || benutzer.rollen().size() > 1) {
            throw new FachFehler(Fehlercode.STATUS_KONFLIKT);
        }
        if (benutzer.rollen().contains(Rolle.INHABER) && identitaet.inhaberZaehlen(mandant.wert()) == 0) {
            throw new FachFehler(Fehlercode.LETZTER_INHABER);
        }
    }

    void externLoeschen(MandantCode mandant, String id) {
        identitaet.sitzungenBeenden(mandant.wert(), id);
        identitaet.loeschen(mandant.wert(), id);
    }

    void abschluss(String id) {
        widerruf.setzen(id, Instant.now());
        status.entfernen(id);
    }

    private static UUID kennung(String id) {
        try {
            UUID ziel = UUID.fromString(id);
            if (!ziel.toString().equalsIgnoreCase(id)) {
                throw new IllegalArgumentException("Ungültige Kennung");
            }
            return ziel;
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new FachFehler(Fehlercode.NICHT_GEFUNDEN);
        }
    }

    private static ZugriffEintrag eintrag(Benutzer akteur, Optional<IpAdresse> ip, UUID vorgangId, UUID ziel,
                                          ProtokollAktion aktion, ProtokollDetails.Verwaltungsvorgang art,
                                          ProtokollDetails.Verwaltungsgrund grund,
                                          ProtokollDetails.Verwaltungsphase phase) {
        var details = new ProtokollDetails.Verwaltung(vorgangId, phase, ziel, art, null, null, List.of(), grund);
        return new ZugriffEintrag(Optional.of(akteur.id()), Optional.of(akteur.rolle().name()), aktion,
                Optional.empty(), ip, ProtokollDetails.verwaltung(details));
    }
}
