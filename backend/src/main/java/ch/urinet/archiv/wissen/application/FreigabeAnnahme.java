package ch.urinet.archiv.wissen.application;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.gemeinsam.domain.EntwurfBefunde;
import ch.urinet.archiv.ki.application.KiPort;
import ch.urinet.archiv.sicherheit.domain.Benutzer;
import ch.urinet.archiv.verarbeitung.application.AuftragService;
import ch.urinet.archiv.wissen.domain.Quelleneintrag;
import ch.urinet.archiv.wissen.domain.WissensMarkdown;
import ch.urinet.archiv.wissen.domain.Wissensseite;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.IntSupplier;
import java.util.stream.Collectors;

@ApplicationScoped
public class FreigabeAnnahme {
    private final FreigabeSperre sperre;
    private final FreigabeRepository repository;
    private final FreigabeKontext kontext;
    private final WissenIngestPort daten;
    private final WissenHuelleDienst huelle;
    private final WissenIngestSpeichern ingest;
    private final WissenKonfiguration konfiguration;
    private final FreigabeProtokoll protokoll;
    private final AuftragService auftraege;

    public FreigabeAnnahme(FreigabeSperre sperre, FreigabeRepository repository, FreigabeKontext kontext,
                           WissenIngestPort daten, WissenHuelleDienst huelle, WissenIngestSpeichern ingest,
                           WissenKonfiguration konfiguration, FreigabeProtokoll protokoll, AuftragService auftraege) {
        this.sperre = sperre;
        this.repository = repository;
        this.kontext = kontext;
        this.daten = daten;
        this.huelle = huelle;
        this.ingest = ingest;
        this.konfiguration = konfiguration;
        this.protokoll = protokoll;
        this.auftraege = auftraege;
    }

    @Transactional(Transactional.TxType.MANDATORY)
    public boolean ausfuehren(Benutzer benutzer, UUID entwurfId, int nummer, IntSupplier ifMatch,
                              Bearbeitung bearbeitung, String gewuenschteStufe, String grund, String realIp) {
        FreigabeRepository.Aenderung aenderung = sperre.laden(benutzer, entwurfId, nummer, ifMatch);
        if (!sperre.basisAktuell(aenderung)) {
            return false;
        }
        if (grund != null && grund.length() > 1000) {
            throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
        }
        FreigabeRepository.Vorschlag vorschlag = repository.vorschlagLaden(aenderung);
        Wissensseite entwurf = vorschlag.fassung();
        Set<String> entfernt = vorschlag.entfernteAussagen();
        if (bearbeitung != null) {
            entfernt = bearbeitung.entfernteAussagen() == null ? Set.of()
                    : bearbeitung.entfernteAussagen().stream()
                    .filter(a -> a != null && a.schluessel() != null
                            && a.begruendung() != null && !a.begruendung().isBlank())
                    .map(KiPort.EntfernteAussage::schluessel).collect(Collectors.toUnmodifiableSet());
            entwurf = new Wissensseite(entwurf.id(), entwurf.pfad(), entwurf.typ(), bearbeitung.titel(),
                    bearbeitung.beschreibung(), bearbeitung.tags(), entwurf.status(), bearbeitung.veraltetAb(),
                    bearbeitung.quellen(), entwurf.vertraulichkeit(), bearbeitung.inhalt(), entwurf.version(),
                    null, null, null, null, false, false, entwurf.wissensquelleId());
        }
        var gepruefterVorschlag = new FreigabeRepository.Vorschlag(entwurf, entfernt, vorschlag.kontextstufe(),
                vorschlag.wissensquelleId(), vorschlag.modell(), vorschlag.erstelltVon());
        var ergebnis = kontext.pruefen(benutzer, entwurfId, aenderung, gepruefterVorschlag, entwurf, gewuenschteStufe);
        if (!ergebnis.pruefung().befunde().isEmpty()) {
            throw new EntwurfBefunde(ergebnis.pruefung().befunde().stream().map(b -> b.code().name()).toList());
        }
        UUID seiteId = aenderung.seiteId() == null ? UUID.randomUUID() : aenderung.seiteId();
        int version = aenderung.basisVersion() == null ? 1 : aenderung.basisVersion() + 1;
        Wissensseite.Status status = "VERALTEN".equals(aenderung.aktion()) ? Wissensseite.Status.VERALTET
                : aenderung.seiteId() == null ? Wissensseite.Status.AKTIV
                : Wissensseite.Status.valueOf(daten.seiteLaden(seiteId).orElseThrow().metadaten().status());
        Wissensseite fassung = new Wissensseite(seiteId, entwurf.pfad(), entwurf.typ(), entwurf.titel(),
                entwurf.beschreibung(), entwurf.tags(), status, entwurf.veraltetAb(), entwurf.quellen(),
                Wissensseite.Vertraulichkeit.valueOf(ergebnis.wirksameStufe().name()), entwurf.inhalt(), version,
                null, null, null, null, false, false, entwurf.wissensquelleId());
        String erzeugt;
        if (bearbeitung != null) {
            erzeugt = "human:" + benutzer.id();
        } else if ("INGEST".equals(aenderung.anlass())) {
            if (vorschlag.modell() == null || vorschlag.modell().isBlank()) {
                throw new IllegalStateException("Modell des Ingest-Entwurfs fehlt");
            }
            erzeugt = "infomaniak/" + vorschlag.modell();
        } else {
            erzeugt = "human:" + vorschlag.erstelltVon();
        }
        Set<String> links = WissensMarkdown.analysieren(fassung.inhalt(),
                konfiguration.ingest().seiteMaxZeichen()).links();
        repository.fassungSpeichern(entwurfId, aenderung, fassung, ergebnis.mindeststufe(), ergebnis.wirksameStufe(),
                erzeugt, benutzer.id(), grund, links);
        huelle.versionFreigeben(seiteId, version);
        protokoll.angenommen(benutzer, entwurfId, realIp);
        auftraege.wissensEmbeddingEinreihen(seiteId, version);
        if (repository.entwurfAbschliessen(entwurfId)) {
            repository.abschlussProtokollieren(entwurfId);
            if ("INGEST".equals(aenderung.anlass())) {
                ingest.wartendenIngestStarten();
            }
        }
        return true;
    }

    public record Bearbeitung(String titel, String beschreibung, List<String> tags, LocalDate veraltetAb,
                              List<Quelleneintrag> quellen, String inhalt,
                              List<KiPort.EntfernteAussage> entfernteAussagen) {
    }
}
