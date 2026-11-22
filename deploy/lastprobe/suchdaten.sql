\set ON_ERROR_STOP on

BEGIN;

DO $$
BEGIN
    IF current_database() <> 'archiv_lastprobe' OR inet_server_addr() IS NOT NULL THEN
        RAISE EXCEPTION 'Suchdaten nur lokal in der Wegwerf-Datenbank archiv_lastprobe anlegen';
    END IF;
    IF (SELECT count(*) FROM public.mandant) <> 1
            OR NOT EXISTS (SELECT 1 FROM public.mandant WHERE code = 'last') THEN
        RAISE EXCEPTION 'Die Datenbank muss ausschliesslich den Mess-Mandanten last enthalten';
    END IF;
    IF EXISTS (SELECT 1 FROM m_last.dokument) THEN
        RAISE EXCEPTION 'Der Mess-Mandant muss für die Füllung leer sein';
    END IF;
END
$$;

CREATE TEMP TABLE lastprobe_daten ON COMMIT DROP AS
SELECT gen_random_uuid() AS id, n, DATE '2022-01-01' + (n * 271 % 1825) AS datum
FROM generate_series(1, 50000) AS n;

INSERT INTO m_last.dokument
    (id, art, titel, datum, quelle, partner_id, partner_name, tags, vertraulichkeit, person_benutzer,
     erstellt_von, status, bestaetigt, bestaetigt_von, geschaeftsjahr, volltext, dateiname)
SELECT id,
       (ARRAY['AUSGANGSRECHNUNG', 'LIEFERANTENBELEG', 'OFFERTE', 'QUITTUNG', 'VERTRAG',
              'KORRESPONDENZ', 'LOHNABRECHNUNG', 'VERSICHERUNG'])[1 + n % 8],
       'Synthetischer Beleg ' || n,
       datum, 'UPLOAD', md5('partner-' || (n % 8))::uuid, 'Testpartner ' || (n % 8),
       ARRAY['lastprobe', 'gruppe' || (n % 17)],
       CASE WHEN n % 5 < 3 THEN 'NORMAL' WHEN n % 5 = 3 THEN 'INTERN' ELSE 'PERSONAL' END,
       CASE WHEN n % 5 = 4 THEN '00000000-0000-4000-8000-000000000002' END,
       '00000000-0000-4000-8000-000000000001', 'ARCHIVIERT', now(),
       '00000000-0000-4000-8000-000000000001', extract(year FROM datum)::integer,
       array_to_string(ARRAY(
           SELECT (ARRAY['Rechnung', 'Lieferung', 'Trikots', 'Clubhaus', 'Beleuchtung', 'Wartung',
                         'Netzwerk', 'Server', 'Drucker', 'Offerte', 'Vertrag', 'Versicherung', 'Lohn', 'Spesen',
                         'Miete', 'Reparatur', 'Installation', 'Schulung', 'Beratung', 'Material', 'Kabel', 'Lizenz',
                         'Abonnement', 'Mahnung', 'Gutschrift', 'Projekt', 'Webseite', 'Hosting'])
                  [1 + (hashint4(n * 163 + w * 293) & 2147483647) % 28]
           FROM generate_series(1, 175) AS w
       ), ' '),
       (ARRAY['Scan', 'Kassenzettel', 'Fotobeleg', 'Ablage', 'Einzahlung'])[1 + n % 5]
           || '_' || n || '.pdf'
FROM lastprobe_daten;

INSERT INTO m_last.dokument_finanz (dokument_id, belegnummer, richtung, betrag, waehrung)
SELECT id, extract(year FROM datum)::integer || '-' || lpad((n % 1000)::text, 3, '0'),
       CASE WHEN n % 2 = 0 THEN 'EINNAHME' ELSE 'AUSGABE' END,
       (n % 500000)::numeric / 100, 'CHF'
FROM lastprobe_daten
WHERE n % 3 = 0;

ANALYZE m_last.dokument;
ANALYZE m_last.dokument_finanz;
COMMIT;
