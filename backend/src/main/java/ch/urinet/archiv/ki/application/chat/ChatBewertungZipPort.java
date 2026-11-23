package ch.urinet.archiv.ki.application.chat;

import ch.urinet.archiv.ki.domain.chat.Schwaerzung;

import java.io.IOException;
import java.io.OutputStream;

/** Schreibt den nicht persistierten ZIP-Export der Chat-Bewertungen. */
public interface ChatBewertungZipPort {

    void schreiben(ChatAuswertungSpeicher.ExportSnapshot snapshot, Schwaerzung schwaerzung, byte[] salz,
                   OutputStream ziel) throws IOException;
}
