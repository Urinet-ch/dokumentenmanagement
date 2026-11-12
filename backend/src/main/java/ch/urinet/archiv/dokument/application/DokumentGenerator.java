package ch.urinet.archiv.dokument.application;

import ch.urinet.archiv.dokument.application.DokumentErfassen.Geprueft;
import ch.urinet.archiv.dokument.application.DokumentStatusPort.Bestaetigt;
import ch.urinet.archiv.dokument.application.DokumentStatusPort.Finanz;
import ch.urinet.archiv.dokument.application.DokumentStatusPort.Vertrag;
import ch.urinet.archiv.dokument.domain.DokumentStatus;
import ch.urinet.archiv.dokumentart.application.DokumentartVerwaltung;
import ch.urinet.archiv.dokumentart.domain.Aufbewahrung;
import ch.urinet.archiv.dokumentart.domain.Dokumentart;
import ch.urinet.archiv.dokumentart.domain.Vertraulichkeit;
import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.gemeinsam.domain.PflichtfelderFehlen;
import ch.urinet.archiv.kontakt.application.KontaktRegister;
import ch.urinet.archiv.kontakt.domain.Kontakt;
import ch.urinet.archiv.mandant.application.MandantAufloesung;
import ch.urinet.archiv.mandant.application.MandantEinstellungen;
import ch.urinet.archiv.verarbeitung.application.AuftragService;
import ch.urinet.archiv.verarbeitung.domain.Nutzlast;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.MonthDay;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.jboss.logging.Logger;

/**
 * Generator-Pfad (Aufgabe 4.7, Req 11.1–11.3, 11.6–11.8, 11.10; design.md «Generator-Pfad»): Ein Skript mit API-Token
 * vom Scope {@code generator} legt eine PDF mit fertigen Metadaten ab. Alles läuft in einer Transaktion und in dieser
 * Reihenfolge; jeder Fehler legt nichts an:
 * <ol>
 *   <li>PDF wie beim Upload prüfen, Prüfsumme zuerst (Regel 3).</li>
 *   <li>Metadaten gegen die Art: Pflichtfelder samt {@code vertraulichkeit} und {@code person_benutzer} bei Standard
 *       {@code PERSONAL} (422 {@code PFLICHTFELD_FEHLT}, Req 11.6, 11.10), Werte (400), {@code partner.crmId}
 *       (422 {@code PARTNER_UNBEKANNT}, Req 11.7), {@code zusatz} (422 {@code ZUSATZ_UNGUELTIG}).</li>
 *   <li>Dublette (SHA-256 eines nicht verworfenen Dokuments): das vorhandene Dokument, nichts Neues (Req 11.8).</li>
 *   <li>Eigene Art mit vergebener Belegnummer: 409 {@code BELEGNUMMER_VERGEBEN} (Req 11.3).</li>
 *   <li>Frist schon abgelaufen: 409 {@code FRIST_ABGELAUFEN}; das entscheidet der Inhaber bei der Erfassung von Hand
 *       (Req 9.8), nicht das Skript.</li>
 *   <li>Dokument mit {@code quelle = GENERATOR} und bestätigten Metadaten anlegen, Belegnummer reservieren und
 *       {@code VERARBEITEN} mit {@code ohneKi} einreihen (Req 11.2).</li>
 * </ol>
 */
@ApplicationScoped
@Transactional
public class DokumentGenerator {

    static final String OHNE_KI = "ohneKi";

    private static final Logger LOG = Logger.getLogger(DokumentGenerator.class);

    private final DokumentErfassen erfassen;
    private final DokumentRepository repository;
    private final DokumentStatusPort status;
    private final DokumentartVerwaltung arten;
    private final KontaktRegister kontakte;
    private final MandantAufloesung aufloesung;
    private final MandantEinstellungen einstellungen;
    private final AuftragService auftraege;

