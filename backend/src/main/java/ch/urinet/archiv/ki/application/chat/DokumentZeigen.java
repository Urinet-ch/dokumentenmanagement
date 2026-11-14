package ch.urinet.archiv.ki.application.chat;

import ch.urinet.archiv.dokument.application.DokumentRepository;
import ch.urinet.archiv.dokument.domain.Dokument;
import ch.urinet.archiv.dokument.domain.DokumentFinanz;
import ch.urinet.archiv.dokument.domain.DokumentVertrag;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.ki.domain.Werteformat;
import ch.urinet.archiv.protokoll.application.ZugriffProtokoll;
import ch.urinet.archiv.protokoll.domain.ProtokollAktion;
import ch.urinet.archiv.protokoll.domain.ProtokollDetails;
import ch.urinet.archiv.protokoll.domain.ZugriffEintrag;
import ch.urinet.archiv.sicherheit.domain.Aktion;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.Optional;
import java.util.UUID;

/**
 * Werkzeug {@code dokument_zeigen} (Spec 02, Req 3.1, 5.2, 5.3): Metadaten, Anfang des Volltexts (höchstens
 * {@value #INHALT_MAX} Zeichen, eigenes Feld {@code inhalt}, damit der Agent ihn als Daten kennzeichnet) und Link,
 * ohne Dateinamen (Spec 01, Req 3.13). Ungültige, fremde, unsichtbare und nicht vorhandene IDs ergeben gleich
 * {@link WerkzeugFehler#NICHT_GEFUNDEN} (Spec 01, Req 2.3).
 * <p>
 * Zugriffsprotokoll (E4, entschieden von Fabian am 14.11.2026): {@code ANZEIGEN} mit Benutzer, Rolle, IP aus dem
 * Kontext, Dokument-ID und {@code details} {@code {"kanal":"CHAT"}}, vor dem Lesen des Inhalts in derselben
 * Transaktion (Spec 01, Req 10.2, 10.8): Scheitert der Eintrag oder der Commit, gibt es keinen Inhalt. Nicht gefunden
 * schreibt wie {@code GET /dokumente/{id}} {@code VERWEIGERT} (Grund A2, Fehlercode {@code NICHT_GEFUNDEN}, Req 10.7) in
 * einer eigenen Transaktion; scheitert das, bleibt die Antwort «nicht gefunden» (wie der {@code VerweigerungMelder}).
 */
@ApplicationScoped
public class DokumentZeigen {

    public static final int INHALT_MAX = 4_000;

    private static final Logger LOG = Logger.getLogger(DokumentZeigen.class);

    /** Angaben aus {@code dokument_vertrag}, soweit vorhanden. */
    public record Vertrag(String gueltigAb, String gueltigBis, String kuendigungsfrist, String naechsteFrist) {
    }

    /** Ein sichtbares Dokument; fehlende Angaben sind {@code null}. */
    public record Ansicht(String id, String titel, String art, String datum, String partner, String belegnummer,
                          String betrag, String faelligkeit, Vertrag vertrag, boolean bestaetigt, String inhalt,
                          String link) {
    }

    private final DokumentRepository dokumente;
    private final WerkzeugDaten daten;
    private final ZugriffProtokoll protokoll;
    private final WerkzeugTransaktion transaktion;

    public DokumentZeigen(DokumentRepository dokumente, WerkzeugDaten daten, ZugriffProtokoll protokoll,
                          WerkzeugTransaktion transaktion) {
        this.dokumente = dokumente;
        this.daten = daten;
        this.protokoll = protokoll;
        this.transaktion = transaktion;
    }

    public Ansicht zeigen(WerkzeugKontext kontext, String id) {
        UUID dokumentId = Werkzeugparameter.id(id);
        Optional<Ansicht> ansicht = dokumentId == null ? Optional.empty()
                : transaktion.neu(() -> lesen(kontext, dokumentId));
        if (ansicht.isEmpty()) {
            verweigert(kontext, Optional.ofNullable(dokumentId));
            throw new WerkzeugFehler(WerkzeugFehler.NICHT_GEFUNDEN);
        }
        return ansicht.get();
    }

    private Optional<Ansicht> lesen(WerkzeugKontext kontext, UUID id) {
        Optional<Dokument> gefunden = dokumente.finden(id, kontext.sicht());
        if (gefunden.isEmpty()) {
            return Optional.empty();
        }
        protokoll.schreiben(eintrag(kontext, ProtokollAktion.ANZEIGEN, Optional.of(id), ProtokollDetails.KEINE));
        Dokument d = gefunden.get();
        String inhalt = daten.inhalt(id, kontext.sicht(), INHALT_MAX).orElse(null);
        DokumentFinanz f = d.finanz();
        DokumentVertrag v = d.vertrag();
        return Optional.of(new Ansicht(id.toString(), d.titel(), d.art(), Werteformat.datum(d.datum()),
                d.partnerName(), f == null ? null : f.belegnummer(),
                f == null ? null : Werteformat.betrag(f.betrag(), f.waehrung()),
                f == null ? null : Werteformat.datum(f.faelligkeit()),
                v == null ? null : new Vertrag(Werteformat.datum(v.gueltigAb()), Werteformat.datum(v.gueltigBis()),
                        v.kuendigungsfrist(), Werteformat.datum(v.naechsteFrist())),
                d.bestaetigtesDokument(), inhalt, DokumenteSuchen.link(id)));
    }

    private void verweigert(WerkzeugKontext kontext, Optional<UUID> id) {
        ProtokollDetails details = new ProtokollDetails(Aktion.A2.name(), null, Fehlercode.NICHT_GEFUNDEN.name());
        try {
            transaktion.neu(() -> {
                protokoll.schreiben(eintrag(kontext, ProtokollAktion.VERWEIGERT, id, details));
                return null;
            });
        } catch (RuntimeException e) {
            LOG.errorf("Verweigerung im Chat nicht protokolliert: fehlercode=%s", Fehlercode.NICHT_GEFUNDEN.name());
        }
    }

    private static ZugriffEintrag eintrag(WerkzeugKontext kontext, ProtokollAktion aktion, Optional<UUID> id,
                                          ProtokollDetails details) {
        return new ZugriffEintrag(Optional.of(kontext.benutzer().id()), Optional.of(kontext.rolle().name()), aktion,
                id, kontext.ip(), details.mitKanal(ProtokollDetails.KANAL_CHAT));
    }
}
