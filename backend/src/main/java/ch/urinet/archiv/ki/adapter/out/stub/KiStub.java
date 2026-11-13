package ch.urinet.archiv.ki.adapter.out.stub;

import ch.urinet.archiv.ki.adapter.out.langchain4j.LangChain4jKiAdapter;
import ch.urinet.archiv.ki.application.KiPort;
import ch.urinet.archiv.ki.domain.Feld;
import ch.urinet.archiv.ki.domain.Rohvorschlag;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Dev-Stub von {@link KiPort}: feste, gültige Antwort statt Infomaniak AI Services (Art {@code KORRESPONDENZ}, ohne
 * Beträge); aktiv mit {@code archiv.ki.stub=true} (Profil dev). Antwortschema und Art-Codes wie der echte Adapter.
 */
@ApplicationScoped
@Typed(KiStub.class)
public class KiStub implements KiPort {

    static final String MODELL = "stub";

    @Override
    public Antwort metadaten(Anfrage anfrage) {
        Map<Feld, BigDecimal> sicherheit = new EnumMap<>(Feld.class);
        sicherheit.put(Feld.ART, new BigDecimal("0.9"));
        sicherheit.put(Feld.TITEL, new BigDecimal("0.9"));
        sicherheit.put(Feld.RICHTUNG, new BigDecimal("0.5"));
        sicherheit.put(Feld.VERTRAULICHKEIT, new BigDecimal("0.9"));
        Rohvorschlag roh = new Rohvorschlag("KORRESPONDENZ", "Vorschlag des Dev-Stubs", null, null, null, null, null,
                null, null, null, null, null, null, null, List.of(), sicherheit);
        return new Gueltig(roh, new Verbrauch(MODELL, 0, 0));
    }

    @Override
    public String antwortschema() {
        return LangChain4jKiAdapter.schema();
    }

    @Override
    public List<String> artCodes() {
        return LangChain4jKiAdapter.codes();
    }
}
