package ch.urinet.archiv.integration.domain.webseite;

import java.util.List;
import java.util.Objects;

/** Öffentlich filterbare Klassifikation und die sichtbaren Werte eines Dokuments oder Filters. */
public record WebseiteKlassifikation(String code, String name, List<Wert> werte) {

    public WebseiteKlassifikation {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(name, "name");
        werte = List.copyOf(werte);
    }

    public record Wert(String code, String name) {

        public Wert {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(name, "name");
        }
    }
}
