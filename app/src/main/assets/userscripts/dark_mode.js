(function () {
  if (document.getElementById('gravity-dark-style')) return;
  var style = document.createElement('style');
  style.id = 'gravity-dark-style';
  style.textContent = [
    'html { filter: invert(1) hue-rotate(180deg) !important; background: #fff !important; }',
    'img, video, picture, svg, canvas, iframe { filter: invert(1) hue-rotate(180deg) !important; }'
  ].join('\n');
  document.documentElement.appendChild(style);
})();
