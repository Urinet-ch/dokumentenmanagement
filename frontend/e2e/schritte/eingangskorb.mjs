// Eingangskorb-Seite im Browser (Aufgabe 3.6) gegen die Attrappe der Archiv-API (hilfen/archiv-attrappe.mjs).
// Getippt wird mit click(); jeder Tipp zählt für Req 8.4 («Bestätigen in höchstens zwei Tipps»).
import { Given, Then, When } from '@cucumber/cucumber';
import assert from 'node:assert/strict';
import { basisUrl, benutzer, keycloakAttrappe } from '../hilfen/keycloak-attrappe.mjs';
import { dokumentAus } from '../hilfen/archiv-attrappe.mjs';

const eintrag = (welt, titel) =>
  welt.seite
    .locator('[data-test=eintrag]')
    .filter({ has: welt.seite.getByRole('heading', { name: titel }) });

async function tippen(welt, ziel) {
  await ziel.click();
  welt.tipps = (welt.tipps ?? 0) + 1;
}

async function warten(pruefung, nachricht) {
  for (let i = 0; i < 50; i++) {
    if (await pruefung()) {
      return;
    }
    await new Promise((r) => setTimeout(r, 100));
  }
  assert.fail(typeof nachricht === 'function' ? await nachricht() : nachricht);
}

Given(/^der Eingangskorb enthält:$/, function (tabelle) {
  this.archiv.eintraege = tabelle.hashes().map(dokumentAus);
});

Given(/^der Eingangskorb enthält (\d+) Dokumente$/, function (anzahl) {
  this.archiv.eintraege = Array.from({ length: Number(anzahl) }, (_, i) =>
    dokumentAus({ titel: `Beleg ${i + 1}` }, i),
  );
});

Given(/^das Archiv kennt den Kontakt «([^»]+)» in «([^»]+)»$/, function (name, ort) {
  this.archiv.kontakte.push({
    id: `k-${this.archiv.kontakte.length + 1}`,
    typ: 'ORGANISATION',
    name,
    ort,
  });
});

Given(/^das Archiv verweigert das Bestätigen, weil «([^»]+)» fehlt$/, function (feld) {
  this.archiv.ablehnung = {
    status: 422,
    body: {
      status: 422,
      fehlercode: 'PFLICHTFELD_FEHLT',
      detail: 'Pflichtfelder fehlen',
      felder: [feld],
    },
  };
});

When(
  /^ich als «([^»]+)» im Mandanten «([^»]+)» den Eingangskorb auf dem Handy öffne$/,
  async function (rolle, mandant) {
    const kc = await keycloakAttrappe(this);
    kc.benutzer = benutzer(rolle, mandant);
    await this.seite.setViewportSize({ width: 375, height: 812 });
    await this.seite.goto(`${basisUrl}/eingangskorb`);
    // Anmelden gehört nicht zum Bestätigen (Req 8.4): Die Tipps zählen erst danach.
    await this.seite.getByRole('button', { name: 'Anmelden', exact: true }).click();
    await this.seite.waitForURL((url) => url.pathname === '/eingangskorb');
    await this.seite.locator('[data-test=anzahl], [data-test=leer]').first().waitFor();
    this.tipps = 0;
  },
);

When(/^ich beim Eintrag «([^»]+)» auf «([^»]+)» tippe$/, async function (titel, knopf) {
  await tippen(this, eintrag(this, titel).getByRole('button', { name: knopf, exact: true }));
});

When(
  /^ich beim Eintrag «([^»]+)» in «([^»]+)» «([^»]*)» eingebe$/,
  async function (titel, feld, text) {
    await eintrag(this, titel).getByLabel(feld).fill(text);
  },
);

When(
  /^ich beim Eintrag «([^»]+)» den Partner «([^»]+)» suche und «([^»]+)» wähle$/,
  async function (titel, suche, treffer) {
    const e = eintrag(this, titel);
    await e.getByLabel('Partner suchen').fill(suche);
    await tippen(this, e.getByRole('button', { name: treffer, exact: true }));
  },
);

When(
  /^ich beim Eintrag «([^»]+)» «([^»]+)» mit der Tastatur wähle$/,
  async function (titel, knopf) {
    const ziel = eintrag(this, titel).getByRole('button', { name: knopf, exact: true });
    await ziel.waitFor();
    for (let i = 0; i < 60 && !(await ziel.evaluate((el) => el === document.activeElement)); i++) {
      await this.seite.keyboard.press('Tab');
    }
    assert.ok(
      await ziel.evaluate((el) => el === document.activeElement),
      `«${knopf}» mit Tab nicht erreichbar`,
    );
    await this.seite.keyboard.press('Enter');
  },
);

Then(/^zeigt der Eingangskorb (\d+) (?:Eintrag|Einträge)$/, async function (anzahl) {
  await warten(
    async () => (await this.seite.locator('[data-test=eintrag]').count()) === Number(anzahl),
    `nicht ${anzahl} Einträge`,
  );
});

Then(/^zeigt der Eintrag «([^»]+)» ein Vorschaubild$/, async function (titel) {
  const bild = eintrag(this, titel).locator('img[data-test=vorschau]');
  await bild.waitFor();
  await warten(
    () => bild.evaluate((img) => img.complete && img.naturalWidth > 0),
    'Vorschaubild nicht geladen',
  );
  assert.match((await bild.getAttribute('alt')) ?? '', new RegExp(titel));
});

