(function () {
  var selectors = [
    '[id*="cookie" i]', '[class*="cookie" i]',
    '[id*="consent" i]', '[class*="consent" i]',
    '[id*="gdpr" i]', '[class*="gdpr" i]'
  ];
  var style = document.createElement('style');
  style.textContent = selectors.join(',') + ' { display: none !important; }';
  document.documentElement.appendChild(style);
  // Also restore page scroll in case the banner locked it
  document.documentElement.style.overflow = 'auto';
  document.body && (document.body.style.overflow = 'auto');
})();
