package ch.urinet.archiv.dokument.domain;

import java.time.LocalDate;

/** Gespeicherte Vertragsangaben eines Dokuments ({@code dokument_vertrag}). */
public record DokumentVertrag(LocalDate gueltigAb, LocalDate gueltigBis, String kuendigungsfrist,
                              LocalDate naechsteFrist) {
}