Then(
  /^zeigt der Eintrag «([^»]+)» bei «([^»]+)» den Wert «([^»]+)» (mit|ohne) «bitte prüfen»$/,
  async function (titel, feld, wert, mitOhne) {
    const zeile = eintrag(this, titel)
      .locator('.zeile')
      .filter({ has: this.seite.locator('dt', { hasText: new RegExp(`^${feld}$`) }) });
    const text = (await zeile.locator('dd').innerText()).trim();
    assert.ok(text.startsWith(wert), `${feld}: ${text}`);
    assert.equal(text.includes('bitte prüfen'), mitOhne === 'mit', `${feld}: ${text}`);
  },
);

Then(/^zeigt der Eintrag «([^»]+)» den erkannten Partner «([^»]+)»$/, async function (titel, name) {
  const hinweis = eintrag(this, titel).locator('[data-test=partner-vorschlag]');
  assert.match(await hinweis.innerText(), new RegExp(`«${name}»`));
});

Then(/^zeigt der Eintrag «([^»]+)» den Fehlertext «([^»]+)»$/, async function (titel, text) {
  assert.equal(
    (await eintrag(this, titel).locator('[data-test=fehlertext]').innerText()).trim(),
    text,
  );
});

Then(/^zeigt der Eintrag «([^»]+)» nur die Aktionen «(.*)»$/, async function (titel, liste) {
  const knoepfe = eintrag(this, titel).locator('[data-test=aktionen] button');
  const namen = (await knoepfe.allInnerTexts()).map((t) => t.trim());
  assert.deepEqual(namen, liste ? liste.split(', ') : []);
});

Then(/^zeigt der Eintrag «([^»]+)» den Hinweis «([^»]+)»$/, async function (titel, text) {
  await eintrag(this, titel).getByText(text, { exact: true }).waitFor();
});

Then(
  /^zeigt der Eintrag «([^»]+)» die Warnung zur Belegnummer mit Verweis auf «([^»]+)»$/,
  async function (titel, ziel) {
    const warnung = eintrag(this, titel).locator('[data-test=warnung-belegnummer]');
    assert.match(await warnung.innerText(), /gleicher Belegnummer/);
    const link = warnung.getByRole('link', { name: 'Vorhandenes Dokument öffnen' });
    assert.equal(await link.getAttribute('href'), ziel);
    assert.equal(
      await eintrag(this, titel)
        .getByRole('button', { name: 'Bestätigen', exact: true })
        .isEnabled(),
      true,
    );
  },
);

Then('scrollt die Seite nicht horizontal', async function () {
  const breiten = await this.seite.evaluate(() => ({
    scroll: document.documentElement.scrollWidth,
    sichtbar: document.documentElement.clientWidth,
  }));
  assert.ok(
    breiten.scroll <= breiten.sichtbar,
    `scrollWidth ${breiten.scroll} > clientWidth ${breiten.sichtbar}`,
  );
});

async function aktionPruefen(welt, titel, was, felder = {}) {
  const aktion = { bestätigt: 'bestaetigen', verworfen: 'verwerfen', wiederholt: 'wiederholen' }[
    was
  ];
  const treffer = () =>
    welt.archiv.aktionen.find((a) => a.titel === titel && a.aktion === aktion && !a.abgelehnt);
  await warten(() => Boolean(treffer()), `«${titel}» nicht ${was}`);
  const { body, ifMatch } = treffer();
  assert.equal(ifMatch, '"1"');
  for (const [feld, wert] of Object.entries(felder)) {
    assert.equal(String(body?.[feld]), wert, feld);
  }
}

Then(/^hat das Archiv «([^»]+)» (bestätigt|verworfen|wiederholt)$/, async function (titel, was) {
  await aktionPruefen(this, titel, was);
});

Then(
  /^hat das Archiv «([^»]+)» (bestätigt|verworfen|wiederholt) mit:$/,
  async function (titel, was, tabelle) {
    await aktionPruefen(this, titel, was, tabelle.rowsHash());
  },
);

Then(/^hat das Archiv nichts (?:bestätigt|verworfen)$/, function () {
  assert.deepEqual(
    this.archiv.aktionen.filter((a) => !a.abgelehnt),
    [],
  );
});

Then(/^habe ich dafür höchstens (\d+) Mal getippt$/, function (anzahl) {
  assert.ok(this.tipps <= Number(anzahl), `${this.tipps} Tipps`);
});

Then(
  /^ist beim Eintrag «([^»]+)» das Feld «([^»]+)» als ungültig markiert$/,
  async function (titel, feld) {
    const element = eintrag(this, titel).getByLabel(feld);
    await warten(
      async () => (await element.getAttribute('aria-invalid')) === 'true',
      `«${feld}» nicht ungültig`,
    );
  },
);

Then(/^steht der Fokus auf «([^»]+)»$/, async function (name) {
  await warten(
    () =>
      this.seite.evaluate((n) => {
        const el = document.activeElement;
        const label = el?.id ? document.querySelector(`label[for="${el.id}"]`)?.textContent : null;
        return (label ?? el?.textContent ?? '').replace('*', '').trim() === n;
      }, name),
    `Fokus nicht auf «${name}»`,
  );
});

Then(/^zeigt die Navigation den Eingangskorb-Zähler «(\d+)»$/, async function (anzahl) {
  const zaehler = this.seite.locator('[data-test=eingangskorb-zaehler]');
  await warten(
    async () =>
      (await zaehler.count()) === 1 && (await zaehler.textContent())?.trim() === String(anzahl),
    `Zähler nicht ${anzahl}`,
  );
  const vorgelesen = await this.seite
    .locator('.punkt', { has: zaehler })
    .locator('.nur-screenreader')
    .textContent();
  assert.match(vorgelesen ?? '', new RegExp(anzahl));
});