    public DokumentGenerator(DokumentErfassen erfassen, DokumentRepository repository, DokumentStatusPort status,
                             DokumentartVerwaltung arten, KontaktRegister kontakte, MandantAufloesung aufloesung,
                             MandantEinstellungen einstellungen, AuftragService auftraege) {
        this.erfassen = erfassen;
        this.repository = repository;
        this.status = status;
        this.arten = arten;
        this.kontakte = kontakte;
        this.aufloesung = aufloesung;
        this.einstellungen = einstellungen;
        this.auftraege = auftraege;
    }

    /**
     * Metadaten des Generators. {@code angaben.partnerId()} bleibt leer, der Partner kommt nur über {@code crmId};
     * {@code zusatzJson} ist ein JSON-Objekt oder {@code null}, {@code zusatzFelder} sind seine Schlüssel mit Wert.
     */
    public record Anfrage(Bestaetigung angaben, String crmId, String zusatzJson, Set<String> zusatzFelder) {

        public Anfrage {
            Objects.requireNonNull(angaben, "angaben");
            crmId = crmId == null || crmId.isBlank() ? null : crmId.strip();
            zusatzFelder = zusatzFelder == null ? Set.of() : Set.copyOf(zusatzFelder);
        }
    }

    /** @param neu {@code false} bei einer Dublette (200 statt 202, Req 11.8) */
    public record Ergebnis(UUID id, DokumentStatus status, boolean neu) {
    }

