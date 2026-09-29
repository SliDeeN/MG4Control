/* ==========================================================================
   MG4Control : comportements du site (langue, thème, navigation, widgets)
   ========================================================================== */
(function () {
  'use strict';

  const root = document.documentElement;
  const store = {
    get(k) { try { return localStorage.getItem(k); } catch (e) { return null; } },
    set(k, v) { try { if (v == null) localStorage.removeItem(k); else localStorage.setItem(k, v); } catch (e) { /* ignoré */ } }
  };
  const lang = () => root.getAttribute('data-lang') || 'fr';
  const L = (fr, en) => (lang() === 'en' ? en : fr);
  const FW6 = ['SWI133', 'SWI132', 'SWI68', 'SWI69', 'SWI131', 'SWI165'];

  // ── Langue ────────────────────────────────────────────────────────────────
  function initialLang() {
    const q = new URLSearchParams(location.search).get('lang');
    if (q === 'fr' || q === 'en') return q;
    const saved = store.get('mg4site.lang');
    if (saved === 'fr' || saved === 'en') return saved;
    return (navigator.language || 'fr').toLowerCase().startsWith('fr') ? 'fr' : 'en';
  }
  function setLang(l, persist) {
    root.setAttribute('data-lang', l);
    root.setAttribute('lang', l);
    if (persist) store.set('mg4site.lang', l);
    document.title = root.getAttribute('data-title-' + l) || document.title;
    const md = document.querySelector('meta[name="description"]');
    if (md && md.getAttribute('data-' + l)) md.setAttribute('content', md.getAttribute('data-' + l));
    document.querySelectorAll('[data-lang-btn]').forEach((b) => b.setAttribute('aria-pressed', b.getAttribute('data-lang-btn') === l ? 'true' : 'false'));
    document.querySelectorAll('[data-aria-fr]').forEach((n) => n.setAttribute('aria-label', n.getAttribute('data-aria-' + l)));
    if (window.MG4Sim) window.MG4Sim.refresh();
    refreshWidgets();
  }

  // ── Thème ─────────────────────────────────────────────────────────────────
  const mq = window.matchMedia ? window.matchMedia('(prefers-color-scheme: dark)') : null;
  const effectiveTheme = () => root.getAttribute('data-theme') || (mq && mq.matches ? 'dark' : 'light');
  function setTheme(t, persist) {
    if (t) root.setAttribute('data-theme', t); else root.removeAttribute('data-theme');
    if (persist) store.set('mg4site.theme', t);
    document.querySelectorAll('[data-theme-btn]').forEach((b) => {
      b.setAttribute('aria-pressed', effectiveTheme() === 'dark' ? 'true' : 'false');
    });
    if (window.MG4Sim) window.MG4Sim.refresh();
  }
  if (mq && mq.addEventListener) mq.addEventListener('change', () => { if (!root.getAttribute('data-theme') && window.MG4Sim) window.MG4Sim.refresh(); });

  // ── Widgets dépendants du firmware ────────────────────────────────────────
  function refreshWidgets() {
    const fw = window.MG4Sim ? window.MG4Sim.firmware() : 'SWI133';
    document.querySelectorAll('.fw-pick button').forEach((b) => b.setAttribute('aria-pressed', b.getAttribute('data-fw') === fw ? 'true' : 'false'));
    document.querySelectorAll('.fw-tags[data-fw]').forEach((box) => {
      const yes = box.getAttribute('data-fw').split(/[ ,]+/);
      box.innerHTML = FW6.map((g) => '<span class="fw-tag' + (yes.includes(g) ? ' yes' : '') + (g === fw ? ' cur' : '') + '" title="' +
        (yes.includes(g) ? L('Disponible', 'Available') : L('Non disponible', 'Not available')) + '">' + g + '</span>').join('');
    });
    document.querySelectorAll('.matrix [data-col]').forEach((c) => c.classList.toggle('col-on', c.getAttribute('data-col') === fw));
    document.querySelectorAll('[data-fw-name]').forEach((n) => { n.textContent = fw; });
    precedence.update();
    gate.update();
  }

  // ── Précédence des déclencheurs au démarrage ─────────────────────────────
  const precedence = {
    el: null,
    mount(el) { this.el = el; el.addEventListener('change', () => this.update()); this.update(); },
    update() {
      const el = this.el; if (!el) return;
      const v = (n) => { const i = el.querySelector('[name="' + n + '"]'); return !!(i && i.checked); };
      // Candidats dans l'ordre de MG4ControlService.applyDefaultProfileOnIgnition.
      let winner = 'none', ask = false;
      if (v('auto')) {
        if (v('manual')) winner = 'manual';
        else if (v('temp') && !v('tempconfirm')) winner = 'temp';
        else {
          ask = v('temp') && v('tempconfirm');
          winner = v('bt') ? 'bt' : v('def') ? 'def' : 'none';
        }
      }
      el.querySelectorAll('.flow-step').forEach((s) => {
        const k = s.getAttribute('data-step');
        s.classList.remove('win', 'skip', 'ask');
        if (ask && k === 'temp') s.classList.add('ask');
        else if (k === winner && !ask) s.classList.add('win');
        else if (k === winner && ask) s.classList.add('ask');
        else s.classList.add('skip');
      });
      const out = el.querySelector('.flow-out');
      const msg = {
        none: L('Aucun profil n\'est appliqué.', 'No profile is applied.'),
        manual: L('Le choix manuel récent (popup volant ou application) est ré-appliqué.', 'The recent manual choice (steering popup or app) is re-applied.'),
        temp: L('Le profil de l\'automatisation température est appliqué directement.', 'The temperature automation profile is applied directly.'),
        bt: L('Le profil associé au téléphone connecté est appliqué.', 'The profile tied to the connected phone is applied.'),
        def: L('Le profil par défaut est appliqué.', 'The default profile is applied.')
      };
      if (!v('auto')) out.textContent = L('Mode Garage actif : rien n\'est appliqué au démarrage (ni profil, ni Bluetooth, ni automatisation).', 'Garage mode on: nothing is applied at start-up (no profile, Bluetooth or automation).');
      else if (ask) out.textContent = L('La popup OUI / NON s\'affiche 8 s. OUI → profil température ; NON ou délai écoulé → on retombe sur Bluetooth puis profil par défaut (', 'The YES / NO popup is shown for 8 s. YES → temperature profile; NO or timeout → falls back to Bluetooth then default profile (') +
        (v('bt') ? L('ici : Bluetooth', 'here: Bluetooth') : v('def') ? L('ici : profil par défaut', 'here: default profile') : L('ici : aucun', 'here: none')) + ').';
      else out.textContent = msg[winner];
    }
  };

  // ── Verrou de vitesse ─────────────────────────────────────────────────────
  const gate = {
    el: null,
    mount(el) { this.el = el; el.addEventListener('input', () => this.update()); el.addEventListener('change', () => this.update()); this.update(); },
    update() {
      const el = this.el; if (!el) return;
      const on = el.querySelector('[name=gon]').checked;
      const max = +el.querySelector('[name=gmax]').value;
      const unreadable = el.querySelector('[name=gunk]').checked;
      const speed = +el.querySelector('[name=gspeed]').value;
      el.querySelector('[data-o=gmax]').textContent = max + ' km/h';
      el.querySelector('[data-o=gspeed]').textContent = unreadable ? L('illisible', 'unreadable') : speed + ' km/h';
      el.querySelector('[name=gspeed]').disabled = unreadable;
      const res = el.querySelector('.gate-res');
      let ok, title, sub;
      if (!on) { ok = true; title = L('Écriture autorisée', 'Write allowed'); sub = L('Verrou désactivé (réglage par défaut) : aucune restriction, API externe comprise.', 'Lock disabled (default): no restriction, external API included.'); }
      else if (unreadable) { ok = false; title = L('Écriture refusée', 'Write refused'); sub = L('Vitesse illisible = refus (« fail closed ») : une vitesse inconnue peut être n\'importe quelle vitesse.', 'Unreadable speed = refusal (fail closed): an unknown speed could be any speed.'); }
      else if (speed <= max) { ok = true; title = L('Écriture autorisée', 'Write allowed'); sub = L('Vitesse ≤ limite (borne incluse).', 'Speed ≤ limit (inclusive).'); }
      else { ok = false; title = L('Écriture refusée', 'Write refused'); sub = L('Toast : « Réglage de conduite refusé : ', 'Toast: “Driving setting refused: ') + speed + ' km/h (' + L('limite ', 'limit ') + max + ' km/h). ' + L('Ralentissez pour modifier. »', 'Slow down to change it.”'); }
      res.className = 'gate-res ' + (ok ? 'ok' : 'no');
      res.innerHTML = (ok ? '✓ ' : '✕ ') + title + '<small>' + sub + '</small>';
    }
  };

  // ── Initialisation ────────────────────────────────────────────────────────
  function init() {
    setLang(initialLang(), false);
    const savedTheme = store.get('mg4site.theme');
    setTheme(savedTheme === 'dark' || savedTheme === 'light' ? savedTheme : null, false);

    document.querySelectorAll('[data-lang-btn]').forEach((b) => b.addEventListener('click', () => setLang(b.getAttribute('data-lang-btn'), true)));
    document.querySelectorAll('[data-theme-btn]').forEach((b) => b.addEventListener('click', () => setTheme(effectiveTheme() === 'dark' ? 'light' : 'dark', true)));

    // Menu mobile
    const menuBtn = document.querySelector('.menu-btn'), mnav = document.querySelector('.mobile-nav');
    if (menuBtn && mnav) {
      menuBtn.addEventListener('click', () => { const o = mnav.classList.toggle('open'); menuBtn.setAttribute('aria-expanded', o ? 'true' : 'false'); });
      mnav.addEventListener('click', (e) => { if (e.target.closest('a')) { mnav.classList.remove('open'); menuBtn.setAttribute('aria-expanded', 'false'); } });
    }

    // Maquettes
    const Sim = window.MG4Sim;
    document.querySelectorAll('[data-sim]').forEach((el) => {
      Sim.mount(el, {
        screen: el.getAttribute('data-screen') || 'dashboard',
        tab: el.getAttribute('data-tab'),
        settingsTab: el.getAttribute('data-settings-tab'),
        scTab: el.getAttribute('data-sc-tab'),
        statsTab: el.getAttribute('data-stats-tab'),
        winOpen: el.hasAttribute('data-win-open')
      });
    });
    const dock = document.querySelector('.dock');
    let dockApi = null;
    if (dock) dockApi = Sim.mountDock(dock);
    document.querySelectorAll('[data-open-dock]').forEach((b) => b.addEventListener('click', () => dockApi && dockApi.open()));
    document.querySelectorAll('[data-presslab]').forEach((el) => Sim.mountPressLab(el));
    document.querySelectorAll('[data-api-console]').forEach((el) => Sim.mountApi(el));

    // Sélecteur de firmware (hero)
    document.querySelectorAll('.fw-pick').forEach((box) => box.addEventListener('click', (e) => {
      const b = e.target.closest('[data-fw]'); if (b) Sim.setFirmware(b.getAttribute('data-fw'));
    }));
    Sim.onChange(refreshWidgets);

    // Points d'explication ↔ maquette : survol = surbrillance, clic = navigation
    const simFor = (node) => {
      const id = node.getAttribute('data-sim-target') || (node.closest('[data-sim-scope]') || node.closest('section')).querySelector('[data-sim]').id;
      return Sim.get(id);
    };
    // Montre le résultat dans la maquette : la ramène à l'écran si elle n'y est pas (maquette
    // détachée, lecture plus bas) et fait clignoter l'élément visé pendant 3,5 s.
    let hlTimer = null;
    const reveal = (s, key) => {
      if (key) s.highlight(key);
      const r = s.el.getBoundingClientRect();
      if (r.top < 70 || r.bottom > window.innerHeight) s.el.scrollIntoView({ behavior: 'smooth', block: 'center' });
      clearTimeout(hlTimer);
      if (key) hlTimer = setTimeout(() => { document.querySelectorAll('.pt.active').forEach((p) => p.classList.remove('active')); s.highlight(null); }, 3500);
    };
    document.querySelectorAll('[data-hl-key]').forEach((pt) => {
      pt.setAttribute('tabindex', '0');
      pt.setAttribute('role', 'button');
      const on = () => { const s = simFor(pt); if (s) s.highlight(pt.getAttribute('data-hl-key')); };
      const off = () => { const s = simFor(pt); if (s && !pt.classList.contains('active')) s.highlight(null); };
      const go = () => {
        const s = simFor(pt); if (!s) return;
        const target = pt.getAttribute('data-go');
        if (target) { const [scr, tab] = target.split(':'); s.go(scr, tab); }
        const demo = pt.getAttribute('data-demo'); if (demo) s.demo(demo);
        document.querySelectorAll('.pt.active').forEach((p) => p.classList.remove('active'));
        pt.classList.add('active');
        reveal(s, pt.getAttribute('data-hl-key'));
      };
      pt.addEventListener('mouseenter', on);
      pt.addEventListener('mouseleave', off);
      pt.addEventListener('focus', on);
      pt.addEventListener('blur', off);
      // « En savoir plus » (details) sert à lire : il déplie le texte sans toucher à la maquette.
      pt.addEventListener('click', (e) => { if (!e.target.closest('a, button, details')) go(); });
      pt.addEventListener('keydown', (e) => { if (e.target === pt && (e.key === 'Enter' || e.key === ' ')) { e.preventDefault(); go(); } });
    });

    // Boutons de démonstration (« Essayer : … ») : même retour visuel que les cartes, l'élément
    // indiqué par data-hl clignote. Seul le véhicule virtuel, qui flotte déjà à l'écran, n'y touche pas.
    document.querySelectorAll('[data-demo-btn]').forEach((b) => b.addEventListener('click', () => {
      const s = simFor(b); if (!s) return;
      const d = b.getAttribute('data-demo-btn');
      if (d === 'dock') { if (dockApi) dockApi.open(); return; }
      if (d.startsWith('go:')) { const [, scr, tab] = d.split(':'); s.go(scr, tab); }
      else if (d === 'ignition') Sim.ignition();
      else if (d === 'leave') Sim.leaveCar();
      else s.demo(d);
      reveal(s, b.getAttribute('data-hl'));
    }));

    // Widgets
    const pr = document.querySelector('[data-precedence]'); if (pr) precedence.mount(pr);
    const gt = document.querySelector('[data-gate]'); if (gt) gate.mount(gt);

    // Surlignage de la section courante dans la navigation
    const links = Array.from(document.querySelectorAll('.nav a[href^="#"]'));
    const secs = links.map((a) => document.querySelector(a.getAttribute('href'))).filter(Boolean);
    if ('IntersectionObserver' in window && secs.length) {
      const io = new IntersectionObserver((es) => {
        es.forEach((e) => {
          if (!e.isIntersecting) return;
          links.forEach((a) => a.classList.toggle('on', a.getAttribute('href') === '#' + e.target.id));
        });
      }, { rootMargin: '-45% 0px -50% 0px' });
      secs.forEach((s) => io.observe(s));
    }

    // Apparition progressive, jamais bloquante : un contrôle au défilement double
    // l'IntersectionObserver, et tout est révélé au bout de 2,5 s quoi qu'il arrive.
    const rev = Array.from(document.querySelectorAll('.reveal'));
    const show = (r) => r.classList.add('in');
    const check = () => {
      const h = window.innerHeight;
      rev.forEach((r) => { if (!r.classList.contains('in')) { const b = r.getBoundingClientRect(); if (b.top < h * 0.95 && b.bottom > 0) show(r); } });
    };
    if ('IntersectionObserver' in window) {
      const io2 = new IntersectionObserver((es) => es.forEach((e) => { if (e.isIntersecting) { show(e.target); io2.unobserve(e.target); } }), { rootMargin: '0px 0px -5% 0px' });
      rev.forEach((r) => io2.observe(r));
    }
    let ticking = false;
    window.addEventListener('scroll', () => { if (!ticking) { ticking = true; requestAnimationFrame(() => { ticking = false; check(); }); } }, { passive: true });
    check();
    setTimeout(() => rev.forEach(show), 2500);

    const y = document.querySelector('[data-year]'); if (y) y.textContent = new Date().getFullYear();
    refreshWidgets();
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init); else init();
})();
