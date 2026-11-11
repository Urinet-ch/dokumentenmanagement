package ch.urinet.archiv.verarbeitung.application;

import ch.urinet.archiv.verarbeitung.domain.AuftragTyp;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import org.eclipse.microprofile.config.Config;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Die registrierten {@link AuftragHandler} je Typ mit ihrer Zeitgrenze aus {@code archiv.auftrag.zeitgrenze.<typ>}
 * (Req 5.6, design.md «Verarbeitung»). Fehlt die Zeitgrenze eines Handlers oder gibt es einen Typ doppelt, bricht der
 * Start ab: Ein Auftrag ohne Zeitgrenze könnte unbegrenzt laufen.
 */
@ApplicationScoped
public class AuftragHandlerVerzeichnis {

    public static final String ZEITGRENZE = "archiv.auftrag.zeitgrenze.";

    private final Map<AuftragTyp, AuftragHandler> handler;
    private final Map<AuftragTyp, Duration> zeitgrenzen;

    public AuftragHandlerVerzeichnis(Instance<AuftragHandler> alle, Config config) {
        Map<AuftragTyp, AuftragHandler> nachTyp = new LinkedHashMap<>();
        Map<AuftragTyp, Duration> grenzen = new LinkedHashMap<>();
        for (AuftragHandler h : alle) {
            AuftragTyp typ = h.typ();
            if (nachTyp.putIfAbsent(typ, h) != null) {
                throw new IllegalStateException("Mehr als ein Handler für den Auftragstyp " + typ);
            }
            Duration grenze = config.getOptionalValue(ZEITGRENZE + typ.wert(), Duration.class)
                    .orElseThrow(() -> new IllegalStateException(ZEITGRENZE + typ.wert() + " fehlt"));
            if (grenze.isNegative() || grenze.isZero()) {
                throw new IllegalStateException(ZEITGRENZE + typ.wert() + " muss positiv sein");
            }
            grenzen.put(typ, grenze);
        }
        this.handler = Collections.unmodifiableMap(nachTyp);
        this.zeitgrenzen = Collections.unmodifiableMap(grenzen);
    }

    public Set<AuftragTyp> typen() {
        return handler.keySet();
    }

    public AuftragHandler handler(AuftragTyp typ) {
        AuftragHandler h = handler.get(typ);
        if (h == null) {
            throw new IllegalArgumentException("Kein Handler für den Auftragstyp " + typ);
        }
        return h;
    }

    public Duration zeitgrenze(AuftragTyp typ) {
        return zeitgrenzen.get(typ);
    }

    public Map<AuftragTyp, Duration> zeitgrenzen() {
        return zeitgrenzen;
    }
}
