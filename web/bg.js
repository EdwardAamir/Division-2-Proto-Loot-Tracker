/* Escalation Loot (web) — "DC at dusk" backdrop.
 *
 * A fully procedural canvas scene in the mood of The Division 2: a dark
 * overgrown city silhouette, warm dusk sky, an orange moon, faint lit office
 * windows and slow drifting ash motes. Zero image assets, so it can never 404
 * and bundles nothing copyrighted.
 *
 * Pauses when the tab is hidden and honours prefers-reduced-motion.
 */
"use strict";

(function () {
  const canvas = document.getElementById("bgScene");
  if (!canvas) return;
  const ctx = canvas.getContext("2d");
  if (!ctx) return;

  const reduced = window.matchMedia &&
    window.matchMedia("(prefers-reduced-motion: reduce)").matches;

  let W = 0, H = 0;
  let moon = { x: 0, y: 0, r: 0 };
  let far = [], near = [], motes = [];
  let rafId = null;
  let lastTs = 0;
  let seed = 12345;

  const rand = (a, b) => a + Math.random() * (b - a);
  const rng = () => (seed = (seed * 16807) % 2147483647) / 2147483647;

  function buildWindows(bw, bh, density) {
    const wins = [];
    const pad = 8, cw = 3.5, gap = 15;
    const cols = Math.max(1, Math.floor((bw - 2 * pad) / gap));
    const rows = Math.max(1, Math.floor((bh * 0.72 - 2 * pad) / gap));
    for (let c = 0; c < cols; c++) {
      for (let r = 0; r < rows; r++) {
        if (Math.random() > density) continue;
        wins.push({
          x: pad + c * gap + (gap - cw) / 2,
          y: pad + r * gap + (gap - cw) / 2,
          cool: Math.random() < 0.12,
          phase: rng() * Math.PI * 2,
          speed: 0.6 + rng() * 2.2,
        });
      }
    }
    return wins;
  }

  function makeBuildings(w, h, opts) {
    const list = [];
    let x = -10;
    while (x < w + 40) {
      const bw = opts.minW + Math.random() * (opts.maxW - opts.minW);
      const bh = opts.minH + Math.random() * (opts.maxH - opts.minH);
      list.push({
        x,
        w: bw,
        h: bh,
        color: opts.color,
        antH: Math.random() < opts.antenna ? 26 + Math.random() * 18 : 0,
        tank: Math.random() < opts.tank,
        wins: buildWindows(bw, bh, opts.density),
      });
      x += bw + (opts.gap + Math.random() * opts.gap);
    }
    return list;
  }

  function layout() {
    const dpr = Math.min(window.devicePixelRatio || 1, 1.75);
    W = window.innerWidth;
    H = window.innerHeight;
    canvas.width = Math.round(W * dpr);
    canvas.height = Math.round(H * dpr);
    canvas.style.width = W + "px";
    canvas.style.height = H + "px";
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);

    moon = { x: W * 0.74, y: H * 0.24, r: Math.min(W, H) * 0.085 };

    far = makeBuildings(W, H, {
      minW: 46, maxW: 150,
      minH: H * 0.10, maxH: H * 0.30,
      gap: 8, color: "#232d44",
      density: 0.10, antenna: 0.06, tank: 0.12,
    });
    near = makeBuildings(W, H, {
      minW: 70, maxW: 220,
      minH: H * 0.16, maxH: H * 0.52,
      gap: 14, color: "#0d131d",
      density: 0.16, antenna: 0.10, tank: 0.18,
    });

    const count = Math.max(20, Math.min(70, Math.round((W * H) / 42000)));
    motes = [];
    for (let i = 0; i < count; i++) {
      motes.push({
        x: Math.random() * W,
        y: Math.random() * H,
        r: rand(0.6, 2.2),
        vy: rand(0.08, 0.30),
        vx: rand(-0.08, 0.08),
        a: rand(0.05, 0.18),
      });
    }
  }

  function drawSky() {
    const g = ctx.createLinearGradient(0, 0, 0, H);
    g.addColorStop(0.00, "#070b16");
    g.addColorStop(0.38, "#101a30");
    g.addColorStop(0.62, "#33283a");
    g.addColorStop(0.78, "#6b3a2c");
    g.addColorStop(0.90, "#c26b33");
    g.addColorStop(1.00, "#e08a3c");
    ctx.fillStyle = g;
    ctx.fillRect(0, 0, W, H);

    // Moon glow then disc
    const glow = ctx.createRadialGradient(moon.x, moon.y, moon.r * 0.2, moon.x, moon.y, moon.r * 4.2);
    glow.addColorStop(0, "rgba(255,180,110,0.55)");
    glow.addColorStop(1, "rgba(255,180,110,0)");
    ctx.fillStyle = glow;
    ctx.fillRect(0, 0, W, H);

    const disc = ctx.createRadialGradient(moon.x, moon.y, 0, moon.x, moon.y, moon.r);
    disc.addColorStop(0, "#ffd9a0");
    disc.addColorStop(0.75, "#ffb263");
    disc.addColorStop(1, "#e08a3c");
    ctx.fillStyle = disc;
    ctx.beginPath();
    ctx.arc(moon.x, moon.y, moon.r, 0, Math.PI * 2);
    ctx.fill();

    ctx.fillStyle = "rgba(224,138,60,0.18)";
    for (const [dx, dy, dr] of [[-0.30, 0.15, 0.16], [0.22, -0.30, 0.11], [0.05, 0.42, 0.09]]) {
      ctx.beginPath();
      ctx.arc(moon.x + moon.r * dx, moon.y + moon.r * dy, moon.r * dr, 0, Math.PI * 2);
      ctx.fill();
    }

    // Sparse fixed stars up top
    for (let i = 0; i < 60; i++) {
      ctx.globalAlpha = 0.15 + rng() * 0.4;
      ctx.fillStyle = "#ffd6aa";
      ctx.fillRect(rng() * W, rng() * H * 0.5, 1.2, 1.2);
    }
    ctx.globalAlpha = 1;
  }

  function drawCity(layer, t) {
    for (const b of layer) {
      const y = H - b.h;
      ctx.fillStyle = b.color;
      ctx.fillRect(b.x, y, b.w, b.h);

      if (b.antH) {
        ctx.strokeStyle = b.color;
        ctx.lineWidth = 2;
        ctx.beginPath();
        ctx.moveTo(b.x + b.w * 0.5, y);
        ctx.lineTo(b.x + b.w * 0.5, y - b.antH);
        ctx.stroke();
        ctx.fillRect(b.x + b.w * 0.5 - 3, y - b.antH - 3, 6, 4);
      }

      if (b.tank) {
        ctx.fillStyle = "rgba(0,0,0,0.28)";
        const tw = 18, th = 26, tx = b.x + b.w * 0.62, ty = y - th;
        ctx.fillRect(tx, ty, tw, th);
        ctx.fillRect(tx - 3, ty - 4, tw + 6, 4);
      }

      for (const win of b.wins) {
        let a;
        if (reduced) a = 0.6;
        else a = 0.28 + 0.55 * (0.5 + 0.5 * Math.sin(t * win.speed + win.phase));
        if (a < 0.05) continue;
        ctx.fillStyle = win.cool
          ? "rgba(140,168,255," + (a * 0.8).toFixed(3) + ")"
          : "rgba(255,203,138," + a.toFixed(3) + ")";
        ctx.fillRect(b.x + win.x, y + win.y, 3.5, 3.5);
      }
    }
  }

  function drawMotes() {
    ctx.fillStyle = "#ffc072";
    for (const m of motes) {
      ctx.globalAlpha = m.a;
      ctx.beginPath();
      ctx.arc(m.x, m.y, m.r, 0, Math.PI * 2);
      ctx.fill();
      m.y += m.vy;
      m.x += m.vx;
      if (m.y > H + 4) { m.y = -4; m.x = Math.random() * W; }
      if (m.x < -4) m.x = W + 4;
      if (m.x > W + 4) m.x = -4;
    }
    ctx.globalAlpha = 1;
  }

  function draw(t) {
    drawSky();
    drawMotes();
    drawCity(far, t);
    drawCity(near, t);
  }

  function loop(ts) {
    // ~30 fps cap keeps low-end phones comfortable
    if (ts - lastTs >= 33) { draw(ts * 0.001); lastTs = ts; }
    rafId = requestAnimationFrame(loop);
  }

  function start() {
    cancel();
    layout();
    if (reduced) { draw(0); return; }
    rafId = requestAnimationFrame(loop);
  }

  function cancel() {
    if (rafId !== null) { cancelAnimationFrame(rafId); rafId = null; }
  }

  let resizeTimer = null;
  window.addEventListener("resize", () => {
    if (resizeTimer) clearTimeout(resizeTimer);
    resizeTimer = setTimeout(start, 200);
  });

  document.addEventListener("visibilitychange", () => {
    if (document.hidden) cancel();
    else start();
  });

  start();
})();