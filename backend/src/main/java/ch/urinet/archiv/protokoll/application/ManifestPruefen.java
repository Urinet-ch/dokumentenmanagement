package ch.urinet.archiv.protokoll.application;

import ch.urinet.archiv.ablage.application.AblagePort;
import ch.urinet.archiv.ablage.domain.ManifestDatei;
import ch.urinet.archiv.ablage.domain.ManifestTeil;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.protokoll.application.ManifestDaten.ManifestStand;
import ch.urinet.archiv.protokoll.domain.Jcs;
import ch.urinet.archiv.protokoll.domain.ManifestInhalt;
import ch.urinet.archiv.protokoll.domain.ManifestSignatur;
import ch.urinet.archiv.protokoll.domain.Pruefbericht;
import ch.urinet.archiv.protokoll.domain.Pruefdaten;
import ch.urinet.archiv.protokoll.domain.Sha256;
import ch.urinet.archiv.protokoll.domain.Vorher;
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
 * Prüfwerkzeug {@code archiv manifest pruefen} (Req 15.12; design.md «Manifest»): prüft ohne Netz je Datum Signatur,
 * kanonische Form, Hash-Kette mit Erneuerung (Req 15.2) und jeden Zeitstempel gegen seine abgelegten Prüfdaten, alles
 * aus der Ablage und mit dem öffentlichen Schlüssel, also auch ohne Datenbank. Welche Zeitstempel festgehalten sind,
 * kommt dann aus {@code vorher} des Folgemanifests; am jüngsten Tag ohne Folgemanifest ist das ohne Datenbank nicht
 * bestimmbar (Meldung). Mit Datenbank ({@link FestgehalteneManifeste}, {@code manifest_zeitstempel}) prüft es strenger:
 * Die Datenbank bestimmt das auch für den jüngsten Tag, und jede Abweichung zwischen Datenbank, Ablage und Folgemanifest
 * ist ein Fehler. Fehler: fehlendes oder ungültiges Manifest, Lücke oder Bruch der Kette, kein gültiger Zeitstempel, ein
 * festgehaltener Zeitstempel ohne {@code .tsr} oder Prüfdaten, weitere Versionen oder Löschmarken unter einem
 * Manifest-Schlüssel oder ein ungesperrter Teil (geprüft wird immer die älteste Version). Meldungen: fehlender oder
 * unvollständiger Zeitstempel eines Dienstes, der nicht festgehalten ist (Req 15.11), ein Manifest ohne Eintrag in der
 * Datenbank, Verspätung über {@code archiv.manifest.verspaetung-schwelle}.
 * <p>
 * Geprüft werden die Tokens der konfigurierten Dienste und der festgehaltenen Dienste, damit ein aus der Liste
 * ausgetragener Dienst weiter geprüft wird.
 */
@ApplicationScoped
public class ManifestPruefen {

    private final AblagePort ablage;
    private final JsonLeser json;
    private final ManifestSchluessel schluessel;
    private final ZeitstempelPort zeitstempel;
    private final FestgehalteneManifeste festgehalten;
    private final Duration schwelle;

    public ManifestPruefen(AblagePort ablage, JsonLeser json, ManifestSchluessel schluessel,
                           ZeitstempelPort zeitstempel, FestgehalteneManifeste festgehalten,
                           @ConfigProperty(name = "archiv.manifest.verspaetung-schwelle", defaultValue = "PT24H")
                           Duration schwelle) {
        this.ablage = ablage;
        this.json = json;
        this.schluessel = schluessel;
        this.zeitstempel = zeitstempel;
        this.festgehalten = festgehalten;
        this.schwelle = schwelle;
    }

