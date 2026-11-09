# Infomaniak AI: Datenschutz und AVV, geprüft am 08.11.2026

Ich habe jede genannte URL selbst heruntergeladen und die Zitate im Originaltext gesucht. Bei Widersprüchen gilt die französische Fassung, denn laut ANB Art. 1.2 sind Übersetzungen «only indicative». Die deutschen Zitate unten sind meine Übersetzungen. Die offizielle deutsche Fassung der LLM-Bedingungen stimmt inhaltlich überein, ist aber nicht wörtlich gleich.

**Verworfen oder herabgestuft:**
- **24heures-Artikel (D4 Plan-les-Ouates):** Die URL leitet endlos auf sich selbst weiter. Ich konnte das Zitat nicht prüfen, deshalb ist es verworfen.
- **apertus-ai.org («no-logs policy») und goeuropean.eu:** Beide Zitate stehen dort wörtlich. Es sind aber Drittquellen, deshalb zählen sie nur als indirekt und nicht als Beleg.
- **Newsroom-Beitrag zum S3-Speicher:** Der Fund war als «nur Suchtreffer» markiert. Das Zitat steht aber tatsächlich auf der Seite, deshalb ist er jetzt belegt.

## 1. Speichert Infomaniak Eingaben und Ausgaben?

**Status: belegt** (Grundsatz). Keine Frist gefunden.

Vertraglich gilt: Was du über die API schickst, wird nicht dauerhaft erhoben. Ausgenommen sind Daten für Abrechnung und Betrieb. Gewisse Daten liegen «vorübergehend» vor, solange die Anfrage läuft. Als Betriebsdaten nennt der Vertrag Kundennummer, Anzahl und Dauer der Anfragen, Zeitpunkt und Metadaten, aber keine Prompt- oder Antwortinhalte. Wie lange irgendetwas davon aufbewahrt wird, steht nirgends. Ob Prompts zur Missbrauchserkennung gespeichert werden, steht auch nirgends.

**Belege:**
- **Besondere Bedingungen LLM API, Art. 6** (Revision 07/10/2025): https://welcome.infomaniak.com/api/web-components/1/cgu/latest?id=87&locale=fr_FR
  - Zitat: «Infomaniak ne collecte durablement aucune information transmise par le Client lors de l'utilisation des API outre les donnés requises pour la facturation et le bon fonctionnement du service fourni. Certaines données sont stockées temporairement durant le temps nécessaire au traitement de la requête du Client.»
  - DE: «Infomaniak erhebt keine bei der API-Nutzung übermittelten Informationen dauerhaft, ausser den für Abrechnung und ordnungsgemässen Betrieb nötigen Daten. Gewisse Daten werden vorübergehend für die Dauer der Bearbeitung der Anfrage gespeichert.»
  - Liste der Betriebsdaten, gleiche Stelle: «Données collectées à des fins opérationnelles : Numéro client / Nombre et temps de traitement des requêtes / Heure d'appel des requêtes / Métadonnées (taille, format, durée, nombre de pages...)»
  - DE: «Zu Betriebszwecken erhobene Daten: Kundennummer / Anzahl und Bearbeitungszeit der Anfragen / Uhrzeit der Anfragen / Metadaten (Grösse, Format, Dauer, Seitenzahl …)»
- **FAQ 1632** (Mise à jour 19/10/2026): https://www.infomaniak.com/fr/support/faq/1632/generer-des-images-avec-ai-services
  - Zitat: «(donc appels API exclus, où certaines métadonnées techniques sont journalisées, mais pas les réponses)»
  - DE: «API-Aufrufe ausgenommen; bei ihnen werden gewisse technische Metadaten protokolliert, nicht aber die Antworten.»
  - Status: belegt, die FAQ steht aber im Zusammenhang mit Bildgenerierung.
- **Produktseite** (ohne Datum): https://www.infomaniak.com/en/hosting/ai-services
  - Zitate: «No queries are stored» und «Your queries are neither recorded nor used to train models or improve our services.»
  - Die deutsche Seite sagt: «Ihre Prompts werden weder gespeichert noch …»
  - Status: belegt. Das ist Werbetext und geht weiter als der Vertrag, der eine vorübergehende Speicherung nennt.
