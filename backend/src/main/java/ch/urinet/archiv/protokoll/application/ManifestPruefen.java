package ch.urinet.archiv.protokoll.application;

import ch.urinet.archiv.ablage.application.AblagePort;
import ch.urinet.archiv.ablage.domain.ManifestDatei;
import ch.urinet.archiv.ablage.domain.ManifestTeil;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.protokoll.domain.Jcs;
import ch.urinet.archiv.protokoll.domain.ManifestInhalt;
import ch.urinet.archiv.protokoll.domain.ManifestSignatur;
import ch.urinet.archiv.protokoll.domain.Pruefbericht;
import ch.urinet.archiv.protokoll.domain.Pruefdaten;
import ch.urinet.archiv.protokoll.domain.Sha256;
import ch.urinet.archiv.protokoll.domain.ZeitstempelVerweis;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Prüfwerkzeug {@code archiv manifest pruefen} (Req 15.12; design.md «Manifest»): prüft nur aus der Ablage, ohne Netz
 * und ohne Datenbank, je Datum Signatur, kanonische Form, Hash-Kette mit Erneuerung (Req 15.2) und jeden Zeitstempel
 * gegen seine abgelegten Prüfdaten. Fehler: fehlendes oder ungültiges Manifest, Lücke oder Bruch der Kette, kein gültiger
 * Zeitstempel, weitere Versionen oder Löschmarken unter einem Manifest-Schlüssel oder ein ungesperrter Teil (geprüft wird
 * immer die älteste Version). Meldungen: fehlender oder unvollständiger Zeitstempel eines Dienstes (ein {@code .tsr} ohne
 * Kette oder Prüfdaten, auf das das Folgemanifest nicht verweist, Req 15.11), Verspätung über
 * {@code archiv.manifest.verspaetung-schwelle}.
 * <p>
 * Geprüft werden die Tokens der konfigurierten Dienste und der Dienste, auf die das Folgemanifest verweist, damit ein
 * aus der Liste ausgetragener Dienst weiter geprüft wird.
 */
@ApplicationScoped
public class ManifestPruefen {

    private final AblagePort ablage;
    private final JsonLeser json;
    private final ManifestSchluessel schluessel;
    private final ZeitstempelPort zeitstempel;
    private final Duration schwelle;

    public ManifestPruefen(AblagePort ablage, JsonLeser json, ManifestSchluessel schluessel,
                           ZeitstempelPort zeitstempel,
                           @ConfigProperty(name = "archiv.manifest.verspaetung-schwelle", defaultValue = "PT24H")
                           Duration schwelle) {
        this.ablage = ablage;
        this.json = json;
        this.schluessel = schluessel;
        this.zeitstempel = zeitstempel;
        this.schwelle = schwelle;
    }

    public Pruefbericht pruefen(MandantCode mandant, LocalDate von, LocalDate bis) {
        if (bis.isBefore(von)) {
            throw new IllegalArgumentException("Das Enddatum liegt vor dem Startdatum");
        }
        Pruefbericht bericht = new Pruefbericht();
        PublicKey oeffentlich = schluessel.oeffentlich();
        Lauf lauf = new Lauf(mandant, bericht, new HashSet<>());
        Map<LocalDate, Optional<Gelesen>> gelesen = new HashMap<>();
        for (LocalDate datum = von; !datum.isAfter(bis); datum = datum.plusDays(1)) {
            Optional<Gelesen> manifest = gelesen.computeIfAbsent(datum, d -> lesen(lauf, d));
            if (manifest.isEmpty()) {
                bericht.fehler(datum + ": Manifest fehlt (Lücke in der Kette)");
                continue;
            }
            Gelesen g = manifest.get();
            if (g.inhalt() == null) {
                bericht.fehler(datum + ": " + g.fehler());
                continue;
            }
            einzeln(mandant, datum, g, oeffentlich, bericht);
            Optional<Gelesen> folge = gelesen.computeIfAbsent(datum.plusDays(1), d -> lesen(lauf, d));
            stempel(lauf, datum, g, folge.filter(f -> f.inhalt() != null));
            Optional<Gelesen> vorher = gelesen.computeIfAbsent(datum.minusDays(1), d -> lesen(lauf, d));
            kette(lauf, datum, g, vorher);
            bericht.geprueft();
        }
        return bericht;
    }

