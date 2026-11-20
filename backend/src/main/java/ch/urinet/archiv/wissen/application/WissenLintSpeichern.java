package ch.urinet.archiv.wissen.application;

import ch.urinet.archiv.verarbeitung.application.Auftrag;
import ch.urinet.archiv.verarbeitung.application.AuftragService;
import ch.urinet.archiv.verarbeitung.domain.AuftragTyp;
import ch.urinet.archiv.verarbeitung.domain.Nutzlast;
import ch.urinet.archiv.wissen.domain.WissensGraph;
import ch.urinet.archiv.wissen.domain.WissensLint;
import ch.urinet.archiv.wissen.domain.WissensPruefung;
import ch.urinet.archiv.wissen.domain.WissensStufe;
import ch.urinet.archiv.wissen.domain.Wissensseite;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class WissenLintSpeichern {
    public static final AuftragTyp TYP = new AuftragTyp("WISSEN_LINT");
    public static final String LINT_ID = "lintId";
    private static final String VORGAENGER = "vorgaengerId";

    private final WissenLintRepository lint;
    private final WissenIngestPort ingest;
    private final WissenHuelleRepository huellen;
    private final WissenEinstellungenRepository einstellungen;
    private final WissenKonfiguration konfiguration;
    private final AuftragService auftraege;
    private final FreigabePruefung freigabePruefung;
    private final long standardBudget;
    private final int maxPaare;

    public WissenLintSpeichern(WissenLintRepository lint, WissenIngestPort ingest, WissenHuelleRepository huellen,
                              WissenEinstellungenRepository einstellungen, WissenKonfiguration konfiguration,
                              AuftragService auftraege, FreigabePruefung freigabePruefung,
                              @ConfigProperty(name = "archiv.wissen.lint-budget-tokens", defaultValue = "100000")
                              long standardBudget,
                              @ConfigProperty(name = "archiv.wissen.lint-paare", defaultValue = "30") int maxPaare) {
        if (standardBudget < 1 || maxPaare < 0) {
            throw new IllegalStateException("Ungültige Lint-Grenzen");
        }
        this.lint = lint;
        this.ingest = ingest;
        this.huellen = huellen;
        this.einstellungen = einstellungen;
        this.konfiguration = konfiguration;
        this.auftraege = auftraege;
        this.freigabePruefung = freigabePruefung;
        this.standardBudget = standardBudget;
        this.maxPaare = maxPaare;
    }

    @Transactional
    public WissensLint.Ergebnis planen(LocalDate heute) {
        huellen.sperren();
        List<Wissensseite> seiten = lint.seitenLaden();
        WissensGraph graph = huellen.graphLaden(quellDokumente(seiten));
        Set<UUID> dokumente = new HashSet<>();
        seiten.forEach(seite -> dokumente.addAll(graph.dokumente(seite.id())));
        dokumente.addAll(quellDokumente(seiten));
        Map<UUID, WissensPruefung.DokumentQuelle> quellen = ingest.dokumentQuellenLaden(dokumente);
        return WissensLint.pruefen(seiten.stream().map(WissensLint.Seite::new).toList(), graph, quellen,
                lint.paarZeiten(), heute, konfiguration.ingest().seiteMaxZeichen(), maxPaare);
    }

    /** Lädt die beiden Seiten und alle Dokumentstufen unmittelbar vor einem Modellaufruf erneut. */
    @Transactional
    public Optional<PaarKontext> paarLaden(WissensLint.PaarId paar) {
        huellen.sperren();
        Map<UUID, Wissensseite> seiten = lint.seitenLaden().stream()
                .collect(Collectors.toUnmodifiableMap(Wissensseite::id, s -> s));
        WissensGraph graph = huellen.graphLaden(quellDokumente(seiten.values()));
        Set<UUID> ids = Set.of(paar.erste(), paar.zweite());
        if (!bekannteHuelle(ids, graph)) {
            return Optional.empty();
        }
        if (!seiten.keySet().containsAll(ids)) {
            return Optional.empty();
        }
        Set<UUID> ersteDokumente = dokumente(paar.erste(), graph, seiten);
        Set<UUID> zweiteDokumente = dokumente(paar.zweite(), graph, seiten);
        Set<UUID> dokumente = new HashSet<>();
        dokumente.addAll(ersteDokumente);
        dokumente.addAll(zweiteDokumente);
        Map<UUID, WissensPruefung.DokumentQuelle> quellen = ingest.dokumentQuellenLaden(dokumente);
        if (!quellen.keySet().containsAll(dokumente) || !ingest.personalDokumenteLaden(dokumente).isEmpty()
                || quellen.values().stream().anyMatch(q -> !q.gleicherMandant() || !q.archiviert() || q.personal())) {
            return Optional.empty();
        }
        WissensStufe erste = graph.wirksameStufe(paar.erste(), ersteDokumente);
        WissensStufe zweite = graph.wirksameStufe(paar.zweite(), zweiteDokumente);
        return Optional.of(new PaarKontext(seiten.get(paar.erste()), seiten.get(paar.zweite()),
                erste, zweite, erste.hoechste(zweite)));
    }

    @Transactional
    public Optional<SeitenKontext> seitenLaden(WissenLintRepository.AuftragSeite auftrag) {
        huellen.sperren();
        Map<UUID, Wissensseite> alle = lint.seitenLaden().stream()
                .collect(Collectors.toUnmodifiableMap(Wissensseite::id, s -> s));
        WissensGraph graph = huellen.graphLaden(quellDokumente(alle.values()));
        Set<UUID> ids = new HashSet<>();
        ids.add(auftrag.seiteId());
        auftrag.bezuege().forEach(b -> ids.add(b.seiteId()));
        if (!bekannteHuelle(ids, graph)) {
            return Optional.empty();
        }
        Wissensseite ziel = alle.get(auftrag.seiteId());
        if (ziel == null || ziel.version() != auftrag.seiteVersion()
                || ziel.quellenRevision() != auftrag.quellenRevision()
                || auftrag.bezuege().stream().anyMatch(b -> !alle.containsKey(b.seiteId())
                || alle.get(b.seiteId()).version() != b.version()
                || alle.get(b.seiteId()).quellenRevision() != b.quellenRevision())) {
            return Optional.empty();
        }
        Set<UUID> dokumente = new HashSet<>();
        WissensStufe hoechste = auftrag.kontextstufe();
        for (UUID id : ids) {
            Set<UUID> huelle = dokumente(id, graph, alle);
            dokumente.addAll(huelle);
            hoechste = hoechste.hoechste(graph.wirksameStufe(id, huelle));
        }
        Map<UUID, WissensPruefung.DokumentQuelle> quellen = ingest.dokumentQuellenLaden(dokumente);
        if (!quellen.keySet().containsAll(dokumente) || !ingest.personalDokumenteLaden(dokumente).isEmpty()
                || quellen.values().stream().anyMatch(q -> !q.gleicherMandant() || !q.archiviert() || q.personal())
                || (hoechste == WissensStufe.INTERN && "ueberblick".equals(ziel.pfad()))) {
            return Optional.empty();
        }
        WissensStufe zielStufe = graph.wirksameStufe(ziel.id(), dokumente(ziel.id(), graph, alle));
        if (zielStufe.ordinal() < hoechste.ordinal()) {
            return Optional.empty();
        }
        List<Wissensseite> bezuege = auftrag.bezuege().stream().map(b -> alle.get(b.seiteId())).toList();
        return Optional.of(new SeitenKontext(ziel, bezuege, hoechste));
    }

    @Transactional
    public WissensPruefung.Ergebnis pruefen(Wissensseite basis, Wissensseite vorschlag, WissensStufe kontextstufe,
                                           Set<String> entfernte) {
        huellen.sperren();
        WissensGraph graph = huellen.graphLaden();
        List<Wissensseite> seiten = lint.seitenLaden();
        Set<UUID> dokumente = new HashSet<>();
        vorschlag.quellen().forEach(q -> {
            if (q.dokumentId() != null) {
                dokumente.add(q.dokumentId());
            }
            if (q.quellSeiteId() != null) {
                dokumente.addAll(graph.dokumente(q.quellSeiteId()));
            }
        });
        if (vorschlag.wissensquelleId() != null) {
            dokumente.add(vorschlag.wissensquelleId());
        }
        var kontext = new WissensPruefung.Kontext(konfiguration.ingest().seiteMaxZeichen(), basis.pfad(),
                seiten.stream().map(Wissensseite::pfad).collect(Collectors.toSet()),
                ingest.dokumentQuellenLaden(dokumente), seiten.stream().map(Wissensseite::id).collect(Collectors.toSet()),
                basis.quellen().stream().map(q -> q.schluessel()).collect(Collectors.toSet()),
                entfernte, Set.of(), false, false, false, basis.vertraulichkeit(),
                Wissensseite.Vertraulichkeit.valueOf(kontextstufe.name()));
        return freigabePruefung.pruefen(vorschlag, graph,
                graph.mindeststufen().getOrDefault(basis.id(), WissensStufe.INTERN), kontextstufe, null, kontext)
                .pruefung();
    }

    private static Set<UUID> dokumente(UUID seite, WissensGraph graph, Map<UUID, Wissensseite> seiten) {
        Set<UUID> dokumente = new HashSet<>(graph.dokumente(seite));
        Set<UUID> besucht = new HashSet<>();
        ArrayDeque<UUID> offen = new ArrayDeque<>();
        offen.add(seite);
        while (!offen.isEmpty()) {
            UUID id = offen.removeFirst();
            if (besucht.add(id)) {
                Wissensseite fassung = seiten.get(id);
                if (fassung == null) {
                    throw new IllegalStateException("Quellseite während Lint-Ladung entfernt");
                }

                if (fassung.wissensquelleId() != null) {
                    dokumente.add(fassung.wissensquelleId());
                }
                offen.addAll(graph.quellseitenJeSeite().getOrDefault(id, Set.of()));
            }
        }
        return dokumente;
    }

    private static Set<UUID> quellDokumente(java.util.Collection<Wissensseite> seiten) {
        return seiten.stream().map(Wissensseite::wissensquelleId).filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
    }

    private static boolean bekannteHuelle(Set<UUID> seiten, WissensGraph graph) {
        Set<UUID> besucht = new HashSet<>();
        ArrayDeque<UUID> offen = new ArrayDeque<>(seiten);
        while (!offen.isEmpty()) {
            UUID id = offen.removeFirst();
            if (besucht.add(id)) {
                if (!graph.kenntSeite(id)) {
                    return false;
                }
                offen.addAll(graph.quellseitenJeSeite().getOrDefault(id, Set.of()));
            }
        }
        return true;
    }

    @Transactional
    public WissenIngestPort.BudgetReservierung budgetReservieren(UUID entwurfId, long tokens, Instant jetzt) {
        if (tokens < 1) {
            throw new IllegalArgumentException("Die Tokenreservierung muss positiv sein");
        }
        lint.budgetsperre();
        var werte = einstellungen.lesen();
        long limit = werte.lintBudgetTokens() == null ? standardBudget : werte.lintBudgetTokens();
        long rest = Math.max(0, limit - lint.lintVerbrauch(entwurfId));
        long monat = werte.monatsbudgetTokens() == null
                ? konfiguration.ingest().monatsbudgetTokens() : werte.monatsbudgetTokens();
        var zeitraum = WissenIngestStarten.monat(jetzt);
        return ingest.budgetReservieren(entwurfId, tokens, rest, monat, zeitraum.von(), zeitraum.bis());
    }

    @Transactional
    public Optional<AufrufBudget> budgetFuerAufruf(UUID entwurfId, long eingabe, int maxAusgabe, Instant jetzt) {
        if (eingabe < 0 || maxAusgabe < 1) {
            throw new IllegalArgumentException("Ungültige Tokenzahl für den Lint-Aufruf");
        }
        lint.budgetsperre();
        int unten = 1;
        int oben = maxAusgabe;
        int passend = 0;
        while (unten <= oben) {
            int mitte = unten + (oben - unten) / 2;
            var reservierung = budgetReservieren(entwurfId, Math.addExact(eingabe, mitte), jetzt);
            if (reservierung.reserviert()) {
                ingest.budgetFreigeben(reservierung.id());
                passend = mitte;
                unten = mitte + 1;
            } else {
                oben = mitte - 1;
            }
        }
        if (passend == 0) {
            return Optional.empty();
        }
        var reservierung = budgetReservieren(entwurfId, Math.addExact(eingabe, passend), jetzt);
        if (!reservierung.reserviert()) {
            throw new IllegalStateException("Lint-Budget unter Sperre nicht reservierbar");
        }
        return Optional.of(new AufrufBudget(reservierung.id(), passend));
    }

    @Transactional
    public void budgetFreigeben(UUID id) {
        ingest.budgetFreigeben(id);
    }

    @Transactional
    public Optional<WissenLintRepository.Lauf> laden(UUID id) {
        return lint.laden(id);
    }

    @Transactional
    public boolean kontextSichern(Auftrag auftrag, UUID id, String erwartet,
                                   WissenLintRepository.Fortschritt stand, long ein, long aus) {
        auftraege.anspruchSichern(auftrag);
        if (auftraege.nachfolgerVorhanden(auftrag, VORGAENGER)
                || lint.laden(id).filter(l -> erwartet.equals(l.schritt())).isEmpty()) {
            return false;
        }
        lint.fortschritt(id, erwartet, stand, ein, aus);
        return true;
    }

    @Transactional
    public void fortsetzen(Auftrag auftrag, UUID id, String erwartet, String neu,
                           WissenLintRepository.Fortschritt stand, long ein, long aus) {
        auftraege.anspruchSichern(auftrag);
        if (auftraege.nachfolgerVorhanden(auftrag, VORGAENGER)
                || lint.laden(id).filter(l -> erwartet.equals(l.schritt())).isEmpty()) {
            return;
        }
        lint.fortschritt(id, neu, stand, ein, aus);
        auftraege.einreihen(TYP, new Nutzlast(Map.of(LINT_ID, id, VORGAENGER, auftrag.id())));
    }

    @Transactional
    public void abschliessen(Auftrag auftrag, UUID id, String erwartet, WissenLintRepository.Fortschritt stand,
                             long ein, long aus, boolean unvollstaendig, String hinweis, boolean hatAenderungen) {
        auftraege.anspruchSichern(auftrag);
        if (auftraege.nachfolgerVorhanden(auftrag, VORGAENGER)
                || lint.laden(id).filter(l -> erwartet.equals(l.schritt())).isEmpty()) {
            return;
        }
        stand.aenderungen().forEach(ingest::aenderungAnlegen);
        lint.abschliessen(id, stand, ein, aus, unvollstaendig, hinweis, hatAenderungen);
    }

    @Transactional
    public void nachFehlversuch(Auftrag auftrag, UUID id) {
        if (auftraege.nachfolgerVorhanden(auftrag, VORGAENGER)) {
            return;
        }
        lint.laden(id).ifPresent(lauf -> {
            var alt = lauf.fortschritt();
            List<WissensLint.Befund> befunde = new java.util.ArrayList<>(alt.befunde());
            befunde.add(new WissensLint.Befund(null, "LINT_UNVOLLSTAENDIG", null));
            var stand = new WissenLintRepository.Fortschritt(befunde, alt.paare(), alt.paarIndex(), alt.seiten(),
                    alt.seitenIndex(), WissenLintVerarbeiten.ohneUnausgefuehrte(alt), alt.kontextstufe(), null, false,
                    alt.hinweise(),
                    alt.aenderungen());
            stand.aenderungen().forEach(ingest::aenderungAnlegen);
            lint.abschliessen(id, stand, lauf.ein(), lauf.aus(), true,
                    "Der Lint konnte nicht vollständig verarbeitet werden. Bitte erneut starten.",
                    !stand.aenderungen().isEmpty());
        });
    }

    public record PaarKontext(Wissensseite erste, Wissensseite zweite, WissensStufe ersteStufe,
                              WissensStufe zweiteStufe, WissensStufe kontextstufe) {
    }

    public record SeitenKontext(Wissensseite ziel, List<Wissensseite> bezuege, WissensStufe kontextstufe) {
        public SeitenKontext {
            bezuege = List.copyOf(bezuege);
        }
    }

    public record AufrufBudget(UUID id, int ausgabeTokens) {
    }
}
