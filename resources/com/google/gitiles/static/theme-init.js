(function() {
  try {
    var t = localStorage.getItem('gitiles-theme');
    if (!t) {
      var m = document.cookie.match(/(?:^|; )gitiles-theme=([^;]*)/);
      if (m) t = decodeURIComponent(m[1]);
    }
    if (t === 'dark' || t === 'light') {
      document.documentElement.setAttribute('data-theme', t);
    }
  } catch (e) {}
})();
