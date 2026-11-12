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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Prüfwerkzeug {@code archiv manifest pruefen} (Req 15.12; design.md «Manifest»): prüft nur aus der Ablage, ohne Netz
 * und ohne Datenbank, je Datum Signatur, kanonische Form, Hash-Kette mit Erneuerung (Req 15.2) und jeden Zeitstempel
 * gegen seine abgelegten Prüfdaten. Fehler: fehlendes oder ungültiges Manifest, Lücke oder Bruch der Kette, kein gültiger
 * Zeitstempel. Meldungen: fehlender Zeitstempel eines Dienstes, Verspätung über {@code archiv.manifest.verspaetung-schwelle}.
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
        Map<LocalDate, Optional<Gelesen>> gelesen = new HashMap<>();
        for (LocalDate datum = von; !datum.isAfter(bis); datum = datum.plusDays(1)) {
            Optional<Gelesen> manifest = gelesen.computeIfAbsent(datum, d -> lesen(mandant, d));
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
            Optional<Gelesen> folge = gelesen.computeIfAbsent(datum.plusDays(1), d -> lesen(mandant, d));
            stempel(mandant, datum, g, folge.filter(f -> f.inhalt() != null), bericht);
            Optional<Gelesen> vorher = gelesen.computeIfAbsent(datum.minusDays(1), d -> lesen(mandant, d));
            kette(mandant, datum, g, vorher, bericht);
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

    private void stempel(MandantCode mandant, LocalDate datum, Gelesen g, Optional<Gelesen> folge,
                         Pruefbericht bericht) {
        if (g.sig() == null) {
            return;
        }
        Set<String> dienste = new LinkedHashSet<>(zeitstempel.dienste());
        folge.map(f -> f.inhalt().vorher()).ifPresent(v -> v.zeitstempel().forEach(z -> dienste.add(z.dienst())));
        byte[] imprint = ManifestSignatur.imprint(g.json(), g.sig());
        Instant bis = g.inhalt().bis();
        int gueltig = 0;
        for (String dienst : dienste) {
            Optional<ManifestDatei> tsr = ablage.lesenManifest(mandant, datum, ManifestTeil.tsr(dienst));
            if (tsr.isEmpty()) {
                bericht.meldung(datum + ": Zeitstempel von " + dienst + " fehlt");
                continue;
            }
            Optional<ManifestDatei> kette = ablage.lesenManifest(mandant, datum, ManifestTeil.kette(dienst));
            Optional<Pruefdaten> pruefdaten = pruefdaten(mandant, datum, dienst);
            if (kette.isEmpty() || pruefdaten.isEmpty()) {
                bericht.fehler(datum + ": Prüfdaten des Zeitstempels von " + dienst + " fehlen");
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
        }
    }

    /** Hash-Kette und Erneuerung (Req 15.2): {@code vorher_sha256}, {@code von}, {@code .sig}, {@code .tsr}, Prüfdaten. */
    private void kette(MandantCode mandant, LocalDate datum, Gelesen g, Optional<Gelesen> vorher,
                       Pruefbericht bericht) {
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
            Optional<ManifestDatei> tsr = ablage.lesenManifest(mandant, tag, ManifestTeil.tsr(dienst));
            if (tsr.isEmpty() || !Sha256.hexVon(tsr.get().inhalt()).equals(verweis.tsrSha256())) {
                bericht.fehler(datum + ": Erneuerung: Zeitstempel von " + dienst + " vom " + tag + " passt nicht");
                continue;
            }
            List<String> abgelegt = new ArrayList<>();
            ablage.lesenManifest(mandant, tag, ManifestTeil.kette(dienst))
                    .ifPresent(k -> abgelegt.add(Sha256.hexVon(k.inhalt())));
            pruefdaten(mandant, tag, dienst).ifPresent(p -> abgelegt.add(Sha256.hexVon(p.inhalt())));
            if (!abgelegt.equals(verweis.pruefdatenSha256())) {
                bericht.fehler(datum + ": Erneuerung: Prüfdaten von " + dienst + " vom " + tag + " passen nicht");
            }
        }
    }

    private Optional<Pruefdaten> pruefdaten(MandantCode mandant, LocalDate datum, String dienst) {
        for (Pruefdaten.Art art : Pruefdaten.Art.values()) {
            Optional<ManifestDatei> datei = ablage.lesenManifest(mandant, datum, ManifestErzeugen.teil(dienst, art));
            if (datei.isPresent()) {
                return Optional.of(new Pruefdaten(art, datei.get().inhalt()));
            }
        }
        return Optional.empty();
    }

    private Optional<Gelesen> lesen(MandantCode mandant, LocalDate datum) {
        Optional<ManifestDatei> datei = ablage.lesenManifest(mandant, datum, ManifestTeil.JSON);
        if (datei.isEmpty()) {
            return Optional.empty();
        }
        byte[] bytes = datei.get().inhalt();
        byte[] sig = ablage.lesenManifest(mandant, datum, ManifestTeil.SIG).map(ManifestDatei::inhalt).orElse(null);
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

    private record Gelesen(byte[] json, byte[] sig, ManifestInhalt inhalt, String fehler) {
    }
}
