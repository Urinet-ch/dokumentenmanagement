package ch.urinet.archiv.mandant.application;

import ch.urinet.archiv.mandant.domain.DokumentartVorlage;
import ch.urinet.archiv.mandant.domain.Geschaeftsjahresende;
import ch.urinet.archiv.mandant.domain.Mandant;
import ch.urinet.archiv.mandant.domain.MandantCode;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.List;

/**
 * Legt einen Mandanten an (Req 1.1, 1.5, 9.6; ADR-002): Schema {@code m_<code>} über {@code db/mandant}, Seed der
 * Dokumentarten, Prüfung des neuen Schemas gegen das Mapping (Req 1.7), Bucket {@code <präfix><code>}; erst danach der
 * Eintrag im Register, damit ein abgebrochener Lauf keinen aktiven Mandanten hinterlässt. Ein erneuter Lauf nach einem
 * Abbruch setzt fort.
 */
@ApplicationScoped
public class MandantProvisionierung {

    private static final Logger LOG = Logger.getLogger(MandantProvisionierung.class);

    private final MandantRegister register;
    private final MandantDatenbank datenbank;
    private final MandantBucket bucket;
    private final DokumentartSeed seed;
    private final MandantSchemaPruefung schemaPruefung;
    private final String bucketPraefix;

    public MandantProvisionierung(MandantRegister register, MandantDatenbank datenbank, MandantBucket bucket,
                                  DokumentartSeed seed, MandantSchemaPruefung schemaPruefung,
                                  @ConfigProperty(name = "archiv.ablage.bucket-praefix") String bucketPraefix) {
        this.register = register;
        this.datenbank = datenbank;
        this.bucket = bucket;
        this.seed = seed;
        this.schemaPruefung = schemaPruefung;
        this.bucketPraefix = bucketPraefix;
    }

    public Mandant anlegen(String code, String name, String geschaeftsjahresende) {
        MandantCode mandantCode;
        Geschaeftsjahresende ende;
        String bucketName;
        try {
            mandantCode = new MandantCode(code);
            ende = geschaeftsjahresende == null ? Geschaeftsjahresende.STANDARD
                    : new Geschaeftsjahresende(geschaeftsjahresende);
            bucketName = mandantCode.bucket(bucketPraefix);
        } catch (IllegalArgumentException e) {
            throw new MandantFehler(MandantFehler.Grund.UNGUELTIG, e.getMessage(), e);
        }
        if (register.existiert(mandantCode)) {
            throw new MandantFehler(MandantFehler.Grund.MANDANT_EXISTIERT, "Mandant existiert schon: " + code);
        }
        List<DokumentartVorlage> arten = seed.lesen(mandantCode);
        datenbank.schemaAnlegen(mandantCode.schema());
        datenbank.dokumentartenEinspielen(mandantCode.schema(), arten);
        schemaPruefung.pruefen(mandantCode.schema());
        bucket.einrichten(bucketName);
        Mandant mandant = new Mandant(mandantCode, name == null || name.isBlank() ? code : name,
                mandantCode.schema(), bucketName, ende, true);
        register.eintragen(mandant);
        LOG.infof("Mandant %s angelegt (Schema %s, Bucket %s, %d Dokumentarten)", code, mandant.schema(), bucketName,
                arten.size());
        return mandant;
    }
}
