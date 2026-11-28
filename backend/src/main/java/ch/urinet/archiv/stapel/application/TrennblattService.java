package ch.urinet.archiv.stapel.application;

import ch.urinet.archiv.dokumentart.application.DokumentartVerwaltung;
import ch.urinet.archiv.dokumentart.domain.Dokumentart;
import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.stapel.domain.TrennblattCode;
import ch.urinet.archiv.stapel.domain.TrennblattSatz;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Trennblätter und Ordner-Deckblätter erzeugen (Spec 09, Req 6.1–6.3, 6.5; Recht A1 prüft die REST-Schicht). Arten
 * nur aus den aktiven Dokumentarten des eigenen Mandanten; das Repository arbeitet im Schema der laufenden Anfrage.
 */
@ApplicationScoped
public class TrennblattService {

    /** Höchstens so viele Blätter je Anfrage (design.md «Trennblatt-Generator»). */
    public static final int MAX_BLAETTER = 100;

    private final DokumentartVerwaltung arten;
    private final TrennblattPdfPort pdf;

    public TrennblattService(DokumentartVerwaltung arten, TrennblattPdfPort pdf) {
        this.arten = arten;
        this.pdf = pdf;
    }

    /**
     * Ein Wunsch aus der Anfrage: Trennblatt mit {@code art} oder ohne, Deckblatt mit {@code titel}.
     *
     * @param anzahl Blätter, 1 bis {@value #MAX_BLAETTER}
     */
    public record Wunsch(TrennblattCode.Typ typ, String art, String titel, int anzahl) {
    }

    /**
     * PDF für alle Wünsche in ihrer Reihenfolge. 400 {@code ANFRAGE_UNGUELTIG}: keine Wünsche, Anzahl unter 1, mehr als
     * {@value #MAX_BLAETTER} Blätter zusammen, Feld des anderen Typs gesetzt oder Art keine aktive Dokumentart des
     * Mandanten. 400 {@code TRENNBLATT_UNGUELTIG}: Titel eines Deckblatts ungültig (Req 6.5) oder mit Zeichen, die
     * die eingebettete Schrift nicht darstellen kann (sonst trüge das Blatt den Titel nicht, Req 6.2).
     */
    public byte[] erzeugen(List<Wunsch> wuensche) {
        if (wuensche == null || wuensche.isEmpty()) {
            throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
        }
        long blaetter = 0;
        for (Wunsch w : wuensche) {
            if (w.typ() == null || w.anzahl() < 1 || w.anzahl() > MAX_BLAETTER) {
                throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
            }
            blaetter += w.anzahl();
        }
        if (blaetter > MAX_BLAETTER) {
            throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
        }
        Map<String, String> aktiv = wuensche.stream().anyMatch(w -> w.art() != null) ? aktiveArten() : Map.of();
        List<TrennblattSatz> saetze = new ArrayList<>();
        for (Wunsch w : wuensche) {
            saetze.add(switch (w.typ()) {
                case TRENN -> trennblatt(w, aktiv);
                case ORDNER -> {
                    if (w.art() != null) {
                        throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
                    }
                    TrennblattCode code = TrennblattCode.ordner(w.titel());
                    // das Blatt muss den Titel tragen (Req 6.2): Zeichen ausserhalb der Schrift lehnt 6.5 mit ab
                    if (!pdf.darstellbar(code.titel())) {
                        throw new FachFehler(Fehlercode.TRENNBLATT_UNGUELTIG);
                    }
                    yield new TrennblattSatz(code, code.titel(), w.anzahl());
                }
            });
        }
        return pdf.erzeugen(saetze);
    }

    private static TrennblattSatz trennblatt(Wunsch w, Map<String, String> aktiv) {
        if (w.titel() != null || w.art() != null && !aktiv.containsKey(w.art())) {
            throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
        }
        return new TrennblattSatz(TrennblattCode.trenn(w.art()), w.art() == null ? null : aktiv.get(w.art()),
                w.anzahl());
    }

    private Map<String, String> aktiveArten() {
        return arten.alle().stream().filter(Dokumentart::aktiv)
                .collect(Collectors.toMap(Dokumentart::code, Dokumentart::name, (a, b) -> a));
    }
}
