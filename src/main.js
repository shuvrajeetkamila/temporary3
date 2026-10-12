import './style.css';

const $ = (selector) => document.querySelector(selector);
const homeScreen = $('#homeScreen');
const gameScreen = $('#gameScreen');
const canvas = $('#gameCanvas');
const ctx = canvas.getContext('2d', { alpha: false });
const arena = $('#arenaShell');
const pauseOverlay = $('#pauseOverlay');
const resultOverlay = $('#resultOverlay');
const soundToggle = $('#soundToggle');

const TARGET = 12;
const RUN_SECONDS = 45;
const BEST_KEY = 'nauru-the-game-best-v1';
const clamp = (value, min, max) => Math.max(min, Math.min(max, value));
const random = (min, max) => min + Math.random() * (max - min);

const game = {
  mode: 'home',
  width: 0,
  height: 0,
  dpr: 1,
  playerX: 0,
  targetX: 0,
  playerY: 0,
  items: [],
  particles: [],
  elapsed: 0,
  timeLeft: RUN_SECONDS,
  spawnClock: 0.4,
  score: 0,
  rescued: 0,
  lives: 3,
  streak: 0,
  lastPickup: -10,
  invulnerable: 0,
  moveDir: 0,
  keys: { left: false, right: false },
  pointerDown: false,
  best: readBest(),
  sound: true,
  toastClock: 0,
  lastFrame: 0,
  lastPickupType: 'seed',
};

function readBest() {
  try {
    return Number(window.localStorage.getItem(BEST_KEY)) || 0;
  } catch {
    return 0;
  }
}

function formatScore(score) {
  return String(Math.max(0, Math.floor(score))).padStart(4, '0');
}

function renderBest() {
  $('#homeBest').textContent = formatScore(game.best);
}

function renderLives() {
  const pill = $('#livesPill');
  pill.innerHTML = Array.from({ length: 3 }, (_, index) => `<span class="life-heart${index < game.lives ? ' is-alive' : ''}" aria-hidden="true">♥</span>`).join('');
  pill.setAttribute('aria-label', `${game.lives} ${game.lives === 1 ? 'life' : 'lives'} remaining`);
}

function updateHud() {
  $('#rescuedCount').textContent = String(game.rescued).padStart(2, '0');
  $('#scoreValue').textContent = formatScore(game.score);
  $('#timeLeft').textContent = String(Math.max(0, Math.ceil(game.timeLeft))).padStart(2, '0');
  $('#progressFill').style.width = `${(game.rescued / TARGET) * 100}%`;
  $('#progressTrack').setAttribute('aria-valuenow', String(game.rescued));
  renderLives();
}

function showScreen(screen) {
  const isHome = screen === 'home';
  homeScreen.hidden = !isHome;
  gameScreen.hidden = isHome;
  if (isHome) {
    game.mode = 'home';
    pauseOverlay.hidden = true;
    resultOverlay.hidden = true;
    renderBest();
  }
  requestAnimationFrame(resizeCanvas);
}

function resizeCanvas() {
  const rect = canvas.getBoundingClientRect();
  if (!rect.width || !rect.height) return;
  game.width = rect.width;
  game.height = rect.height;
  game.dpr = Math.min(window.devicePixelRatio || 1, 2);
  canvas.width = Math.round(rect.width * game.dpr);
  canvas.height = Math.round(rect.height * game.dpr);
  ctx.setTransform(game.dpr, 0, 0, game.dpr, 0, 0);
  game.playerY = game.height - Math.max(61, game.height * 0.125);
  const inset = Math.min(34, game.width * 0.1);
  game.playerX = clamp(game.playerX || game.width / 2, inset, game.width - inset);
  game.targetX = clamp(game.targetX || game.playerX, inset, game.width - inset);
}

const resizeObserver = new ResizeObserver(resizeCanvas);
resizeObserver.observe(arena);
window.addEventListener('resize', resizeCanvas, { passive: true });

