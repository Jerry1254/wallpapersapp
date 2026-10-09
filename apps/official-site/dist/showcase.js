(() => {
  const shop = document.getElementById('wallpapers');
  const dialog = document.getElementById('purchase-dialog');
  if (!shop || !dialog) return;

  const cards = [...shop.querySelectorAll('.wallpaper-card')];
  const filters = [...shop.querySelectorAll('[data-filter]')];
  const count = document.getElementById('shop-count');
  let purchaseButton;

  filters.forEach(button => {
    button.addEventListener('click', () => {
      const category = button.dataset.filter;
      filters.forEach(filter => {
        const active = filter === button;
        filter.classList.toggle('is-active', active);
        filter.setAttribute('aria-pressed', String(active));
      });
      cards.forEach(card => {
        card.hidden = category !== 'all' && card.dataset.category !== category;
      });
      count.textContent = '共 ' + cards.filter(card => !card.hidden).length + ' 款';
    });
  });

  shop.querySelectorAll('.buy-button').forEach(button => {
    button.addEventListener('click', () => {
      const card = button.closest('.wallpaper-card');
      const cover = card.querySelector('img');
      document.getElementById('purchase-wallpaper').textContent = card.querySelector('h3').textContent;
      document.getElementById('purchase-price').textContent = '展示价格 ' + card.querySelector('.wallpaper-price').textContent;
      const preview = document.getElementById('purchase-cover');
      preview.src = cover.getAttribute('src');
      preview.alt = cover.alt;
      purchaseButton = button;
      dialog.showModal();
      document.body.classList.add('purchase-open');
    });
  });

  dialog.querySelectorAll('.dialog-close, .dialog-confirm').forEach(button => {
    button.addEventListener('click', () => dialog.close());
  });
  dialog.addEventListener('keydown', event => {
    if (event.key !== 'Tab') return;
    const controls = [...dialog.querySelectorAll('button, a[href]')];
    const first = controls[0], last = controls[controls.length - 1];
    if (event.shiftKey && document.activeElement === first) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault();
      first.focus();
    }
  });
  let backdropPressed = false;
  const outside = event => {
    const rect = dialog.getBoundingClientRect();
    return event.clientX < rect.left || event.clientX > rect.right ||
      event.clientY < rect.top || event.clientY > rect.bottom;
  };
  dialog.addEventListener('pointerdown', event => { backdropPressed = outside(event); });
  dialog.addEventListener('click', event => {
    if (backdropPressed && outside(event)) dialog.close();
    backdropPressed = false;
  });
  dialog.addEventListener('close', () => {
    document.body.classList.remove('purchase-open');
    purchaseButton?.focus({ preventScroll: true });
  });
})();
