package ch.urinet.archiv.export.application;

import ch.urinet.archiv.ablage.domain.AbgelegteDatei;
import ch.urinet.archiv.export.domain.Export;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Driven Port: Tabelle {@code export} des Mandanten aus dem Kontext (design.md «Jahresexport»). */
public interface ExportRepository {

    /**
     * Neuer Export {@code OFFEN}; {@code false}, wenn im Mandanten schon ein Export {@code OFFEN} ist (höchstens einer
     * zugleich, Index {@code export_offen_uk}). In einer laufenden Transaktion Teil davon.
     */
    boolean anlegen(UUID id, int geschaeftsjahr, String benutzer, String rolle);

    Optional<Export> laden(UUID id);

    /** Die neuesten Exporte, neueste zuerst. */
    List<Export> neueste(int anzahl);

    /** {@code OFFEN} → {@code FERTIG} mit dem abgelegten ZIP; {@code false}, wenn der Export nicht mehr offen ist. */
    boolean fertig(UUID id, AbgelegteDatei zip, int anzahl);

    /** {@code OFFEN} → {@code FEHLER} mit Fehler-ID; {@code false}, wenn der Export nicht mehr offen ist. */
    boolean fehler(UUID id, UUID fehlerId);
}