    /**
     * @param mitDatenbank {@code false}: nur aus der Ablage (unabhängige Prüfung); {@code true}: zusätzlich gegen
     *                     {@link FestgehalteneManifeste}
     */
    public Pruefbericht pruefen(MandantCode mandant, LocalDate von, LocalDate bis, boolean mitDatenbank) {
        if (bis.isBefore(von)) {
            throw new IllegalArgumentException("Das Enddatum liegt vor dem Startdatum");
        }
        Pruefbericht bericht = new Pruefbericht();
        PublicKey oeffentlich = schluessel.oeffentlich();
        Lauf lauf = new Lauf(mandant, mitDatenbank, bericht, new HashSet<>());
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
            Optional<Set<String>> festgehalteneDienste = festgehalten(lauf, datum, g,
                    folge.filter(f -> f.inhalt() != null));
            stempel(lauf, datum, g, festgehalteneDienste);
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

    /**
     * Dienste, deren Zeitstempel zum Manifest festgehalten sind: die Verweise des Folgemanifests, mit Datenbank dazu deren
     * Eintrag. Leer, wenn das nicht bestimmbar ist (ohne Datenbank am jüngsten Tag ohne Folgemanifest). Fehlt der Eintrag
     * in der Datenbank oder widerspricht er Ablage oder Folgemanifest, ist das ein Fehler; nur beim jüngsten Tag ohne
     * Folgemanifest ist ein fehlender Eintrag eine Meldung (Erzeugung noch nicht abgeschlossen).
     */
    private Optional<Set<String>> festgehalten(Lauf lauf, LocalDate datum, Gelesen g, Optional<Gelesen> folge) {
        Pruefbericht bericht = lauf.bericht();
        Optional<Vorher> imFolgetag = folge.map(f -> f.inhalt().vorher());
        Set<String> dienste = new LinkedHashSet<>();
        imFolgetag.ifPresent(v -> v.zeitstempel().forEach(z -> dienste.add(z.dienst())));
        if (!lauf.mitDatenbank()) {
            if (imFolgetag.isEmpty()) {
                bericht.meldung(datum + ": festgehaltene Zeitstempel ohne Datenbank nicht bestimmbar (kein Folgemanifest)");
                return Optional.empty();
            }
            return Optional.of(dienste);
        }
        Optional<ManifestStand> db = festgehalten.stand(lauf.mandant(), datum);
        if (db.isEmpty()) {
            if (imFolgetag.isPresent()) {
                bericht.fehler(datum + ": Manifest ist in der Datenbank nicht festgehalten, das Folgemanifest verweist "
                        + "aber darauf");
            } else {
                bericht.meldung(datum + ": Manifest ist in der Datenbank nicht festgehalten");
            }
            return Optional.of(dienste);
        }
        Vorher festgehaltenVerweis = db.get().verweis();
        festgehaltenVerweis.zeitstempel().forEach(z -> dienste.add(z.dienst()));
        if (!db.get().sha256().equals(Sha256.hexVon(g.json()))) {
            bericht.fehler(datum + ": Manifest passt nicht zum SHA-256 in der Datenbank");
        }
        if (imFolgetag.isPresent()) {
            if (!imFolgetag.get().equals(festgehaltenVerweis)) {
                bericht.fehler(datum + ": Folgemanifest und Datenbank nennen verschiedene Zeitstempel");
            }
            return Optional.of(dienste);
        }
        // jüngster Tag: dieselben Hashes, die sonst die Erneuerung im Folgemanifest bestätigt
        if (g.sig() == null || !Sha256.hexVon(g.sig()).equals(festgehaltenVerweis.sigSha256())) {
            bericht.fehler(datum + ": Signatur passt nicht zur Datenbank");
        }
        for (ZeitstempelVerweis verweis : festgehaltenVerweis.zeitstempel()) {
            String dienst = verweis.dienst();
            Optional<ManifestDatei> tsr = teil(lauf, datum, ManifestTeil.tsr(dienst));
            if (tsr.isPresent() && !Sha256.hexVon(tsr.get().inhalt()).equals(verweis.tsrSha256())) {
                bericht.fehler(datum + ": Zeitstempel von " + dienst + " passt nicht zur Datenbank");
            }
            Optional<ManifestDatei> kette = teil(lauf, datum, ManifestTeil.kette(dienst));
            Optional<Pruefdaten> pruefdaten = pruefdaten(lauf, datum, dienst);
            if (kette.isPresent() && pruefdaten.isPresent() && !List.of(Sha256.hexVon(kette.get().inhalt()),
                    Sha256.hexVon(pruefdaten.get().inhalt())).equals(verweis.pruefdatenSha256())) {
                bericht.fehler(datum + ": Prüfdaten von " + dienst + " passen nicht zur Datenbank");
            }
        }
        return Optional.of(dienste);
    }

    private void stempel(Lauf lauf, LocalDate datum, Gelesen g, Optional<Set<String>> festgehalteneDienste) {
        Pruefbericht bericht = lauf.bericht();
        if (g.sig() == null) {
            return;
        }
        Set<String> verwiesen = festgehalteneDienste.orElse(Set.of());
        Set<String> dienste = new LinkedHashSet<>(zeitstempel.dienste());
        dienste.addAll(verwiesen);
        List<String> unvollstaendig = new ArrayList<>();
        byte[] imprint = ManifestSignatur.imprint(g.json(), g.sig());
        Instant bis = g.inhalt().bis();
        int gueltig = 0;
        for (String dienst : dienste) {
            Optional<ManifestDatei> tsr = teil(lauf, datum, ManifestTeil.tsr(dienst));
            if (tsr.isEmpty()) {
                // wie fehlende Prüfdaten: Was festgehalten ist, muss vollständig in der Ablage liegen
                if (verwiesen.contains(dienst)) {
                    bericht.fehler(datum + ": Zeitstempel von " + dienst + " fehlt, ist aber festgehalten");
                } else {
                    bericht.meldung(datum + ": Zeitstempel von " + dienst + " fehlt");
                }
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
        String zusatz = festgehalteneDienste.isPresent() ? ", nicht festgehalten" : "";
        unvollstaendig.forEach(dienst -> bericht.meldung(datum + ": Zeitstempel von " + dienst
                + " unvollständig (Prüfdaten fehlen)" + zusatz));
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

    private record Lauf(MandantCode mandant, boolean mitDatenbank, Pruefbericht bericht, Set<String> gemeldet) {
    }

    private record Gelesen(byte[] json, byte[] sig, ManifestInhalt inhalt, String fehler) {
    }
}
