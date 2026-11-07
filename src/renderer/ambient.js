'use strict';
// Música ambiental ORIGINAL generada en tiempo real (Web Audio): pad en Re dórico, bajo suave y campanas con eco.
// No usa ningún archivo de audio ni material de terceros. Si el servidor publica pistas, el launcher usa esas.
(function () {
  const BAR = 8; // segundos por compás
  const CHORDS = [[50, 57, 62, 65, 69], [46, 53, 58, 62, 65], [48, 55, 60, 64, 67], [45, 52, 57, 60, 64]]; // Dm9, Bbmaj7, Cmaj7, Am7
  const SCALE = [62, 64, 65, 67, 69, 71, 72, 74, 76, 77];
  const hz = (m) => 440 * Math.pow(2, (m - 69) / 12);
  const prng = (a) => () => { a |= 0; a = (a + 0x6d2b79f5) | 0; let t = Math.imul(a ^ (a >>> 15), 1 | a); t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t; return ((t ^ (t >>> 14)) >>> 0) / 4294967296; };

  function graph(ctx) {
    const master = ctx.createGain(); master.gain.value = 0;
    const comp = ctx.createDynamicsCompressor(); comp.threshold.value = -18; comp.ratio.value = 4;
    master.connect(comp); comp.connect(ctx.destination);
    const bus = ctx.createGain(); bus.gain.value = 0.9; bus.connect(master);
    const echo = ctx.createDelay(2); echo.delayTime.value = 0.62;
    const fb = ctx.createGain(); fb.gain.value = 0.42;
    const lp = ctx.createBiquadFilter(); lp.type = 'lowpass'; lp.frequency.value = 2200;
    echo.connect(lp); lp.connect(fb); fb.connect(echo); lp.connect(master);
    return { master, bus, echo };
  }

  function voice(ctx, dest, type, freq, t0, dur, peak, att, rel, cutoff, detune) {
    const o = ctx.createOscillator(); o.type = type; o.frequency.value = freq; o.detune.value = detune || 0;
    const f = ctx.createBiquadFilter(); f.type = 'lowpass'; f.frequency.value = cutoff; f.Q.value = 0.4;
    const g = ctx.createGain(); g.gain.setValueAtTime(0.0001, t0);
    g.gain.linearRampToValueAtTime(peak, t0 + att);
    g.gain.setValueAtTime(peak, t0 + dur - rel);
    g.gain.linearRampToValueAtTime(0.0001, t0 + dur);
    o.connect(f); f.connect(g); g.connect(dest);
    o.start(t0); o.stop(t0 + dur + 0.05);
  }

  function bar(ctx, gr, t0, i) {
    const r = prng(i * 7919 + 13), ch = CHORDS[i % CHORDS.length], dur = BAR + 3.5;
    ch.forEach((n, k) => { // pad: dos osciladores ligeramente desafinados por nota
      voice(ctx, gr.bus, 'triangle', hz(n), t0, dur, 0.05, 2.6, 3.2, 900 + k * 120, -7);
      voice(ctx, gr.bus, 'sine', hz(n), t0, dur, 0.04, 3.0, 3.2, 1400, 6);
    });
    voice(ctx, gr.bus, 'sine', hz(ch[0] - 12), t0, dur, 0.09, 1.6, 3.0, 400, 0); // bajo
    const notes = 3 + Math.floor(r() * 3); // campanas
    for (let k = 0; k < notes; k++) {
      const tt = t0 + 0.8 + r() * (BAR - 1.5), f = hz(SCALE[Math.floor(r() * SCALE.length)]);
      const g = ctx.createGain(); g.gain.setValueAtTime(0.0001, tt);
      g.gain.exponentialRampToValueAtTime(0.07 + r() * 0.03, tt + 0.03);
      g.gain.exponentialRampToValueAtTime(0.0001, tt + 3.4);
      [[1, 1], [2.01, 0.28], [3.98, 0.1]].forEach(([m, a]) => {
        const o = ctx.createOscillator(); o.type = 'sine'; o.frequency.value = f * m;
        const gg = ctx.createGain(); gg.gain.value = a; o.connect(gg); gg.connect(g); o.start(tt); o.stop(tt + 3.5);
      });
      g.connect(gr.bus); g.connect(gr.echo);
    }
  }

  const A = {
    ctx: null, gr: null, playing: false, vol: 0.4, muted: false, next: 0, i: 0, timer: null,
    _graph: graph, _bar: bar, BAR,
    level() { return this.muted ? 0 : this.vol * 0.9; },
    apply() { if (this.gr) this.gr.master.gain.setTargetAtTime(this.playing ? this.level() : 0, this.ctx.currentTime, 0.4); },
    tick() {
      if (this.next < this.ctx.currentTime) this.next = this.ctx.currentTime + 0.1;
      while (this.next < this.ctx.currentTime + 12) { bar(this.ctx, this.gr, this.next, this.i++); this.next += BAR; }
    },
    start() {
      if (this.playing) return;
      if (!this.ctx) { const C = window.AudioContext || window.webkitAudioContext; if (!C) return; this.ctx = new C(); this.gr = graph(this.ctx); }
      this.ctx.resume(); this.playing = true; this.tick(); this.apply();
      this.timer = setInterval(() => this.tick(), 1500);
    },
    stop() {
      if (!this.playing) return;
      this.playing = false; this.apply(); clearInterval(this.timer);
      setTimeout(() => { if (!this.playing && this.ctx) this.ctx.suspend(); }, 1500);
    },
    setVolume(v) { this.vol = v; this.apply(); },
    setMuted(m) { this.muted = m; this.apply(); },
  };
  window.Ambient = A;
})();
