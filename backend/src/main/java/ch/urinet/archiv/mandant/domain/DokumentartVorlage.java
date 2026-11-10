package ch.urinet.archiv.mandant.domain;

import java.util.List;

/** Eine Dokumentart aus dem Seed des Mandanten ({@code seed/<code>/dokumentarten.json}), Spalten wie {@code dokumentart}. */
public record DokumentartVorlage(String code, String name, List<String> gruppen, List<String> pflichtfelder,
                                 String zusatzSchemaJson, String vertraulichkeit, Integer aufbewahrungJahre,
                                 String aufbewahrungAb, boolean gesetzlichePflicht, boolean aktiv) {

    public DokumentartVorlage {
        gruppen = List.copyOf(gruppen);
        pflichtfelder = List.copyOf(pflichtfelder);
    }
}
