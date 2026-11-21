package ch.urinet.archiv.wissen.application;

import ch.urinet.archiv.ki.application.KiPort;
import ch.urinet.archiv.wissen.domain.WissensLint;
import ch.urinet.archiv.wissen.domain.WissensStufe;
import ch.urinet.archiv.wissen.domain.Wissensseite;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Prüft alle Verweise der KI-Antwort gegen das konkrete Paar und seine deterministischen Kandidaten. */
public final class WissenLintAuswertung {
    private static final int MAX_TREFFER = 100;
    private static final int MAX_TEXT = 2_000;

    private WissenLintAuswertung() {
    }

    public static Ergebnis auswerten(WissensLint.Paar paar, WissenLintSpeichern.PaarKontext kontext,
                                     KiPort.LintPaar antwort) {
        if (antwort.widersprueche().size() + antwort.veraltet().size() + antwort.links().size() > MAX_TREFFER) {
            throw ungueltig();
        }
        Wissensseite a = kontext.erste();
        Wissensseite b = kontext.zweite();
        if (!a.id().equals(paar.id().erste()) || !b.id().equals(paar.id().zweite())) {
            throw ungueltig();
        }
        List<WissensLint.Befund> befunde = new ArrayList<>();
        List<Seitenauftrag> auftraege = new ArrayList<>();
        for (var widerspruch : antwort.widersprueche()) {
            Wissensseite seite = seite(widerspruch.seite(), a, b);
            Wissensseite gegen = seite(widerspruch.gegenseite(), a, b);
            if (seite.id().equals(gegen.id()) || !schluessel(seite, widerspruch.schluessel())
                    || !schluessel(gegen, widerspruch.gegenschluessel()) || !text(widerspruch.text())) {
                throw ungueltig();
            }
            befunde.add(new WissensLint.Befund(seite.id(), "WIDERSPRUCH", gegen.id()));
            befunde.add(new WissensLint.Befund(gegen.id(), "WIDERSPRUCH", seite.id()));
            String anweisung = "Widerspruch zwischen [^" + widerspruch.schluessel() + "] und [^"
                    + widerspruch.gegenschluessel() + "]: " + widerspruch.text()
                    + ". Beide Aussagen und beide Belege erhalten; Widerspruchsblock ergänzen.";
            if (erlaubt(seite.id(), kontext)) {
                auftraege.add(new Seitenauftrag(seite.id(), gegen.id(), anweisung));
            }
            if (erlaubt(gegen.id(), kontext)) {
                auftraege.add(new Seitenauftrag(gegen.id(), seite.id(), anweisung));
            }
        }
        for (var veraltet : antwort.veraltet()) {
            Wissensseite seite = seite(veraltet.seite(), a, b);
            Wissensseite gegen = seite.id().equals(a.id()) ? b : a;
            boolean kandidat = paar.veraltet().stream().anyMatch(k -> k.seiteId().equals(seite.id())
                    && k.schluessel().equals(veraltet.schluessel())
                    && k.neuereQuelleId().toString().equals(veraltet.neuereQuelle()));
            if (!kandidat || !schluessel(seite, veraltet.schluessel()) || !text(veraltet.text())) {
                throw ungueltig();
            }
            befunde.add(new WissensLint.Befund(seite.id(), "VERALTET", gegen.id()));
            if (erlaubt(seite.id(), kontext)) {
                auftraege.add(new Seitenauftrag(seite.id(), gegen.id(), "Möglicherweise veraltete Aussage [^"
                        + veraltet.schluessel() + "]: " + veraltet.text()
                        + ". Alte Aussage nicht löschen, die neuere Quelle nachvollziehbar darstellen."));
            }
        }
        for (var link : antwort.links()) {
            Wissensseite seite = seite(link.seite(), a, b);
            Wissensseite ziel = seite(link.ziel(), a, b);
            if (seite.id().equals(ziel.id()) || !text(link.text())) {
                throw ungueltig();
            }
            befunde.add(new WissensLint.Befund(seite.id(), "LINK", ziel.id()));
            if (erlaubt(seite.id(), kontext)) {
                auftraege.add(new Seitenauftrag(seite.id(), ziel.id(),
                        "Querverweis auf /" + ziel.pfad() + " prüfen: " + link.text()));
            }
        }
        return new Ergebnis(List.copyOf(befunde), List.copyOf(auftraege));
    }

    private static boolean erlaubt(UUID id, WissenLintSpeichern.PaarKontext kontext) {
        WissensStufe stufe = id.equals(kontext.erste().id()) ? kontext.ersteStufe() : kontext.zweiteStufe();
        return stufe == kontext.kontextstufe()
                && !(stufe == WissensStufe.INTERN
                && "ueberblick".equals(id.equals(kontext.erste().id())
                        ? kontext.erste().pfad() : kontext.zweite().pfad()));
    }

    private static Wissensseite seite(String id, Wissensseite a, Wissensseite b) {
        if (a.id().toString().equals(id)) {
            return a;
        }
        if (b.id().toString().equals(id)) {
            return b;
        }
        throw ungueltig();
    }

    private static boolean schluessel(Wissensseite seite, String schluessel) {
        return schluessel != null && seite.quellen().stream().anyMatch(q -> schluessel.equals(q.schluessel()));
    }

    private static boolean text(String text) {
        return text != null && !text.isBlank() && text.length() <= MAX_TEXT;
    }

    private static IllegalArgumentException ungueltig() {
        return new IllegalArgumentException("Ungültige Referenz in der Lint-Antwort");
    }

    public record Seitenauftrag(UUID seiteId, UUID gegenSeiteId, String anweisung) {
    }

    public record Ergebnis(List<WissensLint.Befund> befunde, List<Seitenauftrag> auftraege) {
    }
}
