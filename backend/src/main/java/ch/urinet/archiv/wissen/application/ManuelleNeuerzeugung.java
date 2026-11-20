package ch.urinet.archiv.wissen.application;

import ch.urinet.archiv.ki.application.KiPort;
import ch.urinet.archiv.verarbeitung.application.Auftrag;
import ch.urinet.archiv.wissen.domain.Quelleneintrag;
import ch.urinet.archiv.wissen.domain.Seitentyp;
import ch.urinet.archiv.wissen.domain.Wissensseite;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class ManuelleNeuerzeugung {
    private final ManuelleNeuerzeugungSpeichern speichern;
    private final WissenIngestVerarbeiten ki;
    private final WissenKonfiguration konfiguration;

    public ManuelleNeuerzeugung(ManuelleNeuerzeugungSpeichern speichern, WissenIngestVerarbeiten ki,
                                WissenKonfiguration konfiguration) {
        this.speichern = speichern;
        this.ki = ki;
        this.konfiguration = konfiguration;
    }

    public void ausfuehren(Auftrag auftrag, UUID entwurfId, int nummer) throws InterruptedException {
        var geladen = speichern.laden(auftrag, entwurfId, nummer);
        if (geladen.isEmpty()) {
            return;
        }
        var stand = geladen.get();
        Wissensseite vorher = stand.vorschlag().fassung();
        Wissensseite basis = ManuelleNeuerzeugungSpeichern.seite(stand.basis());
        Map<String, Quelleneintrag> quellen = new LinkedHashMap<>();
        for (Quelleneintrag quelle : basis.quellen()) {
            quellen.put(quelle.schluessel(), quelle);
        }
        for (Quelleneintrag quelle : vorher.quellen()) {
            Quelleneintrag vorhanden = quellen.putIfAbsent(quelle.schluessel(), quelle);
            if (vorhanden != null && !vorhanden.equals(quelle)) {
                speichern.abbrechen(auftrag, entwurfId,
                        "Ein Quellenschlüssel bezeichnet verschiedene Quellen. Bitte die Änderung von Hand überarbeiten.");
                return;
            }
        }
        String nachricht = WissenIngestVerarbeiten.feld("Geplanter Pfad", basis.pfad())
                + WissenIngestVerarbeiten.feld("Aktuelle Seite", basis.titel() + "\n" + basis.beschreibung()
                        + "\n" + basis.inhalt())
                + WissenIngestVerarbeiten.feld("Bisheriger Vorschlag", vorher.titel() + "\n"
                        + vorher.beschreibung() + "\n" + vorher.inhalt())
                + WissenIngestVerarbeiten.feld("Zulässige Quellenschlüssel und Auszüge",
                        quellen.values().stream().map(q -> q.schluessel() + ": "
                                + (q.auszug() == null ? "" : q.auszug()))
                                .collect(java.util.stream.Collectors.joining("\n")))
                + WissenIngestVerarbeiten.feld("Aufgabe",
                        "Übertrage die Änderung des bisherigen Vorschlags auf die aktuelle Seite. "
                                + "Verwende nur die angegebenen Quellenschlüssel.");
        long budget = begrenzt(stand.budget().budgetTokens(), konfiguration.ingest().budgetTokens());
        long monat = begrenzt(stand.budget().monatsbudgetTokens(), konfiguration.ingest().monatsbudgetTokens());
        var antwort = ki.aufrufen(entwurfId, konfiguration.modell(), stand.lauf().tokensEin(),
                stand.lauf().tokensAus(), stand.monatsverbrauch(), KiPort.WissensSchritt.SEITE, nachricht, budget, monat);
        if (!(antwort.inhalt() instanceof KiPort.Seite inhalt) || inhalt.inhalt() == null) {
            speichern.abbrechen(auftrag, entwurfId, antwort.budgetErreicht()
                    ? "Budget erschöpft" : "Die Änderung konnte nicht neu erzeugt werden. Bitte erneut versuchen.");
            return;
        }
        if (basis.typ() == Seitentyp.QUELLE) {
            var alt = new WissenIngestPort.SeitenVorschlag(nummer, stand.aenderung().aktion(), basis.id(),
                    basis.pfad(), stand.aenderung().basisVersion(), vorher, null, List.of(), List.of(),
                    List.of(), List.of(), stand.stufe().name(), null);
            if (!WissenIngestVerarbeiten.quelleStrukturErhalten(stand.basis(), alt, inhalt, List.of(),
                    basis.wissensquelleId())) {
                speichern.abbrechen(auftrag, entwurfId,
                        "Die Quelle-Seite konnte nicht vollständig neu erzeugt werden. Bitte erneut versuchen.");
                return;
            }
        }
        Wissensseite neu = new Wissensseite(basis.id(), basis.pfad(), vorher.typ(), inhalt.titel(),
                inhalt.beschreibung(), inhalt.tags(), basis.status(), vorher.veraltetAb(), List.copyOf(quellen.values()),
                Wissensseite.Vertraulichkeit.valueOf(stand.stufe().name()), inhalt.inhalt(), basis.version(),
                null, null, null, null, false, false, basis.wissensquelleId());
        speichern.sichern(auftrag, entwurfId, nummer, stand, neu, inhalt.widersprueche(),
                inhalt.entfernteAussagen(), inhalt.begruendung(), antwort.tokensEin(), antwort.tokensAus());
    }

    private static long begrenzt(Integer wert, long standard) {
        return Math.min(wert == null || wert <= 0 ? standard : wert, Integer.MAX_VALUE);
    }
}
