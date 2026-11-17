package ch.urinet.archiv.mandant.application;

import ch.urinet.archiv.mandant.domain.DokumentartVorlage;

import java.util.List;

/** Driven Port: Schema je Mandant (Liquibase {@code db/mandant}, Rechte der App-Rolle, Seed). */
public interface MandantDatenbank {

    /** Legt das Schema an (nur bei der Provisionierung) und bringt es über {@code db/mandant} auf den aktuellen Stand. */
    void schemaAnlegen(String schema);

    /** Bringt ein vorhandenes Schema über {@code db/mandant} auf den aktuellen Stand; legt nie ein Schema an. */
    void schemaMigrieren(String schema);

    boolean schemaVorhanden(String schema);

    /** Entfernt genau das validierte Mandanten-Schema nach abgelaufener Abbaufrist. */
    void schemaEntfernen(String schema);

    /** Spielt die Dokumentarten ein; vorhandene Codes bleiben unverändert. */
    void dokumentartenEinspielen(String schema, List<DokumentartVorlage> arten);

    /**
     * Legt nach dem Seed und dem Registereintrag eine Konfigurationsversion mit Quelle {@code SEED} an, wenn sich das
     * Abbild von der letzten Version unterscheidet (Spec 05, Req 1.1, 1.3).
     */
    void konfigurationSeedAnlegen(String schema);

    /** Rechte der App-Rolle auf die Tabellen in {@code public} (nach der Migration von {@code db/public}). */
    void gemeinsamesSchemaBerechtigen();
}
