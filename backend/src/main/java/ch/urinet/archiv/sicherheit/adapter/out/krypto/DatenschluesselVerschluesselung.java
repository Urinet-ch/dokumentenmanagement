package ch.urinet.archiv.sicherheit.adapter.out.krypto;

import ch.urinet.archiv.sicherheit.application.Verschluesselung;
import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Optional;

/**
 * {@link Verschluesselung} mit dem Datenschlüssel aus der Secret-Datei {@code archiv_data_key} (tech.md Regel 7; in
 * {@code prod} über {@code smallrye.config.source.file.locations=/run/secrets}). Fehlt die Datei oder ist sie leer, ist
 * der Port nicht verfügbar. Ist sie da, aber kein Base64 von 32 Byte, oder kommt der Wert mit
 * {@code archiv.secrets.datenschluessel-nur-datei} (prod) nicht aus einer Datei, bricht der Start ab; die Meldung nennt
 * nie den Inhalt.
 */
@Startup
@ApplicationScoped
public class DatenschluesselVerschluesselung implements Verschluesselung {

    static final String SCHLUESSEL = "archiv_data_key";
    private static final String DATEI_QUELLE = "FileSystemConfigSource[";

    private final Optional<Datenschluessel> schluessel;

    public DatenschluesselVerschluesselung(Config config,
                                           @ConfigProperty(name = "archiv.secrets.datenschluessel-nur-datei",
                                                   defaultValue = "false") boolean nurDatei) {
        var wert = config.getConfigValue(SCHLUESSEL);
        Optional<String> inhalt = Optional.ofNullable(wert.getValue()).filter(s -> !s.isBlank());
        if (inhalt.isPresent() && nurDatei
                && (wert.getSourceName() == null || !wert.getSourceName().startsWith(DATEI_QUELLE))) {
            throw new IllegalStateException(SCHLUESSEL + " muss aus der Secret-Datei kommen (tech.md Regel 7)");
        }
        try {
            this.schluessel = inhalt.map(Datenschluessel::aus);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(SCHLUESSEL + " ungültig: " + e.getMessage());
        }
    }

    @Override
    public boolean verfuegbar() {
        return schluessel.isPresent();
    }

    @Override
    public byte[] verschluesseln(byte[] klartext, String bezug) {
        return schluessel().verschluesseln(klartext, bezug);
    }

    @Override
    public byte[] entschluesseln(byte[] chiffrat, String bezug) {
        return schluessel().entschluesseln(chiffrat, bezug);
    }

    private Datenschluessel schluessel() {
        return schluessel.orElseThrow(() -> new IllegalStateException(SCHLUESSEL + " fehlt"));
    }
}