- **Blog** (22.05.2026): https://news.infomaniak.com/en/understanding-sovereign-cloud/
  - Zitat: «No query is retained after processing … No prompt logging, no retraining on your data.»
  - DE: «Keine Anfrage wird nach der Verarbeitung aufbewahrt … kein Prompt-Logging, kein Nachtraining mit Ihren Daten.»
  - Status: belegt, aber Blog, kein Vertrag.
- **Datenschutzrichtlinie** (modifiée le 25 septembre 2026): https://www.infomaniak.com/fr/cgv/politique-de-confidentialite
  - Zitat: «… supprime vos données trois ans après la résiliation de votre compte … notamment les pièces comptables pendant dix ans.»
  - DE: «… löscht Ihre Daten drei Jahre nach Kündigung des Kontos … namentlich Buchhaltungsbelege während zehn Jahren.»
  - Status: indirekt. Die Regel betrifft Konto- und Vertragsdaten, nicht die API-Metadaten.
- **ANB Art. 20.1** (Review of 24/04/2026): https://welcome.infomaniak.com/api/web-components/1/cgu/latest?id=1&locale=en_GB
  - Zitat: «data from the information system of Infomaniak or its subcontractors, such as connection logs, consumption statements … are fully opposable to the Customer»
  - DE: «Daten aus dem Informationssystem von Infomaniak oder seinen Unterauftragnehmern, etwa Verbindungsprotokolle und Verbrauchsübersichten, können dem Kunden voll entgegengehalten werden.»
  - Status: indirekt. Es gibt Logs, ihr Inhalt und ihre Frist für die API sind aber offen.

**Nicht gefunden:**
- Aufbewahrungsfrist für die Betriebsdaten.
- Wie lange «vorübergehend» dauert, und ob es Caches gibt (z.B. Prompt- oder KV-Cache).
- Ob Prompts zur Missbrauchserkennung oder Moderation geprüft oder gespeichert werden.

## 2. Werden die Daten zum Training verwendet, und wo laufen die Drittmodelle?

**Training: belegt.** Kein Training und keine sonstige Verwertung. **Wo die Drittmodelle laufen: belegt nur allgemein.** Eine Aussage pro Modell gibt es nicht.

Der Vertrag schliesst aus, dass Prompts und Antworten zum Training verwendet oder sonst verwertet werden. Die Klausel gilt für «les API» insgesamt, also für alle Modelle. Infomaniak sagt öffentlich, die Open-Source-Modelle (namentlich Mistral, Qwen und Apertus) liefen auf eigenen GPUs in der Schweiz. Für Moonshot/Kimi, Google/Gemma und BAAI gibt es keine Aussage pro Modell. Nirgends steht, dass Modellhersteller Zugriff auf die Daten erhalten.

**Belege:**
- **LLM API Art. 6** (07/10/2025), URL wie oben:
  - Zitat: «Infomaniak précise explicitement ne pas utiliser les données transmises (“prompts”, contenu des réponses) pour entraîner ses propres IA et valoriser ces données de quelques autres manières.»
  - DE: «Infomaniak hält ausdrücklich fest, die übermittelten Daten (Prompts, Antwortinhalte) weder zum Training eigener KI noch auf andere Weise zu verwerten.»
  - Status: belegt.
- **Blog** (22.05.2026): https://news.infomaniak.com/en/understanding-sovereign-cloud/
  - Zitat: «The models — built on open-source foundations such as Mistral, Qwen, and Apertus — run on Infomaniak's own GPUs, in Switzerland.»
  - DE: «Die Modelle auf Open-Source-Basis wie Mistral, Qwen und Apertus laufen auf Infomaniaks eigenen GPUs in der Schweiz.»
  - Status: belegt, aber Blog.
- **Newsroom** (06.11.2026): https://news.infomaniak.com/en/new-s3-object-storage-public-cloud/
  - Zitat: «AI Services gives you access to open-source models that we host in Switzerland, through an OpenAI-compatible API»
  - DE: «AI Services gibt Ihnen über eine OpenAI-kompatible API Zugang zu Open-Source-Modellen, die wir in der Schweiz hosten.»
  - Status: belegt.