function startGame() {
  game.mode = 'running';
  game.elapsed = 0;
  game.timeLeft = RUN_SECONDS;
  game.spawnClock = 0.55;
  game.items = [];
  game.particles = [];
  game.score = 0;
  game.rescued = 0;
  game.lives = 3;
  game.streak = 0;
  game.lastPickup = -10;
  game.invulnerable = 0;
  game.moveDir = 0;
  game.keys.left = false;
  game.keys.right = false;
  game.playerX = game.width / 2 || canvas.clientWidth / 2;
  game.targetX = game.playerX;
  pauseOverlay.hidden = true;
  resultOverlay.hidden = true;
  showScreen('game');
  updateHud();
  $('#startButton').blur();
  tone(520, 0.09, 'sine', 0.045);
  window.setTimeout(resizeCanvas, 40);
}

function pauseGame() {
  if (game.mode !== 'running') return;
  game.mode = 'paused';
  game.moveDir = 0;
  game.keys.left = false;
  game.keys.right = false;
  pauseOverlay.hidden = false;
  $('#resumeButton').focus({ preventScroll: true });
}

function resumeGame() {
  if (game.mode !== 'paused') return;
  game.mode = 'running';
  pauseOverlay.hidden = true;
  game.lastFrame = performance.now();
  tone(460, 0.07, 'sine', 0.035);
  $('#pauseButton').focus({ preventScroll: true });
}

function showToast(message) {
  const toast = $('#gameToast');
  toast.textContent = message;
  toast.classList.remove('is-visible');
  void toast.offsetWidth;
  toast.classList.add('is-visible');
  game.toastClock = 1.2;
}

function finishRun(won) {
  if (game.mode !== 'running') return;
  game.mode = won ? 'won' : 'lost';
  game.moveDir = 0;
  game.keys.left = false;
  game.keys.right = false;
  pauseOverlay.hidden = true;
  $('#resultKicker').textContent = won ? 'THE ISLAND IS GROWING' : 'THE REEF WILL WAIT';
  $('#resultTitle').textContent = won ? 'Look at it grow!' : 'Every little bit counts.';
  $('#resultCopy').textContent = won
    ? 'Rain caught. Seedlings gathered. Nauru is a little greener.'
    : game.lives === 0
      ? 'The drift got rough. Take a breath and give it another go.'
      : `You gathered ${game.rescued} of ${TARGET} supplies. The island is still counting on you.`;
  $('#resultSupplies').textContent = `${String(game.rescued).padStart(2, '0')} / ${TARGET}`;
  $('#resultScore').textContent = formatScore(game.score);
  $('#resultSeal').classList.toggle('is-miss', !won);
  resultOverlay.hidden = false;
  $('#replayButton').focus({ preventScroll: true });
  if (game.score > game.best) {
    game.best = game.score;
    try {
      window.localStorage.setItem(BEST_KEY, String(game.best));
    } catch {
      // The game remains playable when storage is disabled.
    }
  }
  renderBest();
  tone(won ? 760 : 190, won ? 0.22 : 0.25, won ? 'triangle' : 'sawtooth', 0.055);
}

function addParticles(x, y, color, count = 12, burst = 1) {
  for (let i = 0; i < count; i += 1) {
    const angle = random(0, Math.PI * 2);
    const speed = random(30, 130) * burst;
    game.particles.push({
      x,
      y,
      vx: Math.cos(angle) * speed,
      vy: Math.sin(angle) * speed - 22,
      radius: random(2, 5),
      color,
      life: random(0.35, 0.8),
      maxLife: 0,
    });
    game.particles.at(-1).maxLife = game.particles.at(-1).life;
  }
}

function spawnItem() {
  const roll = Math.random();
  const type = roll < 0.43 ? 'seed' : roll < 0.73 ? 'water' : 'debris';
  const radius = type === 'debris' ? 16 : 15;
  game.items.push({
    type,
    x: random(21 + radius, game.width - 21 - radius),
    y: -30,
    radius,
    speed: random(126, 160) + game.elapsed * 1.32,
    phase: random(0, Math.PI * 2),
    drift: random(-12, 12),
  });
}

