package ch.urinet.archiv.benutzer.application;

import ch.urinet.archiv.benutzer.application.AbgleichIdentitaet.RealmBenutzer;
import ch.urinet.archiv.benutzer.application.AbgleichPruefung.Abweichung;
import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.mandant.application.MandantAbbauRegister;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.mandant.domain.MandantVerwaltungsrollen;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Einmaliger, rein lesender Keycloak-Abgleich mit optionaler Übernahme ins Benutzerverzeichnis. */
@ApplicationScoped
public class BenutzerAbgleichen {
    private static final int MAX_REALM_BENUTZER = 10_000;

    public record Befund(String code, UUID benutzer) {
    }

    public record Bericht(List<Befund> abweichungen, int uebernahmeGeplant, int uebernommen) {
        public Bericht {
            abweichungen = List.copyOf(abweichungen);
        }
    }

    private final AbgleichIdentitaet realm;
    private final AbgleichStand staende;
    private final MandantAbbauRegister mandanten;
    private final IdentitaetsDienst identitaet;
    private final BenutzerVerzeichnisUebernahme uebernahme;
    private final MandantCode betrieb;

    public BenutzerAbgleichen(AbgleichIdentitaet realm, AbgleichStand staende, MandantAbbauRegister mandanten,
                             IdentitaetsDienst identitaet, BenutzerVerzeichnisUebernahme uebernahme,
                             @ConfigProperty(name = "archiv.ueberwachung.betrieb-mandant") String betrieb) {
        this.realm = realm;
        this.staende = staende;
        this.mandanten = mandanten;
        this.identitaet = identitaet;
        this.uebernahme = uebernahme;
        this.betrieb = new MandantCode(betrieb);
    }

    public Bericht ausfuehren(MandantCode code, boolean pruefen) {
        var status = mandanten.status(code).orElseThrow(() -> new FachFehler(Fehlercode.MANDANT_FEHLT));
        if (!status.mandant().aktiv()) {
            List<Befund> aktive = benutzerLesen(code).stream()
                    .filter(IdentitaetsDienst.Benutzer::aktiv)
                    .map(benutzer -> new Befund("BENUTZER_AKTIV", UUID.fromString(benutzer.id())))
                    .toList();
            return new Bericht(aktive, 0, 0);
        }

        List<RealmBenutzer> realmBenutzer = realmLesen();
        AbgleichStand.Stand stand = staende.lesen(code);
        Set<Rolle> erlaubteRollen = rollen(status.mandant().verwaltungsrollen());
        List<Abweichung> abweichungen = AbgleichPruefung.vergleichen(code, code.equals(betrieb), realmBenutzer,
                erlaubteRollen, stand.verwaltet(), stand.dokumentierteInhaber(),
                stand.offeneVorgaenge().stream().map(AbgleichStand.OffenerVorgang::id).collect(Collectors.toSet()));
        List<IdentitaetsDienst.Benutzer> zuUebernehmen = uebernahme.fehlende(code, benutzerLesen(code));
        int uebernommen = pruefen ? 0 : uebernahme.uebernehmen(code, zuUebernehmen);
        return new Bericht(abweichungen.stream().map(befund ->
                new Befund(befund.code().name(), befund.id())).toList(), zuUebernehmen.size(), uebernommen);
    }

    private List<RealmBenutzer> realmLesen() {
        List<RealmBenutzer> ergebnis = new ArrayList<>();
        for (int seite = 0; ; seite++) {
            List<RealmBenutzer> treffer = realm.seite(seite);
            if (treffer.size() > IdentitaetsDienst.MAX_SEITENGROESSE
                    || ergebnis.size() + treffer.size() > MAX_REALM_BENUTZER) {
                throw new IllegalStateException("Realm-Abgleich überschreitet die Benutzergrenze");
            }
            ergebnis.addAll(treffer);
            if (treffer.size() < IdentitaetsDienst.MAX_SEITENGROESSE) {
                return List.copyOf(ergebnis);
            }
        }
    }

    private List<IdentitaetsDienst.Benutzer> benutzerLesen(MandantCode code) {
        List<IdentitaetsDienst.Benutzer> ergebnis = new ArrayList<>();
        for (int seite = 0; ; seite++) {
            List<IdentitaetsDienst.Benutzer> treffer = identitaet.benutzerSuchen(code.wert(), null, seite);
            if (treffer.size() > IdentitaetsDienst.MAX_SEITENGROESSE
                    || ergebnis.size() + treffer.size() > MAX_REALM_BENUTZER) {
                throw new IllegalStateException("Mandantenabgleich überschreitet die Benutzergrenze");
            }
            ergebnis.addAll(treffer);
            if (treffer.size() < IdentitaetsDienst.MAX_SEITENGROESSE) {
                return List.copyOf(ergebnis);
            }
        }
    }

    private static Set<Rolle> rollen(MandantVerwaltungsrollen verwaltungsrollen) {
        return verwaltungsrollen.werte().stream()
                .map(rolle -> Rolle.valueOf(rolle.toUpperCase(Locale.ROOT)))
                .collect(Collectors.toUnmodifiableSet());
    }
}
