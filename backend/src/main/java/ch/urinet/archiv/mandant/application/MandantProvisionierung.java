package ch.urinet.archiv.mandant.application;

import ch.urinet.archiv.konfiguration.application.KonfigurationsdateiVerwaltung;
import ch.urinet.archiv.konfiguration.domain.Konfiguration;
import ch.urinet.archiv.mandant.domain.Geschaeftsjahresende;
import ch.urinet.archiv.mandant.domain.Mandant;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.mandant.domain.MandantVerwaltungsrollen;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.Optional;
import java.util.Set;

/**
 * Legt einen Mandanten an (Req 1.1, 1.5, 9.6; ADR-002): Prüfung des Seeds ({@link SeedPruefung}, vor jeder
 * Änderung), Schema {@code m_<code>} über {@code db/mandant}, Eintrag im Register als <b>inaktiv</b> mit den
 * konfigurierten Verwaltungsrollen, vollständiger Konfigurationsseed im Kontext
 * {@link MandantKontext#zurProvisionierung}, Prüfung des Schemas samt Seed gegen das Mapping (Req 1.7), Bucket
 * {@code <präfix><code>} und erst zuletzt die Aktivierung. Ein abgebrochener Lauf hinterlässt darum keinen aktiven
 * Mandanten; ein erneuter Lauf mit demselben Code setzt fort (Schema, Seed und Bucket sind idempotent). Weichen Name,
 * Geschäftsjahresende oder Verwaltungsrollen des neuen Laufs vom Eintrag ab, bricht er ab.
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
    private final MandantVerwaltungsrollenSeed verwaltungsrollenSeed;
    private final String bucketPraefix;

    public MandantProvisionierung(MandantRegister register, MandantDatenbank datenbank, MandantBucket bucket,
                                  KonfigurationSeed seed, KonfigurationsdateiVerwaltung konfiguration,
                                  MandantKontext kontext,
                                  MandantSchemaPruefung schemaPruefung,
                                  SeedPruefung seedPruefung,
                                  MandantVerwaltungsrollenSeed verwaltungsrollenSeed,
                                  @ConfigProperty(name = "archiv.ablage.bucket-praefix") String bucketPraefix) {
        this.register = register;
        this.datenbank = datenbank;
        this.bucket = bucket;
        this.seed = seed;
        this.konfiguration = konfiguration;
        this.kontext = kontext;
        this.schemaPruefung = schemaPruefung;
        this.seedPruefung = seedPruefung;
        this.verwaltungsrollenSeed = verwaltungsrollenSeed;
        this.bucketPraefix = bucketPraefix;
    }

    public Mandant anlegen(String code, String name, String geschaeftsjahresende) {
        return anlegen(code, name, geschaeftsjahresende, Optional.empty());
    }

    public Mandant anlegen(String code, String name, String geschaeftsjahresende,
                           Optional<MandantVerwaltungsrollen> rollen) {
        MandantCode mandantCode;
        Geschaeftsjahresende ende;
        String bucketName;
        try {
            mandantCode = new MandantCode(code);
            ende = geschaeftsjahresende == null ? null : new Geschaeftsjahresende(geschaeftsjahresende);
            bucketName = mandantCode.bucket(bucketPraefix);
        } catch (IllegalArgumentException e) {
            throw new MandantFehler(MandantFehler.Grund.UNGUELTIG, e.getMessage(), e);
        }
        Optional<Mandant> fortsetzen = register.inProvisionierung(mandantCode);
        if (fortsetzen.isEmpty() && register.existiert(mandantCode)) {
            throw new MandantFehler(MandantFehler.Grund.MANDANT_EXISTIERT, "Mandant existiert schon: " + code);
        }
        Konfiguration seedKonfiguration = seed.lesen(mandantCode);
        seedPruefung.pruefen(mandantCode, seedKonfiguration);
        if (ende == null && fortsetzen.isPresent()) {
            ende = fortsetzen.get().geschaeftsjahresende();
        }
        if (ende == null) {
            try {
                ende = new Geschaeftsjahresende(seedKonfiguration.einstellungen().geschaeftsjahrEnde());
            } catch (IllegalArgumentException e) {
                throw new MandantFehler(MandantFehler.Grund.SEED_UNGUELTIG,
                        "Geschäftsjahresende des Seeds für Mandant " + code + " ist ungültig", e);
            }
        }
        String anzeigename = name == null || name.isBlank()
                ? fortsetzen.map(Mandant::name).orElse(code) : name;
        MandantVerwaltungsrollen verwaltungsrollen = rollen.orElseGet(() -> fortsetzen
                .map(Mandant::verwaltungsrollen).orElseGet(() -> verwaltungsrollenSeed.fuer(mandantCode)));
        if (fortsetzen.isPresent()
                && Set.copyOf(fortsetzen.get().verwaltungsrollen().werte()).equals(Set.copyOf(verwaltungsrollen.werte()))) {
            verwaltungsrollen = fortsetzen.get().verwaltungsrollen();
        }
        Mandant mandant = new Mandant(mandantCode, anzeigename, mandantCode.schema(), bucketName, ende, false,
                verwaltungsrollen);
        if (fortsetzen.isPresent() && !fortsetzen.get().equals(mandant)) {
            throw new MandantFehler(MandantFehler.Grund.MANDANT_EXISTIERT,
                    "Mandant " + code + " ist in Provisionierung mit anderen Angaben "
                            + "(Name, Geschäftsjahresende oder Verwaltungsrollen)");
        }
        Konfiguration konfigurationFuerMandant = new Konfiguration(seedKonfiguration.version(),
                new Konfiguration.Einstellungen(ende.wert(), seedKonfiguration.einstellungen().kennungen(),
                        seedKonfiguration.einstellungen().branding(), seedKonfiguration.einstellungen().chat(),
                        seedKonfiguration.einstellungen().webseitenChat(), seedKonfiguration.einstellungen().wissen()),
                seedKonfiguration.arten(), seedKonfiguration.klassifikationen(), seedKonfiguration.regeln(),
                seedKonfiguration.kiTexte());
        datenbank.schemaAnlegen(mandantCode.schema());
        if (fortsetzen.isEmpty()) {
            register.eintragen(mandant);
        } else {
            LOG.infof("Mandant %s: abgebrochene Provisionierung wird fortgesetzt", code);
        }
        kontext.zurProvisionierung(mandantCode, () -> konfiguration.seedEinspielen(konfigurationFuerMandant));
        schemaPruefung.pruefen(mandantCode.schema());
        bucket.einrichten(bucketName);
        register.aktivieren(mandantCode);
        mandant = new Mandant(mandantCode, anzeigename, mandantCode.schema(), bucketName, ende, true,
                verwaltungsrollen);
        LOG.infof("Mandant %s angelegt (Schema %s, Bucket %s, %d Dokumentarten)", code, mandant.schema(), bucketName,
                konfigurationFuerMandant.arten().size());
        return mandant;
    }

}
