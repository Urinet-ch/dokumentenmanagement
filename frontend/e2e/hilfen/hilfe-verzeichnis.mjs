// Das Inhaltsverzeichnis der Hilfe-Leiste ist beim Öffnen zugeklappt (Spec 07, Req 4.7);
// die Schritte klappen es auf, bevor sie Abschnitte wählen oder zählen.
export async function verzeichnisOeffnen(leiste) {
  const knopf = leiste.getByRole('button', { name: /^Abschnitte/ });
  if ((await knopf.getAttribute('aria-expanded')) !== 'true') await knopf.click();
  const nav = leiste.getByRole('navigation', { name: 'Inhalt der Anleitung' });
  await nav.waitFor({ state: 'visible' });
  return nav;
}
