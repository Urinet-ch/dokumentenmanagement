package ch.urinet.archiv.ki.application.chat;

import ch.urinet.archiv.gemeinsam.domain.FachFehler;
import ch.urinet.archiv.gemeinsam.domain.Fehlercode;
import ch.urinet.archiv.ki.domain.chat.Schwaerzung;
import ch.urinet.archiv.mandant.domain.MandantCode;
import ch.urinet.archiv.protokoll.domain.IpAdresse;
import ch.urinet.archiv.protokoll.domain.ProtokollAktion;
import ch.urinet.archiv.protokoll.domain.ProtokollDetails;
import ch.urinet.archiv.protokoll.domain.ZugriffEintrag;
import ch.urinet.archiv.sicherheit.domain.Rolle;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;

import java.io.IOException;
import java.io.OutputStream;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

/** Auswertung und nicht persistierter ZIP-Download der Bewertungen (Spec 02, Requirement 15.5–15.8). */
@ApplicationScoped
public class ChatBewertungAuswertung {

    public static final int MAX_EXPORT_ZEILEN = 5_000;
    public static final int MAX_SCHLECHTE_ANTWORTEN = 100;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ZoneId ZUERICH = ZoneId.of("Europe/Zurich");

    private final ChatAuswertungSpeicher speicher;
    private final ChatAuswertungProtokoll protokoll;
    private final ChatBewertungZipPort zipPort;

    public ChatBewertungAuswertung(ChatAuswertungSpeicher speicher, ChatAuswertungProtokoll protokoll,
                                   ChatBewertungZipPort zipPort) {
        this.speicher = speicher;
        this.protokoll = protokoll;
        this.zipPort = zipPort;
    }

    @Transactional
    public ChatAuswertungSpeicher.Auswertung auswertung(LocalDate von, LocalDate bis) {
        return speicher.auswertung(filter(von, bis, 1, 5));
    }

    @Transactional(TxType.NOT_SUPPORTED)
    public Export vorbereiten(LocalDate von, LocalDate bis, int minSterne, int maxSterne, boolean schwaerzen,
                              boolean klartextBestaetigt, MandantCode mandant, String benutzer,
                              Optional<IpAdresse> ip) {
        if (minSterne < 1 || maxSterne > 5 || minSterne > maxSterne
                || !schwaerzen && !klartextBestaetigt) {
            throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
        }
        ChatAuswertungSpeicher.Filter filter = filter(von, bis, minSterne, maxSterne);
        ChatAuswertungSpeicher.ExportSnapshot snapshot = speicher.exportSnapshot(filter);
        try {
            long anzahl = snapshot.anzahl();
            if (anzahl > MAX_EXPORT_ZEILEN) {
                throw new FachFehler(Fehlercode.BEWERTUNG_EXPORT_ZU_GROSS);
            }
            protokoll.schreiben(new ZugriffEintrag(Optional.of(benutzer), Optional.of(Rolle.INHABER.name()),
                    ProtokollAktion.CHAT_BEWERTUNG_EXPORT, Optional.empty(), ip,
                    new ProtokollDetails("von=" + von + ";bis=" + bis + ";anzahl=" + anzahl
                            + ";modus=" + (schwaerzen ? "GESCHWAERZT" : "KLARTEXT"), null, null),
                    Optional.empty()));

            Schwaerzung redaktion = schwaerzen ? new Schwaerzung(snapshot.kontaktUndPartnerNamen()) : null;
            byte[] salz = new byte[32];
            RANDOM.nextBytes(salz);
            return new Export(snapshot, redaktion, salz, zipPort);
        } catch (RuntimeException e) {
            snapshot.close();
            throw e;
        }
    }

    public static final class Export implements AutoCloseable {
        private final ChatAuswertungSpeicher.ExportSnapshot snapshot;
        private final Schwaerzung redaktion;
        private final byte[] salz;
        private final ChatBewertungZipPort zipPort;

        private Export(ChatAuswertungSpeicher.ExportSnapshot snapshot, Schwaerzung redaktion, byte[] salz,
                       ChatBewertungZipPort zipPort) {
            this.snapshot = snapshot;
            this.redaktion = redaktion;
            this.salz = salz;
            this.zipPort = zipPort;
        }

        public void schreiben(OutputStream ziel) throws IOException {
            zipPort.schreiben(snapshot, redaktion, salz, ziel);
            snapshot.commit();
        }

        @Override
        public void close() {
            snapshot.close();
        }
    }

    private static ChatAuswertungSpeicher.Filter filter(LocalDate von, LocalDate bis, int minSterne, int maxSterne) {
        if (von == null || bis == null || bis.isBefore(von)
                || bis.isAfter(LocalDate.now(ZUERICH)) || von.isBefore(bis.minusMonths(12))) {
            throw new FachFehler(Fehlercode.ANFRAGE_UNGUELTIG);
        }
        return new ChatAuswertungSpeicher.Filter(von.atStartOfDay(ZUERICH).toInstant(),
                bis.plusDays(1).atStartOfDay(ZUERICH).toInstant(), minSterne, maxSterne);
    }

}
