package ch.urinet.archiv.benutzer.application;

import ch.urinet.archiv.benutzer.domain.BenutzerFormregeln;
import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.gemeinsam.domain.KonfigurationUngueltig;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.protokoll.application.BenutzerVerzeichnis;
import ch.urinet.archiv.protokoll.application.ZugriffProtokoll;
import ch.urinet.archiv.protokoll.domain.IpAdresse;
import ch.urinet.archiv.protokoll.domain.ProtokollAktion;
import ch.urinet.archiv.protokoll.domain.ProtokollDetails;
import ch.urinet.archiv.protokoll.domain.VerzeichnisEintrag;
import ch.urinet.archiv.protokoll.domain.ZugriffEintrag;
import ch.urinet.archiv.sicherheit.application.Berechtigung;
import ch.urinet.archiv.sicherheit.domain.Aktion;
import ch.urinet.archiv.sicherheit.domain.Benutzer;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@ApplicationScoped
public class BenutzerAnlegen {
    private static final Set<IdentitaetsDienst.ErforderlicheAktion> AKTIONEN = Set.of(
            IdentitaetsDienst.ErforderlicheAktion.VERIFY_EMAIL,
            IdentitaetsDienst.ErforderlicheAktion.UPDATE_PASSWORD,
            IdentitaetsDienst.ErforderlicheAktion.CONFIGURE_TOTP);

    private final IdentitaetsDienst identitaet;
    private final MandantenRollen rollen;
    private final BenutzerNamen namen;
    private final BenutzerVerzeichnis verzeichnis;
    private final VerwaltungAntragSpeicher antraege;
    private final ZugriffProtokoll protokoll;
    private final VerwaltungVorgang vorgang;
    private final Berechtigung berechtigung;
    private final StepUp stepUp;
    private final int maximum;
    private final Duration einladung;
    private final Duration antrag;

    public BenutzerAnlegen(IdentitaetsDienst identitaet, MandantenRollen rollen, BenutzerNamen namen,
                           BenutzerVerzeichnis verzeichnis, VerwaltungAntragSpeicher antraege,
                           ZugriffProtokoll protokoll, VerwaltungVorgang vorgang, Berechtigung berechtigung,
                           StepUp stepUp,
                           @ConfigProperty(name = "archiv.verwaltung.max-benutzer", defaultValue = "25") int maximum,
                           @ConfigProperty(name = "archiv.verwaltung.einladung-gueltigkeit", defaultValue = "PT24H")
                           Duration einladung,
                           @ConfigProperty(name = "archiv.verwaltung.antrag-gueltigkeit", defaultValue = "PT72H")
                           Duration antrag) {
        if (maximum < 1 || einladung.isNegative() || einladung.isZero()
                || einladung.compareTo(Duration.ofDays(7)) > 0 || antrag.isNegative() || antrag.isZero()) {
            throw new IllegalArgumentException("Ungültige Verwaltungsgrenzen");
        }
        this.identitaet = identitaet;
        this.rollen = rollen;
        this.namen = namen;
        this.verzeichnis = verzeichnis;
        this.antraege = antraege;
        this.protokoll = protokoll;
        this.vorgang = vorgang;
        this.berechtigung = berechtigung;
        this.stepUp = stepUp;
        this.maximum = maximum;
        this.einladung = einladung;
        this.antrag = antrag;
    }

    public record Eingabe(String benutzername, String email, String vorname, String nachname, String rolle) {
        @Override
        public String toString() {
            return "Eingabe[geschützt]";
        }
    }

    public record Ergebnis(UUID id, UUID antragId) {
    }

