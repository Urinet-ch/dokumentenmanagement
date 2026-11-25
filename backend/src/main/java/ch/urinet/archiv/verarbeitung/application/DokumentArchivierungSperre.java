package ch.urinet.archiv.verarbeitung.application;

import ch.urinet.archiv.mandant.domain.MandantCode;

import java.util.UUID;

/** Serialisiert Archivierungen desselben Dokuments über Worker-JVMs hinweg. */
public interface DokumentArchivierungSperre {

    Sperre sperren(MandantCode mandant, UUID dokumentId) throws InterruptedException;

    interface Sperre extends AutoCloseable {
        @Override
        void close();
    }
}