function collectItem(item) {
  game.rescued = Math.min(TARGET, game.rescued + 1);
  game.streak = game.elapsed - game.lastPickup < 2.7 ? game.streak + 1 : 1;
  game.lastPickup = game.elapsed;
  game.lastPickupType = item.type;
  const base = item.type === 'seed' ? 110 : 90;
  game.score += base + Math.min(game.streak - 1, 5) * 15;
  addParticles(item.x, item.y, item.type === 'seed' ? '#d9f27b' : '#a8fff2', 13, 1);
  showToast(item.type === 'seed' ? 'NATIVE SEEDLING  +1' : 'FRESH RAIN  +1');
  tone(item.type === 'seed' ? 690 : 840, 0.075, 'sine', 0.042);
  updateHud();
  if (game.rescued >= TARGET) finishRun(true);
}

function hitDebris(item) {
  if (game.invulnerable > 0) return;
  game.lives = Math.max(0, game.lives - 1);
  game.invulnerable = 1.15;
  game.streak = 0;
  addParticles(item.x, item.y, '#ffd18b', 16, 1.2);
  showToast(game.lives > 0 ? 'DRIFTING DEBRIS  −1 LIFE' : 'WATCH THE DRIFT!');
  tone(135, 0.16, 'triangle', 0.055);
  updateHud();
  if (game.lives <= 0) finishRun(false);
}

function update(dt) {
  if (game.mode !== 'running') return;
  game.elapsed += dt;
  game.timeLeft = Math.max(0, RUN_SECONDS - game.elapsed);
  game.invulnerable = Math.max(0, game.invulnerable - dt);
  game.toastClock = Math.max(0, game.toastClock - dt);
  if (game.toastClock === 0) $('#gameToast').classList.remove('is-visible');

  const inset = Math.min(34, game.width * 0.1);
  const direction = (game.keys.right ? 1 : 0) - (game.keys.left ? 1 : 0) || game.moveDir;
  if (direction) {
    game.targetX = clamp(game.targetX + direction * game.width * 1.12 * dt, inset, game.width - inset);
  }
  game.playerX += (game.targetX - game.playerX) * Math.min(1, dt * 14);

  game.spawnClock -= dt;
  if (game.spawnClock <= 0) {
    spawnItem();
    game.spawnClock = Math.max(0.47, 0.91 - game.elapsed * 0.007) + random(0, 0.22);
  }

  for (let index = game.items.length - 1; index >= 0; index -= 1) {
    const item = game.items[index];
    item.y += item.speed * dt;
    item.x += Math.sin(game.elapsed * 2 + item.phase) * item.drift * dt;
    item.x = clamp(item.x, 13, game.width - 13);
    item.phase += dt * 1.6;

    const closeToCanoe = Math.abs(item.y - game.playerY) < item.radius + 18;
    const overlapsCanoe = Math.abs(item.x - game.playerX) < item.radius + 27;
    if (closeToCanoe && overlapsCanoe) {
      game.items.splice(index, 1);
      if (item.type === 'debris') hitDebris(item);
      else collectItem(item);
      if (game.mode !== 'running') break;
      continue;
    }
    if (item.y > game.height + 35) game.items.splice(index, 1);
  }

  for (let index = game.particles.length - 1; index >= 0; index -= 1) {
    const particle = game.particles[index];
    particle.life -= dt;
    particle.x += particle.vx * dt;
    particle.y += particle.vy * dt;
    particle.vx *= Math.max(0, 1 - dt * 1.6);
    particle.vy += 100 * dt;
    if (particle.life <= 0) game.particles.splice(index, 1);
  }

  updateHud();
  if (game.timeLeft <= 0) finishRun(game.rescued >= TARGET);
}

