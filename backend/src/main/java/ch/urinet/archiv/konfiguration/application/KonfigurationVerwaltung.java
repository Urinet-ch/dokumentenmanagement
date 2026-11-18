package ch.urinet.archiv.konfiguration.application;

import ch.urinet.archiv.dokumentart.application.DokumentartRepository;
import ch.urinet.archiv.dokumentart.application.ZusatzSchemaPruefer;
import ch.urinet.archiv.konfiguration.domain.Konfiguration;
import ch.urinet.archiv.konfiguration.domain.Konfiguration.Kennungen;
import ch.urinet.archiv.konfiguration.domain.KonfigurationQuelle;
import ch.urinet.archiv.konfiguration.domain.KonfigurationsVersion;
import ch.urinet.archiv.dokumentart.domain.Dokumentart;
import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.mandant.application.MandantAufloesung;
import ch.urinet.archiv.mandant.application.MandantKontext;
import ch.urinet.archiv.mandant.application.MandantEinstellungen;
import ch.urinet.archiv.mandant.domain.MandantCode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.util.List;
import java.util.Optional;

@ApplicationScoped
@Transactional
public class KonfigurationVerwaltung implements KonfigurationVersionsPort, KonfigurationLesen {

    private final KonfigurationVersionsRepository repository;
    private final KonfigurationCache cache;
    private final DokumentartRepository dokumentarten;
    private final ZusatzSchemaPruefer zusatzSchema;
    private final KlassifikationRepository klassifikationen;
    private final RegelRepository regeln;
    private final MandantEinstellungen einstellungen;
    private final MandantAufloesung aufloesung;
    private final MandantKontext kontext;
    private final KonfigurationAenderungsProtokoll protokoll;

    public KonfigurationVerwaltung(KonfigurationVersionsRepository repository, KonfigurationCache cache,
                                   DokumentartRepository dokumentarten, ZusatzSchemaPruefer zusatzSchema,
                                   KlassifikationRepository klassifikationen, RegelRepository regeln,
                                   MandantEinstellungen einstellungen, MandantAufloesung aufloesung,
                                   MandantKontext kontext, KonfigurationAenderungsProtokoll protokoll) {
        this.repository = repository;
        this.cache = cache;
        this.dokumentarten = dokumentarten;
        this.zusatzSchema = zusatzSchema;
        this.klassifikationen = klassifikationen;
        this.regeln = regeln;
        this.einstellungen = einstellungen;
        this.aufloesung = aufloesung;
        this.kontext = kontext;
        this.protokoll = protokoll;
    }

    @Override
    public int aktuelleVersion() {
        return basisSicherstellen().version();
    }

    @Override
    public Konfiguration aktuell() {
        MandantCode mandant = mandantAktuell();
        KonfigurationsVersion stand = basisSicherstellen();
        return cache.finden(mandant, stand.version()).orElseGet(() -> {
            cache.speichern(mandant, stand.inhalt());
            return stand.inhalt();
        });
    }

    public List<KonfigurationsVersion> versionen() {
        basisSicherstellen();
        return repository.alle();
    }

    public KonfigurationsVersion version(int nummer) {
        basisSicherstellen();
        return repository.version(nummer).orElseThrow(() -> new FachFehler(Fehlercode.NICHT_GEFUNDEN));
    }

    public void aenderungStarten(int erwarteteVersion) {
        repository.sperren();
        KonfigurationsVersion aktuell = basisSicherstellen();
        if (aktuell.version() != erwarteteVersion) {
            throw new FachFehler(Fehlercode.VERSION_KONFLIKT);
        }
    }

    public int aenderungSpeichern(String wer, String grund) {
        KonfigurationsVersion vorher = basisSicherstellen();
        Konfiguration neu = abbild(vorher.version() + 1);
        if (vorher.inhalt().gleicherInhalt(neu)) {
            return vorher.version();
        }
        if (!neu.pruefen().isEmpty()) {
            throw new FachFehler(Fehlercode.KONFIGURATION_UNGUELTIG);
        }
        KonfigurationsVersion gespeichert = repository.anlegen(neu.version(), wer, grund,
                KonfigurationQuelle.APP, neu);
        cache.invalidieren(mandantAktuell());
        protokoll.schreiben(gespeichert.version(), gespeichert.quelle(), grund, vorher.inhalt().aenderungen(neu));
        return gespeichert.version();
    }

    private KonfigurationsVersion basisSicherstellen() {
        MandantCode mandant = mandantAktuell();
        Optional<KonfigurationsVersion> aktuell = repository.aktuell();
        if (aktuell.isPresent()) {
            return mitFeldTypen(aktuell.get());
        }
        repository.sperren();
        aktuell = repository.aktuell();
        if (aktuell.isPresent()) {
            return mitFeldTypen(aktuell.get());
        }
        Konfiguration initial = abbild(1);
        if (!initial.pruefen().isEmpty()) {
            throw new FachFehler(Fehlercode.KONFIGURATION_UNGUELTIG);
        }
        KonfigurationsVersion angelegt = repository.anlegen(1, "system:migration", null,
                KonfigurationQuelle.MIGRATION, initial);
        cache.invalidieren(mandant);
        return angelegt;
    }

    /**
     * Die Typen der Zusatzfelder gehören nicht ins gespeicherte Abbild; sie werden beim Lesen aus dem Zusatz-Schema
     * abgeleitet, damit Abbilder aus Migration und App gleich sind und Regeln mit {@code zusatz.} prüfbar bleiben.
     */
    private KonfigurationsVersion mitFeldTypen(KonfigurationsVersion stand) {
        Konfiguration inhalt = stand.inhalt();
        Konfiguration ergaenzt = new Konfiguration(inhalt.version(), inhalt.einstellungen(), inhalt.arten().stream()
                .map(art -> art.mitZusatzFeldTypen(zusatzSchema.feldTypen(art.zusatzSchema()))).toList(),
                inhalt.klassifikationen(), inhalt.regeln());
        return new KonfigurationsVersion(stand.version(), stand.zeit(), stand.wer(), stand.grund(), stand.quelle(),
                ergaenzt, stand.sha256());
    }

    private Konfiguration abbild(int version) {
        MandantEinstellungen.Werte werte = einstellungen.lesen(mandantAktuell());
        return new Konfiguration(version, new Konfiguration.Einstellungen(werte.geschaeftsjahresende().wert(),
                new Kennungen(werte.uid().isBlank() ? null : werte.uid(), werte.ibans())), dokumentarten.alle().stream()
                .map(this::art).toList(), klassifikationen.alle(), regeln.alle());
    }

    private Konfiguration.Art art(Dokumentart art) {
        return new Konfiguration.Art(art.code(), art.name(), art.beschreibung(), art.gruppen(), art.pflichtfelder(),
                repository.kanonisch(art.zusatzSchema()), art.vertraulichkeit().name(),
                new Konfiguration.Aufbewahrung(art.aufbewahrung().jahre(),
                        art.aufbewahrung().ab() == null ? null : art.aufbewahrung().ab().name(),
                        art.aufbewahrung().feld()),
                art.gesetzlichePflicht(), art.aktiv(), art.selbstAusgestellt(), art.belegnummerMuster(),
                art.fremdbelegArt(), art.ohneZahlungsdatenArt(), art.kiVorschlag(), art.kiFelder(),
                art.kiHinweise(), art.reihenfolge(), klassifikationen.zuordnungen(art.code()),
                zusatzSchema.feldTypen(art.zusatzSchema()));
    }

    private MandantCode mandantAktuell() {
        return kontext.aktuell().orElseGet(aufloesung::pruefen);
    }
}
