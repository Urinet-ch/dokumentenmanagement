package ch.urinet.archiv.benutzer.adapter.out.stub;

import ch.urinet.archiv.benutzer.application.IdentitaetsDienst;
import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;
import io.quarkus.arc.profile.UnlessBuildProfile;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Speicherbasierter Identitätsdienst für Entwicklung und Tests, ohne tatsächlichen Mailversand. */
@ApplicationScoped
@UnlessBuildProfile("prod")
@Typed(IdentitaetsDienst.class)
public class IdentitaetsDienstStub implements IdentitaetsDienst {
    private final Map<String, Eintrag> benutzer = new HashMap<>();

    @Override
    public synchronized java.util.List<Benutzer> benutzerSuchen(String mandant, String suche, int seite, int groesse) {
        Objects.requireNonNull(mandant);
        int start = IdentitaetsDienst.seitenanfang(suche, seite, groesse);
        String filter = suche == null ? "" : suche.toLowerCase(Locale.ROOT);
        var treffer = new ArrayList<Benutzer>();
        benutzer.values().stream().filter(e -> e.mandant.equals(mandant))
                .map(e -> e.wert)
                .filter(b -> b.benutzername().toLowerCase(Locale.ROOT).contains(filter))
                .sorted(Comparator.comparing(Benutzer::benutzername))
                .forEach(treffer::add);
        if (start >= treffer.size()) {
            return java.util.List.of();
        }
        return java.util.List.copyOf(treffer.subList(start, (int) Math.min((long) start + groesse, treffer.size())));
    }

    @Override
    public synchronized Benutzer benutzerLesen(String mandant, String id) {
        return finden(mandant, id).wert;
    }

    @Override
    public synchronized String anlegen(String mandant, Neuanlage neu, Rolle rolle) {
        Objects.requireNonNull(mandant);
        Objects.requireNonNull(neu);
        Objects.requireNonNull(rolle);
        boolean vorhanden = benutzer.values().stream().map(e -> e.wert)
                .anyMatch(b -> b.benutzername().equalsIgnoreCase(neu.benutzername())
                        || b.email().equalsIgnoreCase(neu.email()));
        if (vorhanden) {
            throw new KonfliktException();
        }
        String id = UUID.randomUUID().toString();
        Benutzer wert = new Benutzer(id, neu.benutzername(), neu.email(), neu.vorname(), neu.nachname(), true,
                Set.of(rolle), Set.of(ErforderlicheAktion.VERIFY_EMAIL, ErforderlicheAktion.UPDATE_PASSWORD,
                ErforderlicheAktion.CONFIGURE_TOTP), false, false);
        benutzer.put(id, new Eintrag(mandant, wert));
        return id;
    }

    @Override
    public synchronized void aktualisieren(String mandant, String id, Aenderung aenderung) {
        Eintrag e = finden(mandant, id);
        Benutzer b = e.wert;
        e.wert = new Benutzer(id, b.benutzername(), aenderung.email() == null ? b.email() : aenderung.email(),
                aenderung.vorname() == null ? b.vorname() : aenderung.vorname(),
                aenderung.nachname() == null ? b.nachname() : aenderung.nachname(), b.aktiv(), b.rollen(),
                b.aktionen(), b.totp(), b.google());
    }

    @Override
    public synchronized void rolleSetzen(String mandant, String id, Rolle rolle) {
        Eintrag e = finden(mandant, id);
        Benutzer b = e.wert;
        e.wert = kopie(b, b.aktiv(), Set.of(Objects.requireNonNull(rolle)), b.aktionen(), b.totp(), b.google());
    }

    @Override
    public synchronized void aktivieren(String mandant, String id, boolean aktiv) {
        Eintrag e = finden(mandant, id);
        Benutzer b = e.wert;
        e.wert = kopie(b, aktiv, b.rollen(), b.aktionen(), b.totp(), b.google());
    }

    @Override
    public synchronized void einladen(String mandant, String id, Set<ErforderlicheAktion> aktionen, Duration gueltigkeit) {
        if (gueltigkeit.isZero() || gueltigkeit.isNegative() || gueltigkeit.compareTo(Duration.ofDays(7)) > 0) {
            throw new IllegalArgumentException("Ungültige Gültigkeit");
        }
        Eintrag e = finden(mandant, id);
        Benutzer b = e.wert;
        e.wert = kopie(b, b.aktiv(), b.rollen(), aktionen, b.totp(), b.google());
        e.einladungen++;
    }

    @Override
    public synchronized void sitzungenBeenden(String mandant, String id) {
        finden(mandant, id).sitzungenVersion++;
    }

    @Override
    public synchronized void otpEntfernen(String mandant, String id) {
        Eintrag e = finden(mandant, id);
        Benutzer b = e.wert;
        e.wert = kopie(b, b.aktiv(), b.rollen(), b.aktionen(), false, b.google());
    }

    @Override
    public synchronized void sperreAufheben(String mandant, String id) {
        finden(mandant, id);
    }

    @Override
    public synchronized void googleLoesen(String mandant, String id) {
        Eintrag e = finden(mandant, id);
        Benutzer b = e.wert;
        e.wert = kopie(b, b.aktiv(), b.rollen(), b.aktionen(), b.totp(), false);
    }

    @Override
    public synchronized void loeschen(String mandant, String id) {
        finden(mandant, id);
        benutzer.remove(id);
    }

    @Override
    public synchronized long inhaberZaehlen(String mandant) {
        return benutzer.values().stream().filter(e -> e.mandant.equals(mandant))
                .map(e -> e.wert)
                .filter(b -> b.aktiv() && b.aktionen().isEmpty() && b.rollen().contains(Rolle.INHABER))
                .count();
    }

    @Override
    public void verbindungPruefen() {
        // Der Stub besitzt keine externe Verbindung.
    }

    private Eintrag finden(String mandant, String id) {
        Eintrag e = benutzer.get(id);
        if (e == null || !e.mandant.equals(mandant)) {
            throw new FachFehler(Fehlercode.NICHT_GEFUNDEN);
        }
        return e;
    }

    private static Benutzer kopie(Benutzer b, boolean aktiv, Set<Rolle> rollen, Set<ErforderlicheAktion> aktionen,
                                  boolean totp, boolean google) {
        return new Benutzer(b.id(), b.benutzername(), b.email(), b.vorname(), b.nachname(), aktiv, rollen, aktionen,
                totp, google);
    }

    private static final class Eintrag {
        private final String mandant;
        private Benutzer wert;
        private int einladungen;
        private int sitzungenVersion;

        private Eintrag(String mandant, Benutzer wert) {
            this.mandant = mandant;
            this.wert = wert;
        }
    }
}
