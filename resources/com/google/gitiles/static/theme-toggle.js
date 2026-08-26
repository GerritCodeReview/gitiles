// Copyright (C) 2026 Google Inc. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

(function() {
  function getTheme() {
    try {
      const t = localStorage.getItem('gitiles-theme');
      if (t) {
        return t;
      }
    } catch (e) {}
    try {
      const m = document.cookie.match(/(?:^|; )gitiles-theme=([^;]*)/);
      if (m) {
        return decodeURIComponent(m[1]);
      }
    } catch (e) {}
    return 'auto';
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
        icon.textContent = '\u263E';
      }
      if (label) {
        label.textContent = 'Dark';
      }
      btn.title = 'Theme: Dark (click to switch to Light)';
    } else if (theme === 'light') {
      if (icon) {
        icon.textContent = '\u2600';
      }
      if (label) {
        label.textContent = 'Light';
      }
      btn.title = 'Theme: Light (click to switch to Auto)';
    } else {
      if (icon) {
        icon.textContent = '\u25D1';
      }
      if (label) {
        label.textContent = 'Auto';
      }
      btn.title = 'Theme: Auto (click to switch to Dark)';
    }
    btn.setAttribute('aria-label', btn.title);
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
      try {
        document.cookie = 'gitiles-theme=' + encodeURIComponent(next) + ';path=/;max-age=31536000;SameSite=Lax';
      } catch (e) {}
      updateButton(btn, next);
    };
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', initToggle);
  } else {
    initToggle();
  }
})();
