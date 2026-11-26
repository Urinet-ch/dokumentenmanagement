// Das Inhaltsverzeichnis der Hilfe-Leiste ist mit einem gewählten Abschnitt zugeklappt (Spec 07, Req 4.7);
// die Schritte klappen es auf, bevor sie Abschnitte wählen oder zählen. Der Zustand ändert sich nach jeder Wahl
// asynchron, darum wird er wiederholt geprüft statt einmal gelesen.
export async function verzeichnisOeffnen(leiste) {
  const knopf = leiste.getByRole('button', { name: /^Abschnitte/ });
  const nav = leiste.getByRole('navigation', { name: 'Inhalt der Anleitung' });
  for (let versuch = 0; versuch < 5; versuch += 1) {
    if (await nav.isVisible()) return nav;
    await knopf.waitFor({ state: 'visible' });
    if ((await knopf.getAttribute('aria-expanded')) === 'false') await knopf.click();
    try {
      await nav.waitFor({ state: 'visible', timeout: 1500 });
      return nav;
    } catch {
      // erneut versuchen: der Zustand kann sich nach der letzten Wahl noch geändert haben
    }
  }
  await nav.waitFor({ state: 'visible' });
  return nav;
}
