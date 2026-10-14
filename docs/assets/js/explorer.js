/* ==========================================================================
   MG4Control : explorateur (une seule maquette collée en haut, pilotée par la liste)
   S'exécute après site.js, qui monte déjà la maquette et relie les cartes.
   ========================================================================== */
(function () {
  'use strict';

  const root = document.documentElement;
  const store = {
    get(k) { try { return localStorage.getItem(k); } catch (e) { return null; } },
    set(k, v) { try { localStorage.setItem(k, v); } catch (e) { /* ignoré */ } }
  };

  function init() {
    const Sim = window.MG4Sim;
    const sim = Sim && Sim.get('sim');
    if (!sim) return;
    const head = document.querySelector('.site-head');
    const stage = document.querySelector('.x-stage');
    const list = document.querySelector('.x-list');
    const bar = document.querySelector('.x-chips');
    const chips = Array.from(bar.querySelectorAll('.x-chip[data-grp]'));
    const groups = Array.from(list.querySelectorAll('.grp'));
    const byId = (id) => document.getElementById(id);
    const pinned = () => !root.hasAttribute('data-unpinned');

    // ── Hauteur occupée en haut de l'écran (en-tête + scène collée) ─────────
    let off = 84;
    function measure() {
      off = head.offsetHeight + (pinned() ? stage.offsetHeight : 0) + 12;
      root.style.setProperty('--x-off', off + 'px');
    }
    measure();
    // Mesure différée : la maquette se redimensionne elle-même dans son propre ResizeObserver.
    if ('ResizeObserver' in window) { const ro = new ResizeObserver(() => requestAnimationFrame(measure)); ro.observe(stage); ro.observe(head); }
    window.addEventListener('resize', measure);

    function scrollToEl(el) {
      const y = el.getBoundingClientRect().top + window.scrollY - off;
      window.scrollTo({ top: Math.max(0, y), behavior: 'smooth' });
    }

    // ── Épingler / détacher la maquette ────────────────────────────────────
    const pin = document.querySelector('.x-pin');
    const syncPin = () => pin.setAttribute('aria-pressed', pinned() ? 'true' : 'false');
    pin.addEventListener('click', () => {
      if (pinned()) root.setAttribute('data-unpinned', ''); else root.removeAttribute('data-unpinned');
      store.set('mg4site.pin', pinned() ? 'on' : 'off');
      syncPin(); measure(); onScroll();
    });
    syncPin();

    // ── Plein écran ────────────────────────────────────────────────────────
    // La scène passe en couche fixe sur toute la fenêtre (marche partout, iPhone compris), et on
    // demande en plus le vrai plein écran du navigateur quand il existe. Le document entier passe
    // en plein écran, pas la seule maquette : le véhicule virtuel doit rester accessible.
    const full = document.querySelector('.x-full');
    const isFull = () => root.hasAttribute('data-sim-full');
    const fsEl = () => document.fullscreenElement || document.webkitFullscreenElement;
    const L = (fr, en) => (root.getAttribute('data-lang') === 'en' ? en : fr);
    let savedY = 0;
    function syncFullLabel() {
      const t = isFull() ? L('Quitter le plein écran (Échap)', 'Leave full screen (Esc)') : L('Afficher la maquette en plein écran', 'Show the mockup full screen');
      full.setAttribute('aria-label', t); full.title = t;
      full.setAttribute('aria-pressed', isFull() ? 'true' : 'false');
      pin.title = pin.getAttribute('aria-label');
    }
    function setFull(on) {
      if (on === isFull()) return;
      if (on) {
        savedY = window.scrollY;
        root.setAttribute('data-sim-full', '');
        const req = root.requestFullscreen || root.webkitRequestFullscreen;
        if (req && !fsEl()) {
          try {
            const p = req.call(root);
            // Sur Android, le plein écran autorise à forcer le paysage (ratio 1280 × 480).
            const lock = () => { try { screen.orientation.lock('landscape').catch(() => {}); } catch (e) { /* non géré */ } };
            if (p && p.then) p.then(lock).catch(() => {}); else lock();
          } catch (e) { /* refusé : la couche fixe suffit */ }
        }
      } else {
        root.removeAttribute('data-sim-full');
        try { screen.orientation.unlock(); } catch (e) { /* non géré */ }
        if (fsEl()) { const exit = document.exitFullscreen || document.webkitExitFullscreen; try { const p = exit.call(document); if (p && p.catch) p.catch(() => {}); } catch (e) { /* déjà sorti */ } }
      }
      // Taille de la maquette recalculée tout de suite : si elle ne changeait qu'à l'image suivante,
      // la scène collée changerait de hauteur après coup et la page sauterait de quelques pixels.
      sim.fit();
      if (!on) window.scrollTo({ top: savedY, behavior: 'instant' });
      syncFullLabel(); measure(); onScroll();
    }
    full.addEventListener('click', () => setFull(!isFull()));
    // Échap quitte d'abord le plein écran du navigateur : on suit, pour ne pas rester dans la couche.
    const onFsChange = () => { if (!fsEl() && isFull()) setFull(false); };
    document.addEventListener('fullscreenchange', onFsChange);
    document.addEventListener('webkitfullscreenchange', onFsChange);
    document.addEventListener('keydown', (e) => { if (e.key === 'Escape' && isFull() && !fsEl()) setFull(false); });
    syncFullLabel();

    // ── Firmware ───────────────────────────────────────────────────────────
    const fwSel = document.querySelector('[data-fw-select]');
    fwSel.addEventListener('change', () => Sim.setFirmware(fwSel.value));
    const syncFw = () => { if (fwSel.value !== Sim.firmware()) fwSel.value = Sim.firmware(); };
    Sim.onChange(syncFw); syncFw();

    // ── Filtre « Nouveautés de la version » ────────────────────────────────────────
    list.querySelectorAll('.pt').forEach((pt) => { if (pt.classList.contains('is-new') || pt.querySelector('.badge-new')) pt.classList.add('has-new'); });
    groups.forEach((g) => {
      if (g.querySelector('.pt.has-new')) {
        g.classList.add('has-new');
        const c = bar.querySelector('[data-grp="' + g.id + '"]'); if (c) c.classList.add('has-new');
      }
    });
    const newBtn = bar.querySelector('[data-new-filter]');
    function setOnlyNew(on) {
      list.classList.toggle('only-new', on);
      bar.classList.toggle('only-new', on);
      newBtn.setAttribute('aria-pressed', on ? 'true' : 'false');
      document.querySelectorAll('.wn-all').forEach((b) => b.setAttribute('aria-pressed', on ? 'true' : 'false'));
      onScroll();
    }
    const onlyNew = () => list.classList.contains('only-new');
    newBtn.addEventListener('click', () => { setOnlyNew(!onlyNew()); scrollToEl(list); });
    document.querySelectorAll('[data-new-link]').forEach((a) => a.addEventListener('click', (e) => {
      e.preventDefault();
      setOnlyNew(true);
      scrollToEl(byId('fonctionnalites'));
    }));

    // ── Aller à une fonctionnalité depuis l'accueil ───────────────────────
    document.querySelectorAll('[data-feature]').forEach((a) => a.addEventListener('click', (e) => {
      const card = byId(a.getAttribute('data-feature')); if (!card) return;
      e.preventDefault();
      if (onlyNew() && !card.classList.contains('has-new')) setOnlyNew(false);
      scrollToEl(card);
      card.click();
    }));

    // ── Barre de thèmes : défilement de la liste + écran de la maquette ────
    function showGroup(g) {
      const go = g.getAttribute('data-go'), demo = g.getAttribute('data-demo');
      sim.ui.dialog = null;
      if (sim.ui.overlay) sim.closeOverlay();
      if (go) { const [scr, tab] = go.split(':'); sim.go(scr, tab); }
      if (demo) sim.demo(demo);
    }
    chips.forEach((c) => c.addEventListener('click', (e) => {
      e.preventDefault();
      const g = byId(c.getAttribute('data-grp')); if (!g) return;
      if (onlyNew() && !g.classList.contains('has-new')) setOnlyNew(false);
      scrollToEl(g);
      showGroup(g);
    }));

    // ── Liens ordinaires vers un thème ou une carte masqués par le filtre ──
    // Le filtre des nouveautés retire de la page les thèmes qui n'en ont pas : un lien « #api »
    // (en-tête, menu mobile, renvois dans le texte) n'avait alors plus de cible et la page ne
    // bougeait pas. Les puces et les cartes levaient déjà le filtre ; les liens aussi désormais.
    const hiddenTarget = (hash) => {
      if (!onlyNew() || !hash || hash.length < 2) return null;
      let el = null;
      try { el = byId(decodeURIComponent(hash.slice(1))); } catch (e) { /* ancre mal formée */ }
      return el && list.contains(el) && el.offsetParent === null ? el : null;
    };
    document.addEventListener('click', (e) => {
      const a = e.target.closest ? e.target.closest('a[href^="#"]') : null;
      // Le filtre est levé avant l'action par défaut : le navigateur trouve la cible et y défile.
      if (a && hiddenTarget(a.getAttribute('href'))) setOnlyNew(false);
    });
    // Retour arrière, ou ancre saisie dans la barre d'adresse.
    window.addEventListener('hashchange', () => {
      const el = hiddenTarget(location.hash);
      if (el) { setOnlyNew(false); scrollToEl(el); }
    });

    // Groupe en cours de lecture = dernier dont le titre est passé sous la scène.
    let current = null;
    function spy() {
      let cur = null;
      for (const g of groups) {
        if (g.offsetParent === null) continue;
        if (g.getBoundingClientRect().top <= off + 60) cur = g; else break;
      }
      if (cur !== current) {
        current = cur;
        chips.forEach((c) => c.classList.toggle('on', !!cur && c.getAttribute('data-grp') === cur.id));
        const on = bar.querySelector('.x-chip.on');
        if (on) {
          const l = on.offsetLeft - bar.offsetLeft, r = l + on.offsetWidth;
          if (l < bar.scrollLeft || r > bar.scrollLeft + bar.clientWidth - 24) bar.scrollTo({ left: Math.max(0, l - 40), behavior: 'smooth' });
        }
      }
      updateJump();
    }
    let ticking = false;
    function onScroll() {
      if (ticking) return;
      ticking = true;
      requestAnimationFrame(() => {
        ticking = false;
        const r = stage.getBoundingClientRect();
        stage.classList.toggle('stuck', pinned() && r.top <= head.offsetHeight + 1 && list.getBoundingClientRect().top < r.bottom);
        spy();
      });
    }
    window.addEventListener('scroll', onScroll, { passive: true });

    // ── Maquette → explications : « Voir les explications de cet écran » ──
    // Écrans partagés par plusieurs thèmes : on ne propose rien si l'un d'eux est déjà à l'écran.
    function groupsForView(u) {
      const o = u.overlay && u.overlay.type, d = u.dialog && u.dialog.type;
      if (o === 'picker') return ['selecteur'];
      if (o === 'confirm') return ['automatisation'];
      if (o === 'update' || d === 'update') return ['mises-a-jour'];
      if (d === 'powerOff') return ['verrou'];
      if (d === 'apiConfirm') return ['api'];
      if (d === 'restore') return ['profils'];
      if (d && /cal|win/i.test(d)) return ['vitres'];
      switch (u.screen) {
        case 'dashboard': return [['conduite', 'securite', 'confort'][u.tabs.dash] || 'conduite'];
        case 'profiles': case 'profileEdit': return ['profils'];
        case 'shortcuts': return (u.tabs.sc === 'adv' || u.tabs.sc === 'list') ? ['raccourcis-avances'] : ['raccourcis'];
        case 'automation': return u.winOpen ? ['vitres', 'automatisation'] : ['automatisation', 'vitres'];
        case 'stats': return ['statistiques'];
        case 'audio': return ['audio'];
        case 'settings': return [['reglages'], ['reglages'], ['reglages', 'garage', 'verrou', 'api'], ['reglages', 'mises-a-jour']][u.tabs.set] || ['reglages'];
      }
      return [];
    }
    const jump = document.querySelector('.x-jump');
    let jumpTarget = null;
    function updateJump() {
      const ids = groupsForView(sim.ui);
      const inView = current && ids.includes(current.id);
      jumpTarget = !inView && ids.length ? byId(ids[0]) : null;
      // Rien à proposer tant que la lecture n'a pas atteint la liste (introduction, maquette seule).
      if (!jumpTarget || !pinned() || !current) { jump.hidden = true; return; }
      const chip = bar.querySelector('[data-grp="' + jumpTarget.id + '"]');
      const lang = root.getAttribute('data-lang') === 'en' ? 'en' : 'fr';
      const name = chip ? chip.textContent.trim() : '';
      jump.textContent = '↓ ' + (lang === 'en' ? 'About this screen: ' : 'Explications de cet écran : ') + name;
      jump.hidden = false;
    }
    jump.addEventListener('click', () => {
      if (!jumpTarget) return;
      if (onlyNew() && !jumpTarget.classList.contains('has-new')) setOnlyNew(false);
      scrollToEl(jumpTarget);
    });

    // Après chaque rendu de la maquette : bouton de retour aux explications, et sur
    // mobile (maquette plus large que l'écran) on fait glisser vers l'élément surligné.
    const render = sim.render;
    sim.render = function () { render.apply(this, arguments); updateJump(); };
    const applyHl = sim.applyHl;
    sim.applyHl = function () {
      applyHl.apply(this, arguments);
      if (!this.el.classList.contains('sim-scrolls')) return;
      const n = this.app.querySelector('.hl-on'); if (!n) return;
      const vp = this.viewport, a = n.getBoundingClientRect(), b = vp.getBoundingClientRect();
      if (a.left < b.left || a.right > b.right) vp.scrollTo({ left: vp.scrollLeft + a.left - b.left - (b.width - Math.min(a.width, b.width)) / 2, behavior: 'smooth' });
    };

    onScroll();
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init); else init();
})();
