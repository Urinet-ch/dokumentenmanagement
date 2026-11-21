package ch.urinet.archiv.gemeinsam.domain;

/**
 * Fehlercodes der Problem Details (design.md «Error Handling», RFC 9457). Die Tabelle in design.md und dieses Enum
 * stimmen überein ({@code FehlercodeSpecTest}). {@code titel} und {@code detail} sind allgemein und enthalten nie
 * Eingabewerte, Dokumentinhalte oder Personendaten (Req 16.7).
 */
public enum Fehlercode {
    ANFRAGE_UNGUELTIG(400, "Anfrage ungültig",
            "Die Anfrage ist unvollständig oder hat ein falsches Format. Bitte prüfen Sie die Angaben und senden Sie sie erneut."),
    NICHT_ANGEMELDET(401, "Nicht angemeldet",
            "Für diese Anfrage ist eine gültige Anmeldung nötig. Bitte melden Sie sich neu an."),
    MANDANT_FEHLT(403, "Kein gültiger Mandant",
            "Ihre Anmeldung gehört zu keinem gültigen, aktiven Mandanten. Bitte wenden Sie sich an den Inhaber."),
    NICHT_ERLAUBT(403, "Nicht erlaubt",
            "Ihre Rolle erlaubt diese Aktion nicht. Bitte wenden Sie sich an den Inhaber, falls Sie sie brauchen."),
    ESKALATION_VERBOTEN(403, "Eskalation verhindert",
            "Die angeforderte Änderung überschreitet Ihre Berechtigung. Bitte wenden Sie sich an den Inhaber."),
    NICHT_GEFUNDEN(404, "Nicht gefunden",
            "Den Eintrag gibt es nicht, oder er ist für Sie nicht sichtbar. Bitte prüfen Sie den Link."),
    API_TOKEN_ABGELAUFEN(409, "API-Token abgelaufen",
            "Ein abgelaufenes API-Token kann nicht reaktiviert werden. Bitte stellen Sie ein neues API-Token aus."),
    STATUS_KONFLIKT(409, "Aktion im aktuellen Status nicht möglich",
            "Das Dokument steht in einem Status, in dem diese Aktion nicht möglich ist. Bitte laden Sie es neu."),
    LETZTER_INHABER(409, "Letzter Inhaber",
            "Diese Änderung kann den letzten aktiven Inhaber entfernen. Bitte behalten Sie mindestens einen aktiven Inhaber."),
    REFERENZ_VERGEBEN(409, "Referenz vergeben",
            "Diese Referenz gehört bereits zu einer anderen Datei. Bitte verwenden Sie eine neue Referenz."),
    BELEGNUMMER_VERGEBEN(409, "Belegnummer vergeben",
            "Diese Belegnummer ist schon vergeben. Bitte prüfen Sie die Nummer."),
    FRIST_ABGELAUFEN(409, "Frist abgelaufen",
            "Die Aufbewahrungsfrist liegt in der Vergangenheit. Bitte prüfen Sie Datum und Dokumentart."),
    DOKUMENTART_IN_GEBRAUCH(409, "Dokumentart in Gebrauch",
            "Die Dokumentart wird noch verwendet und kann nicht gelöscht werden. Sie können sie deaktivieren."),
    KONFIGURATION_IN_GEBRAUCH(409, "Konfiguration in Gebrauch",
            "Die Klassifikation oder der Wert wird noch verwendet und kann nicht gelöscht werden. Sie können ihn deaktivieren."),
    KONFIGURATION_VERALTET(409, "Konfiguration veraltet",
            "Die Konfigurationsdatei basiert auf einem älteren Stand. Laden Sie die aktuelle Konfiguration und führen Sie Ihre Änderungen erneut ein."),
    DOKUMENTART_EXISTIERT(409, "Dokumentart existiert",
            "Diesen Code hat schon eine Dokumentart. Bitte wählen Sie einen anderen Code oder ändern Sie die "
                    + "bestehende Art."),
    CURSOR_ABGELAUFEN(410, "Cursor abgelaufen",
            "Der Cursor ist älter als die Aufbewahrung des Feeds. Bitte laden Sie den Bestand neu und beginnen Sie "
                    + "ohne Cursor."),
    VERSION_KONFLIKT(412, "Version veraltet",
            "Jemand hat den Stand inzwischen geändert. Bitte laden Sie ihn neu und wiederholen Sie die Änderung."),
    DATEI_ZU_GROSS(413, "Datei zu gross",
            "Die Datei oder die Anfrage ist zu gross. Bitte teilen Sie die Datei auf oder verkleinern Sie sie."),
    DATEITYP_UNBEKANNT(415, "Dateityp nicht unterstützt",
            "Dieser Dateityp wird nicht unterstützt. Bitte senden Sie ein PDF oder ein Bild (JPEG, PNG, HEIC, TIFF)."),
    MEDIENTYP_FALSCH(415, "Format der Anfrage nicht unterstützt",
            "Die Anfrage hat ein Format (Content-Type), das hier nicht angenommen wird. Bitte senden Sie die Daten im "
                    + "erwarteten Format."),
    DATEI_UNGUELTIG(422, "Datei ungültig",
            "Die Datei ist leer oder beschädigt. Bitte erfassen Sie sie erneut."),
    PFLICHTFELD_FEHLT(422, "Pflichtfeld fehlt",
            "Mindestens ein Pflichtfeld ist leer. Bitte füllen Sie die Pflichtfelder aus."),
    PARTNER_UNBEKANNT(422, "Partner unbekannt",
            "Der angegebene Partner ist im Archiv nicht bekannt. Bitte importieren Sie zuerst die Kontakte."),
    ZUSATZ_UNGUELTIG(422, "Zusatzfelder ungültig",
            "Die Zusatzfelder passen nicht zur Dokumentart. Bitte prüfen Sie die Angaben."),
    KONFIGURATION_UNGUELTIG(422, "Konfiguration ungültig",
            "Die Konfiguration ist ungültig. Bitte prüfen Sie die angegebenen Werte."),
    KLASSIFIKATION_UNGUELTIG(422, "Klassifikation ungültig",
            "Die Klassifikationen passen nicht zur Dokumentart. Bitte prüfen Sie die Werte."),
    REGEL_VERLETZT(422, "Dokumentregel verletzt",
            "Die Angaben verletzen eine Dokumentregel. Bitte prüfen Sie die genannten Regeln."),
    CSV_UNGUELTIG(422, "CSV-Datei ungültig",
            "Die CSV-Datei hat nicht das erwartete Format (UTF-8 oder Windows-1252, Semikolon oder Komma), mehr als "
                    + "10'000 Datenzeilen, mehr als 1'000 Spalten, einen zu langen Spaltennamen oder keine Spalte "
                    + "für den Namen. Bitte prüfen Sie Datei und Zuordnung."),
    CSV_LEER(422, "CSV-Datei leer",
            "Die CSV-Datei enthält keine Datenzeilen. Bitte exportieren Sie sie erneut aus dem CRM."),
    KEINE_WISSENSQUELLE(422, "Keine Wissensquelle",
            "Nur Dokumente der Art «Wissensquelle» können in die Wissensbasis übernommen werden."),
    ENTWURF_BEFUND(422, "Entwurf mit Befund",
            "Der Entwurf hat Befunde und kann so nicht übernommen werden. Bitte beheben Sie die Befunde."),
    PAKET_UNGUELTIG(422, "Wissenspaket ungültig",
            "Das Wissenspaket hat ein ungültiges Format oder überschreitet eine Grenze. Bitte prüfen Sie den Bericht und erstellen Sie das Paket erneut."),
    VERTRAULICHKEIT_ZU_TIEF(422, "Vertraulichkeit zu tief",
            "Die Vertraulichkeit liegt unter der Stufe der Quellen. Bitte wählen Sie eine höhere Stufe."),
    VERTRAULICHKEIT_UNGUELTIG(422, "Vertraulichkeit ungültig",
            "Diese Vertraulichkeit ist für eine Wissensseite nicht möglich. Bitte wählen Sie eine andere."),
    ANALYSE_NICHT_MOEGLICH(422, "Analyse nicht möglich",
            "Aus diesem Gespräch kann kein Analyse-Vorschlag entstehen."),
    VERSION_FEHLT(428, "Version fehlt",
            "Für Änderungen ist die erwartete Version nötig (If-Match). Bitte laden Sie den aktuellen Stand neu."),
    ZU_VIELE_ANFRAGEN(429, "Zu viele Anfragen",
            "Sie haben in kurzer Zeit zu viele Anfragen gestellt. Bitte warten Sie die angegebene Zeit "
                    + "(Retry-After) und versuchen Sie es dann erneut."),
    BUDGET(429, "Budget erschöpft",
            "Der Chatbot ist in diesem Monat ausgelastet. Die Suche funktioniert weiterhin."),
    INTERNER_FEHLER(500, "Interner Fehler",
            "Im Archiv ist ein Fehler aufgetreten. Bitte versuchen Sie es später erneut und "
                    + "nennen Sie bei Rückfragen die Fehler-ID."),
    IDENTITAETSDIENST_FEHLER(502, "Identitätsdienst fehlerhaft",
            "Der Identitätsdienst hat die Anfrage abgelehnt. Bitte versuchen Sie es später erneut."),
    IDENTITAETSDIENST_NICHT_ERREICHBAR(503, "Identitätsdienst nicht erreichbar",
            "Der Identitätsdienst ist gerade nicht erreichbar. Bitte versuchen Sie es später erneut."),
    UEBERLASTET(503, "Archiv ausgelastet",
            "Das Archiv prüft gerade zu viele Anmeldungen gleichzeitig. Bitte versuchen Sie es in einigen Sekunden "
                    + "erneut."),
    SPEICHER_NICHT_ERREICHBAR(503, "Speicher nicht erreichbar",
            "Der Speicher ist gerade nicht erreichbar. Bitte versuchen Sie es später erneut.");

    private final int status;
    private final String titel;
    private final String detail;

    Fehlercode(int status, String titel, String detail) {
        this.status = status;
        this.titel = titel;
        this.detail = detail;
    }

    public int status() {
        return status;
    }

    public String titel() {
        return titel;
    }

    /** Allgemeine Beschreibung für {@code detail}: was passiert ist und was der Benutzer tun kann. */
    public String detail() {
        return detail;
    }
}
