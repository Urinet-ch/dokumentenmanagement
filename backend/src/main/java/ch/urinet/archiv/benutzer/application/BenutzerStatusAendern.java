package ch.urinet.archiv.benutzer.application;

import ch.urinet.archiv.benutzer.domain.BenutzerStatus;
import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.gemeinsam.domain.KonfigurationUngueltig;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.protokoll.application.BenutzerVerzeichnis;
import ch.urinet.archiv.protokoll.domain.IpAdresse;
import ch.urinet.archiv.protokoll.domain.ProtokollAktion;
import ch.urinet.archiv.protokoll.domain.ProtokollDetails;
import ch.urinet.archiv.protokoll.domain.VerzeichnisEintrag;
import ch.urinet.archiv.protokoll.domain.ZugriffEintrag;
import ch.urinet.archiv.sicherheit.application.Berechtigung;
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
public class BenutzerStatusAendern {
    public enum Vorgang { SPERREN, ENTSPERREN, DEAKTIVIEREN, REAKTIVIEREN }

    private final IdentitaetsDienst identitaet;
    private final BenutzerStatusSpeicher status;
    private final BenutzerVerzeichnis verzeichnis;
    private final VerwaltungVorgang verwaltung;
    private final Berechtigung berechtigung;
    private final StepUp stepUp;
    private final Duration gueltigkeit;

    public BenutzerStatusAendern(IdentitaetsDienst identitaet, BenutzerStatusSpeicher status,
                                BenutzerVerzeichnis verzeichnis, VerwaltungVorgang verwaltung,
                                Berechtigung berechtigung, StepUp stepUp,
                                @ConfigProperty(name = "archiv.verwaltung.einladung-gueltigkeit",
                                        defaultValue = "PT24H") Duration gueltigkeit) {
        if (gueltigkeit.isZero() || gueltigkeit.isNegative() || gueltigkeit.compareTo(Duration.ofDays(7)) > 0) {
            throw new IllegalArgumentException("Ungültige Einladungsgültigkeit");
        }
        this.identitaet = identitaet;
        this.status = status;
        this.verzeichnis = verzeichnis;
        this.verwaltung = verwaltung;
        this.berechtigung = berechtigung;
        this.stepUp = stepUp;
        this.gueltigkeit = gueltigkeit;
    }

