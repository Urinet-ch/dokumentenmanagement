document.addEventListener('DOMContentLoaded', () => {
  const form = document.getElementById('urinet-archiv-formular');
  if (!form) return;
  const meldung = document.getElementById('urinet-archiv-meldung');
  const seite = new URL(window.location.href);
  if (seite.searchParams.get('urinet_gespeichert') === '1') {
    meldung.textContent = 'Die Einstellungen wurden gespeichert.';
    seite.searchParams.delete('urinet_gespeichert');
    window.history.replaceState(null, '', seite);
  }
  form.addEventListener('submit', async (event) => {
    event.preventDefault();
    const daten = {};
    for (const [name, wert] of new FormData(form)) {
      if (name === '_wpnonce') daten[name] = wert;
      const feld = /^urinet_archiv_einstellungen\[([a-z_]+)\]$/.exec(name);
      if (feld) daten[feld[1]] = wert;
    }
    const knopf = form.querySelector('[type=submit]');
    knopf.disabled = true;
    meldung.textContent = '';
    const abbruch = new AbortController();
    const zeitgrenze = setTimeout(() => abbruch.abort(), 15000);
    try {
      const antwort = await fetch(form.action, {
        method: 'POST',
        credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(daten),
        signal: abbruch.signal,
      });
      const ergebnis = await antwort.json();
      if (!antwort.ok || !ergebnis.success) {
        meldung.textContent = ergebnis.data?.message || 'Die Einstellungen konnten nicht gespeichert werden. Bitte versuchen Sie es erneut.';
        return;
      }
      seite.searchParams.set('urinet_gespeichert', '1');
      window.location.assign(seite);
    } catch (fehler) {
      meldung.textContent = 'Das Speichern wurde nicht bestätigt. Bitte laden Sie die Seite neu und prüfen Sie die Einstellungen.';
    } finally {
      clearTimeout(zeitgrenze);
      knopf.disabled = false;
    }
  });
});