- **Produktseite**: https://www.infomaniak.com/en/hosting/ai-services
  - Zitat: «Our AI and GPU services are powered from the heart of Europe in our data centers located in Switzerland, with no dependence on foreign players.»
  - DE: «Unsere KI- und GPU-Dienste laufen in unseren Rechenzentren in der Schweiz, ohne Abhängigkeit von ausländischen Akteuren.»
  - Status: indirekt für die einzelnen Modelle.
- **FAQ 2924 zu Euria** (ohne Datum): https://www.infomaniak.com/en/support/faq/2924/discover-euria-and-the-apertus-model
  - Zitat: «… all artificial intelligence models available in Euria, are processed by Infomaniak's infrastructure in Switzerland. The transmitted content is not used to train these models.»
  - DE: «Alle in Euria verfügbaren KI-Modelle werden von Infomaniaks Infrastruktur in der Schweiz verarbeitet. Die übermittelten Inhalte werden nicht zum Training dieser Modelle verwendet.»
  - Status: indirekt, betrifft Euria und nicht die API.
- **FAQ 1369 zu Euria** (05/11/2026): https://www.infomaniak.com/fr/support/faq/1369/guide-de-demarrage-euria-lassistant-ia-souverain-et-gratuit
  - Zitat: «aucune information n'est partagée hors de l'infrastructure ni utilisée pour entraîner des modèles tiers»
  - DE: «Keine Information wird ausserhalb der Infrastruktur geteilt oder zum Training von Drittmodellen verwendet.»
  - Status: indirekt, betrifft Euria.
- **Preisseite und Produktseite, FAQ**: https://www.infomaniak.com/en/hosting/ai-services/prices
  - Zitat: «We don't currently offer the option of training our AI Services using custom data.»
  - DE: «Derzeit gibt es keine Möglichkeit, AI Services mit eigenen Daten zu trainieren.»
  - Status: belegt. Das betrifft Fine-Tuning und ist keine Datenschutzzusage.

## 3. Datenstandort und Unterauftragsverarbeiter

**Standort Schweiz: belegt.** Welches Rechenzentrum: nur indirekt. **Unterauftragsverarbeiter für die AI-API: keiner genannt.**

Nach den ANB liegen alle Kundendaten ausschliesslich in Schweizer Rechenzentren, die Infomaniak gehören. Welches Rechenzentrum die KI nutzt, steht nirgends. Es gibt nur einen Hinweis: Produktseite und D4-Artikel nennen beide die Zahl «6'000 Haushalte». Laut AVV müssen Unterauftragsverarbeiter in den Besonderen Bedingungen oder in der Datenschutzrichtlinie stehen. Die LLM-Bedingungen nennen keine. Die Datenschutzrichtlinie nennt nur Zahlungs-, SMS- und Betrugsdienste und verweist für dienstspezifische auf einen AVV-Anhang. Diesen Anhang enthält keine der drei AVV-PDFs (FR, DE, EN).

**Belege:**
- **ANB Art. 15.1** (24/04/2026): https://welcome.infomaniak.com/api/web-components/1/cgu/latest?id=1&locale=en_GB
  - Zitat: «All data transmitted by the Customer to Infomaniak is stored and hosted exclusively in datacenters located in Switzerland and which are the exclusive property of Infomaniak.»
  - DE: «Alle übermittelten Daten werden ausschliesslich in Rechenzentren in der Schweiz gespeichert und gehostet, die exklusiv Infomaniak gehören.»
  - Status: belegt.
- **DSGVO-Seite** (ohne Datum): https://www.infomaniak.com/en/legal/general-data-protection-regulation
  - Zitat: «Store your data in our data centers based exclusively in Switzerland, and never transfer your data outside our own infrastructure.»
  - DE: «Daten ausschliesslich in Schweizer Rechenzentren speichern und nie ausserhalb der eigenen Infrastruktur übertragen.»
  - Gleiche Seite: «Be transparent when we use subcontractors who may process your data.»
  - DE: «Transparent sein, wenn wir Unterauftragnehmer einsetzen, die Ihre Daten bearbeiten könnten.»
  - Status: belegt.
- **D4-Artikel** (28.01.2025): https://news.infomaniak.com/en/infomaniak-inaugurates-a-revolutionary-data-center-that-recovers-100-of-its-energy-for-building-heating/
  - Zitat: «heat 6,000 homes»
  - Auf der Produktseite steht: «fully recovered to heat 6'000 homes in winter»
  - DE: «heizt 6'000 Haushalte»
  - Status: indirekt.
