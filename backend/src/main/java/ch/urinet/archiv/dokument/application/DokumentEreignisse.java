package ch.urinet.archiv.dokument.application;

import java.util.UUID;

/** Ereignisse, die andere Fachbereiche synchron mit einer Dokumentänderung verarbeiten. */
public interface DokumentEreignisse {

    /** Dokument erreicht {@code ARCHIVIERT}; Aufträge müssen Teil derselben Transaktion sein. */
    void archiviert(UUID dokumentId, String art);
}
