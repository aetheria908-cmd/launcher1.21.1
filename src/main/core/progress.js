'use strict';
// Progreso agregado con velocidad (media móvil de 5 s) y tiempo estimado.
class Progress {
  constructor(emit) { this.emit = emit; this.reset(); }
  reset() { this.phase = ''; this.label = ''; this.total = 0; this.done = 0; this.samples = []; this.last = 0; }
  setPhase(phase, label) { this.phase = phase; this.label = label; this.total = 0; this.done = 0; this.samples = []; this.last = 0; this.push(true); }
  addTotal(n) { this.total += n; }
  add(n) { this.done = Math.max(0, this.done + n); this.push(false); }
  push(force) {
    const now = Date.now();
    if (!force && now - this.last < 200) return;
    this.last = now;
    this.samples.push([now, this.done]);
    while (this.samples.length > 2 && now - this.samples[0][0] > 5000) this.samples.shift();
    const [t0, d0] = this.samples[0];
    const dt = (now - t0) / 1000;
    const speed = dt > 0.4 ? Math.max(0, (this.done - d0) / dt) : 0;
    const eta = speed > 1024 && this.total > this.done ? (this.total - this.done) / speed : null;
    this.emit({ phase: this.phase, label: this.label, done: this.done, total: this.total,
      percent: this.total ? Math.min(100, (this.done / this.total) * 100) : null, speed, eta });
  }
}
module.exports = Progress;