    /**
     * @param erstelltVon Principal des API-Tokens ({@code token:<id>}); auch {@code bestaetigt_von}
     * @param metadaten   erst nach der Prüfsumme der PDF gelesen (Regel 3); darf mit {@link FachFehler} abbrechen
     */
    public Ergebnis annehmen(String erstelltVon, Path datei, Supplier<Anfrage> metadaten) {
        Geprueft pdf = erfassen.pdfPruefen(datei);
        Anfrage anfrage = metadaten.get();
        Bestaetigung b = anfrage.angaben();
        if (b.art() == null) {
            throw new PflichtfelderFehlen(List.of("art"));
        }
        Dokumentart art = DokumentBestaetigen.art(arten, b.art());
        Optional<Kontakt> partner = anfrage.crmId() == null ? Optional.empty() : kontakte.mitCrmId(anfrage.crmId());
        Bestaetigung mitPartner = mitPartner(b, partner.map(Kontakt::id).orElse(null));
        List<String> fehlen = new ArrayList<>(DokumentBestaetigen.fehlendePflichtfelder(art, mitPartner,
                anfrage.zusatzFelder()));
        if (anfrage.crmId() != null) {
            // eine unbekannte crmId ist kein fehlendes Feld, sondern 422 PARTNER_UNBEKANNT (unten)
            fehlen.remove("partner");
        }
        if (art.vertraulichkeit() == Vertraulichkeit.PERSONAL) {
            // Req 11.10: bei Standard PERSONAL keine stille Übernahme, das Skript nennt beides ausdrücklich
            if (b.vertraulichkeit() == null && !fehlen.contains("vertraulichkeit")) {
                fehlen.add("vertraulichkeit");
            }
            if (b.personBenutzer() == null && !fehlen.contains("person_benutzer")) {
                fehlen.add("person_benutzer");
            }
        }
        if (!fehlen.isEmpty()) {
            throw new PflichtfelderFehlen(fehlen);
        }
        DokumentBestaetigen.werte(mitPartner);
        if (anfrage.crmId() != null && partner.isEmpty()) {
            throw new FachFehler(Fehlercode.PARTNER_UNBEKANNT);
        }
        if (anfrage.zusatzJson() != null) {
            arten.zusatzPruefen(art.code(), anfrage.zusatzJson());
        }

        Optional<UUID> vorhanden = repository.dubletteSperren(pdf.sha256());
        if (vorhanden.isPresent()) {
            DokumentStatus stand = status.stand(vorhanden.get(), false).map(DokumentStatusPort.Stand::status)
                    .orElseThrow(() -> new IllegalStateException("Dublette ohne Dokument: " + vorhanden.get()));
            LOG.infof("Generator: Dublette sha256=%s vorhanden=%s", pdf.sha256(), vorhanden.get());
            return new Ergebnis(vorhanden.get(), stand, false);
        }

        MandantEinstellungen.Werte mandant = einstellungen.lesen(aufloesung.pruefen());
        boolean finanz = art.gruppen().contains("finanz");
        boolean belegnummerEigen = mandant.eigeneArten().contains(art.code()) && finanz && b.belegnummer() != null;
        if (belegnummerEigen && status.belegnummerInhaber(art.code(), b.belegnummer()).isPresent()) {
            LOG.infof("Generator: Belegnummer vergeben sha256=%s art=%s", pdf.sha256(), art.code());
            throw new FachFehler(Fehlercode.BELEGNUMMER_VERGEBEN);
        }

        Instant eingang = erfassen.jetzt();
        MonthDay gjEnde = mandant.geschaeftsjahresende().monatTag();
        int geschaeftsjahr = b.geschaeftsjahr() != null ? b.geschaeftsjahr()
                : Aufbewahrung.geschaeftsjahrVorschlagen(b.datum(), eingang, gjEnde);
        boolean vertrag = art.gruppen().contains("vertrag");
        Aufbewahrung aufbewahrung = Aufbewahrung.berechne(art.aufbewahrung(), b.datum(), eingang, geschaeftsjahr,
                vertrag ? b.gueltigBis() : null, gjEnde, LocalDate.now(Aufbewahrung.ZUERICH));
        if (aufbewahrung.fristAbgelaufen()) {
            LOG.infof("Generator: Frist abgelaufen sha256=%s art=%s", pdf.sha256(), art.code());
            throw new FachFehler(Fehlercode.FRIST_ABGELAUFEN);
        }

        UUID id = erfassen.generatorAnlegen(pdf, datei, erstelltVon, eingang);
        String vertraulichkeit = b.vertraulichkeit() != null ? b.vertraulichkeit() : art.vertraulichkeit().name();
        Bestaetigt daten = new Bestaetigt(art.code(), b.titel(), b.datum(), partner.map(Kontakt::id).orElse(null),
                partner.map(Kontakt::name).orElse(null), b.projekt(), b.tags(), vertraulichkeit, b.personBenutzer(),
                erstelltVon, geschaeftsjahr, aufbewahrung.bis(), false,
                finanz ? Optional.of(new Finanz(b.belegnummer(), b.richtung(), b.betrag(),
                        b.waehrung() != null ? b.waehrung() : "CHF", b.faelligkeit(), b.qrReferenz(), b.konto()))
                        : Optional.empty(),
                vertrag ? Optional.of(new Vertrag(b.gueltigAb(), b.gueltigBis(), b.kuendigungsfrist(),
                        b.naechsteFrist())) : Optional.empty());
        if (!status.generatorMetadaten(id, daten, anfrage.zusatzJson())) {
            throw new IllegalStateException("Generator-Dokument nicht mehr NEU: " + id);
        }
        if (belegnummerEigen && !status.belegnummerReservieren(art.code(), b.belegnummer(), id)) {
            // gleichzeitig gesendet: die andere Anfrage hat die Nummer zuerst reserviert
            throw new FachFehler(Fehlercode.BELEGNUMMER_VERGEBEN);
        }
        auftraege.einreihen(DokumentErfassen.VERARBEITEN,
                new Nutzlast(Map.of(DokumentErfassen.NUTZLAST_DOKUMENT, id, OHNE_KI, true)));
        LOG.infof("Generator: angenommen dokument=%s art=%s", id, art.code());
        return new Ergebnis(id, DokumentStatus.NEU, true);
    }

    private static Bestaetigung mitPartner(Bestaetigung b, UUID partnerId) {
        return new Bestaetigung(b.art(), b.titel(), b.datum(), partnerId, b.projekt(), b.tags(), b.vertraulichkeit(),
                b.personBenutzer(), b.geschaeftsjahr(), b.belegnummer(), b.richtung(), b.betrag(), b.waehrung(),
                b.faelligkeit(), b.qrReferenz(), b.konto(), b.gueltigAb(), b.gueltigBis(), b.kuendigungsfrist(),
                b.naechsteFrist(), false, false);
    }
}
