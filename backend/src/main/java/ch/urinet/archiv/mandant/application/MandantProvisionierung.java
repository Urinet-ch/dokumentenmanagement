package ch.urinet.archiv.mandant.application;

import ch.urinet.archiv.konfiguration.application.KonfigurationsdateiVerwaltung;
import ch.urinet.archiv.konfiguration.domain.Konfiguration;
import ch.urinet.archiv.mandant.domain.Geschaeftsjahresende;
import ch.urinet.archiv.mandant.domain.Mandant;
import ch.urinet.archiv.mandant.domain.MandantCode;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Legt einen Mandanten an (Req 1.1, 1.5, 9.6; ADR-002): Prüfung des Seeds ({@link SeedPruefung}, vor jeder
 * Änderung), Schema {@code m_<code>} über {@code db/mandant}, vollständiger Konfigurationsseed, Prüfung des neuen
 * Schemas gegen das Mapping (Req 1.7), Bucket {@code <präfix><code>}; erst danach der
 * Eintrag im Register, damit ein abgebrochener Lauf keinen aktiven Mandanten hinterlässt. Ein erneuter Lauf nach einem
 * Abbruch setzt fort.
 */
@ApplicationScoped
public class MandantProvisionierung {

    private static final Logger LOG = Logger.getLogger(MandantProvisionierung.class);

    private final MandantRegister register;
    private final MandantDatenbank datenbank;
    private final MandantBucket bucket;
    private final KonfigurationSeed seed;
    private final KonfigurationsdateiVerwaltung konfiguration;
    private final MandantKontext kontext;
    private final MandantSchemaPruefung schemaPruefung;
    private final SeedPruefung seedPruefung;
    private final String bucketPraefix;

    public MandantProvisionierung(MandantRegister register, MandantDatenbank datenbank, MandantBucket bucket,
                                  KonfigurationSeed seed, KonfigurationsdateiVerwaltung konfiguration,
                                  MandantKontext kontext,
                                  MandantSchemaPruefung schemaPruefung,
                                  SeedPruefung seedPruefung,
                                  @ConfigProperty(name = "archiv.ablage.bucket-praefix") String bucketPraefix) {
        this.register = register;
        this.datenbank = datenbank;
        this.bucket = bucket;
        this.seed = seed;
        this.konfiguration = konfiguration;
        this.kontext = kontext;
        this.schemaPruefung = schemaPruefung;
        this.seedPruefung = seedPruefung;
        this.bucketPraefix = bucketPraefix;
    }

    public Mandant anlegen(String code, String name, String geschaeftsjahresende) {
        MandantCode mandantCode;
        Geschaeftsjahresende ende;
        String bucketName;
        try {
            mandantCode = new MandantCode(code);
            bucketName = mandantCode.bucket(bucketPraefix);
        } catch (IllegalArgumentException e) {
            throw new MandantFehler(MandantFehler.Grund.UNGUELTIG, e.getMessage(), e);
        }
        if (register.existiert(mandantCode)) {
            throw new MandantFehler(MandantFehler.Grund.MANDANT_EXISTIERT, "Mandant existiert schon: " + code);
        }
        Konfiguration seedKonfiguration = seed.lesen(mandantCode);
        seedPruefung.pruefen(mandantCode, seedKonfiguration);
        try {
            ende = geschaeftsjahresende == null
                    ? new Geschaeftsjahresende(seedKonfiguration.einstellungen().geschaeftsjahrEnde())
                    : new Geschaeftsjahresende(geschaeftsjahresende);
        } catch (IllegalArgumentException e) {
            throw new MandantFehler(MandantFehler.Grund.SEED_UNGUELTIG,
                    "Geschäftsjahresende des Seeds für Mandant " + code + " ist ungültig", e);
        }
        datenbank.schemaAnlegen(mandantCode.schema());
        schemaPruefung.pruefen(mandantCode.schema());
        bucket.einrichten(bucketName);
        Mandant mandant = new Mandant(mandantCode, name == null || name.isBlank() ? code : name,
                mandantCode.schema(), bucketName, ende, true);
        register.eintragen(mandant);
        kontext.mit(mandantCode, () -> konfiguration.seedEinspielen(seedKonfiguration));
        LOG.infof("Mandant %s angelegt (Schema %s, Bucket %s, %d Dokumentarten)", code, mandant.schema(), bucketName,
                seedKonfiguration.arten().size());
        return mandant;
    }

}
