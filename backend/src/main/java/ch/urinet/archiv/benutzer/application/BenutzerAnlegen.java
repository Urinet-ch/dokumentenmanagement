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

    public record BenutzerAnlage(String benutzername, String email, String vorname, String nachname, String rolle) {
        @Override
        public String toString() {
            return "Eingabe[geschützt]";
        }
    }

    public record BenutzerAnlageErgebnis(UUID id, UUID antragId) {
    }

    private record Akteur(String id, Optional<String> rolle, Optional<Benutzer> angemeldet) {
    }

    public BenutzerAnlageErgebnis anlegen(MandantCode mandant, Benutzer akteur, BenutzerAnlage eingabe,
                                         Optional<IpAdresse> ip) {
        berechtigung.aktion(akteur, Aktion.A22);
        berechtigung.aktion(akteur, Aktion.A23);
        stepUp.pruefen();
        return anlegen(mandant, new Akteur(akteur.id(), Optional.of(akteur.rolle().name()), Optional.of(akteur)),
                eingabe, ip);
    }

    public UUID erstenInhaberEinladen(MandantCode mandant, String betreiber, BenutzerAnlage eingabe) {
        if (betreiber == null || !betreiber.strip().equals(betreiber)
                || !betreiber.matches("[^\\p{Cntrl}]{1,80}")) {
            throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
        }
        if (eingabe == null || !"inhaber".equals(eingabe.rolle())) {
            throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
        }
        return anlegen(mandant, new Akteur("betreiber:" + betreiber, Optional.empty(), Optional.empty()),
                eingabe, Optional.empty()).id();
    }

    private BenutzerAnlageErgebnis anlegen(MandantCode mandant, Akteur akteur, BenutzerAnlage eingabe,
                                          Optional<IpAdresse> ip) {
        if (eingabe == null) {
            throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
        }
        BenutzerFormregeln.pruefeAnlage(eingabe.benutzername(), eingabe.email(), eingabe.vorname(), eingabe.nachname());
        Rolle rolle = Rolle.ausRealmRollen(eingabe.rolle() == null ? List.of() : List.of(eingabe.rolle()))
                .orElseThrow(() -> new KonfigurationUngueltig(List.of(new KonfigurationUngueltig.Befund(
                        "rolle", "ROLLE_UNGUELTIG", "Bitte wählen Sie eine gültige Archiv-Rolle."))));
        if (!rollen.lesen().contains(rolle)
                || akteur.angemeldet().filter(benutzer -> !benutzer.rolle().darfVergeben(rolle)).isPresent()) {
            if (akteur.angemeldet().isPresent()) {
                berechtigung.eskalationVerweigern(akteur.angemeldet().get(), Aktion.A23);
            } else {
                throw new FachFehler(Fehlercode.KONFIGURATION_UNGUELTIG);
            }
        }
        UUID vorgangId = UUID.randomUUID();
        var rolleNeu = ProtokollDetails.Verwaltungsrolle.valueOf(rolle.name());
        ZugriffEintrag absicht = eintrag(akteur, ip, vorgangId, null, rolleNeu,
                ProtokollDetails.Verwaltungsphase.ANGEFRAGT);
        AtomicBoolean vierAugen = new AtomicBoolean();
        AtomicBoolean arbeitAbgeschlossen = new AtomicBoolean();
        AtomicReference<String> angelegt = new AtomicReference<>();
        try {
            return vorgang.ausfuehren(mandant, absicht, () -> {
                if (identitaet.benutzerZaehlen(mandant.wert()) >= maximum) {
                    throw new FachFehler(Fehlercode.STATUS_KONFLIKT);
                }
                if (namen.vergeben(eingabe.benutzername())) {
                    throw new FachFehler(Fehlercode.BENUTZER_EXISTIERT);
                }
                if (akteur.angemeldet().isEmpty() && inhaberVorhanden(mandant)) {
                    throw new FachFehler(Fehlercode.STATUS_KONFLIKT);
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
                arbeitAbgeschlossen.set(true);
                return new BenutzerAnlageErgebnis(ziel, vierAugen.get() ? UUID.randomUUID() : null);
            }, fehler -> {
                if (fehler instanceof IdentitaetsDienst.KonfliktException) {
                    return VerwaltungVorgang.Kompensation.GEKLAERT;
                }
                if (fehler instanceof IdentitaetsDienst.AnlageException anlageFehler) {
                    return anlageFehler.entfernt() && sicherAbgewiesen(anlageFehler.getCause())
                            ? VerwaltungVorgang.Kompensation.GEKLAERT : VerwaltungVorgang.Kompensation.UNKLAR;
                }
                if (angelegt.get() != null) {
                    identitaet.anlageRueckgaengig(mandant.wert(), angelegt.get());
                    if (arbeitAbgeschlossen.get() || fehler instanceof VerwaltungVorgang.ArbeitFehler arbeitFehler
                            && sicherAbgewiesen(arbeitFehler.getCause())) {
                        return VerwaltungVorgang.Kompensation.GEKLAERT;
                    }
                }
                return VerwaltungVorgang.Kompensation.UNKLAR;
            }, ergebnis -> {
                namen.sperren(eingabe.benutzername());
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
                    protokoll.schreiben(new ZugriffEintrag(Optional.of(akteur.id()), akteur.rolle(),
                            ProtokollAktion.BENUTZER_ANTRAG, Optional.empty(), ip, ProtokollDetails.verwaltung(details)));
                }
            }, ergebnis -> eintrag(akteur, ip, vorgangId, ergebnis.id(), rolleNeu,
                    ProtokollDetails.Verwaltungsphase.ABGESCHLOSSEN));
        } catch (VerwaltungVorgang.ArbeitFehler | IdentitaetsDienst.AnlageException fehler) {
            throw new FachFehler(Fehlercode.IDENTITAETSDIENST_FEHLER);
        }
    }

    private static boolean sicherAbgewiesen(Throwable fehler) {
        return fehler instanceof FachFehler fachfehler && (fachfehler.code() == Fehlercode.IDENTITAETSDIENST_FEHLER
                || fachfehler.code() == Fehlercode.UEBERLASTET || fachfehler.code() == Fehlercode.NICHT_GEFUNDEN);
    }

    private boolean inhaberVorhanden(MandantCode mandant) {
        for (int seite = 0; seite <= 200; seite++) {
            List<IdentitaetsDienst.Benutzer> benutzer = identitaet.benutzerSuchen(mandant.wert(), null, seite);
            if (benutzer.size() > IdentitaetsDienst.MAX_SEITENGROESSE) {
                throw new FachFehler(Fehlercode.IDENTITAETSDIENST_FEHLER);
            }
            if (benutzer.stream().anyMatch(b -> b.rollen().contains(Rolle.INHABER))) {
                return true;
            }
            if (benutzer.size() < IdentitaetsDienst.MAX_SEITENGROESSE) {
                return false;
            }
        }
        throw new FachFehler(Fehlercode.IDENTITAETSDIENST_FEHLER);
    }

    private static ZugriffEintrag eintrag(Akteur akteur, Optional<IpAdresse> ip, UUID vorgangId, UUID ziel,
                                          ProtokollDetails.Verwaltungsrolle rolle,
                                          ProtokollDetails.Verwaltungsphase phase) {
        var details = new ProtokollDetails.Verwaltung(vorgangId, phase, ziel,
                ProtokollDetails.Verwaltungsvorgang.ANLEGEN, null, rolle, List.of());
        return new ZugriffEintrag(Optional.of(akteur.id()), akteur.rolle(),
                ProtokollAktion.BENUTZER_ANLEGEN, Optional.empty(), ip, ProtokollDetails.verwaltung(details));
    }
}
