package ch.urinet.archiv.benutzer.application;

import ch.urinet.archiv.benutzer.domain.BenutzerFormregeln;
import ch.urinet.archiv.benutzer.domain.BenutzerStatus;
import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.protokoll.domain.IpAdresse;
import ch.urinet.archiv.protokoll.domain.ProtokollAktion;
import ch.urinet.archiv.protokoll.domain.ProtokollDetails;
import ch.urinet.archiv.protokoll.domain.ZugriffEintrag;
import ch.urinet.archiv.sicherheit.application.Berechtigung;
import ch.urinet.archiv.sicherheit.domain.Aktion;
import ch.urinet.archiv.sicherheit.domain.Benutzer;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@ApplicationScoped
public class BenutzerAendern {
    private final IdentitaetsDienst identitaet;
    private final BenutzerStatusSpeicher status;
    private final BenutzerAktivitaet aktivitaet;
    private final BenutzerMitteilung mitteilung;
    private final VerwaltungVorgang vorgang;
    private final Berechtigung berechtigung;
    private final StepUp stepUp;
    private final Duration gueltigkeit;

    public BenutzerAendern(IdentitaetsDienst identitaet, BenutzerStatusSpeicher status,
                               BenutzerAktivitaet aktivitaet, BenutzerMitteilung mitteilung,
                               VerwaltungVorgang vorgang, Berechtigung berechtigung,
                               StepUp stepUp, @ConfigProperty(name = "archiv.verwaltung.einladung-gueltigkeit",
                                       defaultValue = "PT24H") Duration gueltigkeit) {
        if (gueltigkeit.isZero() || gueltigkeit.isNegative() || gueltigkeit.compareTo(Duration.ofDays(7)) > 0) {
            throw new IllegalArgumentException("Ungültige Einladungsgültigkeit");
        }
        this.identitaet = identitaet;
        this.status = status;
        this.aktivitaet = aktivitaet;
        this.mitteilung = mitteilung;
        this.vorgang = vorgang;
        this.berechtigung = berechtigung;
        this.stepUp = stepUp;
        this.gueltigkeit = gueltigkeit;
    }

    public void ausfuehren(MandantCode mandant, Benutzer akteur, String id, String ifMatch, String email,
                          String vorname, String nachname, Optional<IpAdresse> ip) {
        berechtigung.aktion(akteur, Aktion.A22);
        if (email != null) {
            stepUp.pruefen();
        }
        ifMatchPruefen(ifMatch);
        if (email == null && vorname == null && nachname == null) {
            throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
        }
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
        AtomicBoolean emailGeaendert = new AtomicBoolean();
        var felder = new ArrayList<ProtokollDetails.Verwaltungsfeld>();
        if (email != null) {
            felder.add(ProtokollDetails.Verwaltungsfeld.EMAIL);
        }
        if (vorname != null) {
            felder.add(ProtokollDetails.Verwaltungsfeld.VORNAME);
        }
        if (nachname != null) {
            felder.add(ProtokollDetails.Verwaltungsfeld.NACHNAME);
        }
        var art = email == null ? ProtokollDetails.Verwaltungsvorgang.NAME_AENDERN
                : ProtokollDetails.Verwaltungsvorgang.EMAIL_AENDERN;
        try {
            vorgang.ausfuehren(mandant, eintrag(akteur, ip, vorgangId, ziel, art, felder,
                            ProtokollDetails.Verwaltungsphase.ANGEFRAGT),
                () -> {
                    var benutzer = identitaet.benutzerLesen(mandant.wert(), id);
                    if (benutzer.rollen().size() > 1) {
                        throw new FachFehler(Fehlercode.STATUS_KONFLIKT);
                    }
                    BenutzerStatus zustand = BenutzerLesen.status(benutzer, status);
                    String etag = BenutzerLesen.etag(benutzer, zustand, aktivitaet.lesen(id));
                    if (!ifMatch.equals("\"" + etag + "\"")) {
                        throw new FachFehler(Fehlercode.VERSION_KONFLIKT);
                    }
                    BenutzerFormregeln.pruefeNamen(vorname, nachname);
                    if (email != null) {
                        BenutzerFormregeln.pruefeEmail(email);
                        if (benutzer.email() == null || benutzer.email().isBlank() || email.equals(benutzer.email())) {
                            throw new FachFehler(Fehlercode.STATUS_KONFLIKT);
                        }
                    }
                    gefunden.set(benutzer);
                }, () -> {
                    identitaet.aktualisieren(mandant.wert(), id, new IdentitaetsDienst.Aenderung(email, vorname, nachname));
                    if (email != null) {
                        emailGeaendert.set(true);
                        var aktionen = EnumSet.noneOf(IdentitaetsDienst.ErforderlicheAktion.class);
                        aktionen.addAll(gefunden.get().aktionen());
                        aktionen.add(IdentitaetsDienst.ErforderlicheAktion.VERIFY_EMAIL);
                        identitaet.aktionenHinzufuegen(mandant.wert(), id,
                                Set.of(IdentitaetsDienst.ErforderlicheAktion.VERIFY_EMAIL));
                        identitaet.einladen(mandant.wert(), id, aktionen, gueltigkeit);
                        mitteilung.emailGeaendert(gefunden.get().email());
                    }
                    return ziel;
                }, fehler -> fehler instanceof IdentitaetsDienst.KonfliktException && !emailGeaendert.get()
                        ? VerwaltungVorgang.Kompensation.GEKLAERT : VerwaltungVorgang.Kompensation.UNKLAR,
                ignoriert -> { },
                ignoriert -> eintrag(akteur, ip, vorgangId, ziel, art, felder,
                        ProtokollDetails.Verwaltungsphase.ABGESCHLOSSEN));
        } catch (IdentitaetsDienst.KonfliktException e) {
            throw new FachFehler(emailGeaendert.get()
                    ? Fehlercode.IDENTITAETSDIENST_FEHLER : Fehlercode.BENUTZER_EXISTIERT);
        }
    }

    public static void ifMatchPruefen(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new FachFehler(Fehlercode.VERSION_FEHLT);
        }
        if (!ifMatch.matches("\"[0-9a-f]{64}\"")) {
            throw new FachFehler(Fehlercode.VERSION_KONFLIKT);
        }
    }

    private static ZugriffEintrag eintrag(Benutzer akteur, Optional<IpAdresse> ip, UUID vorgangId, UUID ziel,
                                          ProtokollDetails.Verwaltungsvorgang art,
                                          List<ProtokollDetails.Verwaltungsfeld> felder,
                                          ProtokollDetails.Verwaltungsphase phase) {
        var details = new ProtokollDetails.Verwaltung(vorgangId, phase, ziel,
                art, null, null, felder);
        return new ZugriffEintrag(Optional.of(akteur.id()), Optional.of(akteur.rolle().name()),
                ProtokollAktion.BENUTZER_AENDERN, Optional.empty(), ip, ProtokollDetails.verwaltung(details));
    }
}