    private void einzeln(MandantCode mandant, LocalDate datum, Gelesen g, PublicKey oeffentlich, Pruefbericht bericht) {
        if (!g.inhalt().mandant().equals(mandant.wert()) || !g.inhalt().datum().equals(datum)) {
            bericht.fehler(datum + ": Mandant oder Datum im Manifest stimmt nicht mit dem Pfad überein");
        }
        if (g.sig() == null) {
            bericht.fehler(datum + ": Signatur fehlt");
        } else if (!ManifestSignatur.pruefen(oeffentlich, g.json(), g.sig())) {
            bericht.fehler(datum + ": Signatur ungültig");
        }
    }

    private void stempel(Lauf lauf, LocalDate datum, Gelesen g, Optional<Gelesen> folge) {
        Pruefbericht bericht = lauf.bericht();
        if (g.sig() == null) {
            return;
        }
        Set<String> dienste = new LinkedHashSet<>(zeitstempel.dienste());
        Set<String> verwiesen = new HashSet<>();
        folge.map(f -> f.inhalt().vorher()).ifPresent(v -> v.zeitstempel().forEach(z -> verwiesen.add(z.dienst())));
        dienste.addAll(verwiesen);
        List<String> unvollstaendig = new ArrayList<>();
        byte[] imprint = ManifestSignatur.imprint(g.json(), g.sig());
        Instant bis = g.inhalt().bis();
        int gueltig = 0;
        for (String dienst : dienste) {
            Optional<ManifestDatei> tsr = teil(lauf, datum, ManifestTeil.tsr(dienst));
            if (tsr.isEmpty()) {
                bericht.meldung(datum + ": Zeitstempel von " + dienst + " fehlt");
                continue;
            }
            Optional<ManifestDatei> kette = teil(lauf, datum, ManifestTeil.kette(dienst));
            Optional<Pruefdaten> pruefdaten = pruefdaten(lauf, datum, dienst);
            if (kette.isEmpty() || pruefdaten.isEmpty()) {
                if (verwiesen.contains(dienst)) {
                    bericht.fehler(datum + ": Prüfdaten des Zeitstempels von " + dienst + " fehlen");
                } else {
                    unvollstaendig.add(dienst);
                }
                continue;
            }
            try {
                Instant genTime = zeitstempel.pruefenAbgelegt(dienst, tsr.get().inhalt(), imprint,
                        new String(kette.get().inhalt(), StandardCharsets.UTF_8), pruefdaten.get());
                gueltig++;
                if (Duration.between(bis, genTime).compareTo(schwelle) > 0) {
                    bericht.meldung(datum + ": Zeitstempel von " + dienst + " verspätet (" + genTime + ", "
                            + Duration.between(bis, genTime).toHours() + " h nach dem Ende des Fensters)");
                }
            } catch (ZeitstempelPort.ZeitstempelFehler e) {
                bericht.fehler(datum + ": Zeitstempel von " + dienst + " ungültig: " + e.getMessage());
            }
        }
        if (gueltig == 0) {
            bericht.fehler(datum + ": kein gültiger Zeitstempel");
            return;
        }
        // Req 15.11: Die Erzeugung hält das Manifest nach dem letzten Versuch ohne diesen Dienst fest
        unvollstaendig.forEach(dienst -> bericht.meldung(datum + ": Zeitstempel von " + dienst
                + " unvollständig (Prüfdaten fehlen), nicht festgehalten"));
    }