    public Ergebnis anlegen(MandantCode mandant, Benutzer akteur, Eingabe eingabe, Optional<IpAdresse> ip) {
        berechtigung.aktion(akteur, Aktion.A22);
        berechtigung.aktion(akteur, Aktion.A23);
        stepUp.pruefen();
        if (eingabe == null) {
            throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
        }
        BenutzerFormregeln.pruefeAnlage(eingabe.benutzername(), eingabe.email(), eingabe.vorname(), eingabe.nachname());
        Rolle rolle = Rolle.ausRealmRollen(eingabe.rolle() == null ? List.of() : List.of(eingabe.rolle()))
                .orElseThrow(() -> new KonfigurationUngueltig(List.of(new KonfigurationUngueltig.Befund(
                        "rolle", "ROLLE_UNGUELTIG", "Bitte wählen Sie eine gültige Archiv-Rolle."))));
        if (!rollen.lesen().contains(rolle) || !akteur.rolle().darfVergeben(rolle)) {
            berechtigung.eskalationVerweigern(akteur, Aktion.A23);
        }
        UUID vorgangId = UUID.randomUUID();
        var rolleNeu = ProtokollDetails.Verwaltungsrolle.valueOf(rolle.name());
        ZugriffEintrag absicht = eintrag(akteur, ip, vorgangId, null, rolleNeu,
                ProtokollDetails.Verwaltungsphase.ANGEFRAGT);
        AtomicBoolean vierAugen = new AtomicBoolean();
        AtomicReference<String> angelegt = new AtomicReference<>();
        return vorgang.ausfuehren(mandant, absicht, () -> {
            if (identitaet.benutzerZaehlen(mandant.wert()) >= maximum) {
                throw new FachFehler(Fehlercode.STATUS_KONFLIKT);
            }
            if (namen.vergeben(eingabe.benutzername())) {
                throw new FachFehler(Fehlercode.BENUTZER_EXISTIERT);
            }
            vierAugen.set(rolle == Rolle.INHABER && identitaet.inhaberZaehlen(mandant.wert()) >= 2);
        }, () -> {
            var neu = new IdentitaetsDienst.Neuanlage(eingabe.benutzername(), eingabe.email(),
                    eingabe.vorname(), eingabe.nachname());
            String id = vierAugen.get() ? identitaet.anlegenOhneRolle(mandant.wert(), neu)
                    : identitaet.anlegen(mandant.wert(), neu, rolle);
            angelegt.set(id);
            UUID ziel;
            try {
                ziel = UUID.fromString(id);
                if (!ziel.toString().equalsIgnoreCase(id)) {
                    throw new IllegalArgumentException("Ungültige Zielkennung");
                }
            } catch (IllegalArgumentException e) {
                throw new FachFehler(Fehlercode.IDENTITAETSDIENST_FEHLER);
            }
            try {
                identitaet.einladen(mandant.wert(), id, AKTIONEN, einladung);
            } catch (RuntimeException fehler) {
                throw new VerwaltungVorgang.ArbeitFehler(ziel, fehler);
            }
            return new Ergebnis(ziel, vierAugen.get() ? UUID.randomUUID() : null);
        }, fehler -> {
            if (fehler instanceof IdentitaetsDienst.KonfliktException) {
                return VerwaltungVorgang.Kompensation.GEKLAERT;
            }
            if (angelegt.get() != null) {
                identitaet.loeschen(mandant.wert(), angelegt.get());
            }
            return VerwaltungVorgang.Kompensation.UNKLAR;
        }, ergebnis -> {
            verzeichnis.einfuegen(Optional.empty(), new BenutzerVerzeichnis.Neu(ergebnis.id().toString(),
                    Optional.of(eingabe.benutzername()), vierAugen.get() ? Optional.empty() : Optional.of(rolle.name()),
                    Optional.empty(), false, VerzeichnisEintrag.Anlass.ANLAGE, Optional.of(akteur.id())));
            if (ergebnis.antragId() != null) {
                Instant jetzt = Instant.now();
                antraege.anlegen(new VerwaltungAntragSpeicher.Antrag(ergebnis.antragId(),
                        VerwaltungAntragSpeicher.Art.INHABER_ROLLE, ergebnis.id().toString(), Rolle.INHABER,
                        null, akteur.id(), jetzt, jetzt.plus(antrag), VerwaltungAntragSpeicher.Status.OFFEN,
                        null, null));
                var details = new ProtokollDetails.Verwaltung(ergebnis.antragId(),
                        ProtokollDetails.Verwaltungsphase.ANGEFRAGT, ergebnis.id(),
                        ProtokollDetails.Verwaltungsvorgang.ANTRAG, null, rolleNeu, List.of());
                protokoll.schreiben(new ZugriffEintrag(Optional.of(akteur.id()), Optional.of(akteur.rolle().name()),
                        ProtokollAktion.BENUTZER_ANTRAG, Optional.empty(), ip, ProtokollDetails.verwaltung(details)));
            }
        }, ergebnis -> eintrag(akteur, ip, vorgangId, ergebnis.id(), rolleNeu,
                ProtokollDetails.Verwaltungsphase.ABGESCHLOSSEN));
    }

    private static ZugriffEintrag eintrag(Benutzer akteur, Optional<IpAdresse> ip, UUID vorgangId, UUID ziel,
                                          ProtokollDetails.Verwaltungsrolle rolle,
                                          ProtokollDetails.Verwaltungsphase phase) {
        var details = new ProtokollDetails.Verwaltung(vorgangId, phase, ziel,
                ProtokollDetails.Verwaltungsvorgang.ANLEGEN, null, rolle, List.of());
        return new ZugriffEintrag(Optional.of(akteur.id()), Optional.of(akteur.rolle().name()),
                ProtokollAktion.BENUTZER_ANLEGEN, Optional.empty(), ip, ProtokollDetails.verwaltung(details));
    }
}
