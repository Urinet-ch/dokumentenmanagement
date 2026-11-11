# language: de
@spec-01
Funktionalität: Auftragsschlange
  Lange Arbeiten laufen als Auftrag in «public.auftrag» (Aufgabe 2.5). Bis die fachlichen Handler kommen (ab 2.6),
  stehen Test-Handler unter src/test für die Fälle: «TEST_OK» läuft durch und merkt sich das Schema, «TEST_FEHLER»
  wirft eine Ausnahme, «TEST_HAENGT» hält bis zum Abbruch an (Zeitgrenze im Test 1 Sekunde, Lebenszeichen alle
  200 Millisekunden). Poller und Aufräumer sind im Test abgeschaltet, die Schritte rufen sie selbst auf.

  Hintergrund:
    Angenommen die Mandanten «urinet» und «vhf-gsk» sind angelegt
    Und die Auftragsschlange ist leer

  @req-1.6
  Szenario: Jeder Auftrag läuft im Kontext seines Mandanten
    Angenommen im Mandanten «vhf-gsk» ist ein Auftrag «TEST_OK» eingereiht
    Und im Mandanten «urinet» ist ein Auftrag «TEST_OK» eingereiht
    Wenn der Worker alle fälligen Aufträge bearbeitet
    Dann ist der Auftrag 1 «FERTIG» und lief im Schema «m_vhf_gsk»
    Und ist der Auftrag 2 «FERTIG» und lief im Schema «m_urinet»

  @req-5.4
  # Teilnachweis: Dokumentstatus und Original folgen mit dem Handler VERARBEITEN (2.6) über nachLetztemFehlversuch.
  Szenario: Ein fehlgeschlagener Auftrag wird mit wachsender Wartezeit wiederholt und nach dem dritten Versuch FEHLER
    Angenommen im Mandanten «urinet» ist ein Auftrag «TEST_FEHLER» eingereiht
    Wenn der Worker alle fälligen Aufträge bearbeitet
    Dann ist der Auftrag 1 «OFFEN» nach 1 Versuch mit Fehlertext «fehlgeschlagen» und wartet 2 Minuten
    Wenn der Auftrag 1 wieder fällig ist
    Und der Worker alle fälligen Aufträge bearbeitet
    Dann ist der Auftrag 1 «OFFEN» nach 2 Versuchen mit Fehlertext «fehlgeschlagen» und wartet 4 Minuten
    Wenn der Auftrag 1 wieder fällig ist
    Und der Worker alle fälligen Aufträge bearbeitet
    Dann ist der Auftrag 1 «FEHLER» nach 3 Versuchen mit Fehlertext «fehlgeschlagen»
    Und der Handler hat nach dem letzten Fehlversuch im Mandanten «urinet» aufgeräumt

  @req-5.6
  Szenario: Ein Auftrag über der Zeitgrenze wird abgebrochen und zählt als Fehlversuch
    Angenommen im Mandanten «urinet» ist ein Auftrag «TEST_HAENGT» eingereiht
    Wenn der Worker alle fälligen Aufträge bearbeitet
    Dann wurde der Auftrag 1 abgebrochen
    Und ist der Auftrag 1 «OFFEN» nach 1 Versuch mit Fehlertext «zeitgrenze» und wartet 2 Minuten

  @req-5.7
  Szenario: Ein laufender Auftrag gibt Lebenszeichen
    Angenommen im Mandanten «urinet» ist ein Auftrag «TEST_HAENGT» eingereiht
    Wenn der Worker den Auftrag startet und 600 Millisekunden wartet
    Dann ist der Auftrag 1 «LAEUFT» mit einem Lebenszeichen nach dem Start

  @req-5.7
  Szenario: Ein verwaister Auftrag wird nach der Zeitgrenze freigegeben und zählt als Fehlversuch
    Angenommen im Mandanten «vhf-gsk» ist ein Auftrag «TEST_OK» eingereiht
    Und im Mandanten «vhf-gsk» ist ein Auftrag «TEST_OK» eingereiht
    Und der Auftrag 1 läuft seit einer Stunde ohne Lebenszeichen
    Und der Auftrag 2 läuft seit einer Stunde mit frischem Lebenszeichen
    Wenn der Aufräumer läuft
    Dann ist der Auftrag 1 «OFFEN» nach 1 Versuch mit Fehlertext «verwaist» und wartet 2 Minuten
    Und ist der Auftrag 2 «LAEUFT» mit einem Lebenszeichen nach dem Start
