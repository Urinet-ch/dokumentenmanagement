package ch.urinet.archiv.export.application;

import ch.urinet.archiv.ablage.application.AblagePort;
import ch.urinet.archiv.ablage.domain.VorsignierteUrl;
import ch.urinet.archiv.export.domain.Export;
import ch.urinet.archiv.export.domain.Geschaeftsjahr;
import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.sicherheit.application.Berechtigung;
import ch.urinet.archiv.sicherheit.domain.Benutzer;
import ch.urinet.archiv.verarbeitung.application.AuftragService;
import ch.urinet.archiv.verarbeitung.domain.Nutzlast;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Anwendungsfälle des Jahresexports über die API (Spec 02, Req 7.1–7.3, 7.5; design.md «Jahresexport»). Zuerst das
 * Recht ({@link Exportrecht}): {@code mitarbeiter} und {@code geschaeftsstelle} erhalten 403 {@code NICHT_ERLAUBT} mit
 * Eintrag {@code VERWEIGERT} (Req 10.7), bevor etwas gelesen oder eingereiht wird. Anfordern legt den Export an,
 * protokolliert {@code EXPORTIEREN} und reiht {@code EXPORT_JAHR} ein, alles in einer Transaktion. Je Mandant ist
 * höchstens ein Export zugleich offen (sonst 409 {@code STATUS_KONFLIKT}), weil ein Export alle Belege eines Jahres
 * liest und Stufe A nur einen Worker hat.
 */
@ApplicationScoped
public class ExportVerwaltung {

    public static final int LISTE = 20;

    private final ExportRepository repository;
    private final ExportProtokoll protokoll;
    private final AuftragService auftraege;
    private final AblagePort ablage;
    private final Berechtigung berechtigung;
    private final Clock uhr;

    @Inject
    public ExportVerwaltung(ExportRepository repository, ExportProtokoll protokoll, AuftragService auftraege,
                            AblagePort ablage, Berechtigung berechtigung) {
        this(repository, protokoll, auftraege, ablage, berechtigung, Clock.systemUTC());
    }

    ExportVerwaltung(ExportRepository repository, ExportProtokoll protokoll, AuftragService auftraege,
                     AblagePort ablage, Berechtigung berechtigung, Clock uhr) {
        this.repository = repository;
        this.protokoll = protokoll;
        this.auftraege = auftraege;
        this.ablage = ablage;
        this.berechtigung = berechtigung;
        this.uhr = uhr;
    }

    /** Ein Export mit der Download-Adresse, falls er fertig und nicht abgelaufen ist. */
    public record Abruf(Export export, Optional<VorsignierteUrl> adresse) {
    }

    @Transactional
    public Export anfordern(Benutzer benutzer, Integer geschaeftsjahr) {
        pruefen(benutzer);
        if (geschaeftsjahr == null || !Geschaeftsjahr.gueltig(geschaeftsjahr)) {
            throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
        }
        UUID id = UUID.randomUUID();
        if (!repository.anlegen(id, geschaeftsjahr, benutzer.id(), benutzer.rolle().name())) {
            throw new FachFehler(Fehlercode.STATUS_KONFLIKT);
        }
        protokoll.angefordert(id);
        auftraege.einreihen(ExportJahrHandler.TYP, new Nutzlast(Map.of(ExportJahrHandler.EXPORT_ID, id)));
        return repository.laden(id).orElseThrow();
    }

    public List<Export> liste(Benutzer benutzer) {
        pruefen(benutzer);
        return repository.neueste(LISTE);
    }

    /**
     * Export mit Adresse (Req 7.2): nur für einen fertigen, nicht abgelaufenen Export, gültig bis 24 Stunden nach der
     * Fertigstellung; jede ausgegebene Adresse protokolliert {@code HERUNTERLADEN} mit der SHA-256 des ZIP. Ein
     * unbekannter Export ergibt 404.
     */
    @Transactional
    public Abruf abrufen(Benutzer benutzer, MandantCode mandant, UUID id) {
        pruefen(benutzer);
        Export export = repository.laden(id).orElseThrow(() -> new FachFehler(Fehlercode.NICHT_GEFUNDEN));
        if (!export.herunterladbar(uhr.instant())) {
            return new Abruf(export, Optional.empty());
        }
        VorsignierteUrl adresse = ablage.exportUrl(mandant, export.schluessel().orElseThrow(),
                export.versionId().orElseThrow(), export.gueltigBis().orElseThrow(),
                "jahresexport-" + mandant.wert() + "-" + export.geschaeftsjahr() + ".zip");
        protokoll.heruntergeladen(id, export.sha256().orElseThrow());
        return new Abruf(export, Optional.of(adresse));
    }

    private void pruefen(Benutzer benutzer) {
        if (!Exportrecht.erlaubt(benutzer.rolle())) {
            berechtigung.ohneZugang(Optional.of(benutzer.id()), Optional.of(benutzer.rolle()), Optional.empty());
        }
    }
}
