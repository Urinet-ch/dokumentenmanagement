package ch.urinet.archiv.konfiguration.application;

import ch.urinet.archiv.konfiguration.domain.Konfiguration;
import ch.urinet.archiv.mandant.domain.MandantCode;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@ApplicationScoped
public class KonfigurationCache {

    private final ConcurrentMap<Schluessel, Konfiguration> eintraege = new ConcurrentHashMap<>();

    public Optional<Konfiguration> finden(MandantCode mandant, int version) {
        return Optional.ofNullable(eintraege.get(new Schluessel(mandant, version)));
    }

    public void speichern(MandantCode mandant, Konfiguration konfiguration) {
        eintraege.put(new Schluessel(mandant, konfiguration.version()), konfiguration);
    }

    public void invalidieren(MandantCode mandant) {
        eintraege.keySet().removeIf(schluessel -> schluessel.mandant().equals(mandant));
    }

    private record Schluessel(MandantCode mandant, int version) {
    }
}