function drawBackground(time) {
  const w = game.width;
  const h = game.height;
  const water = ctx.createLinearGradient(0, 0, w * 0.8, h);
  water.addColorStop(0, '#43c7c3');
  water.addColorStop(0.48, '#25aeb1');
  water.addColorStop(1, '#087d91');
  ctx.fillStyle = water;
  ctx.fillRect(0, 0, w, h);

  const light = ctx.createRadialGradient(w * 0.52, h * 0.25, 5, w * 0.52, h * 0.25, h * 0.7);
  light.addColorStop(0, 'rgba(230,255,213,.28)');
  light.addColorStop(1, 'rgba(230,255,213,0)');
  ctx.fillStyle = light;
  ctx.fillRect(0, 0, w, h);

  ctx.save();
  ctx.lineWidth = 1.3;
  for (let row = 0; row < 10; row += 1) {
    const y = 24 + row * Math.max(36, h * 0.068);
    const offset = (time * (10 + row * 1.1) * (row % 2 ? 1 : -1)) % 110;
    ctx.beginPath();
    for (let x = -120; x < w + 130; x += 110) {
      const startX = x + offset;
      const waveY = y + Math.sin((x + time * 16) * 0.013 + row) * 3;
      ctx.moveTo(startX, waveY);
      ctx.bezierCurveTo(startX + 24, waveY - 5, startX + 38, waveY - 5, startX + 62, waveY);
    }
    ctx.strokeStyle = row < 2 ? 'rgba(237,255,221,.34)' : 'rgba(207,255,235,.19)';
    ctx.stroke();
  }
  for (let index = 0; index < 18; index += 1) {
    const x = ((index * 79 + time * (index % 2 ? 16 : -10)) % (w + 28) + w + 28) % (w + 28) - 14;
    const y = 17 + ((index * 103) % Math.max(1, h - 55));
    const size = 1 + (index % 3) * 0.45;
    ctx.beginPath();
    ctx.arc(x, y, size, 0, Math.PI * 2);
    ctx.fillStyle = 'rgba(239,255,212,.42)';
    ctx.fill();
  }
  ctx.restore();
}

function drawIsland(time) {
  const cx = game.width * 0.5;
  const cy = Math.max(71, game.height * 0.14);
  const scale = Math.min(1, game.width / 370);
  const rx = Math.min(game.width * 0.22, 79) * scale;
  const ry = rx * 0.34;
  const growth = game.rescued / TARGET;

  ctx.save();
  ctx.globalAlpha = 0.82;
  ctx.beginPath();
  ctx.ellipse(cx, cy + 9, rx * 1.24, ry * 1.23, -0.05, 0, Math.PI * 2);
  ctx.fillStyle = 'rgba(3,87,91,.35)';
  ctx.fill();
  ctx.beginPath();
  ctx.ellipse(cx, cy, rx * 1.25, ry * 1.25, -0.05, 0, Math.PI * 2);
  ctx.strokeStyle = '#d2f2ae';
  ctx.lineWidth = 4;
  ctx.setLineDash([3, 5]);
  ctx.stroke();
  ctx.setLineDash([]);

  ctx.beginPath();
  ctx.ellipse(cx, cy, rx * 1.12, ry * 1.08, -0.05, 0, Math.PI * 2);
  ctx.fillStyle = '#f0d18d';
  ctx.fill();
  ctx.beginPath();
  ctx.ellipse(cx, cy - 2, rx, ry * 0.91, -0.05, 0, Math.PI * 2);
  const land = ctx.createLinearGradient(cx - rx, cy - ry, cx + rx, cy + ry);
  land.addColorStop(0, '#70bb72');
  land.addColorStop(1, '#277e63');
  ctx.fillStyle = land;
  ctx.fill();

  // The pale, terraced centre slowly gives way to green as supplies arrive.
  ctx.beginPath();
  ctx.ellipse(cx + 2, cy - 1, rx * 0.48 * (1 - growth * 0.52), ry * 0.43 * (1 - growth * 0.5), -0.05, 0, Math.PI * 2);
  ctx.fillStyle = `rgba(200,159,102,${0.84 - growth * 0.48})`;
  ctx.fill();
  ctx.strokeStyle = `rgba(245,223,169,${0.65 - growth * 0.3})`;
  ctx.lineWidth = 1.5;
  for (let ring = 0; ring < 3; ring += 1) {
    const spread = 0.78 - ring * 0.14;
    ctx.beginPath();
    ctx.ellipse(cx + ring * 1.7, cy - ring * 0.4, rx * spread, ry * spread * 0.83, -0.05, Math.PI * 1.04, Math.PI * 1.95);
    ctx.stroke();
  }

  const treePoints = [
    [-0.58, -0.04], [-0.42, -0.48], [-0.22, 0.45], [0.04, -0.54],
    [0.28, 0.42], [0.5, -0.16], [0.64, 0.14], [-0.05, 0.2],
  ];
  const visibleTrees = Math.floor(2 + growth * 6);
  treePoints.slice(0, visibleTrees).forEach(([dx, dy], index) => {
    drawPalm(cx + dx * rx * 0.9, cy + dy * ry * 0.65, 0.34 * scale, index + time * 0.2);
  });
  ctx.restore();

  drawCoral(15, game.height * 0.49, 0.78, time, '#ffbf7a');
  drawCoral(game.width - 16, game.height * 0.36, 0.72, time + 1, '#ffa08d');
  drawCoral(20, game.height * 0.77, 0.62, time + 2, '#ffc875');
  drawCoral(game.width - 15, game.height * 0.7, 0.8, time + 0.4, '#ed8d8e');
}

