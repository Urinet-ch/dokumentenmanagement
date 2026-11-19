package ch.urinet.archiv.wissen.application;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.ki.application.KiPort;
import ch.urinet.archiv.sicherheit.application.Berechtigung;
import ch.urinet.archiv.sicherheit.domain.Aktion;
import ch.urinet.archiv.sicherheit.domain.Benutzer;
import ch.urinet.archiv.wissen.domain.Quelleneintrag;
import ch.urinet.archiv.wissen.domain.Seitentyp;
import ch.urinet.archiv.wissen.domain.Wissensseite;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class ManuellerWissensentwurf {
    private final Berechtigung berechtigung;
    private final WissenHuelleDienst huelle;
    private final WissenSichtbarkeit sichtbarkeit;
    private final WissenIngestPort daten;
    private final FreigabeRepository repository;
    private final FreigabeKontext kontext;
    private final DiffDienst diff;

    public ManuellerWissensentwurf(Berechtigung berechtigung, WissenHuelleDienst huelle,
                                   WissenSichtbarkeit sichtbarkeit, WissenIngestPort daten,
                                   FreigabeRepository repository, FreigabeKontext kontext, DiffDienst diff) {
        this.berechtigung = berechtigung;
        this.huelle = huelle;
        this.sichtbarkeit = sichtbarkeit;
        this.daten = daten;
        this.repository = repository;
        this.kontext = kontext;
        this.diff = diff;
    }

    @Transactional
    public UUID anlegen(Benutzer benutzer, Eingabe eingabe) {
        berechtigung.aktion(benutzer, Aktion.A17);
        if (eingabe == null || eingabe.pfad() == null || eingabe.typ() == null || eingabe.inhalt() == null
                || enthaeltNull(eingabe.tags()) || enthaeltNull(eingabe.quellen())
                || enthaeltNull(eingabe.entfernteAussagen())) {
            throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
        }
        Seitentyp typ = Seitentyp.vonDatenbankwert(eingabe.typ())
                .orElseThrow(() -> new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG));
        huelle.sperren();
        WissenIngestPort.SeitenFassung basis = null;
        if (eingabe.seiteId() != null) {
            berechtigung.wissensseitenAktion(benutzer, Aktion.A17,
                    sichtbarkeit.aktuelleSeiteSichtbar(benutzer, eingabe.seiteId()));
            repository.seitenVersionSperren(eingabe.seiteId())
                    .orElseThrow(() -> new FachFehler(Fehlercode.NICHT_GEFUNDEN));
            basis = daten.seiteLaden(eingabe.seiteId())
                    .orElseThrow(() -> new FachFehler(Fehlercode.NICHT_GEFUNDEN));
        }
        int version = basis == null ? 0 : basis.metadaten().version();
        Wissensseite.Status status = basis == null ? Wissensseite.Status.AKTIV
                : Wissensseite.Status.valueOf(basis.metadaten().status());
        UUID quelleId = basis == null ? null : basis.wissensquelleId();
        Wissensseite fassung = new Wissensseite(eingabe.seiteId(), eingabe.pfad(), typ, eingabe.titel(),
                eingabe.beschreibung(), eingabe.tags(), status, eingabe.veraltetAb(), eingabe.quellen(),
                Wissensseite.Vertraulichkeit.NORMAL, eingabe.inhalt(), version,
                null, null, null, null, false, false, quelleId);
        Wissensseite vorher = basis == null ? null : new Wissensseite(basis.metadaten().id(),
                basis.metadaten().pfad(), Seitentyp.vonDatenbankwert(basis.metadaten().typ()).orElseThrow(),
                basis.metadaten().titel(), basis.metadaten().beschreibung(), basis.metadaten().tags(), status, null,
                basis.quellen(), Wissensseite.Vertraulichkeit.NORMAL, basis.inhalt(), version,
                null, null, null, null, false, false, quelleId);
        String aktion = basis == null ? "NEU" : "AENDERN";
        UUID id = UUID.randomUUID();
        repository.manuellenEntwurfAnlegen(id, benutzer.id());
        List<KiPort.EntfernteAussage> entfernt = eingabe.entfernteAussagen() == null
                ? List.of() : eingabe.entfernteAussagen();
        Set<String> begruendet = entfernt.stream().filter(a -> a != null && a.schluessel() != null
                        && a.begruendung() != null && !a.begruendung().isBlank())
                .map(KiPort.EntfernteAussage::schluessel).collect(Collectors.toUnmodifiableSet());
        var aenderung = new FreigabeRepository.Aenderung(0, eingabe.seiteId(), "OFFEN", "MANUELL", "OFFEN", 0,
                aktion, eingabe.pfad(), basis == null ? null : version);
        var vorschlag = new FreigabeRepository.Vorschlag(fassung, begruendet, "NORMAL", null, null, benutzer.id());
        var ergebnis = kontext.pruefen(benutzer, id, aenderung, vorschlag, fassung, null);
        daten.aenderungAnlegen(new WissenIngestPort.NeueAenderung(id, 1, aktion, eingabe.seiteId(), eingabe.pfad(),
                basis == null ? null : version, fassung, eingabe.begruendung(), List.of(), entfernt,
                ergebnis.pruefung().befunde(), ergebnis.pruefung().hinweise(), diff.vergleichen(vorher, fassung),
                "NORMAL"));
        return id;
    }

    private static boolean enthaeltNull(List<?> werte) {
        return werte != null && werte.stream().anyMatch(Objects::isNull);
    }

    public record Eingabe(UUID seiteId, String pfad, String typ, String titel, String beschreibung, List<String> tags,
                          LocalDate veraltetAb, List<Quelleneintrag> quellen, String inhalt, String begruendung,
                          List<KiPort.EntfernteAussage> entfernteAussagen) {
    }
}
