package ch.urinet.archiv.suche.adapter.out.konfiguration;

import ch.urinet.archiv.suche.application.AktualitaetsregelPort;
import ch.urinet.archiv.suche.domain.Aktualitaetsregel;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;
import java.util.stream.Collectors;

@ApplicationScoped
public class KonfigurierteAktualitaetsregel implements AktualitaetsregelPort {

    private final Map<String, Aktualitaetsregel> regeln;

    public KonfigurierteAktualitaetsregel(AktualitaetsKonfiguration konfiguration) {
        regeln = konfiguration.mandanten().entrySet().stream().collect(Collectors.toUnmodifiableMap(
                Map.Entry::getKey, eintrag -> new Aktualitaetsregel(eintrag.getValue().aktiviert(),
                        eintrag.getValue().faktor(), eintrag.getValue().halbwertzeitTage())));
    }

    @Override
    public Aktualitaetsregel fuer(String mandantCode) {
        return regeln.getOrDefault(mandantCode, Aktualitaetsregel.AUS);
    }
}
