/* ==========================================================================
   MG4Control : simulateur interactif
   ---------------------------------------------------------------------------
   Reproduit l'interface de l'application (écran 1280 × 480) et son
   comportement, d'après le code source (app/src/main/java/com/mg4/control).
   Toutes les maquettes de la page partagent UN véhicule virtuel : ce que vous
   réglez dans l'une se retrouve dans les autres.
   Les libellés viennent directement des strings.xml de l'app (strings.js).
   ========================================================================== */
(function () {
  'use strict';

  const STR = window.MG4_STRINGS || {};
  const FIRMWARES = ['SWI133', 'SWI132', 'SWI68', 'SWI69', 'SWI131', 'SWI165', 'UNKNOWN'];
  const LS_KEY = 'mg4sim.fw.v1';

  // ── Outils ────────────────────────────────────────────────────────────────
  const esc = (s) => String(s == null ? '' : s).replace(/[&<>"']/g, (c) =>
    ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const clamp = (v, a, b) => Math.max(a, Math.min(b, v));
  const uid = () => Math.random().toString(36).slice(2, 10);
  const siteLang = () => document.documentElement.getAttribute('data-lang') || 'fr';
  const L = (fr, en) => (siteLang() === 'en' ? en : fr);
  const appLang = () => state.appLang || siteLang();

  function fmt(s, args) {
    let i = 0;
    return s.replace(/%(\d+)\$([sd])|%([sd])/g, (m, pos) => {
      const idx = pos ? parseInt(pos, 10) - 1 : i++;
      return args[idx] != null ? args[idx] : '';
    }).replace(/%%/g, '%');
  }
  /** Chaîne de l'app, dans la langue de l'app simulée (repli sur le français, comme Android). */
  function S(key, ...args) {
    const t = (STR[appLang()] || {})[key] ?? (STR.fr || {})[key] ?? key;
    return args.length ? fmt(t, args) : t.replace(/%%/g, '%');
  }

  // ── Modèle ────────────────────────────────────────────────────────────────
  const DRIVE = ['ECO', 'NORMAL', 'SPORT', 'SNOW', 'CUSTOM'];
  const DRIVE_LABEL = { ECO: 'Eco', NORMAL: 'Normal', SPORT: 'Sport', SNOW: 'Snow', CUSTOM: 'Custom' };
  const REGEN_LABEL = { LOW: 'Low', MEDIUM: 'Medium', HIGH: 'High', ADAPTIVE: 'Adaptive', OFF: 'Off', ONE_PEDAL: 'One Pedal' };
  const REGEN_CYCLE = ['LOW', 'MEDIUM', 'HIGH', 'ADAPTIVE'];
  const ELK = { OFF: 1, ALERT: 2, ASSIST: 3, EMERGENCY: 5 };
  const LOOP = { INNER: 0, OUTSIDE: 1, AUTO: 2 };
  const KEY_NAMES = {
    17: ['Étoile gauche', 'Left star'], 286: ['Étoile droite', 'Right star'], 18: ['Étoile droite', 'Right star'],
    297: ['Joystick haut', 'Joystick up'], 298: ['Joystick bas', 'Joystick down'],
    299: ['Joystick gauche', 'Joystick left'], 300: ['Joystick droite', 'Joystick right'], 301: ['Joystick centre', 'Joystick centre']
  };
  const APPS = ['MG Launcher', 'Spotify', 'Google Maps', 'Waze', 'YouTube Music', 'Radio'];

  function mkProfile(o) {
    return Object.assign({
      id: uid(), name: '', driveMode: 'NORMAL', regen: 'MEDIUM', energy: false, tsr: true,
      adas: 0, overspeed: true, speedTone: true, sound: true,
      aebOn: true, aebMode: 2, aebSen: 2,
      elkOn: true, elkMode: ELK.EMERGENCY, elkSen: 2, elkSound: true, elkVib: true,
      esc: true, dms: true, dmsSen: 2,
      steeringApply: true, steeringOn: false, seatApply: true, seatL: 0, seatR: 0,
      bt: null,
      // Réglages du mode Personnalisé (null = non configuré) et bloc climatisation
      customPower: null, customSteer: null, customPedal: null,
      hvac: { enabled: false, power: true, ac: true, auto: false, temp: 21, fan: 4, loop: null, air: null }
    }, o);
  }
  const WINDOWS = ['FL', 'FR', 'RL', 'RR'];
  const WIN_KEY = { FL: 'win_front_left', FR: 'win_front_right', RL: 'win_rear_left', RR: 'win_rear_right' };
  const AIR = { FACE: 1, FEET: 2, WS: 4, REAR: 8 };

  /** Historique d'exemple de l'écran Statistiques (valeurs plausibles, pas des mesures). */
  /** Les trois batteries de la MG4 : capacité nominale → capacité utile, en kWh. */
  const BATTERIES = { 51: 50.8, 64: 61.7, 77: 74.4 };
  const chargeKwh = (c, nominal) => Math.round((c.soc[1] - c.soc[0]) * BATTERIES[nominal] / 10) / 10;
  function sampleStats() {
    const day = 86400000, now = Date.now();
    const at = (d, h, m) => { const t = new Date(now - d * day); t.setHours(h, m, 0, 0); return t.getTime(); };
    const trip = (d, h, m, min, km, kwh, speed, extra) => Object.assign({
      start: at(d, h, m), end: at(d, h, m) + min * 60000, km, kwh, speed,
      motor: +(kwh * 0.86).toFixed(1), climate: +(kwh * 0.09).toFixed(1), acc: +(kwh * 0.05).toFixed(1), regen: +(kwh * 0.14).toFixed(1),
      soc: [0, 0], temp: 14
    }, extra || {});
    const trips = [
      trip(0, 8, 12, 27, 23.4, 3.9, 52, { soc: [81, 76], temp: 11 }),
      trip(1, 18, 3, 31, 24.1, 4.3, 47, { soc: [58, 52], temp: 16 }),
      trip(1, 7, 55, 29, 23.8, 4.0, 49, { soc: [64, 58], temp: 9 }),
      trip(3, 14, 20, 138, 186.2, 34.7, 81, { soc: [96, 41], temp: 18 }),
      trip(5, 11, 40, 6, 1.9, 0.5, 19, { soc: [70, 69], temp: 12, short: true }),
      trip(6, 17, 48, 33, 25.0, 4.4, 45, { soc: [77, 70], temp: 13 }),
      trip(9, 9, 5, 42, 31.6, 5.6, 45, { soc: [88, 79], temp: 7 })
    ];
    const charges = [
      { start: at(1, 22, 30), end: at(0, 5, 10), type: 'AC', kwh: 23.6, soc: [52, 90], temp: 8, reconstructed: true, timesKnown: false },
      { start: at(3, 16, 55), end: at(3, 17, 31), type: 'DC', kwh: 32.9, soc: [41, 94], temp: 18, powerMeasured: 58.4 },
      // Charges de nuit : la fin tombe le lendemain, donc un jour de moins dans le passé (d - 1).
      { start: at(4, 21, 5), end: at(3, 3, 40), type: 'AC', kwh: 18.0, soc: [67, 96], temp: 10, reconstructed: true, timesKnown: true, powerComputed: 2.7 },
      { start: at(8, 20, 40), end: at(7, 2, 20), type: 'AC', kwh: 16.1, soc: [62, 88], temp: 6, powerMeasured: 2.9, fixedPrice: 0.152 }
    ];
    charges.forEach((c) => { c.kwh = chargeKwh(c, 64); });
    return { trips, charges };
  }

  function initialState() {
    const daily = mkProfile({ name: L('Quotidien', 'Daily'), driveMode: 'NORMAL', regen: 'ADAPTIVE', adas: 0 });
    const road = mkProfile({ name: L('Autoroute', 'Motorway'), driveMode: 'ECO', regen: 'LOW', adas: 3, bt: 'A4:C1:38:5E:22:10' });
    const winter = mkProfile({ name: L('Hiver', 'Winter'), driveMode: 'NORMAL', regen: 'MEDIUM', steeringOn: true, seatL: 3, seatR: 2, bt: '5C:F9:38:07:AB:41',
      hvac: { enabled: true, power: true, ac: false, auto: true, temp: 22, fan: 4, loop: 1, air: AIR.FEET | AIR.WS | AIR.REAR } });
    let fw = 'SWI133';
    try { const saved = localStorage.getItem(LS_KEY); if (saved && FIRMWARES.includes(saved)) fw = saved; } catch (e) { /* stockage indisponible */ }
    const sample = sampleStats();
    return {
      fw, forced: null, fwDismissed: false, appLang: null,
      activeProfileId: daily.id,
      car: {
        on: true, ready: true, speed: 0, maxSpeed: 0, outside: 12,
        custom: { power: 1, steer: 1, pedal: 1 },
        windows: { FL: 0, FR: 0, RL: 0, RR: 0 },
        driveMode: 'NORMAL', regen: 'ADAPTIVE', energy: false,
        adas: 0, tsr: true, overspeed: true, speedTone: true, savedAlerts: [true, true], sound: true,
        aebOn: true, aebMode: 2, aebSen: 2,
        elkMode: ELK.EMERGENCY, elkLast: ELK.EMERGENCY, elkSen: 2, elkSound: true, elkVib: true,
        esc: true, dms: true, dmsSen: 2,
        steering: false, seatL: 0, seatR: 0,
        clim: { power: true, ac: true, auto: true, temp: 21, fan: 3, loop: LOOP.AUTO, defF: false, defR: false, tMin: 16, tMax: 32, fMin: 1, fMax: 10,
                air: { face: true, feet: false, ws: false } },
        brightness: 70, volume: 14, volMax: 30, prevVolume: null,
        soc: 64, lights: false, batHeat: false, highBeam: true,
        doors: { L: false, R: false },
        media: { playing: false, track: 3 }
      },
      profiles: [daily, road, winter],
      defaultId: daily.id,
      lastManual: null,
      settings: { defaultScreen: 'dashboard', theme: 'auto', api: false, autoUpdate: true, beta: false, gate: false, gateMax: 30,
                  garage: false, textSize: 'standard', updOverlay: true },
      sc: {
        enabled: true,
        map: { btn1_single: 'PROFILE_PICKER', btn1_long: 'ONE_PEDAL', btn2_single: 'NONE', btn2_long: 'ADAS_CYCLE' },
        extra: {},
        fallback: 'HIGH', adasA: 3, adasB: 0, aebA: 1, aebB: 2,
        regenCycle: null,          // séquence composée (null = ordre d'origine)
        toggles: {},
        advOn: false, advService: false,
        adv: []                    // { key, press, action, scope (profil ; null = tous), app?, profileId? (cible) }
      },
      // Mesures du calibrage par vitre (ms), conservées même quand l'option avancée est éteinte.
      winCal: { FL: 'sensor', FR: null, RL: null, RR: null },
      winCalAdv: false,            // option « Calibrage par vitre » (PowerWindows.advancedCalibration)
      winCourse: 5000,             // durée de course générale, 2 à 10 s par pas de 0,5 s
      winAuto: { on: false, speedOn: true, speed: 20, timeOn: true, time: 5, both: false, delay: 5, beep: true, beepVol: 60 },
      stats: { enabled: false, period: 30, currency: '€', priceAc: 0.187, priceDc: 0.45, battery: 64, batteryConfirmed: false, skipShort: false, minTrip: 1, retention: 90,
               trips: sample.trips, charges: sample.charges },
      auto: {
        // Une carte n'est dépliée que si son automatisation est active (activer déplie, couper replie).
        p: { on: false, open: false, dir: 'BELOW', thr: 5, profileId: winter.id, autoExec: false },
        b: { on: false, open: false, thr: 20, profileId: road.id, autoExec: false, fired: false },
        bri: { on: false, open: false, lights: true, forecast: false, follow: true, day: 80, night: 20, last: '' },
        bh: { on: false, open: false, minutes: 30 },
        c: {
          // Mode de déclenchement : 'start' (au démarrage seulement, défaut), 'once' ou 'ready'.
          on: false, open: false, trigger: 'start', evaluated: false, fired: false,
          hot: { on: true, thr: 28, target: 20, fan: 4, defF: false, defR: false, auto: false, recircForce: true, recirc: LOOP.INNER },
          cold: { on: true, thr: 5, target: 24, fan: 4, defF: true, defR: true, auto: false, recircForce: false, recirc: LOOP.AUTO }
        }
      },
      door: { on: false, open: false, level: 6, restore: true, L: true, R: true },
      bt: { devices: [{ mac: 'A4:C1:38:5E:22:10', name: 'Pixel 8' }, { mac: '5C:F9:38:07:AB:41', name: 'iPhone 15' }], connected: [] },
      diagUnlocked: false
    };
  }

  let state = initialState();

  // ── Capacités par firmware (miroir de FirmwareInfo / MG4Hardware) ─────────
  function gen() { return state.fw === 'UNKNOWN' && state.forced ? state.forced : state.fw; }
  function caps() {
    const g = gen();
    const known = g !== 'UNKNOWN';
    const vsm = ['SWI68', 'SWI69', 'SWI131', 'SWI132', 'SWI165'].includes(g);
    const s132 = g === 'SWI132';
    return {
      g, known, vsm, s132,
      twoAlerts: !vsm || s132,                         // survitesse + changement de limite
      heat: ['SWI133', 'SWI68', 'SWI165'].includes(g), // sièges + volant chauffants
      clim: known, esc: known, bri: known, power: known,
      door: known,                                     // carte « Baisse du volume » : partout où le volume est pilotable
      beam: known, batHeat: known,                     // feux de route automatiques, chauffage de la batterie
      counters: g === 'SWI132' || g === 'SWI133',      // compteurs d'énergie du véhicule ; sinon l'énergie est calculée
      doorSensor: g === 'SWI132' || g === 'SWI133',    // portes lisibles ; sinon sortie de READY
      fifth: (!vsm || s132) ? 'adas_ica' : 'adas_tja'
    };
  }
  const garage = () => state.settings.garage;
  const activeProfile = () => state.profiles.find((p) => p.id === state.activeProfileId) || null;

  // ── Abonnements / rendu groupé ────────────────────────────────────────────
  const instances = [];
  const listeners = new Set();
  // Rendu groupé en fin de tâche (micro-tâche) : ne dépend pas des images affichées,
  // contrairement à requestAnimationFrame qui s'arrête quand l'onglet n'est pas peint.
  let pending = false;
  function commit() {
    if (pending) return;
    pending = true;
    queueMicrotask(() => {
      pending = false;
      instances.forEach((i) => i.render());
      listeners.forEach((f) => f());
    });
  }
  /** Maquette la plus visible à l'écran : c'est elle qui reçoit volant, démarrage et API. */
  function activeSim() {
    let best = null, r = -1;
    const h = window.innerHeight;
    instances.forEach((i) => {
      const b = i.el.getBoundingClientRect();
      const vis = b.height > 0 ? Math.max(0, Math.min(b.bottom, h) - Math.max(b.top, 0)) / b.height : 0;
      if (vis > r) { r = vis; best = i; }
    });
    return best;
  }
  function toastActive(t, ms) { const s = activeSim(); if (s) s.toast(t, ms); }
  function hud(t) { toastActive('🎛 ' + t, 2600); }

  // ── Verrou de vitesse (VehicleWriteGate) ─────────────────────────────────
  function gateOk(silent) {
    const s = state.settings;
    if (!s.gate || state.car.speed <= s.gateMax) return true;
    if (!silent) toastActive(S('write_refused_moving', String(state.car.speed), s.gateMax));
    return false;
  }

  // ── Écritures véhicule ────────────────────────────────────────────────────
  const car = () => state.car;
  const W = {
    drive(m) { if (!gateOk()) return false; car().driveMode = m; return true; },
    regen(l) {
      if (!gateOk()) return false;
      const c = car();
      if (c.driveMode === 'SNOW' || (c.energy && l !== 'ONE_PEDAL')) return false;
      c.regen = l; return true;
    },
    energy(on) { if (!gateOk()) return false; if (car().driveMode === 'SNOW') return false; car().energy = on; return true; },
    adas(i) { if (!gateOk()) return false; car().adas = i; return true; },
    tsr(on) {
      if (!gateOk()) return false;
      const c = car(), k = caps();
      c.tsr = on;
      if (k.twoAlerts) {
        if (!on) { c.savedAlerts = [c.overspeed, c.speedTone]; c.overspeed = false; c.speedTone = false; }
        else if (k.s132) { c.overspeed = true; c.speedTone = true; }
        else { c.overspeed = c.savedAlerts[0]; c.speedTone = c.savedAlerts[1]; }
      }
      return true;
    },
    bool(field, on) { if (!gateOk()) return false; car()[field] = on; return true; },
    aebMode(m) { if (!gateOk()) return false; car().aebMode = m; return true; },
    aebSen(s) { if (!gateOk()) return false; car().aebSen = s; return true; },
    elkOn(on) { if (!gateOk()) return false; const c = car(); c.elkMode = on ? c.elkLast : ELK.OFF; return true; },
    elkMode(m) { if (!gateOk()) return false; const c = car(); c.elkLast = m; c.elkMode = m; return true; },
    elkSen(s) { if (!gateOk()) return false; car().elkSen = s; return true; },
    esc(on) { if (!gateOk()) return false; const c = car(); c.esc = on; if (!on) c.aebOn = false; return true; },
    dmsSen(s) { if (!gateOk()) return false; car().dmsSen = s; return true; },
    custom(field, v) { if (!gateOk()) return false; car().custom[field] = v; return true; }
  };

  /** Bloc climatisation d'un profil (mêmes règles que MG4Hardware.applyProfileClimate). */
  function applyProfileClimate(h) {
    const cl = car().clim;
    if (!h.power) { cl.power = false; return; }          // clim éteinte : rien d'autre n'est écrit
    cl.power = true; cl.ac = h.ac; cl.auto = h.auto;
    cl.temp = clamp(h.temp, cl.tMin, cl.tMax);           // bornes réelles lues sur le véhicule
    if (!h.auto) cl.fan = clamp(h.fan, cl.fMin, cl.fMax); // en AUTO, la vitesse n'est pas appliquée
    if (h.loop != null) cl.loop = h.loop;                 // null = « Inchangé »
    if (h.air != null) {
      const face = !!(h.air & AIR.FACE), feet = !!(h.air & AIR.FEET), ws = !!(h.air & AIR.WS);
      if (face || feet || ws) cl.air = { face, feet, ws };
      cl.defR = !!(h.air & AIR.REAR);
    }
  }

  // ── Application d'un profil (ProfileApplier) ─────────────────────────────
  function applyProfile(p, opts) {
    opts = opts || {};
    const c = car(), k = caps();
    const drivingOk = gateOk(true);
    if (drivingOk) {
      c.driveMode = p.driveMode;
      if (p.driveMode !== 'SNOW') c.regen = p.regen;
      if (k.known) {
        c.energy = p.driveMode === 'SNOW' ? false : p.energy;
        c.tsr = p.tsr;
      }
      c.adas = p.adas;
      if (k.twoAlerts) { c.overspeed = p.tsr ? p.overspeed : false; c.speedTone = p.tsr ? p.speedTone : false; }
      else c.sound = p.sound;
      if (k.known) {
        c.aebOn = p.aebOn; c.aebMode = p.aebMode; c.aebSen = p.aebSen;
        const m = k.s132 && p.elkMode === ELK.EMERGENCY ? ELK.ALERT : p.elkMode;
        c.elkLast = m; c.elkMode = p.elkOn ? m : ELK.OFF; c.elkSen = p.elkSen;
        if (k.s132) { c.elkSound = p.elkSound; c.elkVib = p.elkVib; }
      }
      if (k.esc) {
        c.esc = p.esc; if (!p.esc) c.aebOn = false;
        c.dms = p.dms; c.dmsSen = p.dmsSen;
      }
      // Les trois réglages du mode Personnalisé, écrits seulement si le profil est en CUSTOM.
      if (p.driveMode === 'CUSTOM') {
        if (p.customPower != null) c.custom.power = p.customPower;
        if (p.customSteer != null) c.custom.steer = p.customSteer;
        if (p.customPedal != null) c.custom.pedal = p.customPedal;
      }
    }
    // Le confort n'est jamais bloqué par le verrou de vitesse.
    if (k.heat) {
      if (p.steeringApply) c.steering = p.steeringOn;
      if (p.seatApply) { c.seatL = p.seatL; c.seatR = p.seatR; }
    }
    if (p.hvac && p.hvac.enabled && k.clim) applyProfileClimate(p.hvac);
    state.activeProfileId = p.id;   // « profil actif » des raccourcis par profil
    if (opts.manual) state.lastManual = p.id;
    commit();
    if (!drivingOk) { toastActive(S('write_refused_moving', String(c.speed), state.settings.gateMax)); return false; }
    // Seul « Appliquer maintenant » (liste des profils) affiche un toast dans l'app ; ailleurs, note du simulateur.
    if (opts.toast) toastActive(S('profile_applied', p.name));
    else hud(L('Profil « ', 'Profile “') + p.name + L(' » appliqué', '” applied') + (opts.via ? ' · ' + opts.via : ''));
    return true;
  }

  // ── Démarrage du véhicule : précédence des déclencheurs ───────────────────
  const tempDecision = (cfg, temp) => cfg.on && (cfg.dir === 'BELOW' ? temp <= cfg.thr : temp >= cfg.thr);
  function fallbackBtDefault(trace) {
    const btProfiles = state.profiles.filter((p) => p.bt && state.bt.connected.includes(p.bt));
    if (btProfiles.length > 1) {
      // Conflit : plusieurs téléphones associés connectés → sélecteur restreint, 1er profil au délai.
      trace.push(L('Conflit Bluetooth → sélecteur limité aux profils des téléphones connectés', 'Bluetooth conflict → picker limited to the connected phones’ profiles'));
      const sim = activeSim();
      if (sim) sim.openPicker(btProfiles.map((p) => p.id), () => applyProfile(btProfiles[0], { via: 'Bluetooth' }));
      return;
    }
    const btP = btProfiles[0];
    if (btP) { trace.push(L('Bluetooth : « ', 'Bluetooth: “') + btP.name + L(' »', '”')); applyProfile(btP, { via: 'Bluetooth' }); return; }
    const def = state.profiles.find((p) => p.id === state.defaultId);
    if (def) { trace.push(L('Profil par défaut : « ', 'Default profile: “') + def.name + L(' »', '”')); applyProfile(def, { via: L('profil par défaut', 'default profile') }); return; }
    trace.push(L('Aucun profil à appliquer', 'No profile to apply'));
  }
  const nowHM = () => new Intl.DateTimeFormat(appLang() === 'en' ? 'en-GB' : appLang(), { hour: '2-digit', minute: '2-digit' }).format(Date.now());
  /** Changer de batterie recalcule l'énergie des recharges enregistrées, comme dans l'app. */
  function setBattery(nominal) {
    const st = state.stats;
    if (!BATTERIES[nominal]) return;
    st.battery = nominal; st.batteryConfirmed = true;
    st.charges.forEach((c) => { c.kwh = chargeKwh(c, nominal); });
  }
  /** Luminosité automatique, version « feux seuls » : niveau jour ou nuit selon l'état des feux. */
  function autoBrightness() {
    const c = car(), b = state.auto.bri;
    const v = c.lights ? b.night : b.day;
    c.brightness = v;
    b.last = S('autobri_status_lights', nowHM(), S(c.lights ? 'autobri_source_lights' : 'autobri_source_lights_off'), v);
    hud(S('autobri_title') + ' → ' + v + ' %');
  }
  /** Profil selon la batterie : une fois par épisode, au démarrage ou au franchissement du seuil. */
  function batteryAutomation(trace) {
    const c = car(), ab = state.auto.b;
    if (!ab.on || garage()) return;
    if (c.soc >= ab.thr) { ab.fired = false; return; }
    if (ab.fired) return;
    const p = state.profiles.find((x) => x.id === ab.profileId);
    if (!p) return;
    ab.fired = true;
    if (ab.autoExec) {
      applyProfile(p, { via: L('batterie', 'battery') });
      if (trace) trace.push(L('Batterie sous le seuil → « ', 'Battery below threshold → “') + p.name + L(' » (exécution directe)', '” (direct)'));
    } else {
      const sim = activeSim();
      if (sim) sim.showConfirm(p, () => {}, { thr: ab.thr, soc: c.soc });
      if (trace) trace.push(L('Batterie sous le seuil → popup de confirmation', 'Battery below threshold → confirmation popup'));
    }
  }
  function ignition() {
    const c = car();
    const wasReady = c.ready;
    c.on = true; c.ready = true; c.maxSpeed = c.speed; c.readyAt = Date.now();
    c.esc = true; c.aebOn = true; // la voiture les rétablit à chaque démarrage
    state.lastManual = null;       // nouveau démarrage du service
    if (wasReady) { state.auto.c.evaluated = false; state.auto.c.fired = false; }
    cancelLeaving(!wasReady);
    const trace = [];
    if (garage()) {
      trace.push(L('Mode Garage : rien n\'est appliqué (profils, Bluetooth et automatisations en veille)', 'Garage mode: nothing is applied (profiles, Bluetooth and automations asleep)'));
      commit(); emit('ignition', { trace }); return trace;
    }
    const a = state.auto.p;
    const p = state.profiles.find((x) => x.id === a.profileId);
    if (p && tempDecision(a, c.outside)) {
      if (a.autoExec) { trace.push(L('Température → « ', 'Temperature → “') + p.name + L(' » (exécution directe)', '” (direct)')); applyProfile(p, { via: L('température', 'temperature') }); }
      else {
        trace.push(L('Température → popup de confirmation', 'Temperature → confirmation popup'));
        const sim = activeSim();
        if (sim) sim.showConfirm(p, () => fallbackBtDefault([]));
      }
    } else fallbackBtDefault(trace);
    // Automatisation climatisation : un profil actif qui porte un bloc clim l'emporte.
    const cc = state.auto.c;
    const ap = activeProfile();
    if (cc.on && caps().clim && ap && ap.hvac && ap.hvac.enabled) {
      trace.push(L('Automatisation A/C ignorée : le profil « ', 'A/C automation skipped: profile “') + ap.name + L(' » porte sa propre climatisation', '” carries its own climate'));
      cc.evaluated = true;         // le profil a décidé de la climatisation pour ce démarrage
    } else if (cc.on && caps().clim && (cc.trigger === 'start' ? cc.evaluated : cc.trigger === 'once' && cc.fired)) {
      trace.push(L('Automatisation A/C non relancée : décision déjà prise depuis le démarrage (mode « ', 'A/C automation not re-run: decision already taken since start-up (mode “') +
        S('ac_auto_trigger_' + cc.trigger) + L(' »)', '”)'));
    } else if (cc.on && caps().clim) {
      cc.evaluated = true;         // la température du véhicule virtuel est toujours lisible
      let rule = null;
      if (cc.hot.on && c.outside >= cc.hot.thr) rule = 'hot';
      else if (cc.cold.on && c.outside <= cc.cold.thr) rule = 'cold';
      if (rule) {
        const r = cc[rule];
        cc.fired = true;
        Object.assign(c.clim, {
          power: true, ac: true,
          temp: clamp(r.target, c.clim.tMin, c.clim.tMax), fan: clamp(r.fan, c.clim.fMin, c.clim.fMax),
          defF: r.defF, defR: r.defR, auto: r.auto
        });
        if (r.recircForce) c.clim.loop = r.recirc;
        setTimeout(() => hud(rule === 'hot'
          ? L('Automatisation A/C : règle « température supérieure » appliquée', 'A/C automation: “above” rule applied')
          : L('Automatisation A/C : règle « température inférieure » appliquée', 'A/C automation: “below” rule applied')), 2700);
      }
    }
    // Profil selon la batterie : réarmé à chaque démarrage, évalué après la chaîne des profils.
    state.auto.b.fired = false;
    batteryAutomation(trace);
    if (state.auto.bri.on && caps().bri) autoBrightness();
    commit();
    emit('ignition', { trace });
    return trace;
  }

  // ── Vitres électriques : moteur de déplacement ───────────────────────────
  // La position n'est plus affichée (l'app ne la montre plus) : elle sert à l'assistant de calibration.
  const WIN_TRAVEL_MS = 6000;       // course automatique native du conducteur (WindowCommand.AUTO_TRAVEL_MS)
  const winMotion = {};
  let winTimer = null;
  /**
   * Durée d'une course complète, comme PowerWindows : course native pour le conducteur ; pour les
   * autres, la mesure du calibrage par vitre si l'option avancée est allumée, sinon la durée générale.
   */
  function winCourseMs(w, dir) {
    const cal = state.winCal[w];
    if (cal === 'sensor') return WIN_TRAVEL_MS;
    if (state.winCalAdv && cal) return dir > 0 ? cal.down : cal.up;
    return state.winCourse;
  }
  function moveWindow(w, dir) {
    if (!dir) delete winMotion[w]; else winMotion[w] = dir;
    if (!winTimer && Object.keys(winMotion).length) winTimer = setInterval(winTick, 150);
    commit();
  }
  function winTick() {
    Object.keys(winMotion).forEach((w) => {
      const dir = winMotion[w];
      const pos = clamp(car().windows[w] + dir * 100 * 150 / winCourseMs(w, dir), 0, 100);
      car().windows[w] = Math.round(pos * 10) / 10;
      if (pos <= 0 || pos >= 100) delete winMotion[w];
    });
    if (!Object.keys(winMotion).length) { clearInterval(winTimer); winTimer = null; }
    commit();
  }
  const allWindows = (dir) => WINDOWS.forEach((w) => moveWindow(w, dir));
  /** Note du simulateur : dans la maquette on ne voit pas les vitres, on dit donc ce qui part. */
  function winAllNote(dir) {
    const calibrated = state.winCalAdv && WINDOWS.some((w) => state.winCal[w] && state.winCal[w] !== 'sensor');
    return (dir < 0 ? L('Toutes les vitres se ferment', 'All windows are closing') : L('Toutes les vitres s\'ouvrent', 'All windows are opening')) +
      L(' · conducteur : course d\'origine · autres : ', ' · driver: native travel · others: ') +
      (calibrated ? L('durée mesurée, sinon ', 'measured time, otherwise ') : '') + fmtS(state.winCourse) + ' s';
  }
  function winArmed() {
    const a = state.winAuto, c = car();
    const speedOk = a.speedOn && c.maxSpeed >= a.speed;
    const timeOk = a.timeOn && c.readyAt && (Date.now() - c.readyAt) >= a.time * 60000;
    if (a.speedOn && a.timeOn && a.both) return speedOk && timeOk;
    return speedOk || timeOk;
  }

  // ── Sortie du mode READY (porte conducteur ouverte ou extinction, voiture en P) ──
  let leaveTimer = null;
  function leaveCar() {
    const c = car(), k = caps();
    if (!c.ready) return [];
    c.ready = false;
    const trace = [L('Sortie de READY', 'Leaving READY')];
    if (!garage()) {
      // Baisse du volume : sur les firmwares sans capteur de porte, c'est ce signal qui sert.
      if (k.door && !k.doorSensor && state.door.on) {
        if (c.prevVolume == null) c.prevVolume = c.volume;
        c.volume = Math.min(c.volume, state.door.level);
        trace.push(L('volume média abaissé à ', 'media volume lowered to ') + c.volume);
      }
      const a = state.winAuto;
      if (a.on) {
        if (c.speed > 0) trace.push(L('vitres : voiture pas en P', 'windows: car not in P'));
        else if (!winArmed()) trace.push(L('fermeture des vitres non armée (conditions de roulage non atteintes)', 'window closing not armed (driving conditions not met)'));
        else {
          let left = a.delay;
          trace.push(L('fermeture des vitres dans ', 'windows closing in ') + left + ' s');
          const step = () => {
            if (left <= 0) { leaveTimer = null; allWindows(-1); hud(L('Fermeture automatique : ', 'Auto close: ') + winAllNote(-1)); return; }
            hud((a.beep ? '🔔 ' : '') + L('Vitres : fermeture dans ', 'Windows: closing in ') + left + ' s');
            left -= 1;
            leaveTimer = setTimeout(step, 1000);
          };
          step();
        }
      }
    }
    commit();
    return trace;
  }
  function cancelLeaving(backToReady) {
    const c = car();
    if (leaveTimer) { clearTimeout(leaveTimer); leaveTimer = null; hud(L('Retour en READY : fermeture des vitres annulée', 'Back to READY: window closing cancelled')); }
    if (backToReady && !caps().doorSensor && c.prevVolume != null) {
      if (state.door.restore) c.volume = c.prevVolume;
      c.prevVolume = null;
    }
  }

  // ── Actions des raccourcis (MG4ControlService.executeShortcut) ────────────
  const ACTION_KEY = {
    NONE: 'shortcuts_action_none', ONE_PEDAL: 'shortcuts_action_one_pedal', REGEN_CYCLE: 'shortcuts_action_regen_cycle',
    AEB_CYCLE: 'shortcuts_action_aeb', SOUND_WARNING: 'shortcuts_action_sound', OVERSPEED_ALARM: 'shortcuts_action_overspeed',
    SPEED_LIMIT_TONE: 'shortcuts_action_speed_limit', ADAS_CYCLE: 'shortcuts_action_adas', ENERGY_SAVING_TOGGLE: 'shortcuts_action_energy_saving',
    TSR_TOGGLE: 'shortcuts_action_tsr', ESC_TOGGLE: 'shortcuts_action_esc', DROWSINESS_TOGGLE: 'shortcuts_action_drowsiness',
    DROWSINESS_SEN_CYCLE: 'shortcuts_action_drowsiness_sen', SEAT_HEAT_LEFT_CYCLE: 'shortcuts_action_seat_heat_left',
    SEAT_HEAT_RIGHT_CYCLE: 'shortcuts_action_seat_heat_right', STEERING_HEAT_TOGGLE: 'shortcuts_action_steering_heat',
    HVAC_TOGGLE: 'shortcuts_action_hvac_toggle', HVAC_AC_TOGGLE: 'shortcuts_action_hvac_ac_toggle', HVAC_TEMP_UP: 'shortcuts_action_hvac_temp_up', HVAC_TEMP_DOWN: 'shortcuts_action_hvac_temp_down',
    HVAC_FAN_UP: 'shortcuts_action_hvac_fan_up', HVAC_FAN_DOWN: 'shortcuts_action_hvac_fan_down', DEFROST_FRONT_TOGGLE: 'shortcuts_action_defrost_front',
    DEFROST_REAR_TOGGLE: 'shortcuts_action_defrost_rear', HVAC_RECIRC_CYCLE: 'shortcuts_action_hvac_recirc',
    HVAC_POPUP: 'shortcuts_action_hvac_popup',
    BRIGHTNESS_UP: 'shortcuts_action_brightness_up', BRIGHTNESS_DOWN: 'shortcuts_action_brightness_down',
    AUTO_HIGH_BEAM_TOGGLE: 'shortcuts_action_auto_high_beam', BATTERY_HEAT_TOGGLE: 'shortcuts_action_battery_heat',
    MEDIA_NEXT: 'shortcuts_action_media_next', MEDIA_PREVIOUS: 'shortcuts_action_media_prev', MEDIA_PLAY_PAUSE: 'shortcuts_action_media_play_pause',
    VOLUME_UP: 'shortcuts_action_volume_up', VOLUME_DOWN: 'shortcuts_action_volume_down',
    APPLY_PROFILE: 'shortcuts_action_apply_profile', PROFILE_PICKER: 'shortcuts_action_profile_picker',
    OPEN_APP: 'shortcuts_action_open_app', OPEN_CUSTOM_APP: 'shortcuts_action_open_custom_app', VEHICLE_POWER_OFF: 'shortcuts_action_vehicle_power_off',
    WINDOWS_OPEN_ALL: 'shortcuts_action_windows_open', WINDOWS_CLOSE_ALL: 'shortcuts_action_windows_close'
  };
  const REGEN_SELECTABLE = ['LOW', 'MEDIUM', 'HIGH', 'ADAPTIVE', 'ONE_PEDAL'];
  const regenOrder = () => (state.sc.regenCycle && state.sc.regenCycle.length >= 2 ? state.sc.regenCycle : REGEN_CYCLE);
  /** Liste des actions proposées, filtrée par firmware (ShortcutsFragment.baseActionItems). */
  function availableActions() {
    const k = caps();
    const a = ['NONE', 'ONE_PEDAL', 'REGEN_CYCLE'];
    if (k.known) a.push('AEB_CYCLE');
    if (k.vsm && !k.s132) a.push('SOUND_WARNING');
    if (k.twoAlerts && k.known) a.push('OVERSPEED_ALARM', 'SPEED_LIMIT_TONE');
    if (k.known) a.push('ADAS_CYCLE', 'ENERGY_SAVING_TOGGLE', 'TSR_TOGGLE');
    if (k.esc) a.push('ESC_TOGGLE', 'DROWSINESS_TOGGLE', 'DROWSINESS_SEN_CYCLE');
    a.push('SEAT_HEAT_LEFT_CYCLE', 'SEAT_HEAT_RIGHT_CYCLE', 'STEERING_HEAT_TOGGLE');
    if (k.clim) a.push('HVAC_TOGGLE', 'HVAC_AC_TOGGLE', 'HVAC_TEMP_UP', 'HVAC_TEMP_DOWN', 'HVAC_FAN_UP', 'HVAC_FAN_DOWN', 'DEFROST_FRONT_TOGGLE', 'DEFROST_REAR_TOGGLE', 'HVAC_RECIRC_CYCLE', 'HVAC_POPUP');
    if (k.bri) a.push('BRIGHTNESS_UP', 'BRIGHTNESS_DOWN');
    if (k.beam) a.push('AUTO_HIGH_BEAM_TOGGLE');
    if (k.batHeat) a.push('BATTERY_HEAT_TOGGLE');
    a.push('MEDIA_NEXT', 'MEDIA_PREVIOUS', 'MEDIA_PLAY_PAUSE', 'VOLUME_UP', 'VOLUME_DOWN',
      'WINDOWS_OPEN_ALL', 'WINDOWS_CLOSE_ALL', 'APPLY_PROFILE', 'PROFILE_PICKER', 'OPEN_APP', 'OPEN_CUSTOM_APP');
    if (k.power) a.push('VEHICLE_POWER_OFF');
    return a;
  }
  const onOff = (b) => (b ? 'ON' : 'OFF');
  const adasLabel = (i) => S(['adas_off', 'adas_limiteur_short', 'adas_auto', 'adas_acc', caps().fifth][i] || 'adas_off');
  const regenName = (l) => S({ OFF: 'regen_off', LOW: 'regen_low', MEDIUM: 'regen_medium', HIGH: 'regen_high', ADAPTIVE: 'regen_adaptive', ONE_PEDAL: 'regen_one_pedal' }[l]);
  const levelName = (n) => (n ? S('climate_level_' + n) : S('climate_off'));
  const sensName = (n) => S(['sens_low', 'sens_medium', 'sens_high'][n - 1] || 'sens_medium');
  const loopName = (n) => S(['clim_loop_inner', 'clim_loop_outside', 'clim_loop_auto'][n]);

  /**
   * Exécute une action de raccourci. `extra` porte l'application ou le profil cible.
   * Les actions historiques basculent un état mémorisé ; les plus récentes relisent le
   * véhicule à chaque appui (« lue puis écrite »).
   */
  function runAction(action, extra) {
    const c = car(), sc = state.sc, k = caps();
    const flip = () => { const v = !sc.toggles[action]; sc.toggles[action] = v; return v; };
    let msg = '';
    switch (action) {
      case 'NONE': return '';
      case 'ONE_PEDAL': {
        const v = c.regen !== 'ONE_PEDAL';   // état lu sur le véhicule
        const lvl = v ? 'ONE_PEDAL' : sc.fallback;
        if (!gateOk()) return '';
        if (c.driveMode === 'SNOW') { msg = L('Régénération indisponible en mode SNOW', 'Regeneration unavailable in SNOW mode'); break; }
        c.regen = lvl; msg = S('drive_section_regen') + ' → ' + regenName(lvl); break;
      }
      case 'REGEN_CYCLE': {
        // Repart du niveau LU sur le véhicule ; hors séquence → premier cran.
        const order = regenOrder();
        const idx = order.indexOf(c.regen);
        const next = idx < 0 ? order[0] : order[(idx + 1) % order.length];
        if (c.driveMode === 'SNOW' || (c.energy && next !== 'ONE_PEDAL')) { msg = L('Régénération indisponible (SNOW / Éco. énergie)', 'Regeneration unavailable (SNOW / energy saving)'); break; }
        if (W.regen(next)) msg = S('drive_section_regen') + ' → ' + regenName(next); break;
      }
      case 'WINDOWS_OPEN_ALL': allWindows(1); msg = S('shortcuts_action_windows_open'); break;
      case 'WINDOWS_CLOSE_ALL': allWindows(-1); msg = S('shortcuts_action_windows_close'); break;
      case 'AEB_CYCLE': { const v = flip(); const m = v ? sc.aebA : sc.aebB; if (W.aebMode(m)) msg = S('aeb_card_title') + ' → ' + (m === 1 ? S('adas_aeb_alarm') : S('adas_aeb_alarm_brake')); break; }
      case 'SOUND_WARNING': { const v = !c.sound; if (W.bool('sound', v)) msg = S('adas_sound_warning') + ' → ' + onOff(v); break; }
      case 'OVERSPEED_ALARM': { const v = !c.overspeed; if (W.bool('overspeed', v)) msg = S('adas_overspeed_alarm') + ' → ' + onOff(v); break; }
      case 'SPEED_LIMIT_TONE': { const v = !c.speedTone; if (W.bool('speedTone', v)) msg = S('adas_speed_limit_tone') + ' → ' + onOff(v); break; }
      case 'ADAS_CYCLE': { const v = flip(); const m = v ? sc.adasA : sc.adasB; if (W.adas(m)) msg = 'ADAS → ' + adasLabel(m); break; }
      case 'ENERGY_SAVING_TOGGLE': { const v = !c.energy; if (c.driveMode === 'SNOW') { msg = L('Indisponible en mode SNOW', 'Unavailable in SNOW mode'); break; } if (W.energy(v)) msg = S('drive_energy_saving') + ' → ' + onOff(v); break; }
      case 'TSR_TOGGLE': { const v = !c.tsr; if (W.tsr(v)) msg = S('adas_tsr') + ' → ' + onOff(v); break; }
      case 'ESC_TOGGLE': { const v = !c.esc; if (W.esc(v)) msg = 'ESC → ' + onOff(v) + (v ? '' : L(' (anticollision avant coupée aussi)', ' (forward collision also off)')); break; }
      case 'DROWSINESS_TOGGLE': { const v = !c.dms; if (W.bool('dms', v)) msg = S('safety_drowsiness') + ' → ' + onOff(v); break; }
      case 'DROWSINESS_SEN_CYCLE': { const v = c.dmsSen % 3 + 1; if (W.dmsSen(v)) msg = S('safety_drowsiness_sensitivity') + ' → ' + sensName(v); break; }
      case 'SEAT_HEAT_LEFT_CYCLE': if (!k.heat) { msg = L('Sans effet : pas de sièges chauffants sur ce firmware', 'No effect: no heated seats on this firmware'); break; }
        c.seatL = (c.seatL + 1) % 4; msg = S('climate_seat_left') + ' → ' + levelName(c.seatL); break;
      case 'SEAT_HEAT_RIGHT_CYCLE': if (!k.heat) { msg = L('Sans effet : pas de sièges chauffants sur ce firmware', 'No effect: no heated seats on this firmware'); break; }
        c.seatR = (c.seatR + 1) % 4; msg = S('climate_seat_right') + ' → ' + levelName(c.seatR); break;
      case 'STEERING_HEAT_TOGGLE': if (!k.heat) { msg = L('Sans effet : pas de volant chauffant sur ce firmware', 'No effect: no heated steering wheel on this firmware'); break; }
        c.steering = !c.steering; msg = S('climate_steering_heat') + ' → ' + onOff(c.steering); break;
      case 'HVAC_TOGGLE': c.clim.power = !c.clim.power; msg = S('clim_card_title') + ' → ' + onOff(c.clim.power); break;
      case 'HVAC_AC_TOGGLE': c.clim.ac = !c.clim.ac; msg = S('clim_ac') + ' → ' + onOff(c.clim.ac); break;
      case 'AUTO_HIGH_BEAM_TOGGLE': c.highBeam = !c.highBeam; msg = S('lighting_auto_high_beam') + ' → ' + onOff(c.highBeam); break;
      case 'BATTERY_HEAT_TOGGLE': c.batHeat = !c.batHeat; msg = S('batheat_section') + ' → ' + onOff(c.batHeat); break;
      case 'HVAC_TEMP_UP': case 'HVAC_TEMP_DOWN':
        c.clim.temp = clamp(c.clim.temp + (action === 'HVAC_TEMP_UP' ? 1 : -1), c.clim.tMin, c.clim.tMax); msg = S('clim_temperature') + ' → ' + c.clim.temp + ' °C'; break;
      case 'HVAC_FAN_UP': case 'HVAC_FAN_DOWN':
        c.clim.fan = clamp(c.clim.fan + (action === 'HVAC_FAN_UP' ? 1 : -1), c.clim.fMin, c.clim.fMax); msg = S('clim_fan') + ' → ' + c.clim.fan; break;
      case 'DEFROST_FRONT_TOGGLE': c.clim.defF = !c.clim.defF; msg = S('ac_auto_def_front') + ' → ' + onOff(c.clim.defF); break;
      case 'DEFROST_REAR_TOGGLE': c.clim.defR = !c.clim.defR; msg = S('ac_auto_def_rear') + ' → ' + onOff(c.clim.defR); break;
      case 'HVAC_RECIRC_CYCLE': c.clim.loop = (c.clim.loop + 1) % 3; msg = S('clim_section_loop') + ' → ' + loopName(c.clim.loop); break;
      case 'BRIGHTNESS_UP': case 'BRIGHTNESS_DOWN':
        c.brightness = clamp(c.brightness + (action === 'BRIGHTNESS_UP' ? 10 : -10), 5, 100); msg = S('overlay_brightness_title') + ' → ' + c.brightness + ' %'; break;
      case 'MEDIA_NEXT': c.media.track++; msg = '⏭ ' + S('shortcuts_action_media_next'); break;
      case 'MEDIA_PREVIOUS': c.media.track = Math.max(1, c.media.track - 1); msg = '⏮ ' + S('shortcuts_action_media_prev'); break;
      case 'MEDIA_PLAY_PAUSE': c.media.playing = !c.media.playing; msg = (c.media.playing ? '▶ ' : '⏸ ') + S('shortcuts_action_media_play_pause'); break;
      case 'VOLUME_UP': case 'VOLUME_DOWN':
        c.volume = clamp(c.volume + (action === 'VOLUME_UP' ? 1 : -1), 0, c.volMax); msg = L('Volume média → ', 'Media volume → ') + c.volume; break;
      case 'APPLY_PROFILE': {
        const p = extra && state.profiles.find((x) => x.id === extra.profileId);
        if (!p) { hud(L('Profil introuvable : rien n\x27est appliqué', 'Profile not found: nothing is applied')); return ''; }
        applyProfile(p, { manual: true }); return 'profile';
      }
      case 'PROFILE_PICKER': { const s = activeSim(); if (s) s.openPicker(); return 'picker'; }
      case 'HVAC_POPUP': { const s = activeSim(); if (s) s.toggleHvacPopup(); return 'hvac'; }
      case 'OPEN_APP': { const s = activeSim(); if (s) { if (s.ui.screen === 'closed') s.go(state.settings.defaultScreen); } msg = L('MG4Control au premier plan', 'MG4Control brought to front'); break; }
      case 'OPEN_CUSTOM_APP': msg = L('Lancement de ', 'Launching ') + ((extra && extra.app) || '?'); break;
      case 'VEHICLE_POWER_OFF': { const s = activeSim(); if (s) s.askPowerOff(); return 'power'; }
      default: return '';
    }
    commit();
    if (msg) hud(msg);
    return msg;
  }

  // ── Touches du volant : raccourcis classiques et avancés ─────────────────
  const LONG_MS = 500, DOUBLE_MS = 300;
  const keyRt = {};         // état par touche
  const keyListeners = new Set();
  function emit(type, data) { keyListeners.forEach((f) => f(type, data)); }
  const isStar = (code) => code === 17 || code === 286 || code === 18;
  const starSlot = (code) => (code === 17 ? 'btn1' : 'btn2');
  const advActive = () => state.sc.advOn && state.sc.advService && !garage();
  /** Raccourci avancé pour (touche, appui) : d'abord celui du profil actif, puis « Tous les profils ». */
  function advFor(code, press) {
    const list = state.sc.adv.filter((x) => x.key === code && x.press === press);
    return list.find((x) => x.scope && x.scope === state.activeProfileId) || list.find((x) => !x.scope);
  }
  /** Touche interceptée seulement si elle porte une action pour le profil actif (AdvancedShortcuts.isClaimedNow). */
  function claimed(code) {
    if (!advActive()) return false;
    return ['single', 'long', 'double'].some((p) => advFor(code, p));
  }

  function fire(code, press, path) {
    let action = 'NONE', extra = null;
    if (path === 'adv') { const a = advFor(code, press); if (a) { action = a.action; extra = a; } }
    else if (path === 'classic') { const slot = starSlot(code) + '_' + press; action = state.sc.map[slot] || 'NONE'; extra = state.sc.extra[slot]; }
    // Un appui court sans action n'est pas perdu : il est rendu au système (fonction d'origine).
    if (path === 'adv' && action === 'NONE' && press === 'single') { emit('fire', { code, press, path: 'replay', action: null }); return; }
    emit('fire', { code, press, path, action, scope: extra && extra.scope });
    if (action !== 'NONE') runAction(action, extra);
  }

  const JOYSTICK = { 297: 'up', 298: 'down', 299: 'left', 300: 'right', 301: 'ok' };
  function keyDown(code) {
    const rec = instances.find((i) => i.ui.adv.rec);
    if (rec) { rec.captureKey(code); emit('capture', { code }); keyRt[code] = { capture: true }; return; }
    // Popup de profils ouvert → le joystick droit y navigue (service d'accessibilité requis).
    const picker = instances.find((i) => i.ui.overlay && i.ui.overlay.type === 'picker');
    if (picker && JOYSTICK[code] && state.sc.advService && !garage()) {
      keyRt[code] = { capture: true };
      picker.navigate(JOYSTICK[code]);
      emit('nav', { code, dir: JOYSTICK[code] });
      return;
    }
    // Pop-up HVAC ouvert → le joystick y règle directement la clim (même condition).
    const hvac = instances.find((i) => i.ui.overlay && i.ui.overlay.type === 'hvac');
    if (hvac && JOYSTICK[code] && state.sc.advService && !garage()) {
      keyRt[code] = { capture: true };
      hvac.hvacKey(JOYSTICK[code]);
      emit('nav', { code, dir: JOYSTICK[code], popup: 'hvac' });
      return;
    }
    const path = claimed(code) ? 'adv' : (isStar(code) && state.sc.enabled && !garage() ? 'classic' : 'launcher');
    const rt = keyRt[code] = Object.assign(keyRt[code] || {}, { t0: performance.now(), path, longFired: false, dblFired: false, capture: false });
    emit('down', { code, path });
    if (path === 'adv') {
      if (rt.window) {
        clearTimeout(rt.window); rt.window = null;
        if (advFor(code, 'double')) { rt.dblFired = true; fire(code, 'double', path); return; }
        fire(code, 'single', path);   // double disparu entre-temps : le premier appui se conclut en simple
      }
      if (advFor(code, 'long')) rt.longTimer = setTimeout(() => { rt.longTimer = null; rt.longFired = true; fire(code, 'long', path); }, LONG_MS);
    } else if (path === 'classic') {
      rt.longTimer = setTimeout(() => { rt.longTimer = null; rt.longFired = true; fire(code, 'long', path); }, LONG_MS);
    }
  }
  function keyUp(code) {
    const rt = keyRt[code];
    if (!rt) return;
    if (rt.capture) { rt.capture = false; return; }
    if (rt.longTimer) { clearTimeout(rt.longTimer); rt.longTimer = null; }
    const held = Math.round(performance.now() - rt.t0);
    emit('up', { code, path: rt.path, held });
    if (rt.path === 'launcher') { emit('fire', { code, press: held >= LONG_MS ? 'long' : 'single', path: 'launcher', action: null }); return; }
    if (rt.dblFired || rt.longFired) return;
    if (rt.path === 'classic') { fire(code, 'single', 'classic'); return; }
    // Raccourci avancé : attendre la fenêtre du double appui si la touche en porte un.
    // (Un appui maintenu sans appui long configuré se conclut, lui aussi, en appui simple.)
    if (!advFor(code, 'double')) { fire(code, 'single', 'adv'); return; }
    emit('window', { code });
    rt.window = setTimeout(() => { rt.window = null; fire(code, 'single', 'adv'); }, DOUBLE_MS);
  }

  // ── API externe (ExternalApiReceiver) ────────────────────────────────────
  const API_DIRECT = { ONE_PEDAL: 'ONE_PEDAL', ENERGY_SAVING_TOGGLE: 'ENERGY_SAVING_TOGGLE', PROFILE_PICKER: 'PROFILE_PICKER', OPEN_APP: 'OPEN_APP' };
  const API_FORBIDDEN = ['VEHICLE_POWER_OFF', 'ADAS_CYCLE', 'AEB_CYCLE', 'TSR_TOGGLE', 'OVERSPEED_ALARM', 'SPEED_LIMIT_TONE', 'SOUND_WARNING'];
  const CYCLABLE = ['seat_heat_left', 'seat_heat_right', 'steering_heat', 'hvac_power', 'ac', 'hvac_auto', 'hvac_temp', 'hvac_fan', 'hvac_recirc', 'defrost_front', 'defrost_rear'];

  function apiExec(cmd) {
    const log = (ok, t) => ({ ok, text: 'MG4_API  ' + t });
    if (!state.settings.api) return log(false, 'REFUS ' + cmd.intent + ' : API externe désactivée (Réglages → Réglages avancés)');
    if (garage() && cmd.type !== 'read') return log(false, 'REFUS ' + cmd.intent + ' : Mode Garage actif');
    const findProfile = (name) => state.profiles.find((p) => p.name.toLowerCase() === String(name || '').trim().toLowerCase() || p.id === name);
    if (cmd.type === 'direct') {
      const a = API_DIRECT[cmd.action];
      if (!a) return log(false, 'REFUS action inconnue : ' + cmd.action);
      runAction(a); commit();
      return log(true, 'OK ' + cmd.action);
    }
    if (cmd.type === 'execute') {
      if (API_FORBIDDEN.includes(cmd.action)) return log(false, 'REFUS ' + cmd.action + ' : commande volontairement hors API (sécurité active, extinction)');
      if (!ACTION_KEY[cmd.action] || cmd.action === 'NONE') return log(false, 'REFUS action inconnue : ' + cmd.action);
      if (cmd.action === 'APPLY_PROFILE') {
        const p = findProfile(cmd.profile);
        if (!p) return log(false, 'REFUS APPLY_PROFILE : aucun profil nommé "' + (cmd.profile || '') + '"');
        applyProfile(p, { manual: true }); return log(true, 'OK EXECUTE APPLY_PROFILE "' + p.name + '"');
      }
      if (cmd.action === 'OPEN_CUSTOM_APP') {
        const slot = Object.keys(state.sc.extra).find((s) => state.sc.extra[s] && state.sc.extra[s].app);
        const app = slot ? state.sc.extra[slot].app : null;
        if (!app) return log(false, 'REFUS OPEN_CUSTOM_APP : aucune application configurée dans les raccourcis');
        runAction('OPEN_CUSTOM_APP', { app }); return log(true, 'OK EXECUTE OPEN_CUSTOM_APP → ' + app);
      }
      if (!availableActions().includes(cmd.action)) return log(false, 'REFUS ' + cmd.action + ' : indisponible sur ce firmware');
      runAction(cmd.action); commit();
      return log(true, 'OK EXECUTE ' + cmd.action);
    }
    if (cmd.type === 'set') return apiSet(cmd.key, String(cmd.value || '').trim(), log, findProfile);
    return log(false, 'REFUS intent inconnu');
  }

  function apiSet(key, raw, log, findProfile) {
    const c = car(), k = caps();
    const up = raw.toUpperCase();
    const isCycle = ['NEXT', 'PREV', 'TOGGLE'].includes(up);
    if (isCycle && !CYCLABLE.includes(key)) return log(false, 'REFUS SET ' + key + '=' + up + ' : clé non cyclable (drive_mode, regen et profile en sont exclus)');
    if ((key.startsWith('hvac_') || key === 'ac' || key.startsWith('defrost_')) && !k.clim) return log(false, 'IGNORÉ SET ' + key + ' : climatisation non exposée par ce firmware');
    const step = up === 'PREV' ? -1 : 1;
    const parseBool = (cur) => {
      if (isCycle) return !cur;
      if (up === '1' || up === 'TRUE') return true;
      if (up === '0' || up === 'FALSE') return false;
      return undefined;
    };
    const cyc = (cur, min, max) => (cur + step > max ? min : cur + step < min ? max : cur + step);
    let done = '';
    switch (key) {
      case 'drive_mode':
        if (!DRIVE.includes(up)) return log(false, 'REFUS SET drive_mode : valeur invalide (' + raw + ')');
        if (!W.drive(up)) return log(false, 'REFUS SET drive_mode : verrou de vitesse');
        done = up; break;
      case 'regen':
        if (!REGEN_LABEL[up]) return log(false, 'REFUS SET regen : valeur invalide (' + raw + ')');
        if (!gateOk()) return log(false, 'REFUS SET regen : verrou de vitesse');
        if (!W.regen(up)) return log(false, 'REFUS SET regen=' + up + ' : niveau indisponible dans l\'état actuel (SNOW ou Éco. énergie)');
        done = up; break;
      case 'seat_heat_left': case 'seat_heat_right': {
        const f = key === 'seat_heat_left' ? 'seatL' : 'seatR';
        if (!k.heat) return log(false, 'IGNORÉ SET ' + key + ' : pas de sièges chauffants sur ce firmware');
        const v = isCycle ? cyc(c[f], 0, 3) : parseInt(raw, 10);
        if (!(v >= 0 && v <= 3)) return log(false, 'REFUS SET ' + key + ' : valeur de 0 à 3 attendue');
        c[f] = v; done = String(v); break;
      }
      case 'steering_heat': {
        if (!k.heat) return log(false, 'IGNORÉ SET steering_heat : pas de volant chauffant sur ce firmware');
        const v = parseBool(c.steering); if (v === undefined) return log(false, 'REFUS SET steering_heat : 0/1 ou true/false attendu');
        c.steering = v; done = onOff(v); break;
      }
      case 'profile': {
        const p = findProfile(raw); if (!p) return log(false, 'REFUS SET profile : aucun profil nommé "' + raw + '"');
        applyProfile(p, { manual: true }); done = '"' + p.name + '"'; break;
      }
      case 'hvac_power': case 'ac': case 'hvac_auto': case 'defrost_front': case 'defrost_rear': {
        const f = { hvac_power: 'power', ac: 'ac', hvac_auto: 'auto', defrost_front: 'defF', defrost_rear: 'defR' }[key];
        const v = parseBool(c.clim[f]); if (v === undefined) return log(false, 'REFUS SET ' + key + ' : 0/1 attendu');
        c.clim[f] = v; done = onOff(v); break;
      }
      case 'hvac_temp': {
        const v = isCycle ? cyc(c.clim.temp, c.clim.tMin, c.clim.tMax) : Math.round(parseFloat(raw));
        if (isNaN(v)) return log(false, 'REFUS SET hvac_temp : nombre attendu');
        c.clim.temp = clamp(v, c.clim.tMin, c.clim.tMax); done = c.clim.temp + ' °C' + (c.clim.temp !== v ? ' (ramené entre ' + c.clim.tMin + ' et ' + c.clim.tMax + ')' : ''); break;
      }
      case 'hvac_fan': {
        const v = isCycle ? cyc(c.clim.fan, c.clim.fMin, c.clim.fMax) : parseInt(raw, 10);
        if (isNaN(v)) return log(false, 'REFUS SET hvac_fan : nombre attendu');
        c.clim.fan = clamp(v, c.clim.fMin, c.clim.fMax); done = String(c.clim.fan) + (c.clim.fan !== v ? ' (ramené entre ' + c.clim.fMin + ' et ' + c.clim.fMax + ')' : ''); break;
      }
      case 'hvac_recirc': {
        let v = isCycle ? cyc(c.clim.loop, 0, 2) : ({ INNER: 0, OUTSIDE: 1, AUTO: 2, 0: 0, 1: 1, 2: 2 })[up];
        if (v === undefined) return log(false, 'REFUS SET hvac_recirc : INNER, OUTSIDE ou AUTO attendu');
        c.clim.loop = v; done = ['INNER', 'OUTSIDE', 'AUTO'][v]; break;
      }
      default: return log(false, 'REFUS SET : clé inconnue (' + key + ')');
    }
    commit();
    return log(true, 'OK SET ' + key + ' = ' + done);
  }

  /** Ligne du ContentProvider content://com.mg4.control.state/state */
  function providerRow() {
    const c = car(), k = caps();
    const def = state.profiles.find((p) => p.id === state.defaultId);
    return {
      drive_mode: c.driveMode, regen: c.regen,
      seat_heat_left: k.heat ? c.seatL : null, seat_heat_right: k.heat ? c.seatR : null,
      steering_heat: k.heat ? (c.steering ? 1 : 0) : null,
      speed_kmh: c.speed, outside_temp_c: c.outside,
      tsr: k.known ? (c.tsr ? 1 : 0) : null, energy_saving: k.known ? (c.energy ? 1 : 0) : null,
      aeb_enabled: k.known ? (c.aebOn ? 1 : 0) : null, firmware: gen(),
      profiles: state.profiles.map((p) => p.name).join('|'), default_profile: def ? def.name : null
    };
  }

  // ══════════════════════════════════════════════════════════════════════════
  //  Instance de maquette
  // ══════════════════════════════════════════════════════════════════════════
  const ICON_CHEV = '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M6 9l6 6 6-6"/></svg>';

  function appTheme() {
    const t = state.settings.theme;
    if (t === 'dark' || t === 'light') return t;
    const d = document.documentElement.getAttribute('data-theme');
    if (d === 'dark' || d === 'light') return d;
    return window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
  }

  class Sim {
    constructor(el, opts) {
      this.el = el;
      this.id = el.id || ('sim' + instances.length);
      this.hl = null;
      this.ui = {
        screen: opts.screen || 'dashboard',
        tabs: { dash: +(opts.tab || 0), set: 0, sc: 'classic', edit: 0, st: 0 },
        edit: null, overlay: null, dialog: null, toast: null,
        adv: { rec: false, key: null, press: 'single', action: 'NONE', scope: '' },
        logoTaps: 0, updFb: null, apkFb: null,
        winOpen: !!opts.winOpen, regenDraft: null, statsOpen: null
      };
      if (opts.settingsTab != null) this.ui.tabs.set = +opts.settingsTab;
      if (opts.scTab != null) this.ui.tabs.sc = opts.scTab;
      if (opts.statsTab != null) this.ui.tabs.st = +opts.statsTab;
      el.classList.add('sim');
      el.innerHTML = '<div class="sim-viewport"><div class="sim-bezel"><div class="sim-stage"><div class="sim-screen"><div class="app" role="application"></div></div></div></div></div>';
      this.viewport = el.querySelector('.sim-viewport');
      this.bezel = el.querySelector('.sim-bezel');
      this.stage = el.querySelector('.sim-stage');
      this.screenEl = el.querySelector('.sim-screen');
      this.app = el.querySelector('.app');
      this.app.setAttribute('aria-label', L('Maquette interactive de MG4Control', 'Interactive MG4Control mockup'));
      this.bind();
      this.fit();
      // Recalcul à l'image suivante : fit() change la hauteur de l'élément observé, ce qui relancerait
      // l'observateur dans le même cycle (avertissement « ResizeObserver loop » du navigateur).
      if ('ResizeObserver' in window) new ResizeObserver(() => requestAnimationFrame(() => this.fit())).observe(this.viewport);
      else window.addEventListener('resize', () => this.fit());
      if (opts.screen === 'profileEdit') this.startEdit(state.profiles[0]);
      instances.push(this);
      this.render();
    }

    fit() {
      // Largeur réelle arrondie vers le bas : un conteneur à fraction de pixel ferait apparaître
      // une barre de défilement pour un dépassement de moins d'un pixel.
      const avail = Math.floor(this.viewport.getBoundingClientRect().width) - 20;
      if (avail <= 0) return;
      const k = Math.max(avail / 1280, window.innerWidth < 700 ? 0.5 : 0.3);
      this.stage.style.width = Math.floor(1280 * k) + 'px';
      this.stage.style.height = Math.floor(480 * k) + 'px';
      this.bezel.style.width = Math.floor(1280 * k) + 20 + 'px';
      this.screenEl.style.setProperty('--k', k);
      this.el.classList.toggle('sim-scrolls', 1280 * k > avail + 1);
    }

    // ── Navigation ─────────────────────────────────────────────────────────
    go(screen, tab) {
      if (screen === 'audio') { screen = 'automation'; state.door.open = true; }
      this.ui.screen = screen;
      if (tab != null) {
        const key = { dashboard: 'dash', settings: 'set', shortcuts: 'sc', profileEdit: 'edit', stats: 'st' }[screen];
        if (key) this.ui.tabs[key] = key === 'sc' ? String(tab) : +tab;
      }
      if (screen === 'profileEdit' && !this.ui.edit) this.startEdit(state.profiles[0]);
      this.render();
    }
    toast(text, ms) {
      clearTimeout(this._toastT);
      this.ui.toast = text;
      this.render();
      this._toastT = setTimeout(() => { this.ui.toast = null; this.render(); }, ms || 2400);
    }
    highlight(key) { this.hl = key; this.applyHl(); }
    applyHl() {
      this.app.querySelectorAll('.hl-on').forEach((n) => n.classList.remove('hl-on'));
      if (!this.hl) return;
      const nodes = this.app.querySelectorAll('[data-hl~="' + this.hl + '"]');
      nodes.forEach((n) => n.classList.add('hl-on'));
      const first = nodes[0];
      const scroller = first && first.closest('.a-scroll');
      if (first && scroller) {
        // Bloc entièrement visible s'il tient dans la zone (cadre compris), sinon aligné en haut.
        // Positions mesurées à l'écran puis ramenées à l'échelle de l'app (la maquette est réduite
        // par transform) : offsetTop dépendrait du premier parent positionné.
        const a = first.getBoundingClientRect(), b = scroller.getBoundingClientRect();
        const k = b.height / scroller.offsetHeight || 1;
        const view = scroller.clientHeight, st = scroller.scrollTop;
        const top = (a.top - b.top) / k - scroller.clientTop + st, h = a.height / k;
        let t = st;
        if (top - 10 < st) t = top - 10;
        else if (top + h + 10 > st + view) t = h + 20 <= view ? top + h + 10 - view : top - 10;
        if (t !== st) scroller.scrollTop = Math.max(0, t);
      }
    }

    // ── Rendu ──────────────────────────────────────────────────────────────
    viewKey() { const u = this.ui; return u.screen + ':' + (u.screen === 'dashboard' ? u.tabs.dash : u.screen === 'settings' ? u.tabs.set : u.screen === 'shortcuts' ? u.tabs.sc : u.screen === 'profileEdit' ? u.tabs.edit : u.screen === 'stats' ? u.tabs.st : ''); }
    render() {
      const sc = this.app.querySelector('.a-scroll');
      const st = sc ? sc.scrollTop : 0;
      const key = this.viewKey();
      const focused = document.activeElement && this.app.contains(document.activeElement) ? document.activeElement.getAttribute('data-fid') : null;
      this.app.setAttribute('data-app-theme', appTheme());
      this.app.setAttribute('lang', appLang());
      this.app.setAttribute('data-ts', state.settings.textSize);
      this.app.innerHTML = this.html();
      const sc2 = this.app.querySelector('.a-scroll');
      if (sc2 && key === this._lastKey) sc2.scrollTop = st;
      this._lastKey = key;
      if (focused) { const f = this.app.querySelector('[data-fid="' + focused + '"]'); if (f) { f.focus(); if (f.setSelectionRange && f.type === 'text') { const n = f.value.length; f.setSelectionRange(n, n); } } }
      this.app.querySelectorAll('input[type=range]').forEach(paintRange);
      if (this.hl) this.applyHl();
    }

    html() {
      const u = this.ui, k = caps();
      if (u.screen === 'closed') {
        return '<div class="a-closed"><div>' + esc(L('MG4Control a été fermée.', 'MG4Control was closed.')) + '</div><button class="b" data-a="reopen">' + esc(L('Relancer l\'application', 'Relaunch the app')) + '</button></div>';
      }
      let out = this.topbar() + '<div class="a-main">' + this.screenHtml() + '</div>';
      if (!state.car.on) out += '<div class="a-off-badge">' + esc(L('Véhicule hors tension, écran actif', 'Vehicle powered off, screen on')) + '</div>';
      // Notification persistante du service en Mode Garage
      if (garage()) out += '<div class="a-garage-badge" data-hl="garage-notif">🔧 ' + esc(S('notif_garage_mode')) + '</div>';
      if (u.overlay) out += this.overlayHtml();
      let dlg = u.dialog;
      if (!dlg && k.g === 'UNKNOWN' && !state.fwDismissed) dlg = { type: 'fwUnknown' };
      if (dlg) out += this.dialogHtml(dlg);
      if (u.toast) out += '<div class="a-toast" role="status">' + esc(u.toast) + '</div>';
      return out;
    }

    topbar() {
      const s = this.ui.screen;
      const nav = (id, key) =>
        '<button class="a-nav' + (s === id ? ' on' : '') + '" data-a="nav" data-v="' + id + '" data-hl="nav nav-' + id + '">' + esc(S(key)) + '</button>';
      // Ordre de activity_main.xml : les cinq onglets se partagent la largeur laissée par le logo.
      return '<div class="a-top" data-hl="topbar"><div class="a-logo" data-a="logo" data-hl="logo"><span class="lg"><span class="mg">MG</span><span class="four">4</span><span class="ctl">Control</span></span></div>' +
        nav('profiles', 'nav_profiles') + nav('automation', 'nav_automation') + nav('shortcuts', 'nav_shortcuts') +
        nav('stats', 'nav_stats') + nav('settings', 'nav_settings') + '</div>';
    }

    screenHtml() {
      switch (this.ui.screen) {
        case 'profiles': return this.profilesHtml();
        case 'profileEdit': return this.editHtml();
        case 'settings': return this.settingsHtml();
        case 'shortcuts': return this.shortcutsHtml();
        case 'automation': return this.automationHtml();
        case 'stats': return this.statsHtml();
        case 'audio': return this.automationHtml();   // ancien onglet Audio : sa carte vit dans Automatisation
        default: return this.dashHtml();
      }
    }

    /** Rail de catégories : un onglet sans contenu visible est masqué. */
    railLayout(tabKey, tabs, extraCls) {
      const id = (t, i) => (t.id != null ? t.id : String(i));
      const usable = tabs.map((t, i) => (t.page && !t.hidden ? i : -1)).filter((i) => i >= 0);
      let cur = tabs.findIndex((t, i) => id(t, i) === String(this.ui.tabs[tabKey]));
      if (cur < 0 || !tabs[cur].page) cur = usable.length ? usable[0] : 0;
      const rail = tabs.map((t, i) => {
        if (!t.page || (t.hidden && i !== cur)) return '';
        return '<button class="a-tab' + (t.sub ? ' sub' : '') + (i === cur ? ' on' : '') + '" data-a="tab" data-v="' + tabKey + ':' + id(t, i) + '" data-hl="rail' + (t.hl ? ' ' + t.hl : '') + '">' + esc(t.label) + '</button>';
      }).join('');
      return '<div class="a-body ' + (extraCls || '') + '"><div class="a-rail" data-hl="rail">' + rail + '</div><div class="a-vline"></div><div class="a-scroll"><div class="a-stack">' + (tabs[cur] ? tabs[cur].page : '') + '</div></div></div>';
    }

    // ── Dashboard ──────────────────────────────────────────────────────────
    dashHtml() {
      return this.railLayout('dash', [
        { label: S('profile_cat_drive'), page: this.dashDrive() },
        { label: S('profile_cat_safety'), page: this.dashSafety() },
        { label: S('profile_cat_comfort'), page: this.dashComfort() }
      ]);
    }
    dashDrive() {
      const c = car(), k = caps();
      const snow = c.driveMode === 'SNOW';
      const dm = (m) => {
        const on = c.driveMode === m;
        const v = on ? (m === 'ECO' ? ' eco' : m === 'SPORT' ? ' warn' : '') : '';
        return '<button class="b' + (on ? ' on' : '') + v + '" data-a="drive" data-v="' + m + '">' + esc(S('drive_' + m.toLowerCase())) + '</button>';
      };
      const regenOk = !snow && !c.energy;
      const rg = (l, key) => {
        const en = regenOk || (l === 'ONE_PEDAL' && !snow);
        return '<button class="b' + (c.regen === l ? ' on' : '') + (en ? '' : ' dis') + '" data-a="regen" data-v="' + l + '"' + (en ? '' : ' disabled') + '>' + esc(S(key)) + '</button>';
      };
      const energy = k.known ? '<button class="b full' + (c.energy ? ' on' : '') + (snow ? ' dis' : '') + '" data-a="energy" data-hl="energy"' + (snow ? ' disabled' : '') + ' style="margin-top:5px">' + esc(S('drive_energy_saving')) + '</button>' : '';
      const custom = c.driveMode === 'CUSTOM' ? this.customCard(c.custom, 'custom', 'custom') : '';
      return '<div class="a-sec" data-hl="drive"><div class="a-h">' + esc(S('drive_section_mode')) + '</div>' +
        '<div class="a-grid g2">' + dm('ECO') + dm('NORMAL') + dm('SPORT') + dm('SNOW') + '</div>' +
        '<div class="a-grid" style="margin-top:5px">' + dm('CUSTOM') + '</div>' + energy + '</div>' + custom +
        '<div class="a-sec" data-hl="regen"><div class="a-h">' + esc(S('drive_section_regen')) + '</div><div class="a-grid g2">' +
        rg('OFF', 'regen_off') + rg('LOW', 'regen_low') + rg('MEDIUM', 'regen_medium') + rg('HIGH', 'regen_high') + rg('ADAPTIVE', 'regen_adaptive_short') + rg('ONE_PEDAL', 'regen_one_pedal_short') +
        '</div></div>' + this.batHeatSection();
    }
    /** Chauffage intelligent de la batterie : ON / OFF, état, rappel de la coupure automatique. */
    batHeatSection() {
      const c = car(), bh = state.auto.bh;
      if (!caps().batHeat) return '';
      const b = (on, key) => '<button class="b' + (c.batHeat === on ? ' on' : '') + '" data-a="batHeat" data-v="' + (on ? 1 : 0) + '">' + esc(S(key)) + '</button>';
      const status = !c.batHeat ? S('batheat_status_off') : bh.on ? S('batheat_status_countdown', bh.minutes) : S('batheat_status_stays_on');
      return '<div class="a-sec" data-hl="batheat"><div class="a-h">' + esc(S('batheat_section')) + '</div><div class="a-grid g2">' + b(true, 'common_on') + b(false, 'common_off') + '</div>' +
        '<div class="a-desc" style="margin-top:6px">' + esc(status) + '</div><div class="a-desc">' + esc(S('batheat_hint')) + '</div></div>';
    }
    /** Carte « Mode personnalisé » : puissance, direction, pédale (index 0/1/2). */
    customCard(vals, act, hl) {
      const row = (field, labelKey, keys) => '<div class="a-row"><span class="a-sub" style="min-width:110px;margin:0">' + esc(S(labelKey)) + '</span><div class="a-grid g3" style="flex:1">' +
        keys.map((k, i) => '<button class="b' + (vals[field] === i ? ' on' : '') + '" data-a="' + act + '" data-v="' + field + ':' + i + '">' + esc(S(k)) + '</button>').join('') + '</div></div>';
      return '<div class="a-sec" data-hl="' + hl + '"><div class="a-h">' + esc(S('drive_section_custom')) + '</div>' +
        row('power', 'drive_custom_power', ['drive_eco', 'drive_normal', 'drive_sport']) +
        row('steer', 'drive_custom_steering', ['drive_custom_comfort', 'drive_normal', 'drive_sport']) +
        row('pedal', 'drive_custom_pedal', ['drive_custom_comfort', 'drive_normal', 'drive_sport']) + '</div>';
    }
    adasButtons(cur, act) {
      const k = caps();
      const b = (i, key) => '<button class="b' + (cur === i ? ' on' : '') + '" data-a="' + act + '" data-v="' + i + '">' + esc(S(key)) + '</button>';
      return '<div class="a-grid g3">' + b(0, 'adas_off') + b(1, 'adas_limiteur_short') + b(2, 'adas_auto') + '</div>' +
        '<div class="a-grid g3" style="margin-top:5px">' + b(3, 'adas_acc') + b(4, k.fifth) + '<span></span></div>';
    }
    dashSafety() {
      const c = car(), k = caps();
      let h = '<div class="a-sec" data-hl="adas"><div class="a-h">ADAS</div>' + this.adasButtons(c.adas, 'adas') + '</div>';
      // Carte Alertes
      const tsr = k.known ? '<div data-hl="tsr"><div class="a-row"><span class="lbl">' + esc(S('adas_tsr')) + '</span>' + sw(c.tsr, 'tsr') + '</div></div>' : '';
      let alerts;
      if (k.twoAlerts) {
        const en = !k.known || c.tsr;
        alerts = '<div class="' + (en ? '' : 'dim') + '">' +
          '<div class="a-row"><span class="lbl">' + esc(S('adas_overspeed_alarm')) + '</span>' + sw(c.overspeed, 'overspeed', !en) + '</div>' +
          '<div class="a-row"><span class="lbl">' + esc(S('adas_speed_limit_tone')) + '</span>' + sw(c.speedTone, 'speedTone', !en) + '</div></div>';
      } else {
        alerts = '<div class="a-row"><span class="lbl">' + esc(S('adas_sound_warning')) + '</span>' + sw(c.sound, 'sound') + '</div>';
      }
      h += '<div class="a-card" data-hl="alerts"><div class="a-h">' + esc(S('card_alerts')) + '</div><div class="a-sub">' + esc(S('adas_section_sounds')) + '</div>' +
        '<div class="a-cols sep">' + (tsr ? '<div>' + tsr + '</div>' : '') + '<div data-hl="alerts-sound">' + alerts + '</div></div></div>';
      if (k.known) {
        const md = (m, key) => '<button class="b' + (c.aebMode === m ? ' on' : '') + (c.aebOn ? '' : ' dis') + '" data-a="aebMode" data-v="' + m + '"' + (c.aebOn ? '' : ' disabled') + '>' + esc(S(key)) + '</button>';
        const sn = (s, key) => '<button class="b' + (c.aebSen === s ? ' on' : '') + '" data-a="aebSen" data-v="' + s + '">' + esc(S(key)) + '</button>';
        h += '<div class="a-card" data-hl="aeb"><div class="a-h">' + esc(S('aeb_card_title')) + '</div><div class="a-cols sep">' +
          '<div><div class="a-sub">' + esc(S('elk_section_toggle')) + '</div><div class="a-row" style="justify-content:center">' + sw(c.aebOn, 'aebOn') + '</div></div>' +
          '<div><div class="a-sub">' + esc(S('elk_section_mode')) + '</div><div class="a-grid">' + md(1, 'adas_aeb_alarm') + md(2, 'adas_aeb_alarm_brake') + '</div></div>' +
          '<div><div class="a-sub">' + esc(S('aeb_section_sensitivity')) + '</div><div class="a-grid">' + sn(1, 'aeb_sensitivity_low') + sn(2, 'aeb_sensitivity_standard') + sn(3, 'aeb_sensitivity_high') + '</div></div>' +
          '</div></div>';
      }
      // ELK
      const elkOn = c.elkMode !== ELK.OFF;
      const em = (m, key) => '<button class="b' + (c.elkMode === m ? ' on' : '') + (elkOn ? '' : ' dis') + '" data-a="elkMode" data-v="' + m + '"' + (elkOn ? '' : ' disabled') + '>' + esc(S(key)) + '</button>';
      const es = (s, key) => '<button class="b' + (c.elkSen === s ? ' on' : '') + (elkOn ? '' : ' dis') + '" data-a="elkSen" data-v="' + s + '"' + (elkOn ? '' : ' disabled') + '>' + esc(S(key)) + '</button>';
      const act = k.s132
        ? '<div class="a-row"><span class="lbl">' + esc(S('elk_label_enable')) + '</span>' + sw(elkOn, 'elkOn') + '</div>' +
          '<div class="a-row"><span class="lbl">' + esc(S('elk_sound_warning')) + '</span>' + sw(c.elkSound, 'elkSound', !elkOn) + '</div>' +
          '<div class="a-row"><span class="lbl">' + esc(S('elk_vibration_reminder')) + '</span>' + sw(c.elkVib, 'elkVib', !elkOn) + '</div>'
        : '<div class="a-row" style="justify-content:center">' + sw(elkOn, 'elkOn') + '</div>';
      h += '<div class="a-card" data-hl="elk"><div class="a-h">' + esc(S('elk_card_title')) + '</div><div class="a-cols sep">' +
        '<div><div class="a-sub">' + esc(S('elk_section_toggle')) + '</div>' + act + '</div>' +
        '<div><div class="a-sub">' + esc(S('elk_section_mode')) + '</div><div class="a-grid">' + em(ELK.ALERT, 'elk_mode_alert') + em(ELK.ASSIST, 'elk_mode_assist') + (k.s132 ? '' : em(ELK.EMERGENCY, 'elk_mode_emergency')) + '</div></div>' +
        '<div><div class="a-sub">' + esc(S('elk_section_sensitivity')) + '</div><div class="a-grid">' + es(1, 'elk_sensitivity_low') + es(2, 'elk_sensitivity_standard') + es(3, 'elk_sensitivity_high') + '</div></div>' +
        '</div></div>';
      // ESC + somnolence
      if (k.esc) {
        const pair = (act2, val) => '<div class="a-grid g2"><button class="b' + (val ? ' on' : '') + '" data-a="' + act2 + '" data-v="1">' + esc(S('common_on')) + '</button><button class="b' + (!val ? ' on' : '') + '" data-a="' + act2 + '" data-v="0">' + esc(S('common_off')) + '</button></div>';
        const sn = (s, key) => '<button class="b' + (c.dmsSen === s ? ' on' : '') + '" data-a="dmsSen" data-v="' + s + '">' + esc(S(key)) + '</button>';
        h += '<div class="a-card" data-hl="esc"><div class="a-cols">' +
          '<div data-hl="esc-esc"><div class="a-sub">' + esc(S('safety_esc')) + '</div>' + pair('esc', c.esc) + '</div>' +
          '<div data-hl="esc-dms"><div class="a-sub">' + esc(S('safety_drowsiness')) + '</div>' + pair('dms', c.dms) + '</div>' +
          '</div><div class="a-sub" style="margin-top:10px">' + esc(S('safety_drowsiness_sensitivity')) + '</div><div class="a-grid g3" data-hl="esc-dms">' + sn(1, 'sens_low') + sn(2, 'sens_medium') + sn(3, 'sens_high') + '</div></div>';
      }
      return h;
    }
    dashComfort() {
      const c = car(), k = caps();
      let h = '';
      if (k.heat) {
        const seat = (side, lvl) => {
          const b = (n, key) => '<button class="b' + (lvl === n ? ' on' : '') + '" data-a="seat' + side + '" data-v="' + n + '">' + esc(S(key)) + '</button>';
          return '<div class="a-grid g2">' + b(0, 'climate_off') + b(1, 'climate_level_1') + b(2, 'climate_level_2') + b(3, 'climate_level_3') + '</div>';
        };
        h += '<div class="a-card" data-hl="heat"><div class="a-h">' + esc(S('card_climate')) + '</div><div class="a-cols sep">' +
          '<div data-hl="heat-steer"><div class="a-sub">' + esc(S('climate_steering_heat')) + '</div><div class="a-row" style="justify-content:center;min-height:100px">' + sw(c.steering, 'steer') + '</div></div>' +
          '<div data-hl="heat-seats"><div class="a-sub">' + esc(S('climate_seat_left')) + '</div>' + seat('L', c.seatL) + '</div>' +
          '<div data-hl="heat-seats"><div class="a-sub">' + esc(S('climate_seat_right')) + '</div>' + seat('R', c.seatR) + '</div>' +
          '</div></div>';
      }
      if (k.clim) {
        const cl = c.clim;
        h += '<div class="a-card" data-hl="clim"><div class="a-h">' + esc(S('clim_card_title')) + '</div>' +
          '<div class="a-cols sep"><div><div class="a-row"><span class="lbl">' + esc(S('clim_temperature')) + '</span><span class="a-val" data-out="climTemp">' + cl.temp + '°</span></div>' +
          range('climTemp', cl.tMin, cl.tMax, cl.temp) + '</div>' +
          '<div><div class="a-row"><span class="lbl">' + esc(S('clim_fan')) + '</span><span class="a-val" data-out="climFan">' + cl.fan + '</span></div>' +
          range('climFan', cl.fMin, cl.fMax, cl.fan) + '</div></div></div>';
        const tg = (a, on, key) => '<button class="b' + (on ? ' on' : '') + '" data-a="' + a + '">' + esc(S(key)) + '</button>';
        const lp = (n, key) => '<button class="b' + (cl.loop === n ? ' on' : '') + '" data-a="climLoop" data-v="' + n + '">' + esc(S(key)) + '</button>';
        h += '<div class="a-card" data-hl="climmodes"><div class="a-h">' + esc(S('clim_modes_title')) + '</div><div class="a-cols sep">' +
          '<div><div class="a-sub">' + esc(S('clim_section_switches')) + '</div><div class="a-grid">' + tg('climPower', cl.power, 'clim_power') + tg('climAc', cl.ac, 'clim_ac') + tg('climAuto', cl.auto, 'clim_auto') + '</div></div>' +
          '<div><div class="a-sub">' + esc(S('clim_section_loop')) + '</div><div class="a-grid">' + lp(0, 'clim_loop_inner') + lp(1, 'clim_loop_outside') + lp(2, 'clim_loop_auto') + '</div></div>' +
          '</div></div>';
        // Sens de l'air : boutons cumulables ; la lunette arrière est le dégivrage arrière.
        const ab = (a, on, key, icon) => '<button class="b air' + (on ? ' on' : '') + '" data-a="' + a + '">' + AIR_ICON[icon] + '<span>' + esc(S(key)) + '</span></button>';
        h += '<div class="a-card" data-hl="airflow"><div class="a-h">' + esc(S('clim_section_airflow')) + '</div><div class="a-grid g4">' +
          ab('airFace', cl.air.face, 'clim_air_face', 'face') + ab('airFeet', cl.air.feet, 'clim_air_feet', 'feet') +
          ab('airWs', cl.air.ws, 'clim_air_windshield_front', 'ws') + ab('climDefR', cl.defR, 'clim_air_windshield_rear', 'rear') + '</div></div>';
      }
      if (k.beam) {
        const hb = (on, key) => '<button class="b' + (c.highBeam === on ? ' on' : '') + '" data-a="highBeam" data-v="' + (on ? 1 : 0) + '">' + esc(S(key)) + '</button>';
        h += '<div class="a-card" data-hl="highbeam"><div class="a-h">' + esc(S('card_lighting')) + '</div><div class="a-row"><span class="lbl">' + esc(S('lighting_auto_high_beam')) + '</span>' +
          '<div class="a-grid g2" style="flex:0 0 260px">' + hb(true, 'common_on') + hb(false, 'common_off') + '</div></div></div>';
      }
      return h;
    }

    // ── Profils ────────────────────────────────────────────────────────────
    profileSummary(p) {
      const k = caps();
      let a = '';
      if (k.g === 'SWI133') a = ['ADAS Off', 'Lim.', 'Auto', 'ACC', 'ICA'][p.adas];
      else if (k.vsm) a = p.adas === 3 ? 'ACC' : p.adas === 4 ? 'TJA' : 'ADAS Off';
      return ': ' + DRIVE_LABEL[p.driveMode] + ' · ' + REGEN_LABEL[p.regen] + (a ? ' · ' + a : '');
    }
    profilesHtml() {
      const items = state.profiles.map((p) => {
        const def = p.id === state.defaultId;
        return '<div class="p-item" data-hl="plist"><div class="p-top"><span class="p-name' + (def ? ' def' : '') + '">' + esc(p.name) + '</span><span class="p-sum">' + esc(this.profileSummary(p)) + '</span>' +
          (def ? '<span class="p-badge" data-hl="pdefault">' + esc(S('profile_default_badge')) + '</span>' : '') + '</div><div class="p-line"></div><div class="p-btns" data-hl="pbtns">' +
          '<button class="b primary" data-a="pApply" data-v="' + p.id + '">' + esc(S('profile_apply_now')) + '</button>' +
          '<button class="b" data-a="pEdit" data-v="' + p.id + '">' + esc(S('profile_edit')) + '</button>' +
          '<button class="b" data-a="pDefault" data-v="' + p.id + '">' + esc(S('profile_set_default')) + '</button>' +
          '<button class="b danger" data-a="pDel" data-v="' + p.id + '">' + esc(S('profile_delete')) + '</button></div></div>';
      }).join('');
      const empty = '<div class="a-desc" style="text-align:center;padding:40px 0">' + esc(L('Aucun profil. Créez-en un avec « Nouveau profil ».', 'No profile yet. Create one with “New profile”.')) + '</div>';
      return '<div class="a-page"><div class="a-scroll">' + (items || empty) + '</div>' +
        '<button class="b green full" data-a="pNew" data-hl="pnew">' + esc(S('profile_add')) + ' (' + state.profiles.length + '/5)</button>' +
        '<button class="b close" data-a="close">' + esc(S('nav_close')) + '</button></div>';
    }

    startEdit(p) {
      this.ui.edit = p ? Object.assign({}, p, { hvac: Object.assign({}, p.hvac), _new: false, _default: p.id === state.defaultId })
        : Object.assign(mkProfile({ name: '' }), { _new: true, _default: state.profiles.length === 0 });
      this.ui.tabs.edit = 0;
    }
    editHtml() {
      const e = this.ui.edit; if (!e) return '';
      const head = '<div class="a-card" style="padding:10px 14px;border-radius:12px"><div class="a-row" style="min-height:40px">' +
        '<span class="a-title" style="flex:0 0 auto">' + esc(e._new ? S('profile_add') : S('profile_edit')) + '</span>' +
        '<input class="a-input" style="flex:1" data-c="eName" data-fid="eName" maxlength="24" placeholder="' + esc(S('profile_name_hint')) + '" value="' + esc(e.name) + '" data-hl="ename">' +
        '<span class="lbl" style="flex:0 0 auto;font-size:15px">' + esc(S('profile_set_as_default')) + '</span>' + sw(e._default, 'eDefault') + '</div></div>';
      const body = this.railLayout('edit', [
        { label: S('profile_cat_drive'), page: this.editDrive(e) },
        { label: S('profile_cat_safety'), page: this.editSafety(e) },
        { label: S('profile_cat_comfort'), page: this.editComfort(e) }
      ]);
      const foot = '<div class="a-grid g2"><button class="b" data-a="eCancel">' + esc(S('profile_cancel')) + '</button><button class="b green" data-a="eSave" data-hl="esave">' + esc(S('profile_save')) + '</button></div>';
      return '<div class="a-page">' + head + body + foot + '</div>';
    }
    editDrive(e) {
      const k = caps(), snow = e.driveMode === 'SNOW';
      const dm = (m) => { const on = e.driveMode === m; return '<button class="b' + (on ? ' on' : '') + (on && m === 'ECO' ? ' eco' : on && m === 'SPORT' ? ' warn' : '') + '" data-a="eDrive" data-v="' + m + '">' + esc(S('drive_' + m.toLowerCase())) + '</button>'; };
      const ok = !snow && !e.energy;
      const rg = (l, key) => { const en = ok || (l === 'ONE_PEDAL' && !snow); return '<button class="b' + (e.regen === l ? ' on' : '') + (en ? '' : ' dis') + '" data-a="eRegen" data-v="' + l + '"' + (en ? '' : ' disabled') + '>' + esc(S(key)) + '</button>'; };
      const custom = e.driveMode === 'CUSTOM'
        ? this.customCard({ power: e.customPower, steer: e.customSteer, pedal: e.customPedal }, 'eCustom', 'p-custom') : '';
      return '<div class="a-sec"><div class="a-h">' + esc(S('drive_section_mode')) + '</div><div class="a-grid g3">' + dm('ECO') + dm('NORMAL') + dm('SPORT') + dm('SNOW') + dm('CUSTOM') + '</div>' +
        (k.known ? '<button class="b full' + (e.energy ? ' on' : '') + (snow ? ' dis' : '') + '" style="margin-top:5px" data-a="eEnergy"' + (snow ? ' disabled' : '') + '>' + esc(S('drive_energy_saving')) + '</button>' : '') + '</div>' + custom +
        '<div class="a-sec"><div class="a-h">' + esc(S('drive_section_regen')) + '</div><div class="a-grid g3">' +
        rg('OFF', 'regen_off') + rg('LOW', 'regen_low') + rg('MEDIUM', 'regen_medium') + rg('HIGH', 'regen_high') + rg('ADAPTIVE', 'regen_adaptive_short') + rg('ONE_PEDAL', 'regen_one_pedal_short') + '</div></div>';
    }
    editSafety(e) {
      const k = caps();
      let h = '';
      if (k.known) h += '<div class="a-sec"><div class="a-h">' + esc(S('adas_tsr')) + '</div><div class="a-row"><span class="lbl">' + esc(S('adas_tsr')) + '</span>' + sw(e.tsr, 'eTsr') + '</div></div>';
      const alerts = k.twoAlerts
        ? '<div class="a-row"><span class="lbl">' + esc(S('adas_overspeed_alarm')) + '</span>' + sw(e.overspeed, 'eOverspeed') + '</div><div class="a-row"><span class="lbl">' + esc(S('adas_speed_limit_tone')) + '</span>' + sw(e.speedTone, 'eSpeedTone') + '</div>'
        : '<div class="a-row"><span class="lbl">' + esc(S('adas_sound_warning')) + '</span>' + sw(e.sound, 'eSound') + '</div>';
      h += '<div class="a-sec"><div class="a-h">ADAS</div>' + alerts + this.adasButtons(e.adas, 'eAdas') + '</div>';
      if (k.known) {
        const b = (a, v, cur, key, dis) => '<button class="b' + (cur === v ? ' on' : '') + (dis ? ' dis' : '') + '" data-a="' + a + '" data-v="' + v + '"' + (dis ? ' disabled' : '') + '>' + esc(S(key)) + '</button>';
        h += '<div class="a-sec" data-hl="p-aeb"><div class="a-h">' + esc(S('adas_section_aeb')) + '</div><div class="a-row"><span class="lbl">' + esc(S('adas_aeb_switch')) + '</span>' + sw(e.aebOn, 'eAebOn') + '</div>' +
          '<div class="a-grid g2">' + b('eAebMode', 1, e.aebMode, 'adas_aeb_alarm', !e.aebOn) + b('eAebMode', 2, e.aebMode, 'adas_aeb_alarm_brake', !e.aebOn) + '</div>' +
          '<div class="a-grid g3" style="margin-top:5px">' + b('eAebSen', 1, e.aebSen, 'aeb_sensitivity_low', !e.aebOn) + b('eAebSen', 2, e.aebSen, 'aeb_sensitivity_standard', !e.aebOn) + b('eAebSen', 3, e.aebSen, 'aeb_sensitivity_high', !e.aebOn) + '</div></div>';
        const dis = !e.elkOn;
        h += '<div class="a-sec"><div class="a-h">' + esc(S('elk_card_title')) + '</div><div class="a-row"><span class="lbl">' + esc(S('elk_profile_label')) + '</span>' + sw(e.elkOn, 'eElkOn') + '</div>' +
          (k.s132 ? '<div class="a-row"><span class="lbl">' + esc(S('elk_sound_warning')) + '</span>' + sw(e.elkSound, 'eElkSound', dis) + '</div><div class="a-row"><span class="lbl">' + esc(S('elk_vibration_reminder')) + '</span>' + sw(e.elkVib, 'eElkVib', dis) + '</div>' : '') +
          '<div class="a-grid ' + (k.s132 ? 'g2' : 'g3') + '">' + b('eElkMode', ELK.ALERT, e.elkMode, 'elk_mode_alert', dis) + b('eElkMode', ELK.ASSIST, e.elkMode, 'elk_mode_assist', dis) + (k.s132 ? '' : b('eElkMode', ELK.EMERGENCY, e.elkMode, 'elk_mode_emergency', dis)) + '</div>' +
          '<div class="a-grid g3" style="margin-top:5px">' + b('eElkSen', 1, e.elkSen, 'elk_sensitivity_low', dis) + b('eElkSen', 2, e.elkSen, 'elk_sensitivity_standard', dis) + b('eElkSen', 3, e.elkSen, 'elk_sensitivity_high', dis) + '</div></div>';
      }
      if (k.esc) {
        const b = (a, v, cur, key) => '<button class="b' + (cur === v ? ' on' : '') + '" data-a="' + a + '" data-v="' + v + '">' + esc(S(key)) + '</button>';
        h += '<div class="a-sec" data-hl="p-esc"><div class="a-h">' + esc(S('safety_esc')) + '</div><div class="a-grid g2">' + b('eEsc', 1, e.esc ? 1 : 0, 'common_on') + b('eEsc', 0, e.esc ? 1 : 0, 'common_off') + '</div>' +
          '<div class="a-sub" style="margin-top:8px">' + esc(S('safety_drowsiness')) + '</div><div class="a-grid g2">' + b('eDms', 1, e.dms ? 1 : 0, 'common_on') + b('eDms', 0, e.dms ? 1 : 0, 'common_off') + '</div>' +
          '<div class="a-sub" style="margin-top:8px">' + esc(S('safety_drowsiness_sensitivity')) + '</div><div class="a-grid g3">' + b('eDmsSen', 1, e.dmsSen, 'sens_low') + b('eDmsSen', 2, e.dmsSen, 'sens_medium') + b('eDmsSen', 3, e.dmsSen, 'sens_high') + '</div></div>';
      }
      return h;
    }
    editComfort(e) {
      const k = caps();
      let h = '';
      if (k.heat) {
        const b = (a, v, cur, key, dis) => '<button class="b' + (cur === v ? ' on' : '') + (dis ? ' dis' : '') + '" data-a="' + a + '" data-v="' + v + '"' + (dis ? ' disabled' : '') + '>' + esc(S(key)) + '</button>';
        h += '<div class="a-sec" data-hl="p-heat"><div class="a-row"><span class="lbl"><b>' + esc(S('climate_steering_heat')) + '</b></span>' + sw(e.steeringApply, 'eSteerApply') + '</div>' +
          '<div class="a-grid g2">' + b('eSteer', 0, e.steeringOn ? 1 : 0, 'climate_off', !e.steeringApply) + b('eSteer', 1, e.steeringOn ? 1 : 0, 'climate_on', !e.steeringApply) + '</div></div>';
        const lv = (a, cur) => b(a, 0, cur, 'climate_off', !e.seatApply) + b(a, 1, cur, 'climate_level_1', !e.seatApply) + b(a, 2, cur, 'climate_level_2', !e.seatApply) + b(a, 3, cur, 'climate_level_3', !e.seatApply);
        h += '<div class="a-sec" data-hl="p-heat"><div class="a-row"><span class="lbl"><b>' + esc(S('climate_seat_heat')) + '</b></span>' + sw(e.seatApply, 'eSeatApply') + '</div>' +
          '<div class="a-sub">' + esc(S('climate_seat_left')) + '</div><div class="a-grid g4">' + lv('eSeatL', e.seatL) + '</div>' +
          '<div class="a-sub" style="margin-top:8px">' + esc(S('climate_seat_right')) + '</div><div class="a-grid g4">' + lv('eSeatR', e.seatR) + '</div></div>';
      }
      if (k.clim) h += this.editHvac(e.hvac);
      const opts = '<option value="">' + esc(S('profile_bt_none')) + '</option>' + state.bt.devices.map((d) => '<option value="' + esc(d.mac) + '"' + (e.bt === d.mac ? ' selected' : '') + '>' + esc(d.name) + ' (' + esc(d.mac) + ')</option>').join('');
      h += '<div class="a-sec" data-hl="p-bt"><div class="a-row"><span class="lbl">' + esc(S('profile_bt_device_label')) + '</span><select class="a-select" data-c="eBt">' + opts + '</select></div></div>';
      return h;
    }

    /** Bloc climatisation du profil, décoché par défaut. */
    editHvac(hv) {
      const on = hv.enabled, dimOff = on && hv.power ? '' : ' dim';
      const b = (a, active, label, v, dis) => '<button class="b sm' + (active ? ' on' : '') + (dis ? ' dis' : '') + '" data-a="' + a + '"' + (v != null ? ' data-v="' + v + '"' : '') + (dis ? ' disabled' : '') + '>' + label + '</button>';
      let h = '<div class="a-sec" data-hl="p-hvac"><div class="a-row"><span class="lbl"><b>' + esc(S('profile_hvac_title')) + '</b></span>' + sw(on, 'hvOn') + '</div>';
      if (on) {
        const off = !hv.power;
        const air = hv.air;
        const has = (bit) => air != null && (air & bit) !== 0;
        // Comme fragment_profile_edit.xml : icône de 28 dp au-dessus d'un texte de 12 sp.
        const ba = (active, icon, key, v) => '<button class="b air sm' + (active ? ' on' : '') + (off ? ' dis' : '') + '" data-a="hvAir" data-v="' + v + '"' + (off ? ' disabled' : '') + '>' +
          (icon ? AIR_ICON[icon] : '') + '<span>' + esc(S(key)) + '</span></button>';
        h += '<div class="a-row"><span class="a-sub" style="min-width:80px;margin:0">' + esc(S('profile_hvac_row_clim')) + '</span><div class="a-grid g3" style="flex:1">' +
          b('hvPower', hv.power, esc(S('profile_hvac_on'))) + b('hvAc', hv.ac, esc(S('clim_ac')), null, off) + b('hvAuto', hv.auto, esc(S('clim_auto')), null, off) + '</div></div>' +
          '<div class="' + dimOff.trim() + '"><div class="a-row"><span class="a-sub" style="min-width:80px;margin:0">' + esc(S('clim_temperature')) + '</span><div style="flex:1">' + range('hvTemp', 15, 33, hv.temp, off) + '</div><span class="a-val" data-out="hvTemp">' + esc(S('profile_hvac_temp_value', hv.temp)) + '</span></div>' +
          '<div class="a-row"><span class="a-sub" style="min-width:80px;margin:0">' + esc(S('clim_fan')) + '</span><div style="flex:1">' + range('hvFan', 1, 11, hv.fan, off || hv.auto) + '</div><span class="a-val" data-out="hvFan">' + hv.fan + '</span></div>' +
          '<div class="a-row"><span class="a-sub" style="min-width:80px;margin:0">' + esc(S('profile_hvac_row_loop')) + '</span><div class="a-grid g4" style="flex:1">' +
          b('hvLoop', hv.loop === 0, esc(S('clim_loop_inner')), 0, off) + b('hvLoop', hv.loop === 1, esc(S('clim_loop_outside')), 1, off) + b('hvLoop', hv.loop === 2, esc(S('clim_loop_auto')), 2, off) + b('hvLoop', hv.loop == null, esc(S('profile_hvac_unchanged')), 'none', off) + '</div></div>' +
          '<div class="a-row"><span class="a-sub" style="min-width:80px;margin:0">' + esc(S('profile_hvac_row_air')) + '</span><div class="a-grid g5" style="flex:1">' +
          ba(has(AIR.FACE), 'face', 'clim_air_face', AIR.FACE) + ba(has(AIR.FEET), 'feet', 'clim_air_feet', AIR.FEET) +
          ba(has(AIR.WS), 'ws', 'clim_air_windshield_front', AIR.WS) + ba(has(AIR.REAR), 'rear', 'clim_air_windshield_rear', AIR.REAR) +
          ba(air == null, null, 'profile_hvac_unchanged', 'none') + '</div></div></div>';
      }
      return h + '</div>';
    }

    // ── Réglages ───────────────────────────────────────────────────────────
    settingsHtml() {
      const head = '<div class="a-head"><span class="a-title">' + esc(S('nav_settings')) + '</span></div>';
      const body = this.railLayout('set', [
        { label: S('settings_cat_lang'), page: this.setLang() },
        { label: S('settings_cat_ui'), page: this.setUi() },
        { label: S('settings_cat_advanced'), page: this.setAdv() },
        { label: S('settings_cat_info'), page: this.setInfo() }
      ]);
      return '<div class="a-page">' + head + body + '<button class="b close" data-a="close">' + esc(S('nav_close')) + '</button></div>';
    }
    setLang() {
      const cur = appLang();
      const b = (code) => '<button class="b nc' + (cur === code ? ' on' : '') + '" data-a="lang" data-v="' + code + '">' + esc(S('settings_language_' + code)) + '</button>';
      return '<div class="a-sec" data-hl="s-lang"><div class="a-h">' + esc(S('settings_language')) + '</div><div class="a-grid g3">' + b('fr') + b('en') + b('de') + b('es') + b('pt') + b('it') + b('tr') + '</div></div>';
    }
    setUi() {
      const s = state.settings;
      const ds = (v, key) => '<button class="b' + (s.defaultScreen === v ? ' on' : '') + '" data-a="defScreen" data-v="' + v + '">' + esc(S(key)) + '</button>';
      const th = (v, key) => '<button class="b' + (s.theme === v ? ' on' : '') + '" data-a="theme" data-v="' + v + '">' + esc(S(key)) + '</button>';
      return '<div class="a-sec" data-hl="s-default"><div class="a-h">' + esc(S('settings_default_screen')) + '</div><div class="a-grid g3">' + ds('dashboard', 'settings_default_screen_dashboard') + ds('profiles', 'nav_profiles') + ds('shortcuts', 'nav_shortcuts') + '</div></div>' +
        '<div class="a-sec" data-hl="s-theme"><div class="a-h">' + esc(S('settings_theme')) + '</div><div class="a-grid g3">' + th('auto', 'settings_theme_auto') + th('dark', 'settings_theme_dark') + th('light', 'settings_theme_light') + '</div></div>' +
        '<div class="a-sec" data-hl="s-textsize"><div class="a-h">' + esc(S('settings_text_size')) + '</div><div class="a-grid g3">' +
          ['standard', 'large', 'xlarge'].map((v) => '<button class="b' + (s.textSize === v ? ' on' : '') + '" data-a="textSize" data-v="' + v + '">' + esc(S('settings_text_size_' + v)) + '</button>').join('') + '</div></div>';
    }
    setAdv() {
      const s = state.settings, k = caps();
      const row = (hl, label, desc, swHtml) => '<div class="a-row" data-hl="' + hl + '" style="padding:6px 0;border-bottom:1px solid var(--dash-border)"><span class="lbl">' + esc(label) + (desc ? '<small>' + esc(desc) + '</small>' : '') + '</span>' + swHtml + '</div>';
      let h = '<div class="a-sec"><div class="a-h">' + esc(S('settings_auto_apply')) + '</div>' +
        row('s-garage s-autoapply', S('settings_garage_mode'), S('settings_garage_mode_desc'), sw(s.garage, 'garage')) +
        row('s-api', S('settings_external_api'), S('settings_external_api_warn'), sw(s.api, 'api')) +
        row('s-update', S('settings_auto_update_desc'), '', sw(s.autoUpdate, 'autoUpdate')) +
        row('s-updoverlay', S('settings_update_overlay'), S('settings_update_overlay_desc'), sw(s.updOverlay && s.autoUpdate, 'updOverlay', !s.autoUpdate)) +
        row('s-beta', S('settings_beta_channel'), S('settings_beta_channel_desc'), sw(s.beta, 'beta'));
      if (k.power) h += '<div class="a-row" data-hl="s-power" style="padding:6px 0;border-bottom:1px solid var(--dash-border)"><span class="lbl">' + esc(S('settings_vehicle_power_desc')) + '</span><button class="b danger sm" style="padding:0 18px" data-a="powerOff">' + esc(S('settings_vehicle_power_btn')) + '</button></div>';
      h += '<div data-hl="s-gate">' + row('s-gate', S('settings_speed_gate_desc'), '', sw(s.gate, 'gate')) +
        '<div class="a-row"><span class="lbl' + (s.gate ? '' : ' dim') + '">' + esc(S('settings_speed_gate_max_label')) + '</span><input class="a-input num" type="number" min="0" max="250" data-c="gateMax" data-fid="gateMax" value="' + s.gateMax + '"' + (s.gate ? '' : ' disabled') + ' placeholder="' + esc(S('settings_speed_gate_max_hint')) + '"></div></div></div>';
      return h;
    }
    setInfo() {
      const k = caps(), u = this.ui;
      const upd = u.updFb === 'ok' ? '<button class="b green" data-a="checkUpd">' + esc(S('update_up_to_date')) + '</button>' : '<button class="b" data-a="checkUpd" data-hl="s-checkupd">' + esc(S('btn_check_update')) + '</button>';
      const apk = u.apkFb ? '<button class="b green" data-a="cleanApk">' + esc(u.apkFb) + '</button>' : '<button class="b" data-a="cleanApk">' + esc(S('btn_clean_apk')) + '</button>';
      const unknownMode = state.fw === 'UNKNOWN';
      const chips = ['SWI133', 'SWI132', 'SWI68', 'SWI69', 'SWI131', 'SWI165'].map((g) =>
        '<button class="fw-chip' + (gen() === g ? ' on' : '') + (unknownMode ? ' tap' : '') + '" ' + (unknownMode ? 'data-a="force" data-v="' + g + '"' : 'tabindex="-1"') + '>' + g + '</button>').join('');
      const du = [['data_usage_today', '38 Mo'], ['data_usage_week', '212 Mo'], ['data_usage_month', '0,9 Go'], ['data_usage_30d', '1,2 Go']]
        .map(([key, v]) => '<div><small>' + esc(S(key)) + '</small><b>' + v + '</b></div>').join('');
      return '<div class="a-sec" data-hl="s-maint"><div class="a-h">' + esc(S('settings_maintenance')) + '</div><div class="a-grid g2">' + upd + apk + '</div>' +
        '<button class="b full" style="margin-top:5px" data-a="about" data-hl="s-about">' + esc(S('btn_infos')) + '</button>' +
        (state.diagUnlocked ? '<button class="b full warn on" style="margin-top:5px" data-a="diag" data-hl="s-diag">' + esc(S('btn_diagnostic')) + '</button>' : '') + '</div>' +
        '<div class="a-sec" data-hl="s-fw"><div class="a-h">' + esc(S('settings_firmware_version')) + '</div><div class="fw-chips">' + chips + '</div>' +
        (unknownMode ? '<div class="a-desc" style="margin-top:6px">' + esc(L('Firmware non reconnu : touchez une pastille pour forcer un mode de compatibilité.', 'Unrecognised firmware: tap a chip to force a compatibility mode.')) + '</div>' : '') + '</div>' +
        '<div class="a-sec" data-hl="s-data"><div class="a-h">' + esc(S('data_usage_title')) + '</div><div class="du">' + du + '</div></div>';
    }

    // ── Raccourcis ─────────────────────────────────────────────────────────
    actionLabel(action, extra) {
      if (action === 'OPEN_CUSTOM_APP' && extra && extra.app) return S('shortcuts_open_custom_prefix') + ' ' + extra.app;
      if (action === 'APPLY_PROFILE' && extra && extra.profileId) {
        const p = state.profiles.find((x) => x.id === extra.profileId);
        return S('shortcuts_profile_prefix') + ' ' + (p ? p.name : '?');
      }
      return S(ACTION_KEY[action] || 'shortcuts_action_none');
    }
    actionSelect(cur, dataC, attrs, extra) {
      const list = availableActions();
      if (cur && !list.includes(cur)) list.push(cur);
      return '<select class="a-select" style="flex:1" data-c="' + dataC + '" ' + (attrs || '') + '>' + list.map((a) =>
        '<option value="' + a + '"' + (a === cur ? ' selected' : '') + '>' + esc(a === cur ? this.actionLabel(a, extra) : S(ACTION_KEY[a])) + '</option>').join('') + '</select>';
    }
    shortcutsHtml() {
      const sc = state.sc;
      const head = '<div class="a-card" style="padding:8px 14px;border-radius:12px" data-hl="sc-master"><div class="a-row" style="min-height:36px"><span class="lbl" style="font-weight:700">' + esc(S('shortcuts_enabled_label')) + '</span>' + sw(sc.enabled, 'scOn') + '</div></div>';
      const cur = String(this.ui.tabs.sc);
      const inAdv = cur === 'adv' || cur === 'list';
      // Chaque réglage d'action a sa page, révélée dès que la fonction est attribuée
      // (emplacement classique, raccourci avancé, ou simple sélection dans le formulaire avancé).
      const body = this.railLayout('sc', [
        { id: 'classic', label: S('shortcuts_cat_classic'), page: this.scClassic() },
        { id: 'adv', label: S('shortcuts_cat_advanced'), page: this.scAdvanced() },
        { id: 'onepedal', label: S('shortcuts_cat_onepedal'), page: this.assigned('ONE_PEDAL') ? this.scOnePedal() : '', sub: true, hl: 'sc-sub-onepedal' },
        { id: 'adas', label: S('shortcuts_cat_adas'), page: this.assigned('ADAS_CYCLE') && caps().known ? this.scAdasCfg() : '', sub: true, hl: 'sc-sub-adas' },
        { id: 'regen', label: S('shortcuts_cat_regen_cycle'), page: this.assigned('REGEN_CYCLE') ? this.scRegen() : '', sub: true, hl: 'sc-sub-regen' },
        { id: 'list', label: S('adv_sc_list_title'), page: this.scList(), sub: true, hidden: !inAdv }
      ]);
      return '<div class="a-page">' + head + body + '<button class="b close" data-a="close">' + esc(S('nav_close')) + '</button></div>';
    }
    /** Fonction attribuée quelque part (emplacement classique, raccourci avancé ou formulaire en cours). */
    assigned(action) {
      const sc = state.sc;
      return Object.values(sc.map).includes(action) || sc.adv.some((x) => x.action === action) || this.ui.adv.action === action;
    }
    scOnePedal() {
      const sc = state.sc;
      const f = (l, key) => '<button class="b' + (sc.fallback === l ? ' on' : '') + '" data-a="scFallback" data-v="' + l + '">' + esc(S(key)) + '</button>';
      return '<div class="a-sec" data-hl="sc-onepedal"><div class="a-h">' + esc(S('shortcuts_cfg_one_pedal')) + '</div><div class="a-row"><span class="a-sub" style="min-width:70px;margin:0">' + esc(S('shortcuts_cfg_retour')) + '</span><div class="a-grid g5" style="flex:1">' +
        f('OFF', 'regen_off') + f('LOW', 'regen_low') + f('MEDIUM', 'regen_medium') + f('HIGH', 'regen_high') + f('ADAPTIVE', 'regen_adaptive_short') + '</div></div></div>';
    }
    scAdasCfg() {
      const sc = state.sc;
      const m = (act, cur) => [0, 1, 2, 3, 4].map((i) => '<button class="b sm' + (cur === i ? ' on' : '') + '" data-a="' + act + '" data-v="' + i + '">' + esc(adasLabel(i)) + '</button>').join('');
      return '<div class="a-sec" data-hl="sc-adas"><div class="a-h">' + esc(S('shortcuts_cfg_adas')) + '</div>' +
        '<div class="a-row"><span class="a-sub" style="min-width:70px;margin:0">' + esc(S('shortcuts_cfg_mode1')) + '</span><div class="a-grid g5" style="flex:1">' + m('scAdasA', sc.adasA) + '</div></div>' +
        '<div class="a-row"><span class="a-sub" style="min-width:70px;margin:0">' + esc(S('shortcuts_cfg_mode2')) + '</span><div class="a-grid g5" style="flex:1">' + m('scAdasB', sc.adasB) + '</div></div></div>';
    }
    /** Cycle de régénération : l'ordre des appuis est l'ordre du cycle, rien n'est gardé avant « Sauvegarder ». */
    scRegen() {
      const u = this.ui;
      if (!u.regenDraft) u.regenDraft = regenOrder().slice();
      const d = u.regenDraft;
      const keys = { LOW: 'regen_low', MEDIUM: 'regen_medium', HIGH: 'regen_high', ADAPTIVE: 'regen_adaptive_short', ONE_PEDAL: 'regen_one_pedal_short' };
      const btn = (l) => { const i = d.indexOf(l); return '<button class="b' + (i >= 0 ? ' on' : '') + '" data-a="rcToggle" data-v="' + l + '">' + (i >= 0 ? '<span class="rc-n">' + (i + 1) + '</span>' : '') + esc(S(keys[l])) + '</button>'; };
      const ok = d.length >= 2;
      return '<div class="a-sec" data-hl="sc-regen"><div class="a-h">' + esc(S('shortcuts_cfg_regen_cycle')) + '</div>' +
        '<div class="a-desc">' + esc(S('shortcuts_cfg_regen_hint')) + '</div>' +
        '<div class="a-grid g5" style="margin-top:8px">' + REGEN_SELECTABLE.map(btn).join('') + '</div>' +
        '<div class="a-row"><span class="lbl">' + (ok ? esc(S('shortcuts_cfg_regen_summary', d.map((l) => S(keys[l])).join(' → '))) :
        '<span style="color:var(--dash-warn)">' + esc(S('shortcuts_cfg_regen_min')) + '</span>') + '</span></div>' +
        '<div class="a-grid g2"><button class="b" data-a="rcClear">' + esc(S('shortcuts_cfg_regen_clear')) + '</button><button class="b green' + (ok ? '' : ' dis') + '" data-a="rcSave"' + (ok ? '' : ' disabled') + '>' + esc(S('shortcuts_cfg_regen_save')) + '</button></div></div>';
    }
    scClassic() {
      const sc = state.sc;
      const slot = (btn, press) => {
        const key = btn + '_' + press;
        return '<div class="a-row"><span class="a-sub" style="min-width:70px;margin:0">' + esc(S('shortcuts_press_' + press)) + '</span>' + this.actionSelect(sc.map[key] || 'NONE', 'scSlot', 'data-slot="' + key + '"', sc.extra[key]) + '</div>';
      };
      const dis = sc.enabled ? '' : ' dim';
      return '<div class="a-sec' + dis + '" data-hl="sc-btn1"><div class="a-h">' + esc(S('shortcuts_btn1_label')) + '</div>' + slot('btn1', 'single') + slot('btn1', 'long') + '</div>' +
        '<div class="a-sec' + dis + '" data-hl="sc-btn2"><div class="a-h">' + esc(S('shortcuts_btn2_label')) + '</div>' + slot('btn2', 'single') + slot('btn2', 'long') + '</div>';
    }
    scAdvanced() {
      const sc = state.sc, a = this.ui.adv;
      const keyTxt = a.key != null ? keyName(a.key) + ' (' + a.key + ')' : S('adv_sc_none');
      const pb = (v, key) => '<button class="b' + (a.press === v ? ' on' : '') + '" data-a="advPress" data-v="' + v + '">' + esc(S(key)) + '</button>';
      return '<div class="a-sec" data-hl="adv-card"><div class="a-row"><span class="lbl" style="font-weight:700;font-size:18px">' + esc(S('adv_sc_title')) + '</span>' + sw(sc.advOn, 'advOn') + '</div>' +
        '<div class="a-desc">' + esc(S('adv_sc_desc')) + '</div>' +
        '<div class="a-row" data-hl="adv-service"><span class="a-sub" style="margin:0">' + esc(S('adv_sc_status_label')) + '</span><span class="chip-rec"><span class="dot' + (sc.advService ? ' ok' : '') + '"></span>' + esc(S(sc.advService ? 'adv_sc_status_on' : 'adv_sc_status_off')) + '</span><span style="flex:1"></span>' +
        (sc.advService ? '' : '<button class="b primary sm" style="padding:0 16px" data-a="advGrant">' + esc(S('adv_sc_open_accessibility')) + '</button>') + '</div></div>' +
        '<div class="a-sec" data-hl="adv-new"><div class="a-h">' + esc(S('adv_sc_new')) + '</div>' +
        '<div class="a-row" data-hl="adv-key"><span class="a-sub" style="min-width:130px;margin:0">' + esc(S('adv_sc_step_key')) + '</span><span class="chip-rec" style="flex:1">' + (a.rec ? '<span class="dot rec"></span>' : '') + '<b style="color:var(--text-primary)">' + esc(keyTxt) + '</b></span>' +
        // Pendant l'enregistrement, le bouton lui-même invite à appuyer ; un second appui annule.
        '<button class="b sm' + (a.rec ? ' danger' : ' primary') + (sc.advService ? '' : ' dis') + '" style="padding:0 16px" data-a="advRec"' + (sc.advService ? '' : ' disabled') + '>' + esc(S(a.rec ? 'adv_sc_recording' : 'adv_sc_record')) + '</button></div>' +
        '<div class="a-row" data-hl="adv-press"><span class="a-sub" style="min-width:130px;margin:0">' + esc(S('adv_sc_step_press')) + '</span><div class="a-grid g3" style="flex:1">' + pb('single', 'adv_sc_press_short') + pb('long', 'adv_sc_press_long_lbl') + pb('double', 'adv_sc_press_double') + '</div></div>' +
        '<div class="a-row"><span class="a-sub" style="min-width:130px;margin:0">' + esc(S('adv_sc_step_action')) + '</span>' + this.actionSelect(a.action, 'advAction', '', null) + '</div>' +
        '<div class="a-row" data-hl="adv-scope"><span class="a-sub" style="min-width:130px;margin:0">' + esc(S('adv_sc_step_profile')) + '</span>' + this.scopeSelect(a.scope, 'advScope') + '</div>' +
        '<button class="b green full" style="margin-top:6px" data-a="advCreate">' + esc(S('adv_sc_create')) + '</button>' +
        '<div class="a-desc" style="margin-top:6px;color:var(--dash-warn)">⚠ ' + esc(S('adv_sc_claimed_warning')) + '</div></div>';
    }
    scopeSelect(cur, dataC) {
      return '<select class="a-select" style="flex:1" data-c="' + dataC + '"><option value="">' + esc(S('adv_sc_all_profiles')) + '</option>' +
        state.profiles.map((p) => '<option value="' + p.id + '"' + (cur === p.id ? ' selected' : '') + '>' + esc(p.name) + '</option>').join('') + '</select>';
    }
    scopeName(scope) {
      if (!scope) return S('adv_sc_all_profiles');
      const p = state.profiles.find((x) => x.id === scope);
      return p ? p.name : S('adv_sc_profile_missing');
    }
    scList() {
      const list = state.sc.adv;
      if (!list.length) return '<div class="a-sec" data-hl="adv-list"><div class="a-h">' + esc(S('adv_sc_list_title')) + '</div><div class="a-desc" style="padding:20px 0;text-align:center">' + esc(S('adv_sc_empty')) + '</div></div>';
      const pl = { single: 'adv_sc_press_short', long: 'adv_sc_press_long_lbl', double: 'adv_sc_press_double' };
      // Groupée par touche + type d'appui, « Tous les profils » en tête (ordre de résolution).
      const groups = [];
      list.forEach((x, i) => {
        let g = groups.find((y) => y.key === x.key && y.press === x.press);
        if (!g) { g = { key: x.key, press: x.press, rows: [] }; groups.push(g); }
        g.rows.push({ x, i });
      });
      return '<div class="a-sec" data-hl="adv-list"><div class="a-h">' + esc(S('adv_sc_list_title')) + '</div>' + groups.map((g) => {
        g.rows.sort((a, b) => (a.x.scope ? 1 : 0) - (b.x.scope ? 1 : 0));
        const noFallback = g.rows.every((r) => r.x.scope);
        return '<div class="sc-group"><div class="sc-group-h">' + esc(S('adv_sc_group_title', keyName(g.key) + ' (' + g.key + ')', S(pl[g.press]))) + '</div>' +
          (noFallback ? '<div class="a-desc sc-nofb">' + esc(S('adv_sc_no_fallback')) + '</div>' : '') +
          g.rows.map(({ x, i }) => '<div class="sc-row sc-row2"><span class="sc-scope' + (x.scope ? '' : ' all') + '">' + esc(this.scopeName(x.scope)) + '</span><span>' + esc(this.actionLabel(x.action, x)) + '</span>' +
            '<button class="b" data-a="advEdit" data-v="' + i + '">' + esc(S('adv_sc_edit')) + '</button><button class="b danger" data-a="advDel" data-v="' + i + '">' + esc(S('adv_sc_delete')) + '</button></div>').join('') + '</div>';
      }).join('') + '</div>';
    }

    // ── Automatisation ─────────────────────────────────────────────────────
    automationHtml() {
      const a = state.auto, k = caps();
      // Patron commun : titre + chevron, puis description et interrupteur toujours visibles ;
      // les réglages n'apparaissent que carte dépliée (activer déplie, couper replie).
      const card = (hl, titleKey, desc, on, swAct, open, foldAct, body) =>
        '<div class="a-card" data-hl="' + hl + '"><div class="fold-head" data-a="' + foldAct + '"><span class="a-title">' + esc(S(titleKey)) + '</span><span class="chev' + (open ? ' open' : '') + '">' + ICON_CHEV + '</span></div>' +
        '<div class="a-row" style="margin-top:6px"><span class="lbl">' + desc + '</span>' + sw(on, swAct) + '</div>' + (open ? body : '') + '</div>';
      const profSel = (cur, dataC) => '<select class="a-select" style="width:100%" data-c="' + dataC + '"><option value="">' + esc(S('automation_no_profile')) + '</option>' +
        state.profiles.map((p) => '<option value="' + p.id + '"' + (cur === p.id ? ' selected' : '') + '>' + esc(p.name) + '</option>').join('') + '</select>';
      const check = (dataC, on, label, attrs) => '<label class="a-check"><input type="checkbox" data-c="' + dataC + '"' + (on ? ' checked' : '') + (attrs || '') + '> ' + label + '</label>';
      const note = (key) => '<div class="a-desc" style="margin-top:8px">ℹ ' + esc(S(key)) + '</div>';
      const slider = (id, label, min, max, val, out, step) => '<div class="a-row"><span class="lbl" style="flex:0 0 250px">' + esc(label) + '</span><div style="flex:1">' + range(id, min, max, val, false, '', step) + '</div><span class="a-val" data-out="' + id + '">' + esc(out) + '</span></div>';
      const plain = (s) => String(s).replace(/<[^>]+>/g, '');

      // 1. Profil selon la température
      const dir = (v, key) => '<button class="b' + (a.p.dir === v ? ' on' : '') + '" data-a="autoDir" data-v="' + v + '">' + esc(S(key)) + '</button>';
      const body1 = '<div class="a-sub" style="margin-top:6px">' + esc(S('automation_direction_label')) + '</div><div class="a-grid g2">' + dir('BELOW', 'automation_dir_below') + dir('ABOVE', 'automation_dir_above') + '</div>' +
        '<div class="fields"><div class="field"><label>' + esc(S('automation_threshold_label')) + '</label><input class="a-input" type="number" min="0" max="60" data-c="autoThr" data-fid="autoThr" value="' + a.p.thr + '" placeholder="' + esc(S('automation_threshold_hint')) + '"></div>' +
        '<div class="field" style="grid-column:span 2"><label>' + esc(S('automation_profile_label')) + '</label>' + profSel(a.p.profileId, 'autoProfile') + '</div></div>' +
        '<div class="a-row">' + check('autoExec', a.p.autoExec, esc(S('automation_auto_execute'))) + '</div>';
      const card1 = card('auto-profile', 'automation_profile_title', esc(S('automation_desc')), a.p.on, 'autoP', a.p.open, 'foldP', body1);

      // 2. Profil selon la batterie
      const body2 = slider('batThr', S('battery_auto_threshold_label'), 5, 60, a.b.thr, a.b.thr + ' %') +
        '<div class="fields"><div class="field" style="grid-column:span 3"><label>' + esc(S('automation_profile_label')) + '</label>' + profSel(a.b.profileId, 'batProfile') + '</div></div>' +
        '<div class="a-row">' + check('batExec', a.b.autoExec, esc(S('automation_auto_execute'))) + '</div>' + note('battery_auto_note');
      const card2 = card('auto-battery', 'battery_auto_title', esc(S('battery_auto_desc')), a.b.on, 'autoB', a.b.open, 'foldB', body2);

      // 3. Luminosité automatique (au moins une source : la dernière cochée est verrouillée)
      const b = a.bri;
      const body3 = '<div class="a-sub" style="margin-top:8px">' + esc(plain(S('autobri_sources'))) + '</div>' +
        '<div class="a-row">' + check('briLights', b.lights, esc(S('autobri_lights')), b.lights && !b.forecast ? ' disabled' : '') + '</div>' +
        '<div class="a-row">' + check('briForecast', b.forecast, esc(S('autobri_forecast')) + ' <small>' + esc(S('autobri_forecast_warning')) + '</small>', b.forecast && !b.lights ? ' disabled' : '') + '</div>' +
        '<div class="a-row">' + check('briFollow', b.follow, esc(S('autobri_follow'))) + '</div>' +
        slider('briDay', S('autobri_level_lights_off'), 5, 100, b.day, b.day + ' %', 5) + slider('briNight', S('autobri_level_lights_on'), 5, 100, b.night, b.night + ' %', 5) +
        '<div class="a-row"><span class="lbl" style="color:var(--text-secondary)">' + esc(b.last || S('autobri_status_none')) + '</span><button class="b sm" style="padding:0 16px" data-a="briTest">' + esc(S('autobri_test')) + '</button></div>';
      const card3 = k.bri ? card('auto-bri', 'autobri_title', esc(S('autobri_desc')), b.on, 'autoBri', b.open, 'foldBri', body3) : '';

      // 4. Coupure du chauffage de la batterie
      const bh = a.bh;
      const body4 = slider('bhMin', S('batheat_auto_minutes_label'), 5, 120, bh.minutes, S('batheat_auto_minutes_value', bh.minutes), 5) + note('batheat_auto_note');
      const card4 = k.batHeat ? card('auto-batheat', 'batheat_auto_title', esc(S('batheat_auto_desc')), bh.on, 'autoBh', bh.open, 'foldBh', body4) : '';

      // 5. Climatisation selon la température
      const rule = (id, icon, titleKey) => {
        const r = a.c[id];
        const rc = (v, key) => '<button class="b sm' + (r.recirc === v ? ' on' : '') + (r.recircForce ? '' : ' dis') + '" data-a="ruleRecirc" data-v="' + id + ':' + v + '"' + (r.recircForce ? '' : ' disabled') + '>' + esc(S(key)) + '</button>';
        return '<div class="rule" data-hl="rule-' + id + '"><div class="rule-h"><span class="ico">' + icon + '</span><span style="flex:1">' + esc(S(titleKey)) + '</span>' + sw(r.on, 'ruleOn', false, id) + '</div>' +
          '<div class="fields"><div class="field"><label>' + esc(S('ac_auto_threshold_hot_label')) + '</label><input class="a-input" type="number" min="-20" max="60" data-c="ruleThr" data-rule="' + id + '" data-fid="thr' + id + '" value="' + r.thr + '"></div>' +
          '<div class="field"><label>' + esc(S('ac_auto_target_label')) + '</label><input class="a-input" type="number" min="15" max="33" data-c="ruleTarget" data-rule="' + id + '" data-fid="tg' + id + '" value="' + r.target + '"></div>' +
          '<div class="field"><label>' + esc(S('ac_auto_fan_label')) + '</label><input class="a-input" type="number" min="1" max="10" data-c="ruleFan" data-rule="' + id + '" data-fid="fan' + id + '" value="' + r.fan + '"></div></div>' +
          '<div class="a-row" style="gap:18px"><label class="a-check"><input type="checkbox" data-c="ruleDefF" data-rule="' + id + '"' + (r.defF ? ' checked' : '') + '> ' + esc(S('ac_auto_def_front')) + '</label>' +
          '<label class="a-check"><input type="checkbox" data-c="ruleDefR" data-rule="' + id + '"' + (r.defR ? ' checked' : '') + '> ' + esc(S('ac_auto_def_rear')) + '</label><span style="flex:1"></span>' +
          '<span class="lbl" style="flex:0 0 auto">' + esc(S('ac_auto_auto_mode')) + '</span>' + sw(r.auto, 'ruleAuto', false, id) + '</div>' +
          '<div class="a-row"><span class="lbl" style="flex:0 0 auto">' + esc(S('ac_auto_recirc_force')) + '</span>' + sw(r.recircForce, 'ruleRecircF', false, id) + '<div class="a-grid g3" style="flex:1">' + rc(0, 'recirc_inner') + rc(1, 'recirc_outside') + rc(2, 'recirc_auto') + '</div></div></div>';
      };
      const body5 = '<div style="margin-top:8px">' + rule('hot', '🔥', 'ac_auto_hot') + rule('cold', '❄', 'ac_auto_cold') + '</div>' +
        '<div data-hl="ac-once"><div class="a-sub" style="margin-top:10px">' + esc(S('ac_auto_trigger_label')) + '</div>' +
        ['start', 'once', 'ready'].map((v) => '<div class="a-row" style="min-height:34px"><label class="a-check"><input type="radio" name="acTrigger" data-c="acTrigger" value="' + v + '"' + (a.c.trigger === v ? ' checked' : '') + '> ' + esc(S('ac_auto_trigger_' + v)) + '</label></div>' +
          '<div class="a-desc" style="margin:0 0 4px 30px">' + esc(S('ac_auto_trigger_' + v + '_hint')) + '</div>').join('') + '</div>' + note('ac_auto_note');
      const desc5 = esc(S('ac_auto_desc')) + (k.clim ? '' : '<small>' + esc(L('Climatisation non exposée par ce firmware.', 'Climate not exposed by this firmware.')) + '</small>');
      const card5 = card('auto-clim', 'ac_auto_title', desc5, a.c.on, 'autoC', a.c.open, 'foldC', body5);

      return '<div class="a-page"><div class="a-scroll"><div class="a-stack">' + card1 + card2 + card3 + card4 + card5 + this.windowsCard() + this.doorCard() +
        '</div></div><button class="b close" data-a="close">' + esc(S('nav_close')) + '</button></div>';
    }

    /**
     * Carte « Fermeture automatique des vitres électriques ». Même ordre que
     * automation_card_windows.xml : interrupteur au niveau de la carte, conditions d'armement,
     * durée de course, puis l'option avancée « Calibrage par vitre ». Les boutons Tout fermer /
     * Tout ouvrir ont quitté l'écran : ils restent disponibles en raccourcis et dans l'API.
     */
    windowsCard() {
      const u = this.ui, a = state.winAuto;
      let h = '<div class="a-card" data-hl="windows"><div class="fold-head" data-a="foldW"><span class="a-title">' + esc(S('win_automation_title')) + '</span><span class="chev' + (u.winOpen ? ' open' : '') + '">' + ICON_CHEV + '</span></div>' +
        '<div class="a-row" style="margin-top:6px" data-hl="win-auto"><span class="lbl">' + esc(S('win_auto_label')) + '</span>' + sw(a.on, 'winAutoOn') + '</div>';
      if (!u.winOpen) return h + '</div>';
      h += '<div class="a-desc">' + esc(S('win_auto_desc')) + '</div><div class="a-desc" style="color:var(--dash-danger)">⚠ ' + esc(S('win_auto_warning')) + '</div>';
      if (a.on) {
        h += '<div class="a-sub" style="margin-top:8px">' + esc(S('win_auto_arming_title')) + '</div>' +
          '<div class="a-row">' + sw(a.speedOn, 'waSpeedOn') + '<span class="lbl" style="flex:0 0 190px">' + esc(S('win_auto_speed_label')) + '</span><div style="flex:1">' + range('waSpeed', 5, 50, a.speed, !a.speedOn, '', 5) + '</div><span class="a-val" data-out="waSpeed">' + a.speed + ' km/h</span></div>' +
          '<div class="a-row">' + sw(a.timeOn, 'waTimeOn') + '<span class="lbl" style="flex:0 0 190px">' + esc(S('win_auto_time_label')) + '</span><div style="flex:1">' + range('waTime', 1, 15, a.time, !a.timeOn) + '</div><span class="a-val" data-out="waTime">' + a.time + ' min</span></div>' +
          (a.speedOn && a.timeOn ? '<div class="a-grid g2"><button class="b sm' + (!a.both ? ' on' : '') + '" data-a="waBoth" data-v="0">' + esc(S('win_auto_combine_any')) + '</button><button class="b sm' + (a.both ? ' on' : '') + '" data-a="waBoth" data-v="1">' + esc(S('win_auto_combine_all')) + '</button></div>' : '') +
          '<div class="a-row"><span class="lbl" style="flex:0 0 236px">' + esc(S('win_auto_delay_label')) + '</span><div style="flex:1">' + range('waDelay', 0, 30, a.delay) + '</div><span class="a-val" data-out="waDelay">' + a.delay + ' s</span></div>' +
          '<div class="a-row"><span class="lbl">' + esc(S('win_auto_beep_label')) + '<small>' + esc(S('win_auto_beep_desc')) + '</small></span>' + sw(a.beep, 'waBeep') + '</div>' +
          (a.beep ? '<div class="a-row"><span class="lbl" style="flex:0 0 236px">' + esc(S('win_auto_beep_volume_label')) + '</span><div style="flex:1">' + range('waBeepVol', 0, 100, a.beepVol) + '</div><span class="a-val" data-out="waBeepVol">' + a.beepVol + ' %</span></div>' : '');
      }
      // Durée de course (secondes côté curseur, millisecondes dans l'état)
      h += '<div class="a-sec" style="margin-top:10px" data-hl="win-course"><div class="a-h">' + esc(S('win_course_title')) + '</div>' +
        '<div class="a-row"><span class="lbl" style="flex:0 0 190px">' + esc(S('win_course_label')) + '</span><div style="flex:1">' + range('winCourse', 2, 10, state.winCourse / 1000, false, '', 0.5) + '</div><span class="a-val" data-out="winCourse">' + esc(S('win_cal_seconds', fmtS(state.winCourse))) + '</span></div>' +
        '<div class="a-desc">' + esc(S('win_course_hint')) + '</div></div>';
      // Avancé : calibrage par vitre. Éteint, les mesures restent enregistrées mais ne servent plus.
      const calRow = (w) => {
        const cal = state.winCal[w];
        const st = cal === 'sensor' ? S('win_cal_sensor') : cal ? S('win_cal_done', fmtS(cal.down), fmtS(cal.up)) : S('win_cal_general');
        return '<div class="a-row"><span class="lbl">' + esc(S(WIN_KEY[w])) + '<small>' + esc(st) + '</small></span>' +
          (cal === 'sensor' ? '' : '<button class="b sm' + (cal ? '' : ' primary') + '" style="padding:0 16px" data-a="calStart" data-v="' + w + '">' + esc(S(cal ? 'win_cal_redo_row' : 'win_cal_start')) + '</button>') + '</div>';
      };
      h += '<div class="a-sec" style="margin-top:10px" data-hl="win-cal"><div class="a-h">' + esc(S('win_cal_advanced_title')) + '</div>' +
        '<div class="a-row"><span class="lbl">' + esc(S('win_cal_advanced_label')) + '<small>' + esc(S('win_cal_advanced_desc')) + '</small></span>' + sw(state.winCalAdv, 'winCalAdv') + '</div>' +
        (state.winCalAdv ? '<div class="a-desc">' + esc(S('win_cal_hint')) + '</div><div class="a-desc" style="font-size:12px">' + esc(S('win_cal_limits')) + '</div>' + WINDOWS.map(calRow).join('') : '') +
        '</div>';
      return h + '</div>';
    }

    /** Carte « Baisse du volume en quittant la voiture » : l'ancien onglet Audio, dernière carte. */
    doorCard() {
      const d = state.door, dis = !d.on, k = caps();
      if (!k.door) return '';
      // Sans porte lisible (hors SWI132/133), le signal est la sortie de READY.
      const ready = !k.doorSensor;
      let h = '<div class="a-card" data-hl="door"><div class="fold-head" data-a="foldD"><span class="a-title">' + esc(S('door_volume_title')) + '</span><span class="chev' + (d.open ? ' open' : '') + '">' + ICON_CHEV + '</span></div>' +
        '<div class="a-row" style="margin-top:6px"><span class="lbl">' + esc(S(ready ? 'door_volume_desc_ready' : 'door_volume_desc')) + '</span>' + sw(d.on, 'doorOn') + '</div>';
      if (!d.open) return h + '</div>';
      h += '<div class="' + (dis ? 'dim' : '') + '"><div class="a-row"><span class="a-sub" style="margin:0;flex:1">' + esc(S('door_volume_level_label')) + '</span><span class="a-val" data-out="doorLevel">' + d.level + '</span></div>' +
        range('doorLevel', 0, state.car.volMax, d.level, dis) +
        '<div class="a-row"><span class="lbl">' + esc(S(ready ? 'door_volume_restore_ready_title' : 'door_volume_restore_title')) + '</span>' + sw(d.restore, 'doorRestore', dis) + '</div>' +
        (ready ? '' : '<div class="a-sub" style="margin-top:6px">' + esc(S('door_volume_doors_label')) + '</div><div class="a-row" style="gap:24px">' +
        '<label class="a-check"><input type="checkbox" data-c="doorL"' + (d.L ? ' checked' : '') + (dis ? ' disabled' : '') + '> ' + esc(S('door_left')) + '</label>' +
        '<label class="a-check"><input type="checkbox" data-c="doorR"' + (d.R ? ' checked' : '') + (dis ? ' disabled' : '') + '> ' + esc(S('door_right')) + '</label></div>') + '</div>';
      return h + '</div>';
    }

    // ── Statistiques ───────────────────────────────────────────────
    statsHtml() {
      const body = this.railLayout('st', [
        { label: S('stats_cat_general'), page: this.statsGeneral() },
        { label: S('stats_cat_trips'), page: this.statsTrips() },
        { label: S('stats_cat_charges'), page: this.statsCharges() }
      ]);
      return '<div class="a-page">' + body + '<button class="b close" data-a="close">' + esc(S('nav_close')) + '</button></div>';
    }
    statsSummary() {
      const st = state.stats;
      const since = st.period ? Date.now() - st.period * 86400000 : 0;
      const trips = st.enabled ? st.trips.filter((t) => t.start >= since) : [];
      const charges = st.enabled ? st.charges.filter((c) => c.start >= since) : [];
      const price = (c) => (c.fixedPrice != null ? c.fixedPrice : c.type === 'DC' ? st.priceDc : st.priceAc);
      const sum = (arr, f) => arr.reduce((a, x) => a + f(x), 0);
      const km = sum(trips, (t) => t.km), kwh = sum(trips, (t) => t.kwh), regen = sum(trips, (t) => t.regen);
      const hours = sum(trips, (t) => (t.end - t.start) / 3600000);
      const charged = sum(charges, (c) => c.kwh), chargeCost = sum(charges, (c) => c.kwh * price(c));
      // Le prix d'un trajet suit le prix moyen de l'énergie réellement rechargée sur la période.
      const avgPrice = charged > 0 ? chargeCost / charged : st.priceAc;
      const driving = kwh * avgPrice;
      return {
        trips, charges, price,
        km, kwh, regen, charged, chargeCost, avgPrice: charged > 0 ? avgPrice : null, driving,
        cons: km > 0 ? kwh / km * 100 : null,     // sur les totaux, pas la moyenne des moyennes
        speed: hours > 0 ? km / hours : null,
        per100: km > 0 ? driving / km * 100 : null,
        longest: trips.reduce((m, t) => Math.max(m, t.km), 0),
        ac: charges.filter((c) => c.type === 'AC').length, dc: charges.filter((c) => c.type === 'DC').length
      };
    }
    num(v, d) { return v == null ? '—' : v.toLocaleString(appLang() === 'en' ? 'en-GB' : appLang(), { minimumFractionDigits: d, maximumFractionDigits: d }); }
    money(v) { return v == null ? '—' : this.num(v, 2) + ' ' + state.stats.currency; }
    tiles(list) {
      return '<div class="st-tiles">' + list.map(([k, v]) => '<div class="st-tile"><small>' + esc(k) + '</small><b>' + esc(v) + '</b></div>').join('') + '</div>';
    }
    periodRow() {
      const p = state.stats.period;
      return '<div class="a-grid g2" style="margin-bottom:8px"><button class="b sm' + (p ? ' on' : '') + '" data-a="stPeriod" data-v="30">' + esc(S('stats_period_short', 30)) + '</button>' +
        '<button class="b sm' + (!p ? ' on' : '') + '" data-a="stPeriod" data-v="0">' + esc(S('stats_period_all')) + '</button></div>';
    }
    statsGeneral() {
      const st = state.stats, s = this.statsSummary();
      let h = '<div class="a-sec" data-hl="st-enable"><div class="a-row"><span class="lbl"><b>' + esc(S('stats_enable_label')) + '</b><small>' + esc(S('stats_enable_desc')) + '</small></span>' + sw(st.enabled, 'stEnable') + '</div>' +
        (st.enabled ? '' : '<div class="a-desc">' + esc(S('stats_disabled_note')) + '</div>') + '</div>';
      if (!st.enabled) return h;
      const kwh = (v) => this.num(v, 1) + ' kWh';
      h += '<div class="a-sec" data-hl="st-summary"><div class="a-h">' + esc(S('stats_summary_title')) + '</div>' + this.periodRow() + this.tiles([
        [S('stats_tile_distance'), this.num(s.km, 1) + ' km'],
        [S('stats_tile_consumption'), s.cons != null ? this.num(s.cons, 1) + ' kWh/100 km' : '—'],
        [S('stats_tile_speed'), s.speed != null ? Math.round(s.speed) + ' km/h' : '—'],
        [S('stats_tile_energy'), kwh(s.kwh)], [S('stats_tile_regen'), kwh(s.regen)], [S('stats_tile_charged'), kwh(s.charged)],
        [S('stats_tile_cost'), this.money(s.driving)], [S('stats_tile_cost_per100'), this.money(s.per100)]
      ]) + '</div>';
      // Batterie : menu des trois MG4. En changer recalcule les recharges enregistrées.
      h += '<div class="a-sec" data-hl="st-battery"><div class="a-h">' + esc(S('stats_battery_title')) + '</div><div class="a-row"><span class="lbl">' + esc(S('stats_battery_label')) + '</span>' +
        '<select class="a-select" style="width:340px" data-c="stBattery">' + Object.keys(BATTERIES).map((n) =>
          '<option value="' + n + '"' + (+n === st.battery ? ' selected' : '') + '>' + esc(S('stats_battery_option', +n, this.num(BATTERIES[n], 1), +n === 51 ? 'LFP' : 'NMC')) + '</option>').join('') + '</select></div>' +
        '<div class="a-desc">' + esc(S('stats_battery_note')) + '</div></div>';
      const field = (label, id, v, type) => '<div class="a-row"><span class="lbl">' + esc(label) + '</span><input class="a-input num" style="width:120px" ' + (type === 'text' ? 'type="text" maxlength="3"' : 'type="number" step="0.001" min="0"') + ' data-c="' + id + '" data-fid="' + id + '" value="' + esc(v) + '"></div>';
      h += '<div class="a-sec" data-hl="st-price"><div class="a-h">' + esc(S('stats_price_title')) + '</div>' +
        field(S('stats_currency'), 'stCurrency', st.currency, 'text') + field(S('stats_price_ac'), 'stPriceAc', st.priceAc) + field(S('stats_price_dc'), 'stPriceDc', st.priceDc) +
        '<div class="a-desc">' + esc(S('stats_price_note')) + '</div></div>';
      // Petits trajets : la distance minimale est grisée tant que le filtre est coupé.
      h += '<div class="a-sec" data-hl="st-skip"><div class="a-h">' + esc(S('stats_cat_trips')) + '</div><div class="a-row"><span class="lbl">' + esc(S('stats_skip_short_label')) + '</span>' + sw(st.skipShort, 'stSkipShort') + '</div>' +
        '<div class="a-row' + (st.skipShort ? '' : ' dim') + '"><span class="lbl">' + esc(S('stats_min_trip_label')) + '</span><input class="a-input num" style="width:120px" type="number" step="0.1" min="0.1" max="50" data-c="stMinTrip" data-fid="stMinTrip" value="' + st.minTrip + '"' + (st.skipShort ? '' : ' disabled') + '></div>' +
        '<div class="a-desc">' + esc(S('stats_skip_short_note')) + '</div></div>';
      const ret = [[30, 'stats_retention_30d'], [90, 'stats_retention_3m'], [182, 'stats_retention_6m'], [365, 'stats_retention_1y']];
      h += '<div class="a-sec" data-hl="st-retention"><div class="a-h">' + esc(S('stats_retention_title')) + '</div><div class="a-grid g4">' +
        ret.map(([d, k]) => '<button class="b sm' + (st.retention === d ? ' on' : '') + '" data-a="stRetention" data-v="' + d + '">' + esc(S(k)) + '</button>').join('') + '</div>' +
        '<div class="a-desc" style="margin-top:6px">' + esc(S('stats_retention_note')) + '</div>' +
        '<div class="a-row"><span class="lbl">' + esc(S('stats_history_size', st.trips.length, st.charges.length, Math.max(1, Math.round((st.trips.length * 380 + st.charges.length * 300) / 1024)))) + '</span>' +
        '<button class="b danger sm" style="padding:0 16px" data-a="stClear">' + esc(S('stats_clear')) + '</button></div></div>';
      return h;
    }
    dateLine(a, b) {
      const loc = appLang() === 'en' ? 'en-GB' : appLang();
      const d = new Intl.DateTimeFormat(loc, { weekday: 'short', day: '2-digit', month: '2-digit' }).format(a);
      const t = (x) => new Intl.DateTimeFormat(loc, { hour: '2-digit', minute: '2-digit' }).format(x);
      return d + ' · ' + t(a) + ' → ' + t(b);
    }
    // Comme StatsFragment.duration() : minutes tronquées, « 6 h 40 » dès une heure, sinon « 36 min ».
    dur(ms) { const m = Math.floor(ms / 60000); return m >= 60 ? Math.floor(m / 60) + ' h ' + String(m % 60).padStart(2, '0') : m + ' min'; }
    statsTrips() {
      const s = this.statsSummary(), u = this.ui;
      let h = '<div class="a-sec" data-hl="st-trips"><div class="a-h">' + esc(S('stats_cat_trips')) + '</div>' + this.periodRow() + this.tiles([
        [S('stats_tile_trips'), String(s.trips.length)], [S('stats_tile_distance'), this.num(s.km, 1) + ' km'],
        [S('stats_tile_longest'), this.num(s.longest, 1) + ' km'], [S('stats_tile_cost'), this.money(s.driving)]
      ]) + '</div>';
      if (!s.trips.length) return h + '<div class="a-desc" style="text-align:center;padding:16px">' + esc(S('stats_no_trip')) + '</div>';
      s.trips.forEach((t, i) => {
        const cons = t.short ? '≈ ' + Math.round(t.kwh / t.km * 100) + ' kWh/100 km' : this.num(t.kwh / t.km * 100, 1) + ' kWh/100 km';
        const open = u.statsOpen === 't' + i;
        h += '<div class="st-row" data-a="stOpen" data-v="t' + i + '" data-hl="st-trip-row"><div><b>' + esc(this.dateLine(t.start, t.end)) + '</b><small>' + esc(this.dur(t.end - t.start) + ' · ' + this.num(t.km, 1) + ' km') + '</small></div>' +
          '<div class="st-r"><b>' + esc(cons) + '</b><small>' + esc(t.speed + ' km/h · ' + this.num(t.kwh, 1) + ' kWh') + '</small></div></div>';
        if (open) {
          // Sans compteurs d'énergie (hors SWI132/133), l'énergie est calculée d'après la tension et
          // le courant de la batterie, et la répartition vient du compteur d'origine, au kWh entier.
          const whole = (x) => (x < 1 ? '< 1 kWh' : '≈ ' + Math.round(x) + ' kWh');
          const aux = Math.floor(t.climate + t.acc);
          const energy = caps().counters
            ? [[S('stats_detail_motor'), this.num(t.motor, 1) + ' kWh'], [S('stats_detail_climate'), this.num(t.climate, 1) + ' kWh'], [S('stats_detail_accessories'), this.num(t.acc, 1) + ' kWh']]
            : [[S('stats_detail_origin'), S('stats_detail_origin_integrated')], [S('stats_detail_motor'), whole(Math.floor(t.kwh - aux))], [S('stats_detail_auxiliary'), whole(aux)]];
          h += this.detailBox(energy.concat([
            [S('stats_detail_regen'), '− ' + this.num(t.regen, 1) + ' kWh'],
            [S('stats_detail_consumption'), t.short ? S('stats_detail_consumption_short') : cons],
            [S('stats_detail_battery'), t.soc[0] + ' % → ' + t.soc[1] + ' %'], [S('stats_detail_temp'), this.num(t.temp, 1) + ' °C']
          ]));
        }
      });
      return h;
    }
    statsCharges() {
      const s = this.statsSummary(), u = this.ui, st = state.stats;
      // Tant que la batterie n'a été ni choisie ni confirmée, l'onglet rappelle de la vérifier.
      const banner = st.batteryConfirmed ? '' : '<div class="st-banner" data-hl="st-banner"><div class="a-row"><span class="lbl">' + esc(S('stats_battery_banner', st.battery + ' kWh')) + '</span>' +
        '<button class="b sm" style="padding:0 14px" data-a="stBatOk">' + esc(S('stats_battery_banner_ok')) + '</button>' +
        '<button class="b sm on warn" style="padding:0 14px" data-a="stBatChange">' + esc(S('stats_battery_banner_change')) + '</button></div></div>';
      let h = banner + '<div class="a-sec" data-hl="st-charges"><div class="a-h">' + esc(S('stats_cat_charges')) + '</div>' + this.periodRow() + this.tiles([
        [S('stats_tile_charged'), this.num(s.charged, 1) + ' kWh'], [S('stats_tile_cost_total'), this.money(s.chargeCost)],
        [S('stats_tile_avg_price'), s.avgPrice != null ? this.num(s.avgPrice, 3) + ' ' + st.currency : '—'],
        [S('stats_tile_sessions'), s.charges.length + ' · ' + s.ac + ' AC / ' + s.dc + ' DC']
      ]) + '</div>';
      if (!s.charges.length) return h + '<div class="a-desc" style="text-align:center;padding:16px">' + esc(S('stats_no_charge')) + '</div>';
      s.charges.forEach((c) => {
        const i = st.charges.indexOf(c);
        const open = u.statsOpen === 'c' + i;
        const type = S(c.type === 'DC' ? 'stats_charge_dc' : c.type === 'AC' ? 'stats_charge_ac' : 'stats_charge_unknown');
        h += '<div class="st-row" data-a="stOpen" data-v="c' + i + '" data-hl="st-charge-row"><div><b>' + esc(this.dateLine(c.start, c.end)) + (c.reconstructed ? ' <span class="st-badge">' + esc(S('stats_charge_reconstructed')) + '</span>' : '') + '</b><small>' + esc(type + ' · ' + this.dur(c.end - c.start)) + '</small></div>' +
          '<div class="st-r"><b>+ ' + esc(this.num(c.kwh, 1)) + ' kWh</b><small>' + esc(this.money(c.kwh * s.price(c))) + '</small></div></div>';
        if (open) {
          const pr = s.price(c);
          const tariff = this.num(pr, 3) + ' ' + st.currency + '/kWh · ' + (c.fixedPrice != null ? S('stats_tariff_instead', this.num(c.type === 'DC' ? st.priceDc : st.priceAc, 3)) : S('stats_tariff_default'));
          const lines = [];
          if (c.reconstructed) lines.push([S('stats_detail_origin'), S(c.timesKnown ? 'stats_detail_origin_completed' : 'stats_detail_origin_estimated')]);
          if (c.powerMeasured) lines.push([S('stats_detail_power_measured'), this.num(c.powerMeasured, 1) + ' kW']);
          else if (c.powerComputed) lines.push([S('stats_detail_power_computed'), this.num(c.powerComputed, 1) + ' kW']);
          lines.push([S('stats_detail_battery'), c.soc[0] + ' % → ' + c.soc[1] + ' %'], [S('stats_detail_temp'), this.num(c.temp, 1) + ' °C'], [S('stats_detail_tariff'), tariff]);
          h += this.detailBox(lines, '<div class="a-grid g2" style="margin-top:6px"><button class="b sm" data-a="stFix" data-v="' + i + '">' + esc(S('stats_fix_tariff')) + '</button>' +
            (c.reconstructed && !c.timesKnown ? '<button class="b sm primary" data-a="stComplete" data-v="' + i + '">' + esc(S('stats_complete_session')) + '</button>' : '<span></span>') + '</div>');
        }
      });
      return h;
    }
    detailBox(lines, extra) {
      return '<div class="st-detail">' + lines.map(([k, v]) => '<div class="a-row" style="min-height:30px"><span class="lbl" style="font-size:14px;color:var(--text-secondary)">' + esc(k) + '</span><span style="font-size:14px">' + esc(v) + '</span></div>').join('') + (extra || '') + '</div>';
    }

    // ── Overlays ───────────────────────────────────────────────────────────
    /**
     * Sélecteur de profil. Sans argument : tous les profils (raccourci volant, API).
     * Avec `subset` (conflit Bluetooth) : seuls les profils des téléphones connectés, et
     * `onAuto` (application du 1er) si personne ne choisit avant la fin du décompte.
     */
    openPicker(subset, onAuto) {
      this.closeOverlay();
      this.ui.overlay = { type: 'picker', left: 8, subset: subset || null, onAuto: onAuto || null, focus: null };
      this.startCountdown(() => { const o = this.ui.overlay; this.closeOverlay(); if (o && o.onAuto) o.onAuto(); });
      this.render();
    }
    pickerRows() {
      const o = this.ui.overlay, k = caps(), rows = [];
      if (k.bri) { rows.push(['bri:15', 'bri:50', 'bri:100']); rows.push(['slider']); }
      const ids = (o.subset || state.profiles.map((p) => p.id)).filter((id) => state.profiles.some((p) => p.id === id));
      for (let i = 0; i < ids.length; i += 2) rows.push(ids.slice(i, i + 2).map((id) => 'p:' + id));
      rows.push(['close'].concat(k.power ? ['power'] : [], ['open']));
      return rows;
    }
    /** Joystick droit dans le popup : sans rebouclage, cellule la plus proche horizontalement. */
    navigate(dir) {
      const o = this.ui.overlay; if (!o || o.type !== 'picker') return;
      const rows = this.pickerRows();
      if (!o.focus) { const first = rows.findIndex((r) => r[0].startsWith('p:')); o.focus = { r: first < 0 ? 0 : first, c: 0 }; }
      let { r, c } = o.focus;
      r = clamp(r, 0, rows.length - 1); c = clamp(c, 0, rows[r].length - 1);
      const cell = rows[r][c];
      if (dir === 'ok') {
        if (cell !== 'slider') { const el = this.app.querySelector('[data-cell="' + cell + '"]'); if (el) el.click(); }
        return;
      }
      if (cell === 'slider' && (dir === 'left' || dir === 'right')) {
        car().brightness = clamp(car().brightness + (dir === 'left' ? -5 : 5), 5, 100);
      } else if (dir === 'left' || dir === 'right') {
        c = clamp(c + (dir === 'left' ? -1 : 1), 0, rows[r].length - 1);
      } else {
        const nr = clamp(r + (dir === 'up' ? -1 : 1), 0, rows.length - 1);
        if (nr !== r) {
          const x = (c + 0.5) / rows[r].length;
          let best = 0, bd = 9;
          rows[nr].forEach((_, i) => { const d = Math.abs((i + 0.5) / rows[nr].length - x); if (d < bd) { bd = d; best = i; } });
          r = nr; c = best;
        }
      }
      o.focus = { r, c }; o.left = 8;
      commit();
    }
    /**
     * Pop-up HVAC (HvacPopupOverlay) : le joystick n'y déplace aucun focus, chaque direction agit.
     * Ouvert et refermé par le même raccourci ; se referme seul après 6 s sans action.
     */
    openHvacPopup() {
      if (!caps().clim) return hud(L('Pop-up HVAC indisponible sur ce firmware', 'HVAC pop-up unavailable on this firmware'));
      this.closeOverlay();
      this.ui.overlay = { type: 'hvac', left: 6, total: 6, flash: null };
      this.startCountdown(() => this.closeOverlay());
      this.render();
    }
    toggleHvacPopup() {
      const o = this.ui.overlay;
      if (o && o.type === 'hvac') return this.closeOverlay();
      this.openHvacPopup();
    }
    /** Haut/bas : température ; gauche/droite : ventilation ; centre : fermer. Au doigt comme au joystick. */
    hvacKey(dir) {
      const o = this.ui.overlay; if (!o || o.type !== 'hvac') return;
      if (dir === 'ok') return this.closeOverlay();
      const cl = car().clim;
      // On clampe, on ne boucle pas : comme dans l'application.
      if (dir === 'up' || dir === 'down') cl.temp = clamp(cl.temp + (dir === 'up' ? 1 : -1), cl.tMin, cl.tMax);
      else cl.fan = clamp(cl.fan + (dir === 'right' ? 1 : -1), cl.fMin, cl.fMax);
      o.left = o.total; o.flash = dir;
      clearTimeout(this._hvFlash);
      this._hvFlash = setTimeout(() => { const cur = this.ui.overlay; if (cur && cur.type === 'hvac') { cur.flash = null; this.render(); } }, 200);
      commit();
    }
    showUpdateOverlay() {
      this.closeOverlay();
      this.ui.overlay = { type: 'update' };
      this.render();
    }
    showConfirm(p, onNo, battery) {
      this.closeOverlay();
      const a = state.auto.p;
      this.ui.overlay = { type: 'confirm', profileId: p.id, left: 8, onNo, dir: a.dir, thr: a.thr, temp: state.car.outside, battery: battery || null };
      this.startCountdown(() => { const o = this.ui.overlay; this.closeOverlay(); if (o && o.onNo) o.onNo(); });
      this.render();
    }
    startCountdown(onEnd) {
      clearInterval(this._cd);
      this._cd = setInterval(() => {
        const o = this.ui.overlay; if (!o) { clearInterval(this._cd); return; }
        o.left -= 1;
        if (o.left <= 0) { clearInterval(this._cd); onEnd(); return; }
        const el = this.app.querySelector('[data-cd]');
        const bar = this.app.querySelector('[data-cdbar]');
        if (bar) bar.style.width = (100 * o.left / (o.total || 8)) + '%';
        if (el) el.textContent = S('overlay_countdown', o.left); else this.render();
      }, 1000);
    }
    closeOverlay() { clearInterval(this._cd); this.ui.overlay = null; this.render(); }
    overlayHtml() {
      const o = this.ui.overlay, k = caps();
      if (o.type === 'picker') {
        const c = car();
        // Anneau de focus du joystick : seulement si le service d'accessibilité tourne.
        const nav = state.sc.advService && !garage();
        const rows = this.pickerRows();
        let focusCell = null;
        if (nav) {
          const f = o.focus || { r: Math.max(0, rows.findIndex((r) => r[0].startsWith('p:'))), c: 0 };
          const rr = rows[clamp(f.r, 0, rows.length - 1)];
          focusCell = rr[clamp(f.c, 0, rr.length - 1)];
        }
        const fc = (cell) => ' data-cell="' + cell + '"' + (cell === focusCell ? ' data-kf="1"' : '');
        const kf = (cell) => (cell === focusCell ? ' kfocus' : '');
        const bri = k.bri ? '<div class="pk-bri" data-hl="pk-bri"><span class="lbl">☀ ' + esc(S('overlay_brightness_title')) + '</span>' +
          '<button class="b' + kf('bri:15') + '" data-a="pkBri" data-v="15"' + fc('bri:15') + '>' + esc(S('overlay_brightness_night')) + '</button><button class="b' + kf('bri:50') + '" data-a="pkBri" data-v="50"' + fc('bri:50') + '>' + esc(S('overlay_brightness_mid')) + '</button><button class="b' + kf('bri:100') + '" data-a="pkBri" data-v="100"' + fc('bri:100') + '>' + esc(S('overlay_brightness_day')) + '</button>' +
          '<div style="flex:1" class="pk-slider' + kf('slider') + '">' + range('pkBri', 5, 100, c.brightness, false, 'big') + '</div><span class="a-val" data-out="pkBri">' + c.brightness + '%</span></div><div class="p-line" style="margin:0"></div>' : '';
        const list = o.subset ? state.profiles.filter((p) => o.subset.includes(p.id)) : state.profiles;
        const profiles = list.length ? list.map((p) => '<button class="b' + kf('p:' + p.id) + '" data-a="pkProfile" data-v="' + p.id + '"' + fc('p:' + p.id) + '>' + esc(p.name) + '</button>').join('') : '<div class="pk-empty">' + esc(S('shortcuts_no_profiles')) + '</div>';
        return '<div class="a-scrim" data-a="pkBg"><div class="picker" data-hl="picker"><div class="pk-title">' + esc(S('overlay_profile_title')) + '</div>' + bri +
          '<div class="pk-grid" data-hl="pk-grid">' + profiles + '</div>' +
          '<div class="pk-foot" data-hl="pk-foot"><div><button class="b' + kf('close') + '" data-a="pkClose"' + fc('close') + '>' + esc(S('nav_close')) + '</button><span class="cd" data-cd>' + esc(S('overlay_countdown', o.left)) + '</span></div>' +
          (k.power ? '<button class="b danger' + kf('power') + '" data-a="pkPower"' + fc('power') + '>' + esc(S('shortcuts_action_vehicle_power_off')) + '</button>' : '') +
          '<div style="justify-content:flex-end"><button class="b primary' + kf('open') + '" data-a="pkOpen"' + fc('open') + '>' + esc(S('overlay_open_app')) + '</button></div></div></div></div>';
      }
      if (o.type === 'hvac') {
        const cl = car().clim;
        // Sans service d'accessibilité, le joystick ne parvient pas à l'application : la fenêtre le dit.
        const nav = state.sc.advService && !garage();
        const cell = (dir, cls, ico, key) => '<button class="b hv-cell hv-' + cls + (o.flash === dir ? ' hit' : '') + '" data-a="hvKey" data-v="' + dir + '">' + ico + '<span>' + esc(S(key)) + '</span></button>';
        const ab = (f, on, key, icon) => '<button class="b air sm' + (on ? ' on' : '') + '" data-a="hvAir" data-v="' + f + '">' + AIR_ICON[icon] + '<span>' + esc(S(key)) + '</span></button>';
        return '<div class="a-scrim" data-a="hvBg"><div class="hvacpop" data-hl="hvac-popup"><div class="hv-top"><div class="hv-cross">' +
          cell('up', 'up', HV_ICON.temp, 'hvac_popup_temp_up') + cell('left', 'left', HV_ICON.fan, 'hvac_popup_fan_down') + cell('ok', 'ok', '', 'nav_close') +
          cell('right', 'right', HV_ICON.fan, 'hvac_popup_fan_up') + cell('down', 'down', HV_ICON.temp, 'hvac_popup_temp_down') + '</div>' +
          '<div class="hv-vals"><div class="lbl">' + esc(S('clim_temperature')) + '</div><div class="hv-temp">' + cl.temp + ' °C</div>' +
          '<div class="lbl">' + esc(S('clim_fan')) + '<b>' + cl.fan + '</b></div><div class="hv-fan"><i style="width:' + Math.round(100 * cl.fan / cl.fMax) + '%"></i></div></div></div>' +
          '<div class="lbl hv-airlbl">' + esc(S('clim_section_airflow')) + '</div><div class="a-grid g4">' +
          ab('face', cl.air.face, 'clim_air_face', 'face') + ab('feet', cl.air.feet, 'clim_air_feet', 'feet') +
          ab('ws', cl.air.ws, 'clim_air_windshield_front', 'ws') + ab('rear', cl.defR, 'clim_air_windshield_rear', 'rear') + '</div>' +
          '<div class="hv-bar"><i data-cdbar style="width:' + Math.round(100 * o.left / o.total) + '%"></i></div>' +
          '<div class="cd" data-cd>' + esc(S('overlay_countdown', o.left)) + '</div>' +
          (nav ? '' : '<div class="hv-hint">' + esc(S('hvac_popup_no_joystick')) + '</div>') + '</div></div>';
      }
      if (o.type === 'update') {
        return '<div class="a-scrim" data-a="upBg"><div class="confirm" data-hl="upd-overlay"><div class="msg"><b>' + esc(S('update_overlay_title')) + '</b>\n<span style="font-size:26px;font-weight:700;color:var(--dash-accent)">' + esc(S('update_overlay_versions', 'v2.6.8', 'v2.x.x')) + '</span></div>' +
          '<div class="yn"><button class="b primary" data-a="upInstall">' + esc(S('update_overlay_install')) + '</button><button class="b" data-a="upSkip">' + esc(S('update_overlay_skip')) + '</button></div>' +
          '<button class="b ghost full" style="margin-top:10px;border:0;opacity:.7;text-transform:none" data-a="upDisable">' + esc(S('update_overlay_disable')) + '</button></div></div>';
      }
      if (o.type === 'confirm') {
        const p = state.profiles.find((x) => x.id === o.profileId);
        const msg = o.battery ? S('battery_auto_confirm_msg', o.battery.thr, String(o.battery.soc), p ? p.name : '?')
          : S(o.dir === 'ABOVE' ? 'automation_confirm_msg_above' : 'automation_confirm_msg', o.thr, String(o.temp), p ? p.name : '?');
        return '<div class="a-scrim"><div class="confirm" data-hl="confirm"><div class="msg">' + esc(msg) + '</div><div class="yn">' +
          '<button class="b green" data-a="cfYes">' + esc(S('automation_confirm_yes')) + '</button><button class="b danger" data-a="cfNo">' + esc(S('automation_confirm_no')) + '</button></div>' +
          '<div class="cd" data-cd>' + esc(S('overlay_countdown', o.left)) + '</div></div></div>';
      }
      return '';
    }
    askPowerOff() {
      if (!car().on) return;
      if (car().speed > 0) { this.toast(S('vehicle_power_need_park'), 3000); return; }
      this.ui.dialog = { type: 'powerOff' }; this.render();
    }

    // ── Dialogues ──────────────────────────────────────────────────────────
    dialogHtml(d) {
      const btn = (label, a, cls, v) => '<button class="b ' + (cls || '') + '" data-a="' + a + '"' + (v != null ? ' data-v="' + esc(v) + '"' : '') + '>' + esc(label) + '</button>';
      const wrap = (inner, cls) => '<div class="a-scrim"><div class="a-dialog ' + (cls || '') + '" role="dialog">' + inner + '</div></div>';
      switch (d.type) {
        case 'fwUnknown':
          return wrap('<h3 style="color:var(--dash-warn)">' + esc(S('fw_unknown_title')) + '</h3><p><span class="badge-fw">SWI???-00000</span></p><p>' + esc(S('fw_unknown_body')) + '</p><p style="font-size:13px">' + esc(S('fw_unknown_hint')) + '</p>' +
            '<div class="actions">' + btn(S('fw_unknown_btn_close'), 'fwClose', 'danger') + btn(S('fw_unknown_btn_continue'), 'fwContinue', 'primary') + '</div>');
        case 'lang': {
          const b = (c) => btn(S('settings_language_' + c), 'pickLang', 'nc', c);
          return wrap('<h3>' + esc(S('settings_language_pick_title')) + '</h3><div class="a-grid g3">' + b('fr') + b('en') + b('de') + b('es') + b('pt') + b('it') + b('tr') + '</div>');
        }
        case 'update':
          if (d.step === 'progress') return wrap('<h3>' + esc(S('update_available_title')) + '</h3><p>' + esc(S('update_downloading_pct', d.pct || 0)) + '</p><div style="height:10px;border-radius:5px;background:var(--dash-btn);overflow:hidden"><div style="height:100%;width:' + (d.pct || 0) + '%;background:var(--dash-accent)"></div></div><div class="actions">' + btn(S('update_cancel'), 'dlgClose') + '</div>');
          if (d.step === 'done') return wrap('<h3>' + esc(S('update_manual_title')) + '</h3><p>' + esc(S('update_downloaded_instructions')) + '</p><div class="actions">' + btn(S('update_close'), 'dlgClose', 'primary') + '</div>');
          if (d.step === 'manual') return wrap('<h3>' + esc(S('update_manual_title')) + '</h3><div class="a-cols"><p style="flex:2">' + esc(S('update_manual_instructions')) + '</p><div style="flex:1;text-align:center">' + qrBox() + '<div class="a-desc" style="font-size:12px;margin-top:6px">' + esc(S('update_gh_releases_link')) + '</div></div></div><div class="actions">' + btn(S('update_close'), 'dlgClose', 'primary') + '</div>', 'wide');
          if (d.step === 'data') return wrap('<h3>' + esc(S('update_data_warn_title')) + '</h3><p>' + esc(S('update_data_warn_message')) + '</p><div class="actions">' + btn(S('update_cancel'), 'dlgClose') + btn(S('update_continue'), 'updDownload', 'primary') + '</div>');
          return wrap('<h3>' + esc(S('update_available_title')) + '</h3>' +
            '<div class="a-row" style="gap:20px;justify-content:center;font-size:18px"><span><small class="a-desc">' + esc(S('update_current_label')) + '</small><br><b>v2.6.8</b></span><span style="font-size:24px">→</span><span><small class="a-desc">' + esc(S('update_new_label')) + '</small><br><b style="color:var(--dash-eco)">v2.x.x</b></span></div>' +
            '<div class="a-sub" style="margin-top:8px">' + esc(S('update_release_notes_label')) + '</div><p style="background:var(--dash-section);border-radius:8px;padding:8px">' + esc(L('(exemple : le texte des notes de version de GitHub s\'affiche ici)', '(example: the GitHub release notes are shown here)')) + '</p>' +
            '<p style="color:var(--dash-warn)">' + esc(S('update_data_warning')) + '</p>' +
            '<div class="actions">' + btn(S('update_skip_btn'), 'dlgClose', 'ghost') + '<span class="spacer"></span>' + btn(S('update_later_btn'), 'dlgClose') + btn(S('update_manual_btn'), 'updManual') + btn(S('update_auto_btn'), 'updAuto', 'primary') + '</div>', 'wide');
        case 'restore':
          return wrap('<h3>' + esc(S('profile_restore_title')) + '</h3><p>' + esc(S('profile_restore_msg', 3)) + '</p><div class="actions">' + btn(S('profile_restore_cancel'), 'dlgClose') + btn(S('profile_restore_confirm'), 'restoreYes', 'primary') + '</div>');
        case 'apiConfirm':
          return wrap('<h3>' + esc(S('external_api_confirm_title')) + '</h3><div class="warnbox">' + esc(S('external_api_confirm_warn')) + '</div><p>' + esc(S('external_api_confirm_msg')) + '</p><p><b>' + esc(S('external_api_confirm_risk')) + '</b></p>' +
            '<div class="actions">' + btn(S('profile_cancel'), 'dlgClose') + btn(S('external_api_confirm_ok'), 'apiOk', 'danger') + '</div>', 'wide');
        case 'advWarn':
          return wrap('<h3>' + esc(S('adv_sc_enable_warn_title')) + '</h3><p>' + esc(S('adv_sc_enable_warn_msg')) + '</p><div class="actions">' + btn(S('profile_cancel'), 'dlgClose') + btn(S('adv_sc_enable_warn_ok'), 'advWarnOk', 'primary') + '</div>', 'wide');
        case 'launcherWarn':
          return wrap('<h3>' + esc(S('shortcuts_warning_title')) + '</h3><p>' + esc(S('shortcuts_warning_message')) + '</p><div class="actions">' + btn(S('shortcuts_warning_ok'), 'dlgClose', 'primary') + '</div>', 'wide');
        case 'powerOff':
          return wrap('<h3>' + esc(S('vehicle_power_dialog_title')) + '</h3><p>' + esc(S('vehicle_power_dialog_msg')) + '</p><div class="actions">' + btn(S('vehicle_power_dialog_cancel'), 'dlgClose') + btn(S('vehicle_power_dialog_confirm'), 'powerOk', 'danger') + '</div>');
        case 'pickApp':
          return wrap('<h3>' + esc(S('shortcuts_pick_app_title')) + '</h3><div class="opt-list">' + APPS.map((n) => btn(n, 'pickApp', 'nc', n)).join('') + '</div><div class="actions">' + btn(S('profile_cancel'), 'pickCancel') + '</div>');
        case 'pickProfile':
          return wrap('<h3>' + esc(S('shortcuts_pick_profile_title')) + '</h3><div class="opt-list">' + state.profiles.map((p) => btn(p.name, 'pickProfile', 'nc', p.id)).join('') + '</div><div class="actions">' + btn(S('profile_cancel'), 'pickCancel') + '</div>');
        case 'replace': {
          const pl = { single: 'adv_sc_press_short', long: 'adv_sc_press_long_lbl', double: 'adv_sc_press_double' };
          return wrap('<h3>' + esc(S('adv_sc_replace_title')) + '</h3><p>' + esc(S('adv_sc_replace_msg', keyName(d.item.key), S(pl[d.item.press]).toLowerCase(), this.actionLabel(d.old.action, d.old))) + '</p>' +
            '<div class="actions">' + btn(S('profile_cancel'), 'dlgClose') + btn(S('adv_sc_replace_ok'), 'replaceOk', 'primary') + '</div>');
        }
        case 'editAdv': {
          const it = state.sc.adv[d.index] || {};
          return wrap('<h3>' + esc(S('adv_sc_edit_title')) + '</h3><p><b>' + esc(keyName(it.key) + ' · ' + S({ single: 'adv_sc_press_short', long: 'adv_sc_press_long_lbl', double: 'adv_sc_press_double' }[it.press])) + '</b></p>' +
            '<div class="a-sub">' + esc(S('adv_sc_step_action')) + '</div><div class="a-row">' + this.actionSelect(d.action, 'edAction', '', it) + '</div>' +
            '<div class="a-sub" style="margin-top:8px">' + esc(S('adv_sc_step_profile')) + '</div><div class="a-row">' + this.scopeSelect(d.scope, 'edScope') + '</div>' +
            '<div class="actions">' + btn(S('profile_cancel'), 'dlgClose') + btn(S('profile_save'), 'edSave', 'primary') + '</div>');
        }
        case 'cal': {
          const w = d.w, pos = car().windows[w], moving = !!winMotion[w];
          let body = '', acts = btn(S('win_cal_cancel'), 'calCancel');
          if (d.step === 1) {
            body = '<p>' + esc(S('win_cal_close_instruction')) + '</p>';
            acts += btn(S('win_cal_close_action'), 'calClose') + (pos <= 0 && !moving ? btn(S('win_cal_closed_action'), 'calNext', 'primary') : '');
          } else if (d.step === 2 || d.step === 3) {
            const down = d.step === 2;
            body = '<p>' + esc(S(down ? 'win_cal_down_instruction' : 'win_cal_up_instruction')) + '</p><div class="cal-hold"><button class="b primary cal-btn" data-hold="' + (down ? 'calDown' : 'calUp') + '">' + esc(S(down ? 'win_cal_down_action' : 'win_cal_up_action')) + '</button>' +
              '<span class="a-val" data-cal-timer>' + (d.holding ? fmtS(performance.now() - d.holding) + ' s' : '') + '</span></div>';
          } else {
            body = '<p>' + esc(S('win_cal_result', fmtS(d.down), fmtS(d.up))) + '</p>';
            acts += btn(S('win_cal_redo'), 'calRedo') + btn(S('win_cal_save'), 'calSave', 'primary');
          }
          return wrap('<h3>' + esc(S('win_cal_wizard_title', S(WIN_KEY[w]))) + '</h3><div class="a-sub">' + esc(S('win_cal_step', d.step)) + '</div>' +
            (d.msg ? '<p style="color:var(--dash-warn)">' + esc(d.msg) + '</p>' : '') + body +
            '<div class="win-bar wide"><i style="width:' + pos + '%"></i></div><p style="font-size:13px;color:var(--dash-danger)">⚠ ' + esc(S('win_cal_warning')) + '</p>' +
            '<div class="actions">' + acts + '</div>', 'wide');
        }
        case 'stClear':
          return wrap('<h3>' + esc(S('stats_clear')) + '</h3><p>' + esc(S('stats_clear_confirm', state.stats.trips.length, state.stats.charges.length)) + '</p><div class="actions">' + btn(S('profile_cancel'), 'dlgClose') + btn(S('stats_clear'), 'stClearOk', 'danger') + '</div>');
        case 'stFix': {
          const c = state.stats.charges[d.index];
          const cur = c.fixedPrice != null ? c.fixedPrice : c.type === 'DC' ? state.stats.priceDc : state.stats.priceAc;
          return wrap('<h3>' + esc(S('stats_fix_tariff')) + '</h3><p>' + esc(S('stats_fix_tariff_note')) + '</p><div class="a-row"><input class="a-input num" style="width:140px" type="number" step="0.001" min="0" data-c="stFixVal" data-fid="stFixVal" value="' + cur + '"><span class="lbl">' + esc(state.stats.currency) + '/kWh</span></div>' +
            '<div class="actions">' + btn(S('profile_cancel'), 'dlgClose') + btn(S('stats_save'), 'stFixOk', 'primary') + '</div>');
        }
        case 'stComplete': {
          const t2 = (ms) => new Date(ms).toTimeString().slice(0, 5);
          const c = state.stats.charges[d.index];
          return wrap('<h3>' + esc(S('stats_complete_session')) + '</h3><p>' + esc(S('stats_complete_note')) + '</p>' +
            '<div class="a-sub">' + esc(S('stats_complete_type')) + '</div><div class="a-grid g2">' + ['AC', 'DC'].map((t) => '<button class="b sm' + (d.typeSel === t ? ' on' : '') + '" data-a="stType" data-v="' + t + '">' + esc(S(t === 'AC' ? 'stats_charge_ac' : 'stats_charge_dc')) + '</button>').join('') + '</div>' +
            '<div class="fields" style="grid-template-columns:1fr 1fr"><div class="field"><label>' + esc(S('stats_complete_start')) + '</label><input class="a-input" type="time" data-c="stStart" value="' + (d.startT || t2(c.start)) + '"></div>' +
            '<div class="field"><label>' + esc(S('stats_complete_end')) + '</label><input class="a-input" type="time" data-c="stEnd" value="' + (d.endT || t2(c.end)) + '"></div></div>' +
            '<div class="actions">' + btn(S('profile_cancel'), 'dlgClose') + btn(S('stats_save'), 'stCompleteOk', 'primary') + '</div>');
        }
        case 'delProfile': {
          const p = state.profiles.find((x) => x.id === d.id);
          // Titre identique à ProfileFragment (texte en dur dans l'app).
          return wrap('<h3>' + esc('Supprimer "' + (p ? p.name : '') + '" ?') + '</h3><p>' + esc(L('Ce profil sera supprimé.', 'This profile will be deleted.')) + '</p><div class="actions">' + btn(S('profile_cancel'), 'dlgClose') + btn(S('profile_delete'), 'delOk', 'danger') + '</div>');
        }
        case 'about':
          return wrap('<div class="a-row" style="border-bottom:1px solid var(--dash-border);margin-bottom:10px"><h3 style="margin:0">' + esc(S('info_title')) + '</h3></div><div class="a-cols sep"><div>' +
            '<div style="font-size:24px;font-weight:700">' + esc(S('app_name')) + '</div><div class="a-desc">' + esc(S('info_based_on')) + '</div>' +
            '<div class="a-row"><span class="a-sub" style="margin:0">' + esc(S('info_version_label')) + '</span><b>2.6.8</b></div>' +
            '<div class="a-sub">' + esc(S('info_firmware_label')) + '</div><div class="a-desc" style="font-family:ui-monospace,monospace">' + esc(gen() === 'UNKNOWN' ? 'SWI???-00000' : gen() + '-xxxxx') + '</div></div>' +
            '<div><div class="a-cols"><div style="text-align:center">' + qrBox() + '<div class="a-desc" style="font-size:12px">' + esc(S('info_github_link')) + '</div></div><div style="text-align:center">' + qrBox() + '<div class="a-desc" style="font-size:12px">' + esc(S('info_gitlab_link')) + '</div></div></div>' +
            '<p style="margin-top:8px;text-align:center">' + esc(S('info_made_with_love')) + '</p><p style="font-size:12px;text-align:center">' + esc(S('info_special_thanks')) + '</p></div></div>' +
            '<div class="actions">' + btn(S('info_close'), 'dlgClose', 'primary') + '</div>', 'xl');
        case 'diag':
          return wrap('<h3>' + esc(S('diag_title')) + '</h3><pre style="margin:0;max-height:250px;overflow:auto;background:var(--dash-section);border-radius:8px;padding:10px;font-size:12px;line-height:1.45;color:var(--text-secondary);white-space:pre-wrap">' +
            esc(diagSample()) + '</pre><div class="actions">' + btn(S('diag_copy'), 'diagCopy') + btn(S('diag_download'), 'diagDl') + btn(S('info_close'), 'dlgClose', 'primary') + '</div>', 'xl');
        default: return '';
      }
    }

    // ── Événements ─────────────────────────────────────────────────────────
    bind() {
      this.app.addEventListener('click', (e) => {
        const t = e.target.closest('[data-a]');
        if (!t || !this.app.contains(t)) return;
        if (t.disabled || t.classList.contains('dis')) return;
        if ((t.getAttribute('data-a') === 'pkBg' || t.getAttribute('data-a') === 'hvBg') && e.target !== t) return; // clic dans la carte
        this.act(t.getAttribute('data-a'), t.getAttribute('data-v'), t);
      });
      this.app.addEventListener('input', (e) => {
        const t = e.target;
        if (t.type === 'range') {
          paintRange(t);
          const out = this.app.querySelector('[data-out="' + t.getAttribute('data-c') + '"]');
          const id = t.getAttribute('data-c');
          if (out) out.textContent = outText(id, t.value);
          if (id === 'pkBri' && this.ui.overlay) this.ui.overlay.left = 8;
        } else if (t.getAttribute('data-c') === 'eName' && this.ui.edit) {
          this.ui.edit.name = t.value;
        }
      });
      this.app.addEventListener('change', (e) => {
        const t = e.target.closest('[data-c]');
        if (t) this.change(t.getAttribute('data-c'), t);
      });
      this.app.addEventListener('keydown', (e) => {
        if (e.key === 'Enter' && e.target.matches('input.a-input')) e.target.blur();
      });
      // Assistant de calibration : la vitre bouge tant que le bouton reste enfoncé, et l'appui est chronométré.
      this.app.addEventListener('pointerdown', (e) => {
        const h = e.target.closest('[data-hold]');
        const d = this.ui.dialog;
        if (h && d && d.type === 'cal') {
          e.preventDefault();
          d.holding = performance.now();
          moveWindow(d.w, h.getAttribute('data-hold') === 'calDown' ? 1 : -1);
        }
      });
      const release = () => {
        const d = this.ui.dialog;
        if (d && d.type === 'cal' && d.holding) {
          const ms = Math.round(performance.now() - d.holding);
          d.holding = null;
          moveWindow(d.w, 0);
          if (ms < 800 || ms > 15000) {
            d.msg = S(ms < 800 ? 'win_cal_too_short' : 'win_cal_too_long', fmtS(ms));
            d.step = 1;
          } else if (d.step === 2) { d.down = ms; d.step = 3; d.msg = null; }
          else { d.up = ms; d.step = 4; d.msg = null; }
          this.render();
        }
      };
      this.app.addEventListener('pointerup', release);
      this.app.addEventListener('pointercancel', release);
      window.addEventListener('pointerup', () => { if (this.ui.dialog && this.ui.dialog.holding) release(); });
    }

    act(a, v, el) {
      const c = car(), s = state.settings, u = this.ui, e = u.edit, sc = state.sc;
      const n = v != null ? +v : null;
      const r = () => { commit(); };
      switch (a) {
        // Navigation
        case 'nav': this.ui.screen = u.screen === v ? 'dashboard' : v; if (v === 'profileEdit') this.startEdit(null); return this.render();
        case 'close': this.ui.screen = 'dashboard'; return this.render();
        case 'tab': { const [key, i] = v.split(':'); u.tabs[key] = key === 'sc' ? i : +i; const sc2 = this.app.querySelector('.a-scroll'); if (sc2) sc2.scrollTop = 0; this._lastKey = null; return this.render(); }
        case 'logo':
          if (state.diagUnlocked) return;
          u.logoTaps++;
          if (u.logoTaps >= 5) { u.logoTaps = 0; state.diagUnlocked = true; commit(); this.toast(S('diagnostic_unlocked')); }
          return;
        case 'reopen':
          // Relancer l'app sans avoir forcé de mode : le firmware est toujours inconnu, l'avertissement revient.
          if (state.fw === 'UNKNOWN' && !state.forced) state.fwDismissed = false;
          u.screen = state.settings.defaultScreen; return commit();
        // Conduite
        case 'drive': if (W.drive(v)) r(); return;
        case 'energy': if (W.energy(!c.energy)) r(); return;
        case 'regen': if (W.regen(v)) r(); return;
        // Sécurité
        case 'adas': if (W.adas(n)) r(); return;
        case 'tsr': if (W.tsr(!c.tsr)) r(); return;
        case 'overspeed': if (W.bool('overspeed', !c.overspeed)) r(); return;
        case 'speedTone': if (W.bool('speedTone', !c.speedTone)) r(); return;
        case 'sound': if (W.bool('sound', !c.sound)) r(); return;
        case 'aebOn': if (W.bool('aebOn', !c.aebOn)) r(); return;
        case 'aebMode': if (W.aebMode(n)) r(); return;
        case 'aebSen': if (W.aebSen(n)) r(); return;
        case 'elkOn': if (W.elkOn(c.elkMode === ELK.OFF)) r(); return;
        case 'elkMode': if (W.elkMode(n)) r(); return;
        case 'elkSen': if (W.elkSen(n)) r(); return;
        case 'elkSound': if (W.bool('elkSound', !c.elkSound)) r(); return;
        case 'elkVib': if (W.bool('elkVib', !c.elkVib)) r(); return;
        case 'esc': if (W.esc(n === 1)) r(); return;
        case 'dms': if (W.bool('dms', n === 1)) r(); return;
        case 'dmsSen': if (W.dmsSen(n)) r(); return;
        // Confort (jamais bloqué par le verrou de vitesse)
        case 'steer': c.steering = !c.steering; return r();
        case 'seatL': c.seatL = n; return r();
        case 'seatR': c.seatR = n; return r();
        case 'climPower': c.clim.power = !c.clim.power; return r();
        case 'climAc': c.clim.ac = !c.clim.ac; return r();
        case 'climAuto': c.clim.auto = !c.clim.auto; return r();
        case 'climLoop': c.clim.loop = n; return r();
        case 'climDefR': c.clim.defR = !c.clim.defR; return r();
        // Profils
        case 'pApply': { const p = state.profiles.find((x) => x.id === v); if (p) applyProfile(p, { manual: true, toast: true }); return; }
        case 'pEdit': { const p = state.profiles.find((x) => x.id === v); this.startEdit(p); u.screen = 'profileEdit'; return this.render(); }
        case 'pDefault': {
          // Toast écrit en dur en français dans l'app (ProfileFragment), quelle que soit la langue.
          const p = state.profiles.find((x) => x.id === v);
          state.defaultId = v; r(); return this.toast('Profil par défaut : ' + (p ? p.name : ''));
        }
        case 'pDel': u.dialog = { type: 'delProfile', id: v }; return this.render();
        case 'delOk': state.profiles = state.profiles.filter((x) => x.id !== u.dialog.id); if (state.defaultId === u.dialog.id) state.defaultId = null; u.dialog = null; return r();
        case 'pNew':
          if (state.profiles.length >= 5) return this.toast(S('profile_max_reached', 5));
          this.startEdit(null); u.screen = 'profileEdit'; return this.render();
        // Éditeur
        case 'eDrive': e.driveMode = v; return this.render();
        case 'eEnergy': e.energy = !e.energy; return this.render();
        case 'eRegen': e.regen = v; return this.render();
        case 'eTsr': e.tsr = !e.tsr; return this.render();
        case 'eAdas': e.adas = n; return this.render();
        case 'eOverspeed': e.overspeed = !e.overspeed; return this.render();
        case 'eSpeedTone': e.speedTone = !e.speedTone; return this.render();
        case 'eSound': e.sound = !e.sound; return this.render();
        case 'eAebOn': e.aebOn = !e.aebOn; return this.render();
        case 'eAebMode': e.aebMode = n; return this.render();
        case 'eAebSen': e.aebSen = n; return this.render();
        case 'eElkOn': e.elkOn = !e.elkOn; return this.render();
        case 'eElkMode': e.elkMode = n; return this.render();
        case 'eElkSen': e.elkSen = n; return this.render();
        case 'eElkSound': e.elkSound = !e.elkSound; return this.render();
        case 'eElkVib': e.elkVib = !e.elkVib; return this.render();
        case 'eEsc': e.esc = n === 1; return this.render();
        case 'eDms': e.dms = n === 1; return this.render();
        case 'eDmsSen': e.dmsSen = n; return this.render();
        case 'eSteerApply': e.steeringApply = !e.steeringApply; return this.render();
        case 'eSteer': e.steeringOn = n === 1; return this.render();
        case 'eSeatApply': e.seatApply = !e.seatApply; return this.render();
        case 'eSeatL': e.seatL = n; return this.render();
        case 'eSeatR': e.seatR = n; return this.render();
        case 'eDefault': e._default = !e._default; return this.render();
        case 'eCancel': u.edit = null; u.screen = 'profiles'; return this.render();
        case 'eSave': {
          const name = (e.name || '').trim();
          if (!name) return this.toast(S('profile_name_required'));
          const p = Object.assign({}, e, { name });
          const isDef = p._default; delete p._new; delete p._default;
          const i = state.profiles.findIndex((x) => x.id === p.id);
          if (i >= 0) state.profiles[i] = p; else state.profiles.push(p);
          if (isDef) state.defaultId = p.id; else if (state.defaultId === p.id) state.defaultId = null;
          u.edit = null; u.screen = 'profiles'; return r();
        }
        // Réglages
        case 'lang': state.appLang = v; return r();
        case 'defScreen': s.defaultScreen = v; return r();
        case 'theme': s.theme = v; return r();
        case 'api': if (s.api) { s.api = false; return r(); } u.dialog = { type: 'apiConfirm' }; return this.render();
        case 'apiOk': s.api = true; u.dialog = null; return r();
        case 'autoUpdate': s.autoUpdate = !s.autoUpdate; return r();
        case 'beta': s.beta = !s.beta; return r();
        case 'gate': s.gate = !s.gate; return r();
        case 'powerOff': return this.askPowerOff();
        case 'powerOk': u.dialog = null; if (car().speed === 0) { c.on = false; commit(); this.toast(L('Véhicule mis hors tension, l\'écran reste actif.', 'Vehicle powered off, the screen stays on.')); } return;
        case 'checkUpd': u.updFb = 'ok'; this.render(); clearTimeout(this._updT); this._updT = setTimeout(() => { u.updFb = null; this.render(); }, 3000); return;
        case 'cleanApk': u.apkFb = S('clean_apk_none'); this.render(); clearTimeout(this._apkT); this._apkT = setTimeout(() => { u.apkFb = null; this.render(); }, 3000); return;
        case 'about': u.dialog = { type: 'about' }; return this.render();
        case 'diag': u.dialog = { type: 'diag' }; return this.render();
        case 'diagCopy': return this.toast(S('diag_copied'));
        case 'diagDl': return this.toast(S('diag_downloaded', 'Download/MG4Control_diag.txt'));
        case 'force': state.forced = v; state.fwDismissed = true; return r();
        // Raccourcis
        case 'scOn': sc.enabled = !sc.enabled; if (sc.enabled) u.dialog = { type: 'launcherWarn' }; return r();
        case 'scFallback': sc.fallback = v; return r();
        case 'scAdasA': sc.adasA = n; return r();
        case 'scAdasB': sc.adasB = n; return r();
        case 'advOn': if (sc.advOn) { sc.advOn = false; return r(); } u.dialog = { type: 'advWarn' }; return this.render();
        case 'advWarnOk': sc.advOn = true; u.dialog = null; return r();
        case 'advGrant': sc.advService = true; r(); return this.toast(L('Simulation : service d\'accessibilité activé dans Android', 'Simulation: accessibility service enabled in Android'), 2800);
        case 'advRec':
          if (u.adv.rec) { u.adv.rec = false; clearTimeout(this._recT); this.render(); return this.toast(S('adv_sc_record_cancelled')); }
          u.adv.rec = true; clearTimeout(this._recT);
          this._recT = setTimeout(() => { if (u.adv.rec) { u.adv.rec = false; this.render(); } }, 15000);
          this.render(); emit('recording', { on: true }); return;
        case 'advPress': u.adv.press = v; return this.render();
        case 'advCreate': return this.advCreate();
        case 'advEdit': { const it = sc.adv[n]; u.dialog = { type: 'editAdv', index: n, action: it.action, scope: it.scope || '' }; return this.render(); }
        case 'advDel': sc.adv.splice(n, 1); return r();
        case 'replaceOk': { const d = u.dialog; sc.adv = sc.adv.filter((x) => x !== d.old); sc.adv.push(d.item); u.dialog = null; r(); return this.toast(S('adv_sc_saved')); }
        case 'pickApp': case 'pickProfile': return this.finishPick(a, v);
        case 'pickCancel': u.dialog = null; return this.render();
        // Automatisation
        case 'foldP': state.auto.p.open = !state.auto.p.open; return r();
        case 'foldC': state.auto.c.open = !state.auto.c.open; return r();
        case 'foldB': state.auto.b.open = !state.auto.b.open; return r();
        case 'foldBri': state.auto.bri.open = !state.auto.bri.open; return r();
        case 'foldBh': state.auto.bh.open = !state.auto.bh.open; return r();
        case 'foldD': state.door.open = !state.door.open; return r();
        // Activer une automatisation déplie sa carte, la couper la replie.
        case 'autoB': state.auto.b.on = !state.auto.b.on; state.auto.b.open = state.auto.b.on; state.auto.b.fired = false; return r();
        case 'autoBri': state.auto.bri.on = !state.auto.bri.on; state.auto.bri.open = state.auto.bri.on; return r();
        case 'autoBh': state.auto.bh.on = !state.auto.bh.on; state.auto.bh.open = state.auto.bh.on; return r();
        case 'briTest': autoBrightness(); return r();
        case 'batHeat': c.batHeat = v === '1'; return r();
        case 'highBeam': c.highBeam = v === '1'; return r();
        case 'stSkipShort': state.stats.skipShort = !state.stats.skipShort; return r();
        case 'stBatOk': state.stats.batteryConfirmed = true; return r();
        case 'stBatChange': u.screen = 'stats'; u.tabs.st = 0; commit(); this.highlight('st-battery'); return;
        case 'autoP': state.auto.p.on = !state.auto.p.on; state.auto.p.open = state.auto.p.on; return r();
        case 'autoDir': state.auto.p.dir = v; return r();
        case 'autoC': state.auto.c.on = !state.auto.c.on; state.auto.c.open = state.auto.c.on; return r();
        case 'ruleOn': case 'ruleAuto': case 'ruleRecircF': {
          const rule = state.auto.c[el.getAttribute('data-x')];
          const f = { ruleOn: 'on', ruleAuto: 'auto', ruleRecircF: 'recircForce' }[a];
          rule[f] = !rule[f]; return r();
        }
        case 'ruleRecirc': { const [id, val] = v.split(':'); state.auto.c[id].recirc = +val; return r(); }
        // Audio
        case 'doorOn': state.door.on = !state.door.on; state.door.open = state.door.on; return r();
        case 'doorRestore': state.door.restore = !state.door.restore; return r();
        // Overlays
        case 'pkBg': case 'pkClose': return this.closeOverlay();
        case 'hvBg': return this.closeOverlay();
        case 'hvKey': return this.hvacKey(v);
        case 'hvAir': {
          // Mêmes boutons cumulables que la page Clim ; la lunette arrière est le dégivrage arrière.
          if (u.overlay) u.overlay.left = u.overlay.total;
          if (v === 'rear') { c.clim.defR = !c.clim.defR; return r(); }
          const next = Object.assign({}, c.clim.air, { [v]: !c.clim.air[v] });
          if (!next.face && !next.feet && !next.ws) return;
          c.clim.air = next; return r();
        }
        case 'pkBri': c.brightness = n; if (u.overlay) u.overlay.left = 8; return r();
        case 'pkProfile': { const p = state.profiles.find((x) => x.id === v); this.closeOverlay(); if (p) applyProfile(p, { manual: true }); return; }
        case 'pkPower': this.closeOverlay(); return this.askPowerOff();
        case 'pkOpen': this.closeOverlay(); u.screen = state.settings.defaultScreen; return this.render();
        case 'cfYes': { const o = u.overlay; const p = state.profiles.find((x) => x.id === o.profileId); this.closeOverlay(); if (p) applyProfile(p, { via: L('température', 'temperature') }); return; }
        case 'cfNo': { const o = u.overlay; this.closeOverlay(); if (o && o.onNo) o.onNo(); return; }
        // Dialogues
        case 'dlgClose': u.dialog = null; return this.render();
        case 'fwClose': state.fwDismissed = true; u.screen = 'closed'; commit(); return;
        case 'fwContinue': state.fwDismissed = true; return r();
        case 'pickLang': state.appLang = v; u.dialog = null; return r();
        case 'updAuto': u.dialog = { type: 'update', step: 'data' }; return this.render();
        case 'updManual': u.dialog = { type: 'update', step: 'manual' }; return this.render();
        case 'updDownload': {
          u.dialog = { type: 'update', step: 'progress', pct: 0 }; this.render();
          const tick = () => { if (!u.dialog || u.dialog.step !== 'progress') return; u.dialog.pct += 12; if (u.dialog.pct >= 100) { u.dialog = { type: 'update', step: 'done' }; this.render(); return; } this.render(); setTimeout(tick, 220); };
          setTimeout(tick, 220); return;
        }
        case 'restoreYes': u.dialog = null; this.render(); return this.toast(S('profile_restore_done', 3));

        // ── Mode personnalisé, clim du profil, cycle regen, vitres, statistiques, MAJ ──
        case 'custom': { const [f, i] = v.split(':'); if (W.custom(f, +i)) r(); return; }
        case 'eCustom': { const [f, i] = v.split(':'); const key = { power: 'customPower', steer: 'customSteer', pedal: 'customPedal' }[f]; e[key] = +i; return this.render(); }
        case 'airFace': case 'airFeet': case 'airWs': {
          // Boutons cumulables ; l'air doit bien sortir quelque part : le dernier allumé ne s'éteint pas.
          const f = { airFace: 'face', airFeet: 'feet', airWs: 'ws' }[a];
          const next = Object.assign({}, c.clim.air, { [f]: !c.clim.air[f] });
          if (!next.face && !next.feet && !next.ws) return;
          c.clim.air = next; return r();
        }
        case 'hvOn': e.hvac.enabled = !e.hvac.enabled; return this.render();
        case 'hvPower': e.hvac.power = !e.hvac.power; return this.render();
        case 'hvAc': e.hvac.ac = !e.hvac.ac; return this.render();
        case 'hvAuto': e.hvac.auto = !e.hvac.auto; return this.render();
        case 'hvLoop': e.hvac.loop = v === 'none' ? null : +v; return this.render();
        case 'hvAir': {
          if (v === 'none') e.hvac.air = null;
          else { const m = (e.hvac.air || 0) ^ +v; e.hvac.air = m || null; }
          return this.render();
        }
        case 'garage': s.garage = !s.garage; return r();
        case 'updOverlay': s.updOverlay = !s.updOverlay; return r();
        case 'textSize': s.textSize = v; return r();
        case 'rcToggle': {
          const d = u.regenDraft || regenOrder().slice();
          const i = d.indexOf(v);
          if (i >= 0) d.splice(i, 1); else d.push(v);
          u.regenDraft = d; return this.render();
        }
        case 'rcClear': u.regenDraft = []; return this.render();
        case 'rcSave':
          if (!u.regenDraft || u.regenDraft.length < 2) return this.toast(S('shortcuts_cfg_regen_min'));
          sc.regenCycle = u.regenDraft.slice(); r(); return this.toast(S('shortcuts_cfg_regen_saved'));
        case 'edSave': {
          const d = u.dialog, it = sc.adv[d.index];
          const clash = sc.adv.some((x, i) => i !== d.index && x.key === it.key && x.press === it.press && (x.scope || '') === (d.scope || ''));
          if (clash) return this.toast(S('adv_sc_edit_conflict'));
          if ((d.action === 'OPEN_CUSTOM_APP' || d.action === 'APPLY_PROFILE') && d.action !== it.action) {
            u.dialog = { type: d.action === 'OPEN_CUSTOM_APP' ? 'pickApp' : 'pickProfile', then: { kind: 'advEdit', index: d.index, action: d.action, scope: d.scope } };
            return this.render();
          }
          it.action = d.action; it.scope = d.scope || null; u.dialog = null; r(); return this.toast(S('adv_sc_updated'));
        }
        case 'foldW': u.winOpen = !u.winOpen; return this.render();
        case 'winAll': allWindows(+v); hud(winAllNote(+v)); return;
        case 'winAutoOn': state.winAuto.on = !state.winAuto.on; u.winOpen = state.winAuto.on; return r();
        case 'winCalAdv': state.winCalAdv = !state.winCalAdv; return r();
        case 'waSpeedOn': case 'waTimeOn': {
          const wa = state.winAuto, f = a === 'waSpeedOn' ? 'speedOn' : 'timeOn';
          const other = f === 'speedOn' ? 'timeOn' : 'speedOn';
          if (wa[f] && !wa[other]) return this.toast(S('win_auto_need_one'));
          wa[f] = !wa[f]; return r();
        }
        case 'waBoth': state.winAuto.both = v === '1'; return r();
        case 'waBeep': state.winAuto.beep = !state.winAuto.beep; return r();
        case 'calStart': u.dialog = { type: 'cal', w: v, step: 1 }; return this.render();
        case 'calClose': moveWindow(u.dialog.w, -1); return;
        case 'calNext': u.dialog.step = 2; u.dialog.msg = null; return this.render();
        case 'calCancel': moveWindow(u.dialog.w, 0); u.dialog = null; return this.render();
        case 'calRedo': u.dialog = { type: 'cal', w: u.dialog.w, step: 1 }; return this.render();
        case 'calSave': state.winCal[u.dialog.w] = { down: u.dialog.down, up: u.dialog.up }; u.dialog = null; r(); return this.toast(S('win_cal_saved'));
        case 'stEnable': state.stats.enabled = !state.stats.enabled; return r();
        case 'stPeriod': state.stats.period = +v; return r();
        case 'stRetention': state.stats.retention = +v; return r();
        case 'stOpen': u.statsOpen = u.statsOpen === v ? null : v; return this.render();
        case 'stClear': u.dialog = { type: 'stClear' }; return this.render();
        case 'stClearOk': state.stats.trips = []; state.stats.charges = []; u.dialog = null; return r();
        case 'stFix': u.dialog = { type: 'stFix', index: n }; return this.render();
        case 'stFixOk': {
          const inp = this.app.querySelector('[data-c="stFixVal"]');
          const val = inp ? parseFloat(inp.value) : NaN;
          if (!isNaN(val)) state.stats.charges[u.dialog.index].fixedPrice = clamp(val, 0, 5);
          u.dialog = null; return r();
        }
        case 'stComplete': u.dialog = { type: 'stComplete', index: n, typeSel: state.stats.charges[n].type }; return this.render();
        case 'stType': u.dialog.typeSel = v; return this.render();
        case 'stCompleteOk': {
          const d = u.dialog, c2 = state.stats.charges[d.index];
          const toMs = (hhmm, base) => { const [h, m] = (hhmm || '').split(':').map(Number); const t = new Date(base); t.setHours(h, m, 0, 0); return t.getTime(); };
          if (d.startT) c2.start = toMs(d.startT, c2.start);
          if (d.endT) { let e2 = toMs(d.endT, c2.start); if (e2 <= c2.start) e2 += 86400000; c2.end = e2; }  // une charge de nuit enjambe minuit
          c2.type = d.typeSel || c2.type; c2.timesKnown = true;
          c2.powerComputed = c2.kwh / ((c2.end - c2.start) / 3600000);
          u.dialog = null; return r();
        }
        case 'upBg': return this.closeOverlay();
        case 'upInstall': this.closeOverlay(); u.screen = 'settings'; u.dialog = { type: 'update', step: 'info' }; return this.render();
        case 'upSkip': this.closeOverlay(); return;
        case 'upDisable': s.updOverlay = false; this.closeOverlay(); r(); return this.toast(S('update_overlay_disabled_toast'), 3200);
        default: return;
      }
    }

    change(id, t) {
      const c = car(), u = this.ui, sc = state.sc;
      const num = (min, max, def) => { const x = parseInt(t.value, 10); return isNaN(x) ? def : clamp(x, min, max); };
      switch (id) {
        case 'climTemp': c.clim.temp = +t.value; return commit();
        case 'climFan': c.clim.fan = +t.value; return commit();
        case 'pkBri': c.brightness = +t.value; if (u.overlay) u.overlay.left = 8; return commit();
        case 'doorLevel': state.door.level = +t.value; return commit();
        case 'eName': if (u.edit) u.edit.name = t.value; return;
        case 'eBt': if (u.edit) u.edit.bt = t.value || null; return this.render();
        case 'gateMax': state.settings.gateMax = num(0, 250, 0); return commit();
        case 'scSlot': {
          const slot = t.getAttribute('data-slot'), val = t.value;
          if (val === 'OPEN_CUSTOM_APP' || val === 'APPLY_PROFILE') {
            if (val === 'APPLY_PROFILE' && !state.profiles.length) { this.toast(S('shortcuts_no_profiles')); return this.render(); }
            u.dialog = { type: val === 'OPEN_CUSTOM_APP' ? 'pickApp' : 'pickProfile', then: { kind: 'slot', slot, action: val } };
            return this.render();
          }
          sc.map[slot] = val; delete sc.extra[slot]; delete sc.toggles[val]; return commit();
        }
        case 'advAction': u.adv.action = t.value; this.render(); return;   // révèle la page de réglage de la fonction
        case 'advScope': u.adv.scope = t.value; return;
        case 'edAction': if (u.dialog) u.dialog.action = t.value; return;
        case 'edScope': if (u.dialog) u.dialog.scope = t.value; return;
        case 'hvTemp': if (u.edit) u.edit.hvac.temp = +t.value; return this.render();
        case 'hvFan': if (u.edit) u.edit.hvac.fan = +t.value; return this.render();
        case 'waSpeed': state.winAuto.speed = +t.value; return commit();
        case 'waTime': state.winAuto.time = +t.value; return commit();
        case 'waDelay': state.winAuto.delay = +t.value; return commit();
        case 'waBeepVol': state.winAuto.beepVol = +t.value; return commit();
        case 'winCourse': state.winCourse = Math.round(+t.value * 1000); return commit();
        case 'stCurrency': state.stats.currency = (t.value || '').slice(0, 3) || '€'; return commit();
        case 'stPriceAc': case 'stPriceDc': {
          const x = parseFloat(t.value);
          if (!isNaN(x)) state.stats[id === 'stPriceAc' ? 'priceAc' : 'priceDc'] = clamp(x, 0, 100000);
          return commit();
        }
        case 'stBattery': setBattery(+t.value); return commit();
        case 'stMinTrip': { const x = parseFloat(String(t.value).replace(',', '.')); if (!isNaN(x)) state.stats.minTrip = clamp(x, 0.1, 50); return commit(); }
        case 'batThr': state.auto.b.thr = +t.value; return commit();
        case 'batProfile': state.auto.b.profileId = t.value; return commit();
        case 'batExec': state.auto.b.autoExec = t.checked; return commit();
        case 'briLights': state.auto.bri.lights = t.checked; return commit();
        case 'briForecast': state.auto.bri.forecast = t.checked; return commit();
        case 'briFollow': state.auto.bri.follow = t.checked; return commit();
        case 'briDay': state.auto.bri.day = +t.value; return commit();
        case 'briNight': state.auto.bri.night = +t.value; return commit();
        case 'bhMin': state.auto.bh.minutes = +t.value; return commit();
        case 'acTrigger': state.auto.c.trigger = t.value; return commit();
        case 'stStart': if (u.dialog) u.dialog.startT = t.value; return;
        case 'stEnd': if (u.dialog) u.dialog.endT = t.value; return;
        case 'autoThr': state.auto.p.thr = num(0, 60, 25); return commit();
        case 'autoProfile': state.auto.p.profileId = t.value; return commit();
        case 'autoExec': state.auto.p.autoExec = t.checked; return commit();
        case 'ruleThr': case 'ruleTarget': case 'ruleFan': {
          const rule = state.auto.c[t.getAttribute('data-rule')];
          if (id === 'ruleThr') rule.thr = num(-20, 60, rule.thr);
          if (id === 'ruleTarget') rule.target = num(15, 33, rule.target);
          if (id === 'ruleFan') rule.fan = num(1, 10, rule.fan);
          return commit();
        }
        case 'ruleDefF': case 'ruleDefR': state.auto.c[t.getAttribute('data-rule')][id === 'ruleDefF' ? 'defF' : 'defR'] = t.checked; return commit();
        case 'doorL': state.door.L = t.checked; return commit();
        case 'doorR': state.door.R = t.checked; return commit();
        default: return;
      }
    }

    finishPick(a, v) {
      const u = this.ui, sc = state.sc, then = u.dialog && u.dialog.then;
      u.dialog = null;
      const extra = a === 'pickApp' ? { app: v } : { profileId: v };
      if (!then) return this.render();
      if (then.kind === 'slot') { sc.map[then.slot] = then.action; sc.extra[then.slot] = extra; return commit(); }
      if (then.kind === 'advNew') return this.saveAdv(Object.assign({ key: then.key, press: then.press, action: then.action, scope: then.scope || null }, extra));
      if (then.kind === 'advEdit') {
        const it = sc.adv[then.index]; it.action = then.action; delete it.app; delete it.profileId; Object.assign(it, extra);
        if (then.scope !== undefined) it.scope = then.scope || null;
        commit(); return this.toast(S('adv_sc_updated'));
      }
    }
    advCreate() {
      const a = this.ui.adv;
      if (a.key == null) return this.toast(S('adv_sc_need_key'));
      if (!a.action || a.action === 'NONE') return this.toast(S('adv_sc_need_action'));
      const scope = a.scope || null;
      if (a.action === 'OPEN_CUSTOM_APP' || a.action === 'APPLY_PROFILE') {
        this.ui.dialog = { type: a.action === 'OPEN_CUSTOM_APP' ? 'pickApp' : 'pickProfile', then: { kind: 'advNew', key: a.key, press: a.press, action: a.action, scope } };
        return this.render();
      }
      this.saveAdv({ key: a.key, press: a.press, action: a.action, scope });
    }
    saveAdv(item) {
      // Un couple (touche, type d'appui) est unique par profil.
      const old = state.sc.adv.find((x) => x.key === item.key && x.press === item.press && (x.scope || null) === (item.scope || null));
      if (old) { this.ui.dialog = { type: 'replace', item, old }; return this.render(); }
      state.sc.adv.push(item); commit(); this.toast(S('adv_sc_saved'));
    }
    captureKey(code) {
      this.ui.adv.rec = false; this.ui.adv.key = code; clearTimeout(this._recT);
      emit('recording', { on: false }); this.render();
    }
    startEditById(id) { const p = state.profiles.find((x) => x.id === id) || state.profiles[0]; this.startEdit(p); }

    /** Démonstrations déclenchées depuis le texte de la page. */
    demo(name) {
      const u = this.ui;
      switch (name) {
        case 'picker': return this.openPicker();
        case 'hvacPopup':
          if (caps().clim && !this.assigned('HVAC_POPUP')) { state.sc.map.btn2_single = 'HVAC_POPUP'; delete state.sc.extra.btn2_single; }
          return this.openHvacPopup();
        case 'confirm': { const p = state.profiles.find((x) => x.id === state.auto.p.profileId) || state.profiles[0]; if (p) this.showConfirm(p, () => {}); return; }
        case 'fwUnknown': state.fwDismissed = false; if (state.fw !== 'UNKNOWN') { u.dialog = { type: 'fwUnknown' }; return this.render(); } return commit();
        case 'lang': case 'restore': case 'about': case 'launcherWarn': case 'advWarn': case 'apiConfirm':
          u.dialog = { type: name }; return this.render();
        case 'update': u.dialog = { type: 'update', step: 'info' }; return this.render();
        case 'diag': state.diagUnlocked = true; u.dialog = { type: 'diag' }; return commit();
        case 'powerOff': return this.askPowerOff();
        case 'newProfile': this.startEdit(null); u.screen = 'profileEdit'; return this.render();
        case 'updOverlay': return this.showUpdateOverlay();
        case 'btConflict': {
          state.bt.connected = state.bt.devices.map((d) => d.mac);
          const ps = state.profiles.filter((p) => p.bt && state.bt.connected.includes(p.bt));
          if (ps.length > 1) this.openPicker(ps.map((p) => p.id), () => applyProfile(ps[0], { via: 'Bluetooth' }));
          else this.toast(L('Associez au moins deux profils à deux téléphones pour voir le conflit.', 'Tie at least two profiles to two phones to see the conflict.'), 3200);
          return commit();
        }
        case 'editHvac': { const p = state.profiles.find((x) => x.hvac && x.hvac.enabled) || state.profiles[0]; this.startEdit(p); u.tabs.edit = 2; u.screen = 'profileEdit'; return this.render(); }
        case 'editCustom': {
          const p = state.profiles[0]; this.startEdit(p); u.edit.driveMode = 'CUSTOM';
          u.edit.customPower = u.edit.customPower == null ? 2 : u.edit.customPower; u.tabs.edit = 0; u.screen = 'profileEdit'; return this.render();
        }
        case 'customMode': if (W.drive('CUSTOM')) { u.screen = 'dashboard'; u.tabs.dash = 0; commit(); } return;
        case 'windows': u.screen = 'automation'; u.winOpen = true; return this.render();
        case 'doorVolume': state.door.open = true; u.screen = 'automation'; return commit();
        case 'autoBattery': state.auto.b.open = true; u.screen = 'automation'; return commit();
        case 'autoBri': state.auto.bri.open = true; u.screen = 'automation'; return commit();
        case 'autoBatHeat': state.auto.bh.open = true; u.screen = 'automation'; return commit();
        case 'acOnce': state.auto.c.open = true; u.screen = 'automation'; return commit();
        case 'statsBattery': state.stats.enabled = true; u.screen = 'stats'; u.tabs.st = 0; return commit();
        case 'statsCharges': state.stats.enabled = true; u.screen = 'stats'; u.tabs.st = 2; return commit();
        case 'regenCycle':
          // Révèle la page « Cycle regen » : la fonction doit être attribuée quelque part.
          if (!this.assigned('REGEN_CYCLE')) { state.sc.map.btn2_single = 'REGEN_CYCLE'; delete state.sc.extra.btn2_single; }
          u.screen = 'shortcuts'; u.tabs.sc = 'regen'; return commit();
        // L'assistant vit dans l'option avancée « Calibrage par vitre » : on l'allume d'abord.
        case 'calibrate': state.winCalAdv = true; u.screen = 'automation'; u.winOpen = true; u.dialog = { type: 'cal', w: 'FR', step: 1 }; return commit();
        case 'statsOn': state.stats.enabled = true; u.screen = 'stats'; u.tabs.st = 0; return commit();
        case 'garageOn': state.settings.garage = true; u.screen = 'settings'; u.tabs.set = 2; return commit();
        default: return;
      }
    }
  }

  // ── Petits composants HTML ────────────────────────────────────────────────
  function sw(on, a, disabled, x) {
    return '<button type="button" class="sw' + (on ? ' on' : '') + '" role="switch" aria-checked="' + (on ? 'true' : 'false') + '" data-a="' + a + '"' + (x ? ' data-x="' + x + '"' : '') + (disabled ? ' disabled' : '') + '></button>';
  }
  function range(id, min, max, val, disabled, cls, step) {
    const p = max > min ? ((val - min) / (max - min)) * 100 : 0;
    return '<input type="range" class="a-slider ' + (cls || '') + '" min="' + min + '" max="' + max + '" step="' + (step || 1) + '" value="' + val + '" data-c="' + id + '" style="--p:' + p + '%"' + (disabled ? ' disabled' : '') + '>';
  }
  /** Libellé affiché à côté d'un curseur pendant le glissement. */
  function outText(id, v) {
    switch (id) {
      case 'climTemp': return v + '°';
      case 'pkBri': return v + '%';
      case 'hvTemp': return S('profile_hvac_temp_value', v);
      case 'waSpeed': return v + ' km/h';
      case 'waTime': return v + ' min';
      case 'waDelay': return v + ' s';
      case 'waBeepVol': case 'batThr': case 'briDay': case 'briNight': return v + ' %';
      case 'bhMin': return S('batheat_auto_minutes_value', v);
      case 'winCourse': return S('win_cal_seconds', fmtS(+v * 1000));
      default: return String(v);
    }
  }
  const fmtS = (ms) => (ms / 1000).toFixed(1).replace('.', appLang() === 'en' ? '.' : ',');
  // Icônes de l'app (res/drawable/ic_air_*.xml) : Google Material Symbols car_fan_mid_left,
  // car_fan_low_left, windshield_defrost_front et windshield_defrost_rear (Apache License 2.0).
  const AIR_PATH = {
    face: 'M320,240Q287,240 263.5,216.5Q240,193 240,160Q240,127 263.5,103.5Q287,80 320,80Q353,80 376.5,103.5Q400,127 400,160Q400,193 376.5,216.5Q353,240 320,240ZM731,880L615,680L366,680Q337,680 314.5,662Q292,644 287,616L244,401Q235,353 265.5,316.5Q296,280 344,280Q378,280 405,302Q432,324 442,359L500,560L592,560Q614,560 632.5,570.5Q651,581 662,600L800,840L731,880ZM680,440L520,280L680,120L736,176L673,240L840,240L840,320L673,320L737,384L680,440ZM223,880Q196,880 175,863.5Q154,847 146,821L50,448Q45,431 43,415.5Q41,400 41,386Q41,359 47.5,332Q54,305 67,279Q76,261 92.5,250.5Q109,240 129,240Q152,240 169,257Q186,274 186,297Q186,308 182,318Q178,328 170,336Q151,355 147,381.5Q143,408 155,434L175,476Q208,547 224.5,613.5Q241,680 241,743L241,787Q261,773 280,766.5Q299,760 317,760L521,760Q555,760 578.5,783.5Q602,807 602,840L602,880L223,880Z',
    feet: 'M320,240Q287,240 263.5,216.5Q240,193 240,160Q240,127 263.5,103.5Q287,80 320,80Q353,80 376.5,103.5Q400,127 400,160Q400,193 376.5,216.5Q353,240 320,240ZM731,880L615,680L366,680Q337,680 314.5,662Q292,644 287,616L244,401Q235,353 265.5,316.5Q296,280 344,280Q378,280 405,302Q432,324 442,359L500,560L592,560Q614,560 632.5,570.5Q651,581 662,600L800,840L731,880ZM680,440L520,280L576,224L640,287L640,120L720,120L720,287L784,223L840,280L680,440ZM223,880Q196,880 175,863.5Q154,847 146,821L50,448Q45,431 43,415.5Q41,400 41,386Q41,359 47.5,332Q54,305 67,279Q76,261 92.5,250.5Q109,240 129,240Q152,240 169,257Q186,274 186,297Q186,308 182,318Q178,328 170,336Q151,355 147,381.5Q143,408 155,434L175,476Q208,547 224.5,613.5Q241,680 241,743L241,787Q261,773 280,766.5Q299,760 317,760L521,760Q555,760 578.5,783.5Q602,807 602,840L602,880L223,880Z',
    ws: 'M480,160Q580,160 688.5,176Q797,192 926,227L839,688L761,673L834,286Q730,262 644,251Q558,240 480,240Q402,240 316,251Q230,262 126,286L199,673L121,688L34,227Q163,192 271.5,176Q380,160 480,160ZM681,822L615,778L628,757Q634,748 637,738.5Q640,729 640,718Q640,704 635,691Q630,678 620,668Q599,647 587.5,619.5Q576,592 576,562Q576,539 582.5,518Q589,497 601,478L614,458L681,502L667,523Q661,532 658.5,541.5Q656,551 656,562Q656,576 661,589Q666,602 676,612Q697,633 708.5,660.5Q720,688 720,718Q720,741 713.5,762Q707,783 695,802L681,822ZM513,822L447,778L460,757Q466,748 469,738.5Q472,729 472,718Q472,704 467,691Q462,678 452,668Q431,647 419.5,619.5Q408,592 408,562Q408,539 414.5,518Q421,497 433,478L446,458L513,502L499,523Q493,532 490.5,541.5Q488,551 488,562Q488,576 493,589Q498,602 508,612Q529,633 540.5,660.5Q552,688 552,718Q552,741 545.5,762Q539,783 527,802L513,822ZM346,822L279,778L293,757Q299,748 302,738.5Q305,729 305,718Q305,704 299.5,691Q294,678 284,668Q263,647 251.5,619.5Q240,592 240,562Q240,539 246,518Q252,497 265,478L279,458L346,502L332,523Q326,531 323,541Q320,551 320,562Q320,576 325,589Q330,602 340,612Q361,633 372.5,660.5Q384,688 384,718Q384,741 377.5,762Q371,783 359,802L346,822Z',
    rear: 'M760,720L760,640L800,640Q800,640 800,640Q800,640 800,640L800,240Q800,240 800,240Q800,240 800,240L160,240Q160,240 160,240Q160,240 160,240L160,640Q160,640 160,640Q160,640 160,640L200,640L200,720L160,720Q127,720 103.5,696.5Q80,673 80,640L80,240Q80,207 103.5,183.5Q127,160 160,160L800,160Q833,160 856.5,183.5Q880,207 880,240L880,640Q880,673 856.5,696.5Q833,720 800,720L760,720ZM681,822L615,778L628,757Q634,748 637,738.5Q640,729 640,718Q640,704 635,691Q630,678 620,668Q599,647 587.5,619.5Q576,592 576,562Q576,539 582.5,518Q589,497 601,478L614,458L681,502L667,523Q661,532 658.5,541.5Q656,551 656,562Q656,576 661,589Q666,602 676,612Q697,633 708.5,660.5Q720,688 720,718Q720,741 713.5,762Q707,783 695,802L681,822ZM513,822L447,778L460,757Q466,748 469,738.5Q472,729 472,718Q472,704 467,691Q462,678 452,668Q431,647 419.5,619.5Q408,592 408,562Q408,539 414.5,518Q421,497 433,478L446,458L513,502L499,523Q493,532 490.5,541.5Q488,551 488,562Q488,576 493,589Q498,602 508,612Q529,633 540.5,660.5Q552,688 552,718Q552,741 545.5,762Q539,783 527,802L513,822ZM346,822L279,778L293,757Q299,748 302,738.5Q305,729 305,718Q305,704 299.5,691Q294,678 284,668Q263,647 251.5,619.5Q240,592 240,562Q240,539 246,518Q252,497 265,478L279,458L346,502L332,523Q326,531 323,541Q320,551 320,562Q320,576 325,589Q330,602 340,612Q361,633 372.5,660.5Q384,688 384,718Q384,741 377.5,762Q371,783 359,802L346,822Z',
  };
  const airSvg = (k) => '<svg class="air-ico" viewBox="0 0 960 960" fill="currentColor" aria-hidden="true"><path d="' + AIR_PATH[k] + '"/></svg>';
  const AIR_ICON = { face: airSvg('face'), feet: airSvg('feet'), ws: airSvg('ws'), rear: airSvg('rear') };
  // Croix du pop-up HVAC (res/drawable/ic_hvac_temp.xml et ic_hvac_fan.xml) : d'après Google
  // Material Icons « thermostat » et « toys » (Apache License 2.0).
  const hvSvg = (d) => '<svg class="hv-ico" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="' + d + '"/></svg>';
  const HV_ICON = {
    temp: hvSvg('M15,13V5c0,-1.66 -1.34,-3 -3,-3S9,3.34 9,5v8c-1.21,0.91 -2,2.37 -2,4 0,2.76 2.24,5 5,5s5,-2.24 5,-5c0,-1.63 -0.79,-3.09 -2,-4zM11,5c0,-0.55 0.45,-1 1,-1s1,0.45 1,1h-1v1h1v2h-1v1h1v2h-2L11,5z'),
    fan: hvSvg('M12,12c0,-3 2.5,-5.5 5.5,-5.5S23,9 23,12H12zM12,12c0,3 -2.5,5.5 -5.5,5.5S1,15 1,12h11zM12,12c-3,0 -5.5,-2.5 -5.5,-5.5S9,1 12,1v11zM12,12c3,0 5.5,2.5 5.5,5.5S15,23 12,23V12z')
  };
  // Tout fermer / Tout ouvrir (res/drawable/ic_window_all_*.xml) : Material Symbols keyboard_double_arrow_up/down.
  const WIN_PATH = {
    allUp: 'M296,736L240,680L480,440L720,680L664,736L480,553L296,736ZM296,496L240,440L480,200L720,440L664,496L480,313L296,496Z',
    allDown: 'M480,760L240,520L296,464L480,647L664,464L720,520L480,760ZM480,520L240,280L296,224L480,407L664,224L720,280L480,520Z',
  };
  const winSvg = (k) => '<svg class="win-ico" viewBox="0 0 960 960" fill="currentColor" aria-hidden="true"><path d="' + WIN_PATH[k] + '"/></svg>';
  function paintRange(t) {
    const min = +t.min, max = +t.max, v = +t.value;
    t.style.setProperty('--p', (max > min ? ((v - min) / (max - min)) * 100 : 0) + '%');
  }
  function keyName(code) {
    const n = KEY_NAMES[code];
    if (n) return appLang() === 'fr' ? n[0] : n[1];
    return S('adv_sc_key_unknown', code);
  }
  function qrBox() {
    return '<svg width="110" height="110" viewBox="0 0 110 110" aria-hidden="true" style="border-radius:8px;background:#fff"><rect x="8" y="8" width="28" height="28" fill="none" stroke="#111" stroke-width="6"/><rect x="74" y="8" width="28" height="28" fill="none" stroke="#111" stroke-width="6"/><rect x="8" y="74" width="28" height="28" fill="none" stroke="#111" stroke-width="6"/><text x="55" y="62" text-anchor="middle" font-size="13" font-family="Roboto,sans-serif" fill="#111">QR</text></svg>';
  }
  function diagSample() {
    const g = gen();
    return [
      '── ' + L('Rapport de diagnostic (exemple)', 'Diagnostic report (example)') + ' ──',
      'app=2.6.8  firmware=' + g + (state.forced ? ' (forcé)' : ''),
      'MG4_GATE   sécurité=' + (state.settings.gate ? 'ON max=' + state.settings.gateMax + ' km/h' : 'OFF') + '  vitesse=' + state.car.speed + ' km/h',
      'MG4_API    API externe=' + (state.settings.api ? 'activée' : 'désactivée'),
      'MG4_VOL    volume média=' + state.car.volume + '/' + state.car.volMax,
      'MG4_TEMP   temp. extérieure=' + state.car.outside + ' °C',
      'MG4_DOOR   DIAG porte avant G=' + (state.car.doors.L ? 'ouverte' : 'fermée') + ' D=' + (state.car.doors.R ? 'ouverte' : 'fermée'),
      'MG4_SAFE   ESC=' + onOff(state.car.esc) + ' DMS=' + onOff(state.car.dms) + ' sensibilité=' + state.car.dmsSen,
      'MG4_THEME  source jour/nuit : launcher (' + appTheme() + ')',
      '…'
    ].join('\n');
  }

  // ══════════════════════════════════════════════════════════════════════════
  //  Véhicule virtuel (dock) : capteurs, contact, volant
  // ══════════════════════════════════════════════════════════════════════════
  function setDoor(side, open) {
    const c = car(), d = state.door;
    c.doors[side] = open;
    if (!caps().doorSensor || !d.on || garage()) return commit();
    if (open && d[side]) {
      if (c.prevVolume == null) c.prevVolume = c.volume;
      c.volume = Math.min(c.volume, d.level);
      hud(L('Porte ouverte : volume média abaissé à ', 'Door open: media volume lowered to ') + c.volume);
    } else if (!open && !c.doors.L && !c.doors.R && c.prevVolume != null) {
      if (d.restore) { c.volume = c.prevVolume; hud(L('Portes fermées : volume restauré à ', 'Doors closed: volume restored to ') + c.volume); }
      c.prevVolume = null;
    }
    commit();
  }
  /**
   * Connexion / déconnexion d'un téléphone. Comme MG4ControlService (ACTION_ACL_CONNECTED), la
   * connexion est seulement mémorisée : le profil associé ne s'applique qu'au démarrage de
   * l'infodivertissement ou au passage en READY, si le téléphone est alors connecté.
   */
  function toggleBt(mac) {
    const list = state.bt.connected;
    const i = list.indexOf(mac);
    if (i >= 0) { list.splice(i, 1); return commit(); }
    list.push(mac);
    const p = state.profiles.find((x) => x.bt === mac);
    if (p) hud(L('Téléphone connecté : « ', 'Phone connected: “') + p.name + L(' » s\'appliquera au prochain passage en READY', '” will apply at the next switch to READY'));
    commit();
  }
  function setFirmware(fw) {
    state.fw = fw; state.forced = null; state.fwDismissed = false;
    if (fw === 'SWI132' && car().elkLast === ELK.EMERGENCY) { car().elkLast = ELK.ALERT; if (car().elkMode === ELK.EMERGENCY) car().elkMode = ELK.ALERT; }
    try { localStorage.setItem(LS_KEY, fw); } catch (e) { /* ignoré */ }
    instances.forEach((i) => { if (i.ui.screen === 'closed') i.ui.screen = 'dashboard'; if (i.ui.screen === 'audio' && !caps().door) i.ui.screen = 'dashboard'; });
    commit();
  }
  function reset() {
    const fw = state.fw;
    state = initialState(); state.fw = fw;
    instances.forEach((i) => { i.ui.overlay = null; i.ui.dialog = null; i.ui.edit = null; i.ui.regenDraft = null; if (i.ui.screen === 'profileEdit') i.startEdit(state.profiles[0]); });
    commit();
  }

  const WHEEL_KEYS = [
    { code: 17, label: '★', cls: 'star-l', title: ['★ gauche (17)', '★ left (17)'] },
    { code: 297, label: '▲', cls: 'up', title: ['Joystick haut (297)', 'Joystick up (297)'] },
    { code: 299, label: '◀', cls: 'left', title: ['Joystick gauche (299)', 'Joystick left (299)'] },
    { code: 301, label: '●', cls: 'center', title: ['Joystick centre (301)', 'Joystick centre (301)'] },
    { code: 300, label: '▶', cls: 'right', title: ['Joystick droite (300)', 'Joystick right (300)'] },
    { code: 298, label: '▼', cls: 'down', title: ['Joystick bas (298)', 'Joystick down (298)'] },
    { code: 286, label: '★', cls: 'star-r', title: ['★ droite (286)', '★ right (286)'] }
  ];
  /** Boutons de volant réutilisables (dock, labo d'appui). */
  function wheelHtml(size) {
    return '<div class="wheel ' + (size || '') + '">' + WHEEL_KEYS.map((k) =>
      '<button type="button" class="wk wk-' + k.cls + '" data-key="' + k.code + '" aria-label="' + esc(L(k.title[0], k.title[1])) + '" title="' + esc(L(k.title[0], k.title[1])) + '">' + k.label + '</button>').join('') + '</div>';
  }
  function bindWheel(root) {
    const down = new Set();
    root.addEventListener('pointerdown', (e) => {
      const b = e.target.closest('[data-key]'); if (!b) return;
      e.preventDefault();
      const code = +b.getAttribute('data-key');
      if (down.has(code)) return;
      down.add(code); b.classList.add('pressed');
      try { b.setPointerCapture(e.pointerId); } catch (err) { /* ignoré */ }
      keyDown(code);
    });
    const up = (e) => {
      const b = e.target.closest('[data-key]'); if (!b) return;
      const code = +b.getAttribute('data-key');
      if (!down.has(code)) return;
      down.delete(code); b.classList.remove('pressed');
      keyUp(code);
    };
    root.addEventListener('pointerup', up);
    root.addEventListener('pointercancel', up);
    root.addEventListener('keydown', (e) => {
      const b = e.target.closest('[data-key]'); if (!b || (e.key !== 'Enter' && e.key !== ' ') || e.repeat) return;
      e.preventDefault(); const code = +b.getAttribute('data-key');
      if (down.has(code)) return; down.add(code); b.classList.add('pressed'); keyDown(code);
    });
    root.addEventListener('keyup', (e) => {
      const b = e.target.closest('[data-key]'); if (!b || (e.key !== 'Enter' && e.key !== ' ')) return;
      const code = +b.getAttribute('data-key'); if (!down.has(code)) return; down.delete(code); b.classList.remove('pressed'); keyUp(code);
    });
  }
  function describeKeyEvent(t, d) {
    const nm = keyName(d.code);
    const pl = { single: L('appui court', 'short press'), long: L('appui long', 'long press'), double: L('double appui', 'double press') };
    if (t === 'capture') return L('Touche enregistrée : ', 'Key recorded: ') + nm + ' (' + d.code + ')';
    if (t === 'nav' && d.popup === 'hvac') return nm + L(' → réglage direct dans le pop-up HVAC (joystick)', ' → direct adjustment in the HVAC pop-up (joystick)');
    if (t === 'nav') return nm + L(' → navigation dans le popup de profils (joystick)', ' → navigating the profile popup (joystick)');
    if (t === 'fire') {
      if (d.path === 'launcher') return nm + ', ' + pl[d.press] + (garage() ? L(' → Mode Garage : touche rendue au launcher', ' → Garage mode: key returned to the launcher') : L(' → transmise au launcher d\'origine (non interceptée)', ' → passed to the stock launcher (not intercepted)'));
      if (d.path === 'replay') return nm + ', ' + pl[d.press] + L(' sans action MG4Control → rendu au système (fonction d\'origine)', ' with no MG4Control action → returned to the system (original function)');
      const src = (d.path === 'adv' ? L('raccourci avancé', 'advanced shortcut') : L('raccourci classique', 'classic shortcut')) +
        (d.path === 'adv' ? ' · ' + (d.scope ? L('profil « ', 'profile “') + ((state.profiles.find((p) => p.id === d.scope) || {}).name || '?') + L(' »', '”') : S('adv_sc_all_profiles')) : '');
      const act = d.action && d.action !== 'NONE' ? S(ACTION_KEY[d.action]) : L('aucune action (touche réclamée : rien ne se passe)', 'no action (claimed key: nothing happens)');
      return nm + ', ' + pl[d.press] + ' → ' + act + ' · ' + src;
    }
    return '';
  }

  function mountDock(el) {
    el.innerHTML =
      '<button class="dock-pill" type="button" aria-expanded="false"><span class="dock-car" aria-hidden="true">🚗</span><span class="dock-sum"></span><span class="dock-chev" aria-hidden="true">▴</span></button>' +
      '<div class="dock-panel" hidden>' +
      '<div class="dock-head"><strong data-l10n="dock-title"></strong><button type="button" class="dock-x" aria-label="' + L('Fermer', 'Close') + '">✕</button></div>' +
      '<div class="dock-grid">' +
      '<label class="dock-f"><span data-l10n="dock-fw"></span><select data-d="fw">' + FIRMWARES.map((f) => '<option value="' + f + '">' + f + '</option>').join('') + '</select></label>' +
      '<label class="dock-f"><span><span data-l10n="dock-speed"></span> <b data-o="speed"></b></span><input type="range" min="0" max="130" step="5" data-d="speed"></label>' +
      '<label class="dock-f"><span><span data-l10n="dock-temp"></span> <b data-o="temp"></b></span><input type="range" min="-15" max="45" step="1" data-d="temp"></label>' +
      '<label class="dock-f"><span><span data-l10n="dock-soc"></span> <b data-o="soc"></b></span><input type="range" min="5" max="100" step="1" data-d="soc"></label>' +
      '<div class="dock-f"><span data-l10n="dock-lights"></span><div class="dock-row"><button type="button" class="dock-btn" data-d="lights"></button></div></div>' +
      '<div class="dock-f"><span data-l10n="dock-bt"></span><div class="dock-row" data-o="bt"></div></div>' +
      '<div class="dock-f"><span data-l10n="dock-doors"></span><div class="dock-row"><button type="button" class="dock-btn" data-door="L"></button><button type="button" class="dock-btn" data-door="R"></button></div></div>' +
      '<div class="dock-f"><span data-l10n="dock-media"></span><div class="dock-row dock-media"><b data-o="vol"></b></div></div>' +
      '<div class="dock-f"><span data-l10n="dock-active"></span><div class="dock-row dock-media"><b data-o="active"></b></div></div>' +
      '</div>' +
      '<div class="dock-wheel"><span class="dock-lbl" data-l10n="dock-wheel"></span>' + wheelHtml('sm') + '</div>' +
      '<div class="dock-log" aria-live="polite"></div>' +
      '<div class="dock-actions"><button type="button" class="dock-btn primary" data-d="ignition"></button><button type="button" class="dock-btn" data-d="leave"></button><button type="button" class="dock-btn" data-d="reset"></button></div>' +
      '</div>';
    const pill = el.querySelector('.dock-pill'), panel = el.querySelector('.dock-panel');
    const toggle = (open) => { panel.hidden = !open; pill.setAttribute('aria-expanded', open ? 'true' : 'false'); el.classList.toggle('open', open); };
    pill.addEventListener('click', () => toggle(panel.hidden));
    el.querySelector('.dock-x').addEventListener('click', () => toggle(false));
    const q = (s) => el.querySelector(s);
    q('[data-d=fw]').addEventListener('change', (e) => setFirmware(e.target.value));
    q('[data-d=speed]').addEventListener('input', (e) => { const c = car(); c.speed = +e.target.value; c.maxSpeed = Math.max(c.maxSpeed || 0, c.speed); commit(); });
    q('[data-d=temp]').addEventListener('input', (e) => { car().outside = +e.target.value; commit(); });
    // Batterie : franchir le seuil en roulant déclenche le profil, une fois par épisode.
    q('[data-d=soc]').addEventListener('input', (e) => { car().soc = +e.target.value; batteryAutomation(null); commit(); });
    q('[data-d=lights]').addEventListener('click', () => {
      const c = car(), b = state.auto.bri;
      c.lights = !c.lights;
      if (b.on && b.lights && b.follow && c.ready) autoBrightness();
      commit();
    });
    q('[data-o=bt]').addEventListener('click', (e) => { const b = e.target.closest('[data-mac]'); if (b) toggleBt(b.getAttribute('data-mac')); });
    el.querySelectorAll('[data-door]').forEach((b) => b.addEventListener('click', () => { const s = b.getAttribute('data-door'); setDoor(s, !car().doors[s]); }));
    q('[data-d=ignition]').addEventListener('click', () => {
      const tr = ignition();
      if (tr.length) setLog('⏻ ' + L('Démarrage : ', 'Start-up: ') + tr.join(' → '));
    });
    q('[data-d=leave]').addEventListener('click', () => {
      const tr = leaveCar();
      setLog('🚪 ' + (tr.length ? tr.join(' · ') : L('La voiture n\'est pas en READY.', 'The car is not in READY.')));
    });
    q('[data-d=reset]').addEventListener('click', () => { reset(); setLog(L('Véhicule virtuel réinitialisé.', 'Virtual vehicle reset.')); });
    bindWheel(q('.dock-wheel'));
    const log = q('.dock-log');
    function setLog(t) { log.textContent = t; log.classList.remove('flash'); void log.offsetWidth; log.classList.add('flash'); }
    keyListeners.add((t, d) => { const s = describeKeyEvent(t, d); if (s) setLog(s); });
    function paint() {
      const c = car(), k = caps();
      const L10N = {
        'dock-title': L('Véhicule virtuel', 'Virtual vehicle'), 'dock-fw': 'Firmware', 'dock-speed': L('Vitesse', 'Speed'),
        'dock-temp': L('Temp. extérieure', 'Outside temp.'), 'dock-bt': L('Bluetooth connecté', 'Bluetooth connected'),
        'dock-doors': L('Portes avant', 'Front doors'), 'dock-media': L('Volume média', 'Media volume'), 'dock-wheel': L('Volant', 'Steering wheel'),
        'dock-active': L('Profil en cours (dernier appliqué)', 'Current profile (last applied)'),
        'dock-soc': L('Batterie', 'Battery'), 'dock-lights': L('Feux', 'Lights')
      };
      el.querySelectorAll('[data-l10n]').forEach((n) => { n.textContent = L10N[n.getAttribute('data-l10n')] || ''; });
      q('.dock-sum').textContent = L('Véhicule virtuel', 'Virtual vehicle') + ' · ' + (state.fw === 'UNKNOWN' && state.forced ? 'UNKNOWN→' + state.forced : state.fw) + ' · ' + c.speed + ' km/h · ' + c.outside + ' °C';
      q('[data-d=fw]').value = state.fw;
      q('[data-d=speed]').value = c.speed; q('[data-o=speed]').textContent = c.speed + ' km/h';
      q('[data-d=temp]').value = c.outside; q('[data-o=temp]').textContent = c.outside + ' °C';
      q('[data-d=soc]').value = c.soc; q('[data-o=soc]').textContent = c.soc + ' %';
      q('[data-d=lights]').textContent = c.lights ? L('Allumés', 'On') : L('Éteints', 'Off'); q('[data-d=lights]').classList.toggle('on', c.lights);
      q('[data-o=bt]').innerHTML = state.bt.devices.map((d) => '<button type="button" class="dock-btn' + (state.bt.connected.includes(d.mac) ? ' on bt' : '') + '" data-mac="' + d.mac + '">' + (state.bt.connected.includes(d.mac) ? '● ' : '○ ') + esc(d.name) + '</button>').join('');
      const ap = activeProfile();
      q('[data-o=active]').textContent = ap ? ap.name : L('aucun', 'none');
      q('[data-d=leave]').textContent = '🚪 ' + L('Quitter la voiture', 'Leave the car');
      q('[data-d=leave]').title = L('Sortie du mode READY : ceinture détachée et porte conducteur ouverte, ou extinction, voiture en P', 'Leaving READY: seat belt unfastened and driver door opened, or car switched off, in P');
      el.querySelectorAll('[data-door]').forEach((b) => {
        const s = b.getAttribute('data-door');
        b.textContent = (s === 'L' ? L('Gauche', 'Left') : L('Droite', 'Right')) + ' : ' + (c.doors[s] ? L('ouverte', 'open') : L('fermée', 'closed'));
        b.classList.toggle('on', c.doors[s]);
      });
      q('[data-o=vol]').textContent = c.volume + ' / ' + c.volMax + (c.media.playing ? '  ▶ ' : '  ⏸ ') + L('piste ', 'track ') + c.media.track;
      q('[data-d=ignition]').textContent = '⏻ ' + (!c.on ? L('Démarrer le véhicule', 'Start the vehicle') : c.ready ? L('Simuler un démarrage', 'Simulate a start-up') : L('Revenir en READY', 'Back to READY'));
      q('[data-d=reset]').textContent = L('Réinitialiser', 'Reset');
      el.classList.toggle('moving', c.speed > 0);
      void k;
    }
    listeners.add(paint);
    paint();
    return { open: () => toggle(true), paint };
  }

  /** Labo d'appui : chronologie d'un appui sur une touche (court / long / double). */
  function mountPressLab(el) {
    el.innerHTML = '<div class="lab-wheel"></div><div class="lab-right"><div class="lab-track" aria-hidden="true"><div class="lab-scale"></div><div class="lab-bar"></div><div class="lab-long"></div><div class="lab-win"></div></div><div class="lab-out" aria-live="polite"></div></div>';
    el.querySelector('.lab-wheel').innerHTML = wheelHtml('lg');
    bindWheel(el.querySelector('.lab-wheel'));
    const bar = el.querySelector('.lab-bar'), win = el.querySelector('.lab-win'), out = el.querySelector('.lab-out');
    const scale = el.querySelector('.lab-scale');
    const MAX = 1000;
    scale.innerHTML = [0, 300, 500, 800, 1000].map((ms) => '<span style="left:' + (ms / MAX * 100) + '%">' + ms + ' ms</span>').join('');
    el.querySelector('.lab-long').style.left = (LONG_MS / MAX * 100) + '%';
    let t0 = 0, rafId = 0, cur = null;
    const loop = () => {
      const dt = Math.min(performance.now() - t0, MAX);
      bar.style.width = (dt / MAX * 100) + '%';
      bar.classList.toggle('long', dt >= LONG_MS);
      rafId = requestAnimationFrame(loop);
    };
    const set = (t) => { out.textContent = t; };
    set(L('Maintenez ou tapotez un bouton du volant. Les touches sans raccourci avancé passent par la voie classique (★) ou restent au launcher.', 'Hold or tap a steering-wheel button. Keys without an advanced shortcut go through the classic path (★) or stay with the launcher.'));
    keyListeners.add((t, d) => {
      if (!el.isConnected) return;
      if (t === 'down') { cur = d.code; t0 = performance.now(); win.style.display = 'none'; cancelAnimationFrame(rafId); loop(); el.classList.add('active'); }
      if (t === 'up' && d.code === cur) { cancelAnimationFrame(rafId); el.classList.remove('active'); }
      if (t === 'window' && d.code === cur) {
        const dt = performance.now() - t0;
        win.style.display = 'block'; win.style.left = (dt / MAX * 100) + '%'; win.style.width = (DOUBLE_MS / MAX * 100) + '%';
        set(L('Relâché avant 500 ms. La touche porte un double appui : on attend 300 ms un éventuel second appui avant de valider l\'appui court…', 'Released before 500 ms. This key carries a double press: waiting 300 ms for a possible second press before confirming the short press…'));
      }
      const s = describeKeyEvent(t, d);
      if (s) set(s);
    });
  }

  /** Console API : construit la commande adb et l'exécute sur le véhicule virtuel. */
  function mountApi(el) {
    const EXEC = ['REGEN_CYCLE', 'SEAT_HEAT_LEFT_CYCLE', 'SEAT_HEAT_RIGHT_CYCLE', 'STEERING_HEAT_TOGGLE', 'HVAC_TOGGLE', 'HVAC_TEMP_UP', 'HVAC_TEMP_DOWN', 'HVAC_FAN_UP', 'HVAC_FAN_DOWN',
      'DEFROST_FRONT_TOGGLE', 'DEFROST_REAR_TOGGLE', 'HVAC_RECIRC_CYCLE', 'BRIGHTNESS_UP', 'BRIGHTNESS_DOWN', 'ESC_TOGGLE', 'DROWSINESS_TOGGLE', 'DROWSINESS_SEN_CYCLE',
      'MEDIA_PLAY_PAUSE', 'MEDIA_NEXT', 'MEDIA_PREVIOUS', 'VOLUME_UP', 'VOLUME_DOWN', 'WINDOWS_OPEN_ALL', 'WINDOWS_CLOSE_ALL', 'APPLY_PROFILE', 'OPEN_CUSTOM_APP', 'ONE_PEDAL', 'ENERGY_SAVING_TOGGLE', 'PROFILE_PICKER', 'OPEN_APP',
      'ADAS_CYCLE', 'TSR_TOGGLE', 'VEHICLE_POWER_OFF'];
    const KEYS = { drive_mode: ['ECO', 'NORMAL', 'SPORT', 'SNOW', 'CUSTOM'], regen: ['OFF', 'LOW', 'MEDIUM', 'HIGH', 'ADAPTIVE', 'ONE_PEDAL'], seat_heat_left: ['0', '1', '2', '3', 'NEXT', 'PREV'],
      seat_heat_right: ['0', '1', '2', '3', 'NEXT', 'PREV'], steering_heat: ['1', '0', 'TOGGLE'], profile: null, hvac_power: ['1', '0', 'TOGGLE'], ac: ['1', '0', 'TOGGLE'], hvac_auto: ['1', '0', 'TOGGLE'],
      hvac_temp: ['18', '20', '22', '24', '35', 'NEXT', 'PREV'], hvac_fan: ['1', '3', '5', '10', 'NEXT', 'PREV'], hvac_recirc: ['INNER', 'OUTSIDE', 'AUTO', 'NEXT'], defrost_front: ['1', '0', 'TOGGLE'], defrost_rear: ['1', '0', 'TOGGLE'] };
    el.innerHTML =
      '<div class="api-form">' +
      '<div class="api-tabs" role="tablist"><button type="button" data-t="direct" class="on">' + esc(L('Action directe', 'Direct action')) + '</button><button type="button" data-t="execute">EXECUTE</button><button type="button" data-t="set">SET</button><button type="button" data-t="read">' + esc(L('Lecture (provider)', 'Read (provider)')) + '</button></div>' +
      '<div class="api-fields"></div>' +
      '<pre class="api-cmd"><code></code></pre>' +
      '<div class="api-actions"><button type="button" class="btn btn-primary" data-run></button><button type="button" class="btn btn-ghost" data-copy></button><span class="api-state"></span></div>' +
      '<pre class="api-log" aria-live="polite"></pre></div>';
    let tab = 'direct';
    const f = { direct: 'PROFILE_PICKER', exec: 'REGEN_CYCLE', profile: '', key: 'drive_mode', value: 'SPORT' };
    const fields = el.querySelector('.api-fields'), code = el.querySelector('.api-cmd code'), log = el.querySelector('.api-log');
    const opt = (list, cur) => list.map((x) => '<option' + (x === cur ? ' selected' : '') + '>' + esc(x) + '</option>').join('');
    function cmd() {
      if (tab === 'direct') return { intent: 'com.mg4.control.action.' + f.direct, text: 'adb shell am broadcast -a com.mg4.control.action.' + f.direct, run: { type: 'direct', action: f.direct, intent: f.direct } };
      if (tab === 'execute') {
        const prof = f.exec === 'APPLY_PROFILE' ? ' \\\n  --es profile "' + (f.profile || (state.profiles[0] && state.profiles[0].name) || '') + '"' : '';
        return { text: 'adb shell am broadcast -a com.mg4.control.action.EXECUTE \\\n  --es action ' + f.exec + prof, run: { type: 'execute', action: f.exec, profile: f.profile || (state.profiles[0] && state.profiles[0].name), intent: 'EXECUTE ' + f.exec } };
      }
      if (tab === 'set') return { text: 'adb shell am broadcast -a com.mg4.control.action.SET \\\n  --es key ' + f.key + ' --es value ' + (/\s/.test(f.value) ? '"' + f.value + '"' : f.value), run: { type: 'set', key: f.key, value: f.value, intent: 'SET ' + f.key } };
      return { text: 'adb shell content query --uri content://com.mg4.control.state/state', run: { type: 'read' } };
    }
    function draw() {
      el.querySelectorAll('.api-tabs button').forEach((b) => b.classList.toggle('on', b.getAttribute('data-t') === tab));
      let h = '';
      if (tab === 'direct') h = '<label>' + esc(L('Action', 'Action')) + '<select data-f="direct">' + opt(['ONE_PEDAL', 'ENERGY_SAVING_TOGGLE', 'PROFILE_PICKER', 'OPEN_APP'], f.direct) + '</select></label>';
      if (tab === 'execute') h = '<label>action<select data-f="exec">' + opt(EXEC, f.exec) + '</select></label>' + (f.exec === 'APPLY_PROFILE' ? '<label>profile<input data-f="profile" value="' + esc(f.profile || (state.profiles[0] && state.profiles[0].name) || '') + '"></label>' : '');
      if (tab === 'set') {
        const vals = KEYS[f.key];
        h = '<label>key<select data-f="key">' + opt(Object.keys(KEYS), f.key) + '</select></label>' +
          '<label>value' + (vals ? '<select data-f="value">' + opt(vals, f.value) + '</select>' : '<input data-f="value" value="' + esc(f.value) + '">') + '</label>';
      }
      if (tab === 'read') h = '<p class="api-note">' + esc(L('Tasker sait interroger un ContentProvider (KeyMapper non). Une valeur illisible vaut null, jamais 0.', 'Tasker can query a ContentProvider (KeyMapper cannot). An unreadable value is null, never 0.')) + '</p>';
      fields.innerHTML = h;
      code.textContent = cmd().text;
      el.querySelector('[data-run]').textContent = tab === 'read' ? L('Lire l\'état', 'Read state') : L('Envoyer au véhicule virtuel', 'Send to the virtual vehicle');
      el.querySelector('[data-copy]').textContent = L('Copier', 'Copy');
      paintState();
    }
    function paintState() {
      const st = el.querySelector('.api-state');
      st.textContent = state.settings.api ? L('API externe : activée', 'External API: enabled') : L('API externe : désactivée (Réglages → Réglages avancés)', 'External API: disabled (Settings → Advanced)');
      st.classList.toggle('ok', state.settings.api);
    }
    el.querySelector('.api-tabs').addEventListener('click', (e) => { const b = e.target.closest('[data-t]'); if (!b) return; tab = b.getAttribute('data-t'); draw(); });
    fields.addEventListener('change', (e) => {
      const k = e.target.getAttribute('data-f'); if (!k) return;
      f[k] = e.target.value;
      if (k === 'key') f.value = KEYS[f.key] ? KEYS[f.key][0] : (state.profiles[0] ? state.profiles[0].name : '');
      draw();
    });
    fields.addEventListener('input', (e) => { const k = e.target.getAttribute('data-f'); if (k && e.target.tagName === 'INPUT') { f[k] = e.target.value; code.textContent = cmd().text; } });
    el.querySelector('[data-run]').addEventListener('click', () => {
      const c = cmd();
      let line;
      if (c.run.type === 'read') {
        const row = providerRow();
        line = 'Row: 0 ' + Object.keys(row).map((k) => k + '=' + (row[k] == null ? 'NULL' : row[k])).join(', ');
      } else line = apiExec(c.run).text;
      log.textContent = ('$ ' + c.text.replace(/\\\n\s*/g, '') + '\n' + line + '\n\n' + log.textContent).slice(0, 4000);
    });
    el.querySelector('[data-copy]').addEventListener('click', () => {
      const t = cmd().text;
      if (navigator.clipboard) navigator.clipboard.writeText(t).then(() => { const b = el.querySelector('[data-copy]'); b.textContent = L('Copié ✓', 'Copied ✓'); setTimeout(draw, 1400); }, () => {});
    });
    listeners.add(paintState);
    draw();
    return { redraw: draw };
  }

  // ── API publique ──────────────────────────────────────────────────────────
  const byId = {};
  window.MG4Sim = {
    mount(el, opts) { const s = new Sim(el, opts || {}); byId[s.id] = s; return s; },
    get: (id) => byId[id],
    all: () => instances.slice(),
    mountDock, mountPressLab, mountApi,
    setFirmware, reset, ignition, leaveCar,
    firmware: () => state.fw,
    caps,
    onChange: (f) => listeners.add(f),
    refresh: () => commit(),
    get state() { return state; }
  };
})();