function drawPalm(x, y, scale, sway) {
  ctx.save();
  ctx.translate(x, y);
  ctx.scale(scale, scale);
  const bend = Math.sin(sway) * 2.3;
  ctx.strokeStyle = '#77543a';
  ctx.lineWidth = 2.2;
  ctx.lineCap = 'round';
  ctx.beginPath();
  ctx.moveTo(0, 4);
  ctx.quadraticCurveTo(bend, -3, bend * 0.3, -13);
  ctx.stroke();
  const crownX = bend * 0.3;
  const crownY = -13;
  ctx.strokeStyle = '#246d4e';
  ctx.lineWidth = 2;
  for (let index = 0; index < 5; index += 1) {
    const angle = -Math.PI * 0.9 + index * (Math.PI * 0.8 / 4) + Math.sin(sway) * 0.035;
    ctx.beginPath();
    ctx.moveTo(crownX, crownY);
    ctx.quadraticCurveTo(crownX + Math.cos(angle) * 7, crownY + Math.sin(angle) * 7 - 1, crownX + Math.cos(angle) * 14, crownY + Math.sin(angle) * 13);
    ctx.stroke();
  }
  ctx.restore();
}

function drawCoral(x, y, scale, time, color) {
  ctx.save();
  ctx.translate(x, y + Math.sin(time * 1.5 + x) * 2);
  ctx.scale(scale, scale);
  ctx.globalAlpha = 0.73;
  ctx.fillStyle = color;
  ctx.beginPath();
  ctx.moveTo(-9, 12);
  ctx.lineTo(-9, 1);
  ctx.quadraticCurveTo(-14, -4, -10, -8);
  ctx.quadraticCurveTo(-5, -10, -4, -4);
  ctx.lineTo(-3, 4);
  ctx.lineTo(1, -2);
  ctx.quadraticCurveTo(-1, -9, 5, -11);
  ctx.quadraticCurveTo(11, -9, 7, -3);
  ctx.lineTo(3, 5);
  ctx.lineTo(9, 1);
  ctx.quadraticCurveTo(14, -3, 17, 2);
  ctx.quadraticCurveTo(16, 7, 9, 7);
  ctx.lineTo(5, 12);
  ctx.closePath();
  ctx.fill();
  ctx.restore();
}

function drawSeed(x, y, r) {
  ctx.save();
  ctx.translate(x, y);
  ctx.strokeStyle = '#205e4b';
  ctx.lineWidth = 2.2;
  ctx.lineCap = 'round';
  ctx.lineJoin = 'round';
  ctx.beginPath();
  ctx.moveTo(0, 7);
  ctx.lineTo(0, -2);
  ctx.stroke();
  ctx.fillStyle = '#66b956';
  ctx.beginPath();
  ctx.ellipse(-4, -5, 4.8, 3.3, -0.6, 0, Math.PI * 2);
  ctx.ellipse(4.2, -8, 4.5, 3.1, -0.45, 0, Math.PI * 2);
  ctx.fill();
  ctx.strokeStyle = '#3d8d4b';
  ctx.lineWidth = 1;
  ctx.beginPath();
  ctx.moveTo(0, 1);
  ctx.lineTo(-5, -5);
  ctx.moveTo(0, -3);
  ctx.lineTo(5, -8);
  ctx.stroke();
  ctx.fillStyle = '#e5f4ad';
  ctx.beginPath();
  ctx.ellipse(0, 8, 6, 2, 0, 0, Math.PI * 2);
  ctx.fill();
  ctx.restore();
}