- **Euria-Ankündigung** (09.12.2025): https://news.infomaniak.com/en/euria-sovereign-ai-assistant/
  - Zitat: «… exclusively in Infomaniak's data centers in Switzerland, with no external service providers or data transfer abroad.»
  - DE: «ausschliesslich in Infomaniaks Schweizer Rechenzentren, ohne externe Dienstleister und ohne Übermittlung ins Ausland.»
  - Status: indirekt, betrifft Euria.
- **AVV Art. 5.1** (PDF-Metadaten 12.08.2025): https://www.infomaniak.com/documents/privacy/DPA/Vereinbarung_zur_Datenverarbeitung_(DPA).pdf
  - Zitat (Original DE): «Unterauftragsverarbeiter … müssen in den Besonderen Bedingungen der Dienste, die betroffen sein könnten, oder in der Datenschutzrichtlinie aufgeführt sein.»
  - Status: belegt.
- **AVV Art. 5.2** (EN): https://www.infomaniak.com/documents/privacy/DPA/Data_Processing_Agreement_(DPA).pdf
  - Zitat: «The Client shall have 30 days from the date of this notification to raise an objection to these changes.»
  - DE: «Der Kunde hat 30 Tage ab Mitteilung, um Widerspruch zu erheben.»
  - Status: belegt.
- **Datenschutzrichtlinie** (25.09.2026): https://www.infomaniak.com/fr/cgv/politique-de-confidentialite
  - Zitat: «Sous-traitants propres à certains services (par exemple le filtrage antispam du Service Mail ou la diffusion des newsletters) : ils figurent dans l'annexe « Sous-traitants tiers »»
  - DE: «Dienstspezifische Unterauftragsverarbeiter (z.B. Antispam, Newsletter) stehen im Anhang ‹Sous-traitants tiers›.»
  - Die EN-Fassung nennt zusätzlich: «Checkout and PayPal … Twilio … MaxMind»
  - Status: belegt. Dass dieser Anhang fehlt, ist eigene Prüfung: Er steht in keiner der drei PDFs.

**Nicht gefunden:**
- Ein ausdrücklich genanntes Rechenzentrum für die KI.
- Eine Liste von Unterauftragsverarbeitern für AI Services.

## 4. Gibt es einen AVV, und wie schliesst man ihn ab?

**Status: belegt.** Er gilt automatisch. Einen KI-spezifischen AVV gibt es nicht, und wie man ihn im Manager abschliesst, ist nicht dokumentiert.

Der öffentliche AVV ist integraler Bestandteil der ANB, und ANB Art. 1.7 erklärt ihn seinerseits zum Vertragsbestandteil. Er gilt also ohne Unterschrift für die genutzten Dienste. Die LLM-Bedingungen ergänzen die ANB. Das ist der Weg, über den der AVV die API erfasst. Dieser Schluss ist indirekt, denn weder AVV noch LLM-Bedingungen nennen sich gegenseitig. Der AVV regelt DSG und DSGVO, Schweizer Recht und Gerichtsstand Genf.

**Belege:**
- **AVV, Präambel** (FR), Metadaten 12.08.2025, HTTP last-modified der EN-PDF 05.11.2026: https://www.infomaniak.com/documents/privacy/DPA/Accord_sur_le_traitement_des_donnees_(DPA).pdf
  - Zitat: «Cet Accord sur le Traitement des Données (« Accord ») est partie intégrante des Conditions Générales d'Utilisation»
  - DE (Original-DE-Fassung): «ist integraler Bestandteil der Allgemeinen Nutzungsbedingungen»
  - Status: belegt.
- **ANB Art. 1.7** (24/04/2026), URL wie oben:
  - Zitat: «Where applicable, the appendix relating to the processing of Infomaniak data (DPA), available on the Website, forms an integral part of the Contract.»
  - DE: «Gegebenenfalls ist der Anhang zur Datenverarbeitung (DPA) auf der Website integraler Vertragsbestandteil.»
  - Status: belegt.
- **LLM API Art. 1** (EN, 07/10/2025): https://welcome.infomaniak.com/api/web-components/1/cgu/latest?id=87&locale=en_GB
  - Zitat: «They complement and shall prevail over the General Terms of Use … in the event of any contradiction»
  - DE: «Sie ergänzen die ANB und gehen bei Widerspruch vor.»
  - Art. 6 verweist nur auf die Datenschutzrichtlinie, nicht auf den AVV.
  - Status: indirekt.