    public void ausfuehren(MandantCode mandant, Benutzer akteur, String id, Vorgang vorgang, String grund,
                          Optional<IpAdresse> ip) {
        berechtigung.aktion(akteur, Aktion.A22);
        if (vorgang == Vorgang.ENTSPERREN || vorgang == Vorgang.REAKTIVIEREN) {
            stepUp.pruefen();
        }
        ProtokollDetails.Verwaltungsgrund kategorie = grund(vorgang, grund);
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
        verwaltung.ausfuehren(mandant, eintrag(akteur, ip, vorgangId, ziel, vorgang, kategorie,
                        ProtokollDetails.Verwaltungsphase.ANGEFRAGT),
                () -> {
                    var benutzer = identitaet.benutzerLesen(mandant.wert(), id);
                    if (benutzer.rollen().size() > 1) {
                        throw new FachFehler(Fehlercode.STATUS_KONFLIKT);
                    }
                    Rolle rolle = benutzer.rollen().stream().findFirst().orElse(null);
                    if (vorgang == Vorgang.SPERREN || vorgang == Vorgang.DEAKTIVIEREN) {
                        var selbstaktion = vorgang == Vorgang.SPERREN
                                ? Vergaberegeln.Selbstaktion.SPERREN : Vergaberegeln.Selbstaktion.DEAKTIVIEREN;
                        if (Vergaberegeln.pruefeSelbstzugriff(akteur, id, selbstaktion)
                                != Vergaberegeln.Pruefung.ERLAUBT) {
                            berechtigung.eskalationVerweigern(akteur, Aktion.A22);
                        }
                    }
                    BenutzerStatus bisher = BenutzerLesen.status(benutzer, status);
                    boolean erlaubt = switch (vorgang) {
                        case SPERREN -> bisher == BenutzerStatus.AKTIV;
                        case ENTSPERREN -> bisher == BenutzerStatus.GESPERRT
                                || (bisher == BenutzerStatus.AKTIV || bisher == BenutzerStatus.EINGELADEN)
                                && identitaet.anmeldungGesperrt(mandant.wert(), id);
                        case DEAKTIVIEREN -> bisher == BenutzerStatus.AKTIV || bisher == BenutzerStatus.GESPERRT
                                || bisher == BenutzerStatus.EINGELADEN;
                        case REAKTIVIEREN -> bisher == BenutzerStatus.DEAKTIVIERT;
                    };
                    if (!erlaubt) {
                        throw new FachFehler(Fehlercode.STATUS_KONFLIKT);
                    }
                    if ((vorgang == Vorgang.SPERREN || vorgang == Vorgang.DEAKTIVIEREN)
                            && rolle == Rolle.INHABER && benutzer.aktiv() && benutzer.aktionen().isEmpty()
                            && identitaet.inhaberZaehlen(mandant.wert()) <= 1) {
                        throw new FachFehler(Fehlercode.LETZTER_INHABER);
                    }
                    gefunden.set(benutzer);
                }, () -> {
                    switch (vorgang) {
                        case SPERREN, DEAKTIVIEREN -> {
                            identitaet.aktivieren(mandant.wert(), id, false);
                            identitaet.sitzungenBeenden(mandant.wert(), id);
                        }
                        case ENTSPERREN -> {
                            identitaet.sperreAufheben(mandant.wert(), id);
                            identitaet.aktivieren(mandant.wert(), id, true);
                        }
                        case REAKTIVIEREN -> {
                            var aktionen = EnumSet.noneOf(IdentitaetsDienst.ErforderlicheAktion.class);
                            aktionen.addAll(gefunden.get().aktionen());
                            aktionen.addAll(Set.of(IdentitaetsDienst.ErforderlicheAktion.UPDATE_PASSWORD,
                                    IdentitaetsDienst.ErforderlicheAktion.CONFIGURE_TOTP));
                            identitaet.aktionenHinzufuegen(mandant.wert(), id, aktionen);
                            identitaet.otpEntfernen(mandant.wert(), id);
                            identitaet.aktivieren(mandant.wert(), id, true);
                            identitaet.einladen(mandant.wert(), id, aktionen, gueltigkeit);
                        }
                    }
                    return ziel;
                }, fehler -> VerwaltungVorgang.Kompensation.UNKLAR, ignoriert -> {
                    if (vorgang == Vorgang.SPERREN || vorgang == Vorgang.DEAKTIVIEREN) {
                        BenutzerStatus neu = vorgang == Vorgang.SPERREN
                                ? BenutzerStatus.GESPERRT : BenutzerStatus.DEAKTIVIERT;
                        status.setzen(id, new BenutzerStatusSpeicher.Eintrag(neu, Instant.now(), kategorie, akteur.id()));
                        if (vorgang == Vorgang.DEAKTIVIEREN) {
                            austritt(gefunden.get(), akteur);
                        }
                    } else {
                        status.entfernen(id);
                    }
                }, ignoriert -> eintrag(akteur, ip, vorgangId, ziel, vorgang, kategorie,
                        ProtokollDetails.Verwaltungsphase.ABGESCHLOSSEN));
    }

    private void austritt(IdentitaetsDienst.Benutzer benutzer, Benutzer akteur) {
        verzeichnis.sperren(Optional.empty(), benutzer.id(), BenutzerVerzeichnis.Sperre.ABSCHLUSS);
        var letzter = verzeichnis.letzter(Optional.empty(), benutzer.id());
        verzeichnis.einfuegen(Optional.empty(), new BenutzerVerzeichnis.Neu(benutzer.id(),
                Optional.ofNullable(benutzer.benutzername()),
                benutzer.rollen().stream().findFirst().map(Enum::name),
                letzter.map(VerzeichnisEintrag::gueltigVon), true, VerzeichnisEintrag.Anlass.AUSTRITT,
                Optional.of(akteur.id())));
    }

    private static ProtokollDetails.Verwaltungsgrund grund(Vorgang vorgang, String grund) {
        if (vorgang != Vorgang.SPERREN && vorgang != Vorgang.DEAKTIVIEREN) {
            return null;
        }
        try {
            return ProtokollDetails.Verwaltungsgrund.valueOf(grund);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new KonfigurationUngueltig(List.of(new KonfigurationUngueltig.Befund(
                    "grund", "GRUND_UNGUELTIG", "Bitte wählen Sie eine gültige Grundkategorie.")));
        }
    }

    private static ZugriffEintrag eintrag(Benutzer akteur, Optional<IpAdresse> ip, UUID vorgangId, UUID ziel,
                                          Vorgang vorgang, ProtokollDetails.Verwaltungsgrund grund,
                                          ProtokollDetails.Verwaltungsphase phase) {
        var details = new ProtokollDetails.Verwaltung(vorgangId, phase, ziel,
                ProtokollDetails.Verwaltungsvorgang.valueOf(vorgang.name()), null, null, List.of(), grund);
        return new ZugriffEintrag(Optional.of(akteur.id()), Optional.of(akteur.rolle().name()),
                ProtokollAktion.BENUTZER_AENDERN, Optional.empty(), ip, ProtokollDetails.verwaltung(details));
    }
}
