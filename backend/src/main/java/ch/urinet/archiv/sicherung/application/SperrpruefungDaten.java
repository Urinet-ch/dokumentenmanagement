package ch.urinet.archiv.sicherung.application;

import ch.urinet.archiv.ablage.domain.Objektversion;
import ch.urinet.archiv.dokument.application.DokumentPrueflaufPort;
import ch.urinet.archiv.export.application.ExportBelege;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.protokoll.application.ManifestDaten;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Mandantengebundene, seitenweise Daten für den Soll-/Ist-Abgleich der Restore-Probe. */
public interface SperrpruefungDaten {

    List<DokumentPrueflaufPort.Pruefdokument> dokumente(MandantCode mandant, Optional<UUID> nach, int anzahl);

    List<Pruefmanifest> manifeste(MandantCode mandant, Optional<LocalDate> nach, int anzahl);

    Set<DokumentPrueflaufPort.Bekannt> bekannteDokumente(MandantCode mandant, Collection<String> schluessel);

    Set<Version> bekannteManifeste(MandantCode mandant, Collection<Objektversion> versionen);

    record Pruefmanifest(ManifestDaten.Sperrpruefstand stand, ManifestDaten.Fristen fristen,
                         List<ExportBelege.Manifestdatei> dateien) {
        public Pruefmanifest {
            dateien = List.copyOf(dateien);
        }
    }

    record Version(String schluessel, String versionId) {
    }
}