- **AVV EN**: https://www.infomaniak.com/documents/privacy/DPA/Data_Processing_Agreement_(DPA).pdf
  - Art. 1.1.10: «“FADP” refers to the Federal Act on Data Protection (RS 235.1) and its implementing ordinances, namely the “DPO” … and the “DPCO”»
  - Art. 7.1: «within ten working days of the date of cessation of any service … Infomaniak must delete and cause to be deleted all copies»
  - Art. 7.2: Infomaniak bestätigt die Löschung schriftlich.
  - Art. 12.2: «audits in order to demonstrate compliance with the requirements of the FADP and GDPR»
  - Art. 13.1: «may not transfer … outside the EU and/or the European Economic Area (EEA) without the Company's prior written consent»
  - Art. 14.1: «governed by Swiss law»
  - Art. 14.2: Gerichtsstand Genf.
  - DE, sinngemäss: DSG samt DSV und VDSZ; Löschung innert 10 Arbeitstagen mit schriftlicher Bestätigung; Audits nach DSG und DSGVO; keine Übermittlung ausserhalb EU/EWR ohne schriftliche Zustimmung; Schweizer Recht.
  - Status: belegt.
- **TOMs** («Version May 01 2024»): https://www.infomaniak.com/documents/privacy/DPA/appendix/DPA_technical_organizational_measures_v20240501.pdf
  - Status: belegt. Verlinkt ist die PDF von der ANB-Übersichtsseite aus.
- **Trust Center** (ohne Datum): https://www.infomaniak.com/fr/trust-center
  - Zitat: «un accord sur le traitement des données (DPA) conforme à la LPD suisse et au RGPD européen»
  - DE: «einen DSG- und DSGVO-konformen AVV»
  - Status: belegt.
- **DSG-Seite** (ohne Datum): https://www.infomaniak.com/en/legal/data-protection-act
  - Zitat: «By hosting your data, Infomaniak acts as a subcontractor and complies with the obligations imposed on it by the FADP.»
  - Als Kontakt nennt die Seite den Datenschutzberater Yoann Lopez, dpo@infomaniak.com.
  - Status: belegt.

**Nicht gefunden:**
- Ein Abschluss oder Download des AVV im Manager, oder eine Version zum Unterschreiben. Das behauptet nur die Drittseite infoswitch.fr, sie ist nicht verwendet.
- Eine Datums- oder Versionsangabe im Text des AVV.
- Eine ausdrückliche DSG-Regel für Übermittlungen aus der Schweiz. Art. 13.1 spricht nur von EU/EWR.

## 5. Was gilt für Beta-Modelle?

**Status: belegt** für Verfügbarkeit und Haftung. Für den Datenschutz gibt es keine Sonderregel.

Beta-Modelle können jederzeit ohne Vorankündigung zurückgezogen werden. Die Fristen von 30 bzw. 90 Tagen gelten für sie nicht, und es besteht keine Haftung. Art. 6 zum Datenmanagement nimmt Beta-Modelle nicht aus. Die Modelle für das Archiv (Mistral-Small-4, bge-reranker-v2-m3, Qwen3-Reranker-0.6B und Bge Multilingual Gemma2) tragen am Abrufdatum kein Beta-Kennzeichen.

**Belege:**
- **LLM API Art. 5** (07/10/2025), FR-URL wie oben:
  - Zitat: «Les modèles identifiés par l'indicateur « beta » sur la page de vente constituent une exception aux règles ci-dessus. Ils peuvent être retirés à tout moment, sans préavis … Leur indisponibilité ou leur retrait ne saurait engager la responsabilité d'Infomaniak, ni ouvrir droit à indemnisation.»
  - DE: «Mit ‹beta› gekennzeichnete Modelle sind von diesen Regeln ausgenommen und können jederzeit ohne Vorankündigung zurückgezogen werden; daraus entsteht keine Haftung und kein Anspruch auf Entschädigung.»
  - Status: belegt.
