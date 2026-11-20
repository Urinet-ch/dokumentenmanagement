package ch.urinet.archiv.wissen.application;

import ch.urinet.archiv.sicherheit.domain.Sichtregel;
import ch.urinet.archiv.wissen.domain.Seitentyp;
import ch.urinet.archiv.wissen.domain.Wissensseite;

import java.time.Instant;
import java.util.List;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface WissenLesenRepository {

    IndexErgebnis index(Sichtregel sichtregel, IndexAnfrage anfrage);

    List<SeiteVerweis> seitenFuerDokument(Sichtregel sichtregel, UUID dokumentId);

    Optional<Wissensseite> seite(UUID id, Sichtregel sichtregel);

    Optional<Wissensseite> seite(String pfad, Sichtregel sichtregel);

    Set<String> sichtbarePfade(Sichtregel sichtregel, Set<String> pfade);

    List<VersionZeile> versionen(UUID seiteId, Sichtregel sichtregel);

    Optional<WissensVersion> version(UUID seiteId, int nummer, Sichtregel sichtregel);

    List<IndexZeile> eingehend(UUID seiteId, Sichtregel sichtregel);

    /** Titel und Dateiname der für die Regel sichtbaren Dokumente; unsichtbare fehlen im Ergebnis. */
    Map<UUID, DokumentKopf> dokumentKoepfe(Sichtregel sichtregel, Set<UUID> ids);

    /** Pfad und Titel der für die Regel sichtbaren Wissensseiten; unsichtbare fehlen im Ergebnis. */
    Map<UUID, SeiteVerweis> sichtbareSeiten(Sichtregel sichtregel, Set<UUID> ids);

    LogSeite log(Sichtregel sichtregel, LogCursor vor, int groesse);

    record IndexAnfrage(Seitentyp typ, String tag, String q, UUID dokumentId, int offset, int groesse) {
    }

    record IndexZeile(UUID id, Seitentyp typ, String pfad, String titel, String beschreibung, String status,
                      boolean quellePruefen) {
    }

    record IndexErgebnis(List<IndexZeile> seiten, long gesamt) {
        public IndexErgebnis {
            seiten = List.copyOf(seiten);
        }
    }

    record VersionZeile(int nummer, Optional<WissensVersion> version) {
        public VersionZeile {
            version = Optional.ofNullable(version).orElse(Optional.empty());
        }
    }

    record WissensVersion(int nummer, Map<String, Object> daten, String inhalt, String erzeugtVon,
                          String freigegebenVon, Instant zeit, String grund, UUID entwurfId, String entwurfAnlass,
                          UUID dokumentId) {
        public WissensVersion {
            daten = Collections.unmodifiableMap(new LinkedHashMap<>(daten));
        }
    }

    record DokumentKopf(String titel, String dateiname, java.time.LocalDate datum) {
    }

    record SeiteVerweis(UUID id, String pfad, String titel) {
    }

    record LogEintrag(long id, Instant zeit, String aktion, String akteur, List<SeiteVerweis> seiten,
                      UUID dokumentId, UUID entwurfId, boolean widerspruchAufgeloest) {
        public LogEintrag(long id, Instant zeit, String aktion, String akteur, List<SeiteVerweis> seiten,
                          UUID dokumentId, UUID entwurfId) {
            this(id, zeit, aktion, akteur, seiten, dokumentId, entwurfId, false);
        }

        public LogEintrag {
            seiten = List.copyOf(seiten);
        }
    }

    record LogCursor(Instant zeit, long id) {
    }

    record LogSeite(List<LogEintrag> eintraege, Optional<LogCursor> naechsterCursor) {
        public LogSeite {
            eintraege = List.copyOf(eintraege);
            naechsterCursor = Optional.ofNullable(naechsterCursor).orElse(Optional.empty());
        }
    }
}