function drawWaterDrop(x, y, r) {
  ctx.save();
  ctx.translate(x, y);
  ctx.fillStyle = '#55d0d1';
  ctx.beginPath();
  ctx.moveTo(0, -9);
  ctx.bezierCurveTo(2, -5, 8, 1, 8, 5);
  ctx.arc(0, 5, 8, 0, Math.PI, false);
  ctx.bezierCurveTo(-8, 1, -2, -5, 0, -9);
  ctx.fill();
  ctx.strokeStyle = '#128b9a';
  ctx.lineWidth = 1.6;
  ctx.stroke();
  ctx.strokeStyle = 'rgba(244,255,230,.88)';
  ctx.lineWidth = 1.6;
  ctx.lineCap = 'round';
  ctx.beginPath();
  ctx.moveTo(-2, 2);
  ctx.quadraticCurveTo(-4, 6, -1, 8);
  ctx.stroke();
  ctx.restore();
}

function drawDebris(x, y) {
  ctx.save();
  ctx.translate(x, y);
  ctx.rotate(Math.sin(game.elapsed * 1.8 + x) * 0.18);
  ctx.fillStyle = '#c1d0c3';
  ctx.strokeStyle = '#6f8e87';
  ctx.lineWidth = 1.7;
  ctx.beginPath();
  ctx.roundRect(-6, -9, 12, 18, 3);
  ctx.fill();
  ctx.stroke();
  ctx.fillStyle = '#839e92';
  ctx.fillRect(-3, -12, 6, 3);
  ctx.strokeStyle = '#f1f2d2';
  ctx.lineWidth = 1.2;
  ctx.beginPath();
  ctx.moveTo(-3, -2);
  ctx.lineTo(3, 3);
  ctx.moveTo(3, -2);
  ctx.lineTo(-3, 3);
  ctx.stroke();
  ctx.restore();
}

function drawItems(time) {
  for (const item of game.items) {
    const bob = Math.sin(time * 4 + item.phase) * 2;
    const y = item.y + bob;
    const color = item.type === 'seed' ? '#d2f18e' : item.type === 'water' ? '#b4fff1' : '#fff0c1';
    ctx.save();
    ctx.shadowColor = item.type === 'debris' ? 'rgba(58,74,77,.27)' : 'rgba(1,76,75,.28)';
    ctx.shadowBlur = 12;
    ctx.shadowOffsetY = 4;
    ctx.beginPath();
    ctx.arc(item.x, y, item.radius + 2, 0, Math.PI * 2);
    ctx.fillStyle = color;
    ctx.fill();
    ctx.restore();

    ctx.beginPath();
    ctx.arc(item.x, y, item.radius, 0, Math.PI * 2);
    ctx.fillStyle = item.type === 'debris' ? '#eff3db' : '#f8f9dd';
    ctx.fill();
    ctx.strokeStyle = item.type === 'debris' ? '#849b8c' : '#e2e9bc';
    ctx.lineWidth = 1.5;
    ctx.stroke();
    if (item.type === 'seed') drawSeed(item.x, y, item.radius);
    else if (item.type === 'water') drawWaterDrop(item.x, y, item.radius);
    else drawDebris(item.x, y);
  }
}

