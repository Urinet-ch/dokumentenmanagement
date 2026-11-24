// Klick auf einen Link, der kurz neu gerendert werden kann (Hilfe-Leiste: Einblenden des Drawers, @defer, Wechsel der
// Rolle). Der Locator wird bei jedem Versuch neu aufgelöst; nach einem Detach oder Timeout höchstens zwei Wiederholungen.
export async function stabilKlicken(link, versuche = 3) {
  for (let versuch = 1; ; versuch++) {
    try {
      await link.waitFor({ state: 'visible', timeout: 5_000 });
      await link.click({ timeout: 5_000 });
      return;
    } catch (fehler) {
      if (versuch >= versuche) throw fehler;
    }
  }
}
