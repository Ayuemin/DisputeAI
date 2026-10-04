/** Цикл диалога: модели отвечают по очереди. Пользователь может ставить цикл на паузу. */
'use strict';

var running = false;
var cycleActive = false;
var cyclePaused = false;
var pauseRequested = false;
var stopRequested = false;
var cycleOrder = [];
var cycleIndex = 0;

function updateCycleUi() {
  var pause = $('btnPause');
  var stop = $('btnStop');
  pause.hidden = !cycleActive;
  stop.hidden = !cycleActive;
  pause.textContent = cyclePaused ? 'Продолжить' : (pauseRequested ? 'Пауза…' : 'Пауза');
  pause.classList.toggle('on', cyclePaused);
  $('roundsInput').disabled = cycleActive;
}

function updateComposerLock() {
  var locked = running || (cycleActive && !cyclePaused);
  $('btnSend').disabled = locked;
  $('btnAttach').disabled = locked;
  $('input').disabled = locked;
}

function setRequestRunning(on) {
  running = on;
  State.busy = on;
  updateComposerLock();
  updateCycleUi();
}

function readyOrder() {
  return SLOT_IDS.filter(function (id) { return slotReady(id); });
}

function startCycle() {
  if (cycleActive || running) return;
  var ready = readyOrder();
  if (!ready.length) {
    status('Сначала настройте хотя бы одну модель.');
    openSheet('sheetSettings');
    return;
  }
  var rounds = Math.max(1, Math.min(100, Number(State.rounds) || 1));
  cycleOrder = [];
  for (var r = 0; r < rounds; r++) ready.forEach(function (id) { cycleOrder.push(id); });
  cycleIndex = 0;
  cycleActive = true;
  cyclePaused = false;
  pauseRequested = false;
  stopRequested = false;
  updateCycleUi();
  updateComposerLock();
  runNextCycleStep();
}

function finishCycle(message) {
  cycleActive = false;
  cyclePaused = false;
  pauseRequested = false;
  stopRequested = false;
  cycleOrder = [];
  cycleIndex = 0;
  setRequestRunning(false);
  renderSpeakers();
  saveState();
  status(message || 'Цикл завершён.');
}

function pauseNow() {
  cyclePaused = true;
  pauseRequested = false;
  setRequestRunning(false);
  status('Пауза. Можно прочитать ответы, добавить свою реплику или вложение.');
  updateCycleUi();
  updateComposerLock();
}

function runNextCycleStep() {
  if (!cycleActive || running) return;
  if (stopRequested) { finishCycle('Цикл остановлен.'); return; }
  if (pauseRequested || cyclePaused) { pauseNow(); return; }
  if (cycleIndex >= cycleOrder.length) { finishCycle('Цикл завершён.'); return; }

  var id = cycleOrder[cycleIndex];
  var typing = showTyping(id);
  setRequestRunning(true);
  status(slotName(id) + ' отвечает…');

  askModel(id).then(function (res) {
    hideTyping(typing);
    setRequestRunning(false);

    if (res && res.ok) {
      var msg = addMessage(id, res.text, { ms: res.ms, usage: res.usage });
      appendMessage(msg);
    } else {
      var err = addMessage(id, (res && res.error) || 'Ошибка запроса', { error: true });
      appendMessage(err);
    }
    cycleIndex++;
    renderSpeakers();
    saveState();

    if (stopRequested) { finishCycle('Цикл остановлен.'); return; }
    if (pauseRequested) { pauseNow(); return; }
    runNextCycleStep();
  });
}

function sendUser(text, attachments) {
  attachments = attachments || [];
  if (!String(text || '').trim() && !attachments.length) return;
  if (running || (cycleActive && !cyclePaused)) return;

  addMessage('user', String(text || '').trim(), attachments.length ? { attachments: attachments } : null);
  appendMessage(State.messages[State.messages.length - 1]);
  saveState();

  if (cycleActive && cyclePaused) {
    status('Реплика добавлена. Обе модели увидят её после «Продолжить».');
    return;
  }
  startCycle();
}

function togglePause() {
  if (!cycleActive) return;
  if (cyclePaused) {
    cyclePaused = false;
    pauseRequested = false;
    status('Цикл продолжен.');
    updateCycleUi();
    updateComposerLock();
    runNextCycleStep();
    return;
  }
  if (running) {
    pauseRequested = true;
    status('Пауза после текущего ответа…');
  } else {
    pauseNow();
  }
  updateCycleUi();
  updateComposerLock();
}

function stopTurns() {
  if (!cycleActive) return;
  if (running) {
    stopRequested = true;
    pauseRequested = false;
    status('Остановка после текущего ответа…');
    updateCycleUi();
  } else {
    finishCycle('Цикл остановлен.');
  }
}