function drawCanoe(time) {
  const x = game.playerX;
  const y = game.playerY + Math.sin(time * 4.2) * 2;
  const blink = game.invulnerable > 0 && Math.floor(game.invulnerable * 12) % 2 === 0;
  if (blink) return;
  ctx.save();
  ctx.globalAlpha = game.invulnerable > 0 ? 0.6 : 1;
  ctx.translate(x, y);
  ctx.rotate(Math.max(-0.12, Math.min(0.12, (game.targetX - game.playerX) * 0.004)));

  ctx.fillStyle = 'rgba(5,77,86,.27)';
  ctx.beginPath();
  ctx.ellipse(0, 18, 40, 7, 0, 0, Math.PI * 2);
  ctx.fill();
  ctx.strokeStyle = 'rgba(207,255,242,.76)';
  ctx.lineWidth = 1.4;
  ctx.beginPath();
  ctx.moveTo(-48, 22);
  ctx.quadraticCurveTo(-34, 17, -21, 21);
  ctx.moveTo(27, 22);
  ctx.quadraticCurveTo(41, 17, 51, 21);
  ctx.stroke();

  // Outrigger float and the two crossbeams.
  ctx.strokeStyle = '#875b39';
  ctx.lineWidth = 2.4;
  ctx.lineCap = 'round';
  ctx.beginPath();
  ctx.moveTo(-5, 5);
  ctx.lineTo(-29, 13);
  ctx.moveTo(5, 5);
  ctx.lineTo(29, 13);
  ctx.stroke();
  ctx.fillStyle = '#e8ad62';
  ctx.beginPath();
  ctx.moveTo(-34, 12);
  ctx.quadraticCurveTo(-30, 9, -24, 13);
  ctx.quadraticCurveTo(-29, 17, -34, 12);
  ctx.fill();
  ctx.strokeStyle = '#9a673e';
  ctx.lineWidth = 1.4;
  ctx.stroke();

  // Hull.
  ctx.beginPath();
  ctx.moveTo(-28, 4);
  ctx.quadraticCurveTo(0, -5, 28, 4);
  ctx.lineTo(20, 12);
  ctx.quadraticCurveTo(0, 17, -20, 12);
  ctx.closePath();
  ctx.fillStyle = '#bd7048';
  ctx.fill();
  ctx.strokeStyle = '#794a38';
  ctx.lineWidth = 2;
  ctx.stroke();
  ctx.beginPath();
  ctx.moveTo(-19, 5);
  ctx.quadraticCurveTo(0, 1, 19, 5);
  ctx.strokeStyle = '#f1cb8b';
  ctx.lineWidth = 2;
  ctx.stroke();

  // Sail and a cheerful little skipper.
  ctx.strokeStyle = '#805d43';
  ctx.lineWidth = 2;
  ctx.beginPath();
  ctx.moveTo(0, 4);
  ctx.lineTo(0, -30);
  ctx.stroke();
  ctx.beginPath();
  ctx.moveTo(-1, -28);
  ctx.lineTo(18, -8);
  ctx.lineTo(-1, -9);
  ctx.closePath();
  ctx.fillStyle = '#fff5d7';
  ctx.fill();
  ctx.strokeStyle = '#dbd3ae';
  ctx.lineWidth = 1;
  ctx.stroke();
  ctx.beginPath();
  ctx.arc(-7, -3, 5.2, 0, Math.PI * 2);
  ctx.fillStyle = '#d88c5e';
  ctx.fill();
  ctx.beginPath();
  ctx.arc(-7, -5, 5.2, Math.PI, Math.PI * 2);
  ctx.fillStyle = '#253f43';
  ctx.fill();
  ctx.beginPath();
  ctx.arc(-5.3, -2.7, 0.7, 0, Math.PI * 2);
  ctx.fillStyle = '#273b3a';
  ctx.fill();
  ctx.restore();
}

function drawParticles() {
  for (const particle of game.particles) {
    const alpha = Math.max(0, particle.life / particle.maxLife);
    ctx.globalAlpha = alpha;
    ctx.beginPath();
    ctx.arc(particle.x, particle.y, particle.radius * (0.5 + alpha * 0.5), 0, Math.PI * 2);
    ctx.fillStyle = particle.color;
    ctx.fill();
  }
  ctx.globalAlpha = 1;
}

function draw() {
  if (!game.width || !game.height) return;
  ctx.setTransform(game.dpr, 0, 0, game.dpr, 0, 0);
  drawBackground(game.elapsed);
  drawIsland(game.elapsed);
  drawItems(game.elapsed);
  drawParticles();
  if (game.mode !== 'home') drawCanoe(game.elapsed);
}

function animate(now) {
  const dt = game.lastFrame ? Math.min((now - game.lastFrame) / 1000, 0.04) : 0;
  game.lastFrame = now;
  update(dt);
  draw();
  window.requestAnimationFrame(animate);
}
window.requestAnimationFrame(animate);