- **ANB Art. 11.12** (24/04/2026):
  - Zitat: «Infomaniak cannot be held responsible for the occurrence of any modification, alteration, loss or deletion of data following the use of a Service in its Beta version.»
  - DE: «Keine Haftung für Veränderung, Verlust oder Löschung von Daten infolge der Nutzung einer Beta-Version.»
  - Status: indirekt. Es ist offen, ob «Service in its Beta version» auch ein einzelnes Beta-Modell meint.
- **Modellseite** (Abruf 08.11.2026): https://www.infomaniak.com/en/hosting/ai-services/open-source-models
  - Mit «Beta» markiert: moonshotai/Kimi-K2.6, Qwen/Qwen3.5-397B-A17B-FP8, Qwen/Qwen3.8-27B-FP8 und swiss-ai/Apertus-v1.5-70B.
  - Ohne Beta-Kennzeichen: mistralai/Mistral-Small-4-119B-2603, BAAI/bge-reranker-v2-m3, Qwen/Qwen3-Reranker-0.6B, «Bge Multilingual Gemma2», google/gemma-4-31B-it und Qwen/Qwen3-Embedding-8B.
  - Nebenbefund: Für die Reranker verlinkt die Seite den Endpunkt `https://developer.infomaniak.com/docs/api/post/2/ai/{product_id}/cohere/v2/rerank`, nicht `/openai/v1`.
  - Status: belegt.
- **FAQ 2905** (22/10/2026): https://www.infomaniak.com/fr/support/faq/2905/comprendre-le-cycle-de-vie-des-modeles-ia
  - Zitat: «Les modèles en BETA permettent de tester rapidement de nouvelles versions.»
  - DE: «Beta-Modelle erlauben, neue Versionen rasch zu testen.»
  - Status: belegt.

## Was Fabian bestätigen oder bei Infomaniak nachfragen sollte

1. **Den AVV musst du nicht separat abschliessen.** Er gilt über ANB Art. 1.7 automatisch. Lade aber die geltende Fassung herunter und lege sie ab, weil die PDF keine Versionsangabe hat: AVV-PDF DE oder FR plus TOMs v20240501, zusammen mit dem Abrufdatum, z.B. unter `docs/spikes/`. Prüfe zusätzlich, ob der Manager einen AVV zum Gegenzeichnen anbietet. Öffentlich ist das nicht dokumentiert.
2. **Schriftliche Anfrage an dpo@infomaniak.com** mit folgenden Fragen:
   - Gilt der AVV ausdrücklich für die LLM API bzw. AI Services, Produkt 113354?
   - Gibt es den Anhang «Sous-traitants tiers», und nennt er Unterauftragsverarbeiter für AI Services?
   - Wie lange werden die Betriebs-Metadaten aufbewahrt, und wie lange dauert die «vorübergehende» Speicherung? Gibt es Prompt- oder KV-Caches?
   - Werden Prompts oder Ausgaben zur Missbrauchserkennung gespeichert oder von Menschen eingesehen?
   - Laufen alle Modelle, auch Kimi, Gemma und die BAAI-Modelle, ausschliesslich auf Infomaniak-GPUs? Erhalten die Hersteller Daten?
   - In welchem Rechenzentrum laufen die GPUs?
3. **Eigene Einschätzung, keine Infomaniak-Quelle:** Klär, ob Lohnabrechnungen und andere Belege mit Personendaten eine Datenschutz-Folgenabschätzung oder eine Information der Betroffenen brauchen. Laut AVV Art. 10.1 unterstützt Infomaniak dabei. Hinweise speziell zu Lohndaten gibt es öffentlich nicht.
4. **Keine Beta-Modelle produktiv nutzen.** Der Grund ist die Verfügbarkeit, nicht der Datenschutz. Den Beta-Status vor jedem Modellwechsel neu prüfen, denn die Modellseite ändert sich laufend.

## Formulierungsvorschlag für «Datenschutz und AVV» in `docs/spikes/infomaniak-ai.md`

