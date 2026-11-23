package ch.urinet.archiv.benutzer.application;

import ch.urinet.archiv.benutzer.application.AbgleichIdentitaet.RealmBenutzer;
import ch.urinet.archiv.benutzer.application.AbgleichPruefung.Abweichung;
import ch.urinet.archiv.benutzer.application.AbgleichStand.OffenerVorgang;
import ch.urinet.archiv.mandant.application.MandantKontext;
import ch.urinet.archiv.mandant.application.MandantRegister;
import ch.urinet.archiv.mandant.domain.Mandant;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.protokoll.application.ZugriffProtokoll;
import ch.urinet.archiv.protokoll.domain.ProtokollAktion;
import ch.urinet.archiv.protokoll.domain.ProtokollDetails;
import ch.urinet.archiv.protokoll.domain.ProtokollDetails.Verwaltungsabweichung;
import ch.urinet.archiv.protokoll.domain.ZugriffEintrag;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import ch.urinet.archiv.verarbeitung.application.AlarmSpeicher;
import ch.urinet.archiv.verarbeitung.domain.Befund;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class Abgleich {
    private static final int MAX_REALM_BENUTZER = 10_000;
    private static final Logger LOG = Logger.getLogger(Abgleich.class);

    private final AbgleichIdentitaet identitaet;
    private final AbgleichStand staende;
    private final MandantRegister register;
    private final MandantKontext kontext;
    private final VerwaltungTransaktionen transaktionen;
    private final ZugriffProtokoll protokoll;
    private final AlarmSpeicher alarme;
    private final MandantCode betrieb;

    public Abgleich(AbgleichIdentitaet identitaet, AbgleichStand staende, MandantRegister register,
                    MandantKontext kontext, VerwaltungTransaktionen transaktionen, ZugriffProtokoll protokoll,
                    AlarmSpeicher alarme,
                    @ConfigProperty(name = "archiv.ueberwachung.betrieb-mandant") String betrieb) {
        this.identitaet = identitaet;
        this.staende = staende;
        this.register = register;
        this.kontext = kontext;
        this.transaktionen = transaktionen;
        this.protokoll = protokoll;
        this.alarme = alarme;
        this.betrieb = new MandantCode(betrieb);
    }

    /** Liest den Realm vollständig, bevor ein Mandantenstand geschrieben wird. */
    public void ausfuehren() {
        List<Mandant> mandanten = register.aktive();
        if (mandanten.isEmpty()) {
            return;
        }
        if (mandanten.stream().noneMatch(m -> m.code().equals(betrieb))) {
            throw new IllegalStateException("Betriebsmandant für den Abgleich ist nicht aktiv");
        }
        List<RealmBenutzer> realm = realmLesen();
        boolean fehlgeschlagen = false;
        for (Mandant mandant : mandanten) {
            try {
                kontext.mit(mandant.code(), () -> mandantAbgleichen(mandant, realm));
            } catch (RuntimeException e) {
                fehlgeschlagen = true;
                LOG.errorf("Abgleich von Mandant %s fehlgeschlagen: %s", mandant.code().wert(),
                        e.getClass().getName());
            }
        }
        if (fehlgeschlagen) {
            throw new IllegalStateException("Abgleich mindestens eines Mandanten fehlgeschlagen");
        }
    }

    private List<RealmBenutzer> realmLesen() {
        List<RealmBenutzer> ergebnis = new ArrayList<>();
        for (int seite = 0; ; seite++) {
            List<RealmBenutzer> benutzer = identitaet.seite(seite);
            if (benutzer.size() > IdentitaetsDienst.MAX_SEITENGROESSE
                    || ergebnis.size() + benutzer.size() > MAX_REALM_BENUTZER) {
                throw new IllegalStateException("Realm-Abgleich überschreitet die Benutzergrenze");
            }
            ergebnis.addAll(benutzer);
            if (benutzer.size() < IdentitaetsDienst.MAX_SEITENGROESSE) {
                return List.copyOf(ergebnis);
            }
        }
    }

    private void mandantAbgleichen(Mandant mandant, List<RealmBenutzer> realm) {
        MandantCode code = mandant.code();
        AbgleichStand.Stand stand = staende.lesen(code);
        Set<Rolle> rollen = mandant.verwaltungsrollen().werte().stream()
                .map(rolle -> Rolle.valueOf(rolle.toUpperCase(Locale.ROOT))).collect(Collectors.toUnmodifiableSet());
        transaktionen.neu(() -> {
            Set<UUID> offene = new HashSet<>();
            for (OffenerVorgang vorgang : stand.offeneVorgaenge()) {
                if (staende.unklar(code, vorgang.id())) {
                    offene.add(vorgang.id());
                } else if (staende.lesen(code).offeneVorgaenge().stream()
                        .anyMatch(v -> v.id().equals(vorgang.id()))) {
                    throw new IllegalStateException("Offener Vorgang hat keine passende angefragte Sperre");
                }
            }
            List<Abweichung> abweichungen = AbgleichPruefung.vergleichen(code, code.equals(betrieb), realm, rollen,
                    stand.verwaltet(), stand.dokumentierteInhaber(), offene);
            List<Befund> befunde = abweichungen.stream()
                    .map(a -> new Befund(Befund.Quelle.ABGLEICH, a.id().toString(), a.code().name())).toList();
            protokoll.schreiben(abweichungen.stream().map(Abgleich::eintrag).toList());
            alarme.abgleichen(code, Befund.Quelle.ABGLEICH, befunde);
        });
    }

    private static ZugriffEintrag eintrag(Abweichung abweichung) {
        boolean vorgang = abweichung.code() == Verwaltungsabweichung.VORGANG_OFFEN;
        var details = new ProtokollDetails.Verwaltung(vorgang ? abweichung.id() : UUID.randomUUID(),
                ProtokollDetails.Verwaltungsphase.ABGESCHLOSSEN, vorgang ? null : abweichung.id(),
                ProtokollDetails.Verwaltungsvorgang.ABWEICHUNG, null, null, List.of(), null, abweichung.code());
        return new ZugriffEintrag(Optional.empty(), Optional.empty(), ProtokollAktion.BENUTZER_ABWEICHUNG,
                Optional.empty(), Optional.empty(), ProtokollDetails.verwaltung(details));
    }
}