function tone(frequency, duration, type = 'sine', volume = 0.04) {
  if (!game.sound || !('AudioContext' in window)) return;
  try {
    const AudioContextClass = window.AudioContext || window.webkitAudioContext;
    if (!tone.context) tone.context = new AudioContextClass();
    const audio = tone.context;
    if (audio.state === 'suspended') audio.resume();
    const oscillator = audio.createOscillator();
    const gain = audio.createGain();
    oscillator.type = type;
    oscillator.frequency.setValueAtTime(frequency, audio.currentTime);
    gain.gain.setValueAtTime(volume, audio.currentTime);
    gain.gain.exponentialRampToValueAtTime(0.001, audio.currentTime + duration);
    oscillator.connect(gain);
    gain.connect(audio.destination);
    oscillator.start();
    oscillator.stop(audio.currentTime + duration);
  } catch {
    // Audio is a small enhancement; it should never interrupt a run.
  }
}

function positionFromPointer(event) {
  const rect = canvas.getBoundingClientRect();
  const inset = Math.min(34, game.width * 0.1);
  game.targetX = clamp(event.clientX - rect.left, inset, game.width - inset);
}

canvas.addEventListener('pointerdown', (event) => {
  if (game.mode !== 'running') return;
  game.pointerDown = true;
  canvas.setPointerCapture?.(event.pointerId);
  positionFromPointer(event);
});
canvas.addEventListener('pointermove', (event) => {
  if (game.mode === 'running' && game.pointerDown) positionFromPointer(event);
});
for (const eventName of ['pointerup', 'pointercancel', 'lostpointercapture']) {
  canvas.addEventListener(eventName, () => { game.pointerDown = false; });
}

function bindDirectionButton(button, direction) {
  button.addEventListener('pointerdown', (event) => {
    event.preventDefault();
    button.setPointerCapture?.(event.pointerId);
    game.moveDir = direction;
  });
  const release = () => { if (game.moveDir === direction) game.moveDir = 0; };
  button.addEventListener('pointerup', release);
  button.addEventListener('pointercancel', release);
  button.addEventListener('lostpointercapture', release);
  button.addEventListener('click', (event) => {
    if (event.detail === 0 && game.mode === 'running') {
      game.targetX = clamp(game.targetX + direction * 58, Math.min(34, game.width * 0.1), game.width - Math.min(34, game.width * 0.1));
    }
  });
}
bindDirectionButton($('#leftButton'), -1);
bindDirectionButton($('#rightButton'), 1);

window.addEventListener('keydown', (event) => {
  if (event.key === 'Escape') {
    if (game.mode === 'running') pauseGame();
    else if (game.mode === 'paused') resumeGame();
    return;
  }
  if (game.mode !== 'running') return;
  if (['ArrowLeft', 'a', 'A'].includes(event.key)) {
    event.preventDefault();
    game.keys.left = true;
  }
  if (['ArrowRight', 'd', 'D'].includes(event.key)) {
    event.preventDefault();
    game.keys.right = true;
  }
});
window.addEventListener('keyup', (event) => {
  if (['ArrowLeft', 'a', 'A'].includes(event.key)) game.keys.left = false;
  if (['ArrowRight', 'd', 'D'].includes(event.key)) game.keys.right = false;
});
window.addEventListener('blur', () => {
  game.keys.left = false;
  game.keys.right = false;
  game.moveDir = 0;
});
document.addEventListener('visibilitychange', () => {
  if (document.hidden && game.mode === 'running') pauseGame();
});

$('#startButton').addEventListener('click', startGame);
$('#pauseButton').addEventListener('click', pauseGame);
$('#resumeButton').addEventListener('click', resumeGame);
$('#pauseHomeButton').addEventListener('click', () => showScreen('home'));
$('#replayButton').addEventListener('click', startGame);
$('#homeButton').addEventListener('click', () => showScreen('home'));
soundToggle.addEventListener('click', () => {
  game.sound = !game.sound;
  soundToggle.classList.toggle('is-muted', !game.sound);
  soundToggle.setAttribute('aria-label', game.sound ? 'Turn sound off' : 'Turn sound on');
  if (game.sound) tone(620, 0.08, 'sine', 0.035);
});

renderBest();
renderLives();
