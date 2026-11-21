package ch.urinet.archiv.benutzer.application;

import ch.urinet.archiv.gemeinsam.domain.KonfigurationUngueltig;
import ch.urinet.archiv.protokoll.domain.ProtokollDetails.Verwaltungsgrund;

import java.util.Arrays;
import java.util.List;

public final class VerwaltungsgrundPruefung {
    private VerwaltungsgrundPruefung() {
    }

    public static Verwaltungsgrund pruefen(String eingabe) {
        return Arrays.stream(Verwaltungsgrund.values())
                .filter(grund -> grund.name().equals(eingabe))
                .findFirst()
                .orElseThrow(() -> new KonfigurationUngueltig(List.of(
                        new KonfigurationUngueltig.Befund("grund", "GRUND_UNGUELTIG",
                                "Bitte wählen Sie eine gültige Grundkategorie."))));
    }
}
