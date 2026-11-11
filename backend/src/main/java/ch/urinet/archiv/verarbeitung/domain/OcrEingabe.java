package ch.urinet.archiv.verarbeitung.domain;

import java.util.List;

/**
 * Quellen eines Dokuments für die Verarbeitung (Req 5.1, 5.10): genau ein PDF oder ein bis mehrere Bilder, die in
 * dieser Reihenfolge die Seiten ergeben (mehrere Bilder als ein Dokument, Req 3.5; Seiten der Handy-Erfassung).
 */
public record OcrEingabe(List<OcrQuelle> quellen) {

    public OcrEingabe {
        quellen = List.copyOf(quellen);
        if (quellen.isEmpty()) {
            throw new IllegalArgumentException("Mindestens eine Quelle");
        }
        boolean pdf = quellen.stream().anyMatch(q -> q.typ() == Quelltyp.PDF);
        if (pdf && quellen.size() > 1) {
            throw new IllegalArgumentException("Ein PDF nur als einzige Quelle");
        }
    }

    public static OcrEingabe von(OcrQuelle... quellen) {
        return new OcrEingabe(List.of(quellen));
    }

    public boolean istPdf() {
        return quellen.getFirst().typ() == Quelltyp.PDF;
    }
}
