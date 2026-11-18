-- Seed-Regeln des Mandanten urinet (Spec 05, design.md «Regeltypen»; Aufgabe 17.6). Läuft im Changeset 046 für
-- bestehende Schemas und bei der Provisionierung nach den Dokumentarten (MandantDatenbank.regelnEinspielen).
insert into regel (code, art, typ, parameter, schwere, meldung, aktiv)
select seed.code, art.code, seed.typ, seed.parameter, 'WARNUNG', seed.meldung, true
from (values
    ('ausgangsrechnung_folge', 'AUSGANGSRECHNUNG', 'FOLGE',
        '{"muster":true,"zeitraum":"GESCHAEFTSJAHR","beginn":1}'::jsonb,
        'Die Ausgangsrechnungsnummernfolge weist eine Lücke auf.'),
    ('lohnabrechnung_erwartet', 'LOHNABRECHNUNG', 'ERWARTET',
        '{"je":"MONAT","datum":"BELEGDATUM","nurMonat":"zusatz.periode","gruppe":"zusatz.person"}'::jsonb,
        'Für einen Monat fehlt eine bestätigte Lohnabrechnung.'),
    ('bankbeleg_erwartet', 'BANK', 'ERWARTET',
        '{"je":"MONAT","datum":"zusatz.periode_bis","gruppe":"PARTNER"}'::jsonb,
        'Für einen Monat fehlt ein bestätigter Bankbeleg.')
) as seed(code, art, typ, parameter, meldung)
join dokumentart art on art.code = seed.art
where current_schema() = 'm_urinet'
on conflict (code) do nothing
