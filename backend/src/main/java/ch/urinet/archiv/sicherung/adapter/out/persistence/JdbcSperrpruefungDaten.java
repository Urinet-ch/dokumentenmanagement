package ch.urinet.archiv.sicherung.adapter.out.persistence;

import ch.urinet.archiv.ablage.domain.ManifestTeil;
import ch.urinet.archiv.ablage.domain.Objektversion;
import ch.urinet.archiv.dokument.application.DokumentPrueflaufPort;
import ch.urinet.archiv.export.application.ExportBelege;
import ch.urinet.archiv.mandant.application.MandantKontext;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.protokoll.application.ManifestDaten;
import ch.urinet.archiv.sicherung.application.SperrpruefungDaten;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Verbindet die mandantengebundene Dokument- und Manifest-Datenbank mit dem Restore-Prüflauf. */
@ApplicationScoped
public class JdbcSperrpruefungDaten implements SperrpruefungDaten {

    private final DokumentPrueflaufPort dokumente;
    private final ManifestDaten manifeste;
    private final ExportBelege exportBelege;
    private final MandantKontext kontext;

    public JdbcSperrpruefungDaten(DokumentPrueflaufPort dokumente, ManifestDaten manifeste,
                                  ExportBelege exportBelege, MandantKontext kontext) {
        this.dokumente = dokumente;
        this.manifeste = manifeste;
        this.exportBelege = exportBelege;
        this.kontext = kontext;
    }

    @Override
    public List<DokumentPrueflaufPort.Pruefdokument> dokumente(MandantCode mandant, Optional<UUID> nach,
                                                               int anzahl) {
        return imMandant(mandant, () -> dokumente.archivierte(nach, anzahl));
    }

    @Override
    public List<Pruefmanifest> manifeste(MandantCode mandant, Optional<LocalDate> nach, int anzahl) {
        return imMandant(mandant, () -> {
            List<ManifestDaten.Sperrpruefstand> seite = manifeste.sperrpruefstaende(nach, anzahl);
            List<Pruefmanifest> ergebnis = new ArrayList<>(seite.size());
            for (ManifestDaten.Sperrpruefstand stand : seite) {
                var fristen = manifeste.fristen(stand.von().orElse(null), stand.bis());
                var dateien = exportBelege.manifeste(stand.datum(), stand.datum());
                ergebnis.add(new Pruefmanifest(stand, fristen, dateien));
            }
            return List.copyOf(ergebnis);
        });
    }

    @Override
    public Set<DokumentPrueflaufPort.Bekannt> bekannteDokumente(MandantCode mandant,
                                                                Collection<String> schluessel) {
        if (schluessel.isEmpty()) {
            return Set.of();
        }
        return imMandant(mandant, () -> dokumente.bekannte(schluessel));
    }

    @Override
    public Set<Version> bekannteManifeste(MandantCode mandant, Collection<Objektversion> versionen) {
        if (versionen.isEmpty()) {
            return Set.of();
        }
        return imMandant(mandant, () -> {
            Set<Version> gesucht = new HashSet<>();
            Set<LocalDate> daten = new HashSet<>();
            for (Objektversion version : versionen) {
                gesucht.add(new Version(version.schluessel(), version.versionId()));
                datum(version.schluessel()).ifPresent(daten::add);
            }
            Set<Version> bekannt = new HashSet<>();
            for (LocalDate datum : daten) {
                exportBelege.manifeste(datum, datum).stream()
                        .map(datei -> new Version(datei.schluessel(), datei.versionId()))
                        .filter(gesucht::contains)
                        .forEach(bekannt::add);
            }
            return Set.copyOf(bekannt);
        });
    }

    private <T> T imMandant(MandantCode mandant, java.util.concurrent.Callable<T> abfrage) {
        return kontext.mit(mandant, () -> {
            try {
                return QuarkusTransaction.requiringNew().call(abfrage);
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException("Prüfdaten des Mandanten nicht gelesen", e);
            }
        });
    }

    private static Optional<LocalDate> datum(String schluessel) {
        String teil = schluessel.startsWith("manifest/") ? schluessel.substring("manifest/".length()) : "";
        if (teil.length() < 12 || teil.charAt(10) != '.') {
            return Optional.empty();
        }
        try {
            new ManifestTeil(teil.substring(11));
            return Optional.of(LocalDate.parse(teil.substring(0, 10)));
        } catch (IllegalArgumentException | DateTimeParseException e) {
            return Optional.empty();
        }
    }
}
