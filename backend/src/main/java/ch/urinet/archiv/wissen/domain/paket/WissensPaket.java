package ch.urinet.archiv.wissen.domain.paket;

import java.util.List;

public record WissensPaket(PaketKopf kopf, List<PaketSeite> seiten, List<Hinweis> hinweise) {
    public WissensPaket {
        seiten = List.copyOf(seiten);
        hinweise = List.copyOf(hinweise);
    }

    public enum HinweisCode { DATEI_IGNORIERT, FELD_UNBEKANNT, FELD_IGNORIERT }
    public record Hinweis(HinweisCode code, String pfad) {}
}