    /** Hash-Kette und Erneuerung (Req 15.2): {@code vorher_sha256}, {@code von}, {@code .sig}, {@code .tsr}, Prüfdaten. */
    private void kette(Lauf lauf, LocalDate datum, Gelesen g, Optional<Gelesen> vorher) {
        Pruefbericht bericht = lauf.bericht();
        LocalDate tag = datum.minusDays(1);
        if (g.inhalt().vorherSha256() == null) {
            if (vorher.isPresent()) {
                bericht.fehler(datum + ": Kette neu begonnen, obwohl das Manifest " + tag + " vorliegt");
            }
            return;
        }
        if (vorher.isEmpty()) {
            bericht.fehler(datum + ": Lücke in der Kette, Manifest " + tag + " fehlt");
            return;
        }
        Gelesen v = vorher.get();
        if (!Sha256.hexVon(v.json()).equals(g.inhalt().vorherSha256())) {
            bericht.fehler(datum + ": vorher_sha256 passt nicht zum Manifest " + tag);
        }
        if (v.inhalt() != null && !v.inhalt().bis().equals(g.inhalt().von())) {
            bericht.fehler(datum + ": Fenster schliesst nicht an das Manifest " + tag + " an");
        }
        if (v.sig() == null || !Sha256.hexVon(v.sig()).equals(g.inhalt().vorher().sigSha256())) {
            bericht.fehler(datum + ": Erneuerung: Signatur von " + tag + " passt nicht");
        }
        for (ZeitstempelVerweis verweis : g.inhalt().vorher().zeitstempel()) {
            String dienst = verweis.dienst();
            Optional<ManifestDatei> tsr = teil(lauf, tag, ManifestTeil.tsr(dienst));
            if (tsr.isEmpty() || !Sha256.hexVon(tsr.get().inhalt()).equals(verweis.tsrSha256())) {
                bericht.fehler(datum + ": Erneuerung: Zeitstempel von " + dienst + " vom " + tag + " passt nicht");
                continue;
            }
            List<String> abgelegt = new ArrayList<>();
            teil(lauf, tag, ManifestTeil.kette(dienst))
                    .ifPresent(k -> abgelegt.add(Sha256.hexVon(k.inhalt())));
            pruefdaten(lauf, tag, dienst).ifPresent(p -> abgelegt.add(Sha256.hexVon(p.inhalt())));
            if (!abgelegt.equals(verweis.pruefdatenSha256())) {
                bericht.fehler(datum + ": Erneuerung: Prüfdaten von " + dienst + " vom " + tag + " passen nicht");
            }
        }
    }

    private Optional<Pruefdaten> pruefdaten(Lauf lauf, LocalDate datum, String dienst) {
        for (Pruefdaten.Art art : Pruefdaten.Art.values()) {
            Optional<ManifestDatei> datei = teil(lauf, datum, ManifestErzeugen.teil(dienst, art));
            if (datei.isPresent()) {
                return Optional.of(new Pruefdaten(art, datei.get().inhalt()));
            }
        }
        return Optional.empty();
    }

    /**
     * Liest die älteste Version eines Teils. Weitere Versionen oder Löschmarken und ein ungesperrter Teil sind Fehler
     * (je Schlüssel einmal gemeldet): Jeder Schlüssel unter {@code manifest/} wird nur einmal gesperrt geschrieben.
     */
    private Optional<ManifestDatei> teil(Lauf lauf, LocalDate datum, ManifestTeil teil) {
        Optional<ManifestDatei> datei = ablage.lesenManifest(lauf.mandant(), datum, teil);
        if (datei.isPresent() && lauf.gemeldet().add(teil.pfad(datum))) {
            if (datei.get().weitere() > 0) {
                lauf.bericht().fehler(datum + ": unter " + teil.pfad(datum) + " liegen " + datei.get().weitere()
                        + " weitere Version(en) oder Löschmarke(n); geprüft wird die älteste");
            }
            if (datei.get().datei().sperreBis().isEmpty()) {
                lauf.bericht().fehler(datum + ": " + teil.pfad(datum) + " ist nicht gesperrt");
            }
        }
        return datei;
    }

    private Optional<Gelesen> lesen(Lauf lauf, LocalDate datum) {
        Optional<ManifestDatei> datei = teil(lauf, datum, ManifestTeil.JSON);
        if (datei.isEmpty()) {
            return Optional.empty();
        }
        byte[] bytes = datei.get().inhalt();
        byte[] sig = teil(lauf, datum, ManifestTeil.SIG).map(ManifestDatei::inhalt).orElse(null);
        try {
            Map<String, Object> baum = json.objekt(bytes);
            if (!Arrays.equals(Jcs.utf8(baum), bytes)) {
                return Optional.of(new Gelesen(bytes, sig, null, "Manifest ist nicht in kanonischer Form (RFC 8785)"));
            }
            return Optional.of(new Gelesen(bytes, sig, ManifestInhalt.aus(baum), null));
        } catch (IllegalArgumentException e) {
            return Optional.of(new Gelesen(bytes, sig, null, "Manifest ist ungültig: " + e.getMessage()));
        }
    }

    private record Lauf(MandantCode mandant, Pruefbericht bericht, Set<String> gemeldet) {
    }

    private record Gelesen(byte[] json, byte[] sig, ManifestInhalt inhalt, String fehler) {
    }
}
