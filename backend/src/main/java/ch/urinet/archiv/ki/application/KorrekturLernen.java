package ch.urinet.archiv.ki.application;

import ch.urinet.archiv.ki.domain.Feld;
import ch.urinet.archiv.ki.domain.Korrektur;
import ch.urinet.archiv.ki.domain.Merkmale;
import ch.urinet.archiv.mandant.application.MandantEinstellungen;
import ch.urinet.archiv.mandant.domain.MandantCode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Status;
import jakarta.transaction.Synchronization;
import jakarta.transaction.TransactionSynchronizationRegistry;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Lernen aus Korrekturen beim Bestätigen (Req 7.7; design.md «KI», Korrektur): Differenz zwischen KI-Vorschlag und
 * bestätigtem Ergebnis; bei mindestens einem korrigierten Feld ein Beispiel in {@code ki_beispiel} mit den Merkmalen der
 * Vorauswahl (Req 7.9), in derselben Transaktion wie die Bestätigung. Die Zähler der Korrekturquote laufen erst nach
 * dem Commit, damit eine abgebrochene Bestätigung nicht zählt. Ohne KI-Vorschlag gibt es nichts zu lernen. Das Log
 * nennt nur die Dokument-ID und Anzahlen.
 */
@ApplicationScoped
public class KorrekturLernen {

    private static final Logger LOG = Logger.getLogger(KorrekturLernen.class);

    private final KiBeispielPort beispiele;
    private final KorrekturZaehlerPort zaehler;
    private final MandantEinstellungen einstellungen;
    private final TransactionSynchronizationRegistry transaktion;
    private final Duration zeitgrenze;

    public KorrekturLernen(KiBeispielPort beispiele, KorrekturZaehlerPort zaehler, MandantEinstellungen einstellungen,
                           TransactionSynchronizationRegistry transaktion,
                           @ConfigProperty(name = "archiv.dokumentart.muster-zeitgrenze") Duration zeitgrenze) {
        this.beispiele = beispiele;
        this.zaehler = zaehler;
        this.einstellungen = einstellungen;
        this.transaktion = transaktion;
        this.zeitgrenze = zeitgrenze;
    }

    /** Bestätigte Werte, wie sie in {@code dokument} und {@code dokument_finanz} landen. */
    public record Ergebnis(String art, String titel, LocalDate datum, UUID partnerId, String partnerName,
                           boolean finanz, String belegnummer, String richtung, BigDecimal betrag, String waehrung,
                           LocalDate faelligkeit, String qrReferenz, String vertraulichkeit, String personBenutzer) {
        public Ergebnis {
            Objects.requireNonNull(art, "art");
            Objects.requireNonNull(vertraulichkeit, "vertraulichkeit");
        }

        Map<Feld, String> werte() {
            Map<Feld, String> w = new EnumMap<>(Feld.class);
            w.put(Feld.ART, art);
            w.put(Feld.TITEL, titel);
            w.put(Feld.DATUM, datum == null ? null : datum.toString());
            w.put(Feld.PARTNER, partnerName);
            w.put(Feld.BELEGNUMMER, belegnummer);
            w.put(Feld.RICHTUNG, richtung);
            w.put(Feld.BETRAG, betrag == null ? null : betrag.toPlainString());
            w.put(Feld.WAEHRUNG, waehrung);
            w.put(Feld.FAELLIGKEIT, faelligkeit == null ? null : faelligkeit.toString());
            w.put(Feld.QR_REFERENZ, qrReferenz);
            w.put(Feld.VERTRAULICHKEIT, vertraulichkeit);
            return w;
        }
    }

    /** Stand vor dem Bestätigen (Vorschlag, Volltext, Partner); vor dem Schreiben der Bestätigung lesen. */
    public Optional<KiBeispielPort.Lernstand> vorher(UUID dokumentId) {
        return beispiele.lernstand(dokumentId);
    }

    public void lernen(MandantCode mandant, UUID dokumentId, KiBeispielPort.Lernstand vorher, Ergebnis ergebnis) {
        boolean partnerUebernommen = ergebnis.partnerId() != null
                && vorher.partnerId().equals(Optional.of(ergebnis.partnerId()));
        Korrektur korrektur = Korrektur.ermitteln(vorher.vorschlag(), ergebnis.werte(), ergebnis.finanz(),
                partnerUebernommen);
        if (korrektur.beispiel()) {
            MandantEinstellungen.Werte eigene = einstellungen.lesen(mandant);
            Merkmale.Ergebnis merkmale = Merkmale.erkennen(vorher.volltext(), vorher.partnerIban(), eigene.uid(),
                    eigene.ibans(), BeispielAuswahl.muster(), Merkmale.Frist.cpu(zeitgrenze));
            merkmale.abgebrochen().forEach(nr -> LOG.warnf(
                    "Belegnummer-Muster %d für Dokument %s nach %s abgebrochen, gilt als nicht zutreffend", nr,
                    dokumentId, zeitgrenze));
            beispiele.speichern(new KiBeispielPort.Neu(dokumentId, ergebnis.art(), korrektur.korrigiert(),
                    merkmale.merkmale(), ergebnis.vertraulichkeit(), ergebnis.personBenutzer(),
                    Optional.ofNullable(ergebnis.partnerId())));
        }
        LOG.infof("Korrektur für Dokument %s: %d von %d Feldern korrigiert, Beispiel %s", dokumentId,
                korrektur.korrigiert().size(), korrektur.bestaetigt().size(), korrektur.beispiel());
        Runnable zaehlen = () -> zaehler.zaehlen(mandant, korrektur.bestaetigt(), korrektur.korrigiert().keySet());
        if (transaktion.getTransactionStatus() == Status.STATUS_ACTIVE) {
            transaktion.registerInterposedSynchronization(new NachCommit(zaehlen));
        } else {
            zaehlen.run();
        }
    }

    private record NachCommit(Runnable aktion) implements Synchronization {
        @Override
        public void beforeCompletion() {
        }

        @Override
        public void afterCompletion(int status) {
            if (status == Status.STATUS_COMMITTED) {
                try {
                    aktion.run();
                } catch (RuntimeException e) {
                    LOG.warnf("Korrekturquote nicht fortgeschrieben (%s)", e.getClass().getName());
                }
            }
        }
    }
}
