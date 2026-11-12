package ch.urinet.archiv.importe.domain;

import java.time.Instant;
import java.util.Objects;

/** Bestätigte Zuordnung je Mandant und Importart ({@code import_zuordnung}) mit dem Hash ihrer Kopfzeile (Req 13.2). */
public record GespeicherteZuordnung(ImportArt art, Zuordnung zuordnung, String kopfHash, Instant geaendert) {

    public GespeicherteZuordnung {
        Objects.requireNonNull(art, "art");
        Objects.requireNonNull(zuordnung, "zuordnung");
        Objects.requireNonNull(kopfHash, "kopfHash");
    }

    /** Gilt für diese Kopfzeile: gleicher Hash und die Positionen passen (Req 13.2). */
    public boolean passt(Kopfzeile kopf) {
        return kopfHash.equals(kopf.hash()) && zuordnung.mangel(art, kopf.spalten()).isEmpty();
    }
}
