package ch.urinet.archiv.wissen.application;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.ki.application.chat.ChatAnalyseDaten;
import ch.urinet.archiv.ki.application.chat.ChatAnalyseLesen;
import ch.urinet.archiv.sicherheit.application.Berechtigung;
import ch.urinet.archiv.sicherheit.domain.Aktion;
import ch.urinet.archiv.sicherheit.domain.Benutzer;
import ch.urinet.archiv.sicherheit.domain.Sichtregel;
import ch.urinet.archiv.wissen.domain.Quelleneintrag;
import ch.urinet.archiv.wissen.domain.Seitentyp;
import ch.urinet.archiv.wissen.domain.Wissensseite;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@ApplicationScoped
public class ChatWissensentwurf {
    private final Berechtigung berechtigung;
    private final ChatAnalyseLesen chat;
    private final WissenHuelleDienst huelle;
    private final WissenLesenRepository lesen;
    private final WissenIngestPort daten;
    private final FreigabeRepository repository;
    private final FreigabeKontext kontext;
    private final DiffDienst diff;

    public ChatWissensentwurf(Berechtigung berechtigung, ChatAnalyseLesen chat, WissenHuelleDienst huelle,
                              WissenLesenRepository lesen, WissenIngestPort daten, FreigabeRepository repository,
                              FreigabeKontext kontext, DiffDienst diff) {
        this.berechtigung = berechtigung;
        this.chat = chat;
        this.huelle = huelle;
        this.lesen = lesen;
        this.daten = daten;
        this.repository = repository;
        this.kontext = kontext;
        this.diff = diff;
    }

    @Transactional
    public UUID anlegen(Benutzer benutzer, String nachrichtId) {
        berechtigung.aktion(benutzer, Aktion.A18);
        huelle.sperren();
        ChatAnalyseLesen.Ergebnis antwort;
        try {
            antwort = chat.lesen(nachrichtId, benutzer.id());
        } catch (FachFehler e) {
            if (e.code() == Fehlercode.NICHT_GEFUNDEN) {
                nichtGefunden(benutzer);
            }
            throw e;
        }
        Sichtregel regel = Sichtregel.fuer(benutzer);
        Set<ChatAnalyseDaten.Quelle> quellen = new LinkedHashSet<>(antwort.quellen());
        Set<UUID> dokumente = new LinkedHashSet<>();
        quellen.stream().map(ChatAnalyseDaten.Quelle::dokumentId).filter(java.util.Objects::nonNull)
                .forEach(dokumente::add);
        if (lesen.dokumentKoepfe(regel, dokumente).size() != dokumente.size()) {
            nichtGefunden(benutzer);
        }
        UUID id = UUID.randomUUID();
        List<Quelleneintrag> eintraege = new ArrayList<>();
        for (ChatAnalyseDaten.Quelle quelle : quellen) {
            UUID seiteId = null;
            if (quelle.seitenPfad() != null) {
                var seite = lesen.seite(quelle.seitenPfad(), regel);
                if (seite.isEmpty()) {
                    nichtGefunden(benutzer);
                }
                seiteId = seite.get().id();
            }
            eintraege.add(new Quelleneintrag("q-chat-" + id + "-" + (eintraege.size() + 1), quelle.dokumentId(),
                    null, null, null, seiteId));
        }
        String pfad = "analysen/chat-" + id;
        Wissensseite fassung = new Wissensseite(null, pfad, Seitentyp.ANALYSE, "Analyse aus dem Chat",
                "Vorschlag aus einer Chat-Antwort.", List.of(), Wissensseite.Status.AKTIV, null, eintraege,
                Wissensseite.Vertraulichkeit.NORMAL, antwort.inhalt(), 0, null, null, null, null, false, false, null);
        repository.analyseEntwurfAnlegen(id, benutzer.id());
        var aenderung = new FreigabeRepository.Aenderung(0, null, "OFFEN", "ANALYSE", "OFFEN", 0,
                "NEU", pfad, null);
        var vorschlag = new FreigabeRepository.Vorschlag(fassung, Set.of(), antwort.kontextstufe(),
                null, null, benutzer.id());
        var ergebnis = kontext.pruefen(benutzer, id, aenderung, vorschlag, fassung, null);
        daten.aenderungAnlegen(new WissenIngestPort.NeueAenderung(id, 1, "NEU", null, pfad, null,
                fassung, null, List.of(), List.of(), ergebnis.pruefung().befunde(), ergebnis.pruefung().hinweise(),
                diff.vergleichen(null, fassung), antwort.kontextstufe()));
        return id;
    }

    /** Meldet eine unsichtbare Quelle als VERWEIGERT (404) ins Zugriffsprotokoll, ohne Inhalte oder Titel. */
    private void nichtGefunden(Benutzer benutzer) {
        berechtigung.wissensseitenAktion(benutzer, Aktion.A18, false);
    }
}
