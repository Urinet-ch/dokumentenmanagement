package ch.urinet.archiv.ki.application.chat;

import ch.urinet.archiv.ki.domain.chat.Chatmodus;

import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;

/** Mandantenlokales Lesen von Chat-Bewertungen für Auswertung und Export. */
public interface ChatAuswertungSpeicher {

    record Filter(Instant von, Instant bis, int minSterne, int maxSterne) {
    }

    record SchlechteAntwort(Instant zeit, int sterne, String grund, String frage) {
    }

    record Auswertung(long anzahl, double durchschnitt, long[] sterne, List<GrundAnzahl> gruende,
                      List<SchlechteAntwort> schlechteAntworten) {
        public Auswertung {
            sterne = sterne.clone();
            gruende = List.copyOf(gruende);
            schlechteAntworten = List.copyOf(schlechteAntworten);
        }

        @Override
        public long[] sterne() {
            return sterne.clone();
        }
    }

    record GrundAnzahl(String grund, long anzahl) {
    }

    record ExportZeile(Instant zeit, long nachrichtId, int sterne, String grund, String freitext, String frage,
                       String antwort, String werkzeugeJson, String quellenJson, Integer tokensEin,
                       Integer tokensAus, Long zeitBisErstemTextMs, String modellkennung, String systempromptVersion,
                       Chatmodus modus) {
    }

    interface ExportSnapshot extends AutoCloseable {
        long anzahl();

        List<List<String>> kontaktUndPartnerNamensgruppen();

        void exportZeilen(Consumer<ExportZeile> verarbeiten);

        void commit();

        @Override
        void close();
    }

    ExportSnapshot exportSnapshot(Filter filter);

    Auswertung auswertung(Filter filter);
}
