document.addEventListener('change', function (ereignis) {
  if (!ereignis.target.matches('[data-urinet-art]')) {
    return;
  }

  const formular = ereignis.target.closest('form');
  formular.querySelectorAll('[data-urinet-zusatz-art]').forEach(function (gruppe) {
    const aktiv = gruppe.dataset.urinetZusatzArt === ereignis.target.value;
    gruppe.hidden = !aktiv;
    gruppe.disabled = !aktiv;
  });
});
