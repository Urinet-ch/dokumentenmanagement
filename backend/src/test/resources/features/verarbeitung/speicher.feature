# language: de
@spec-01
Funktionalität: Frühwarnung vor Speicherknappheit der JVM
  Wird ein Speicherbereich der JVM knapp (Standard ab 85 % des Maximums, kritisch ab 95 %), erhält der Inhaber je
  Bereich und Stufe genau eine Mail, die nur Bereich, Prozent und Mengen nennt (Aufgabe 5.6, Req 16.5). Ohne Maximum
  gibt es keine Schwelle. Die Kennzahl «archiv_jvm_speicher_prozent» trägt keine Mandanten-Etiketten. Die Szenarien
  geben der Prüfung synthetische Messwerte vor.

  @req-16.5
  Szenario: Ein knapper Bereich löst die Mail je Stufe genau einmal aus
    Angenommen der Speicherbereich «Probe-Metaspace-A» ist zu 87 Prozent belegt
    Wenn die Speicherüberwachung läuft
    Und die Speicherüberwachung läuft
    Dann hat der Inhaber zu «Probe-Metaspace-A» 1 Mail zur Stufe «knapp» erhalten, die nur Bereich, Prozent und Mengen nennt
    Angenommen der Speicherbereich «Probe-Metaspace-A» ist zu 96 Prozent belegt
    Wenn die Speicherüberwachung läuft
    Und die Speicherüberwachung läuft
    Dann hat der Inhaber zu «Probe-Metaspace-A» 1 Mail zur Stufe «kritisch» erhalten, die nur Bereich, Prozent und Mengen nennt
    Und nennen die Kennzahlen den Speicherbereich «Probe-Metaspace-A» mit 96 Prozent ohne Mandanten-Etikett

  @req-16.5
  Szenario: Unter der Schwelle und ohne Maximum geht keine Mail
    Angenommen der Speicherbereich «Probe-Metaspace-B» ist zu 60 Prozent belegt
    Und der Speicherbereich «Probe-Metaspace-C» hat kein Maximum
    Wenn die Speicherüberwachung läuft
    Dann hat der Inhaber zu «Probe-Metaspace-B» keine Speicher-Mail erhalten
    Und hat der Inhaber zu «Probe-Metaspace-C» keine Speicher-Mail erhalten