```markdown
## Datenschutz und AVV

Stand: Prüfung der öffentlichen Infomaniak-Dokumente am 08.11.2026. Massgebend ist die französische Fassung (ANB Art. 1.2).

- **Speicherung:** Laut Besonderen Bedingungen LLM API (Art. 6, Revision 07.10.2025) erhebt Infomaniak die übermittelten Daten nicht dauerhaft, ausser für Abrechnung und Betrieb. Inhalte werden nur vorübergehend für die Dauer der Anfrage gespeichert. Als Betriebsdaten werden Kundennummer, Anzahl und Dauer der Anfragen, Zeitpunkt und Metadaten (Grösse, Format, Dauer, Seitenzahl) erfasst. Fristen sind keine genannt. *(offen: Rückfrage an dpo@infomaniak.com)*
- **Training:** Prompts und Antworten werden vertraglich weder zum Training noch sonst verwertet (Art. 6). Die Klausel gilt für alle Modelle der API.
- **Standort:** Alle Kundendaten liegen ausschliesslich in Schweizer Rechenzentren im Eigentum von Infomaniak (ANB Art. 15.1, Revision 24.04.2026). Laut Infomaniak laufen die Open-Source-Modelle auf eigenen GPUs in der Schweiz. Eine Bestätigung pro Modell und das konkrete Rechenzentrum fehlen öffentlich.
- **AVV:** Der AVV von Infomaniak (DSG/DSV/VDSZ und DSGVO, Schweizer Recht, Gerichtsstand Genf) ist integraler Bestandteil der ANB (ANB Art. 1.7) und gilt ohne separate Unterzeichnung. Die geltende PDF samt TOMs (v. 01.05.2024) ist mit Abrufdatum unter `docs/spikes/` abgelegt. Er regelt u.a. die Löschung innert 10 Arbeitstagen nach Dienstende mit schriftlicher Bestätigung, Unterauftragsverarbeiter nur mit Nennung und 30 Tagen Widerspruchsfrist sowie keine Übermittlung ausserhalb von EU/EWR ohne schriftliche Zustimmung.
- **Unterauftragsverarbeiter:** Für die LLM API ist öffentlich keiner genannt. Der in der Datenschutzrichtlinie erwähnte AVV-Anhang «Sous-traitants tiers» ist nicht öffentlich auffindbar. *(offen)*
- **Beta-Modelle:** Sie können jederzeit ohne Vorankündigung und ohne Haftung entfallen (Art. 5). Für den Datenschutz gilt keine Sonderregel. Das Archiv nutzt nur Modelle ohne Beta-Kennzeichen: `mistralai/Mistral-Small-4-119B-2603`, `bge_multilingual_gemma2`, `BAAI/bge-reranker-v2-m3` (Endpunkt `/2/ai/{product_id}/cohere/v2/rerank`).
- **Offen vor Produktivbetrieb:** Fristen der Metadaten und der vorübergehenden Speicherung, Logging zur Missbrauchserkennung, Liste der Unterauftragsverarbeiter, ausdrückliche Geltung des AVV für AI Services sowie die Frage, ob für Lohndaten eine Datenschutz-Folgenabschätzung nötig ist.
```

Die heruntergeladenen Originale liegen in `/private/tmp/claude-501/-Users-fabian-git-ai-entwicklung/0ef95298-9209-4edd-995a-76d6aee761c2/scratchpad/dl/`. Die AVV-PDFs heissen `dpafr.pdf`, `dpade.pdf` und `dpaen.pdf`, die TOMs `toms.pdf`, die LLM-Bedingungen `cgu87fr.pdf`.

## Nachtrag 09.11.2026 (Gate 0, Modell der Erfassung)
Seit Gate 0 macht `google/gemma-4-31B-it` den Metadaten-Vorschlag (`archiv.ki.erfassung-modell`, entschieden von Fabian
am 09.11.2026, ADR-006, Nachtrag 09.11.2026). Damit gehen alle erfassten Belege, auch Lohnabrechnungen und Bild-Originale,
an ein Modell, für das Abschnitt 2 keine Aussage pro Modell findet. Vertraglich gilt der Standort Schweiz für alle
Kundendaten (ANB Art. 15.1); die Rückfrage an dpo@infomaniak.com oben nennt Gemma bereits. Das Modell trägt kein
Beta-Kennzeichen (`meta.is_beta: false`, `GET /1/ai/models`, 09.11.2026); die Liste unter «Beta-Modelle» im
Formulierungsvorschlag gilt damit um `google/gemma-4-31B-it` ergänzt.
[offen: Bestätigung von Infomaniak, dass google/gemma-4-31B-it ausschliesslich auf eigenen GPUs in der Schweiz läuft – Entscheid vor Welle 5]
