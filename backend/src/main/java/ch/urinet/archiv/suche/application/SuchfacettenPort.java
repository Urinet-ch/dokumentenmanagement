package ch.urinet.archiv.suche.application;

import ch.urinet.archiv.sicherheit.domain.Sichtregel;
import ch.urinet.archiv.suche.domain.Suchanfrage;

import java.util.List;
import java.util.UUID;

/** Liefert Facetten unter derselben Sicht- und Suchbedingung wie die Trefferliste. */
public interface SuchfacettenPort {

    record Wert(String code, String name, long anzahl) {
    }

    record Facette(String code, String name, List<Wert> werte) {
        public Facette {
            werte = List.copyOf(werte);
        }
    }

    List<Facette> facetten(Suchanfrage anfrage, Sichtregel sichtregel, boolean mitDateiname);

    /** Hybridsuche: zählt nur die gelieferten IDs und prüft Sicht und Filter erneut. */
    List<Facette> facetten(Suchanfrage anfrage, Sichtregel sichtregel, List<UUID> trefferIds);
}
