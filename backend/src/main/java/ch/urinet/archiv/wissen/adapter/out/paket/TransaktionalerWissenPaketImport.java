package ch.urinet.archiv.wissen.adapter.out.paket;

import ch.urinet.archiv.ablage.domain.Pruefsumme;
import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.gemeinsam.domain.PaketUngueltig;
import ch.urinet.archiv.gemeinsam.domain.Ursachen;
import ch.urinet.archiv.sicherheit.domain.Benutzer;
import ch.urinet.archiv.wissen.application.WissenImport;
import ch.urinet.archiv.wissen.application.WissenPaketImport;
import ch.urinet.archiv.wissen.domain.paket.PaketGrenzen;
import ch.urinet.archiv.wissen.domain.paket.WissensPaketException;
import ch.urinet.archiv.wissen.domain.paket.WissensPaketLeser;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.narayana.jta.QuarkusTransactionException;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;

@ApplicationScoped
public class TransaktionalerWissenPaketImport implements WissenPaketImport {
    private final WissenImport importieren;
    private final PaketGrenzen grenzen;
    private final WissensPaketLeser leser;

    public TransaktionalerWissenPaketImport(WissenImport importieren,
            @ConfigProperty(name = "archiv.wissen.import.max-zip-bytes", defaultValue = "10485760") int zip,
            @ConfigProperty(name = "archiv.wissen.import.max-entpackt-bytes", defaultValue = "20971520") int entpackt,
            @ConfigProperty(name = "archiv.wissen.import.max-datei-bytes", defaultValue = "131072") int datei,
            @ConfigProperty(name = "archiv.wissen.import.max-seiten", defaultValue = "200") int seiten,
            @ConfigProperty(name = "archiv.wissen.import.max-tiefe", defaultValue = "4") int tiefe,
            @ConfigProperty(name = "archiv.wissen.import.max-kompression", defaultValue = "100") int kompression,
            @ConfigProperty(name = "archiv.wissen.import.zeitgrenze", defaultValue = "60s") Duration zeit) {
        this.importieren = importieren;
        grenzen = new PaketGrenzen(zip, entpackt, datei, 16384, seiten, seiten + 5, tiefe, kompression, 5, zeit);
        leser = new WissensPaketLeser(grenzen);
    }

    @Override public int maxZipBytes() { return grenzen.zipBytes(); }
    @Override public long zeitgrenzeNanos() { return grenzen.zeit().toNanos(); }

    @Override
    public WissenImport.Antwort importieren(Benutzer benutzer, String mandant, byte[] zip, long deadline, String realIp) {
        String sha256 = Pruefsumme.von(zip);
        try {
            return QuarkusTransaction.requiringNew().timeout((int) Math.max(1, grenzen.zeit().toSeconds())).call(() -> {
                var paket = leser.lesen(zip, deadline);
                if (!mandant.equals(paket.kopf().mandant())) {
                    throw new PaketUngueltig("MANDANT", "paket.yaml");
                }
                return importieren.importieren(benutzer, paket, sha256, deadline, realIp);
            });
        } catch (WissensPaketException e) {
            throw switch (e.code()) {
                case DATEI_ZU_GROSS -> new FachFehler(Fehlercode.DATEI_ZU_GROSS);
                case DATEITYP_UNBEKANNT -> new FachFehler(Fehlercode.DATEITYP_UNBEKANNT);
                default -> new PaketUngueltig(e.code().name(), e.pfad());
            };
        } catch (QuarkusTransactionException e) {
            if (System.nanoTime() >= deadline || Ursachen.enthaeltSqlState(e, "57014")) {
                throw new PaketUngueltig("ZEIT", "paket.yaml");
            }
            throw e;
        }
    }

}
