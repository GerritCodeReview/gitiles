(function() {
  function getTheme() {
    try {
      const t = localStorage.getItem('gitiles-theme');
      if (t) {
        return t;
      }
    } catch (e) {}
    const m = document.cookie.match(/(?:^|; )gitiles-theme=([^;]*)/);
    return m ? decodeURIComponent(m[1]) : 'auto';
  }

  function applyTheme(theme) {
    if (theme === 'dark' || theme === 'light') {
      document.documentElement.setAttribute('data-theme', theme);
    } else {
      document.documentElement.removeAttribute('data-theme');
    }
  }

  function updateButton(btn, theme) {
    const icon = btn.querySelector('.Header-themeIcon');
    const label = btn.querySelector('.Header-themeLabel');
    if (theme === 'dark') {
      if (icon) {
        icon.innerHTML = '&#9789;';
      }
      if (label) {
        label.textContent = 'Dark';
      }
      btn.title = 'Theme: Dark (click to switch to Light)';
    } else if (theme === 'light') {
      if (icon) {
        icon.innerHTML = '&#9788;';
      }
      if (label) {
        label.textContent = 'Light';
      }
      btn.title = 'Theme: Light (click to switch to Auto)';
    } else {
      if (icon) {
        icon.innerHTML = '&#9681;';
      }
      if (label) {
        label.textContent = 'Auto';
      }
      btn.title = 'Theme: Auto (click to switch to Dark)';
    }
  }

  function initToggle() {
    const btn = document.getElementById('gitiles-theme-toggle');
    if (!btn) {
      return;
    }
    const current = getTheme();
    updateButton(btn, current);
    btn.onclick = function() {
      const cur = getTheme();
      const next = (cur === 'auto') ? 'dark' : ((cur === 'dark') ? 'light' : 'auto');
      applyTheme(next);
      try {
        localStorage.setItem('gitiles-theme', next);
      } catch (e) {}
      document.cookie = 'gitiles-theme=' + encodeURIComponent(next) + ';path=/;max-age=31536000;SameSite=Lax';
      updateButton(btn, next);
    };
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', initToggle);
  } else {
    initToggle();
  }
})();
