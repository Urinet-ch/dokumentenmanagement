package ch.urinet.archiv.mandant.application;

import ch.urinet.archiv.mandant.domain.DokumentartVorlage;

import java.util.List;

/** Driven Port: Schema je Mandant (Liquibase {@code db/mandant}, Rechte der App-Rolle, Seed). */
public interface MandantDatenbank {

    /** Legt das Schema an, falls es fehlt, und bringt es über {@code db/mandant} auf den aktuellen Stand. */
    void schemaEinrichten(String schema);

    /** Spielt die Dokumentarten ein; vorhandene Codes bleiben unverändert. */
    void dokumentartenEinspielen(String schema, List<DokumentartVorlage> arten);

    /** Rechte der App-Rolle auf die Tabellen in {@code public} (nach der Migration von {@code db/public}). */
    void gemeinsamesSchemaBerechtigen();
}
