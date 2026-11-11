package ch.urinet.archiv.ablage.domain;

import java.time.LocalDate;
import java.util.Objects;

/** Dateien eines Tages-Manifests: {@code manifest/<yyyy-mm-dd>.json} mit {@code .sig} und {@code .tsr} (design.md «Ablage»). */
public enum ManifestTeil {
    JSON("json"),
    SIG("sig"),
    TSR("tsr");

    private final String endung;

    ManifestTeil(String endung) {
        this.endung = endung;
    }

    public String pfad(LocalDate datum) {
        return "manifest/" + Objects.requireNonNull(datum, "datum") + "." + endung;
    }
}
