/**
 * DisputeAI: состояние, нативный мост и локальное хранение.
 * Секретные API-ключи после сохранения не остаются в JS-состоянии: Android
 * переносит их в Android Keystore и возвращает только флаг hasApiKey.
 */
'use strict';

var SLOT_IDS = ['a', 'b'];

function defaultSlot(id, name) {
  return {
    id: id,
    name: name,
    enabled: true,
    provider: 'openai',
    baseUrl: '',
    model: '',
    apiKey: '',
    hasApiKey: false,
    system: 'Ты участвуешь в беседе. Отвечай по существу, на языке собеседника.',
    temperature: 0.8,
    timeoutSec: 120
  };
}

var State = {
  slots: {
    a: defaultSlot('a', 'Модель A'),
    b: defaultSlot('b', 'Модель B')
  },
  messages: [],
  rounds: 3,
  ctxLimit: 40,
  lastAuthor: null,
  busy: false,
  nextId: 1,
  pendingAttachments: []
};

var Native = (function () {
  var pending = {};
  var seq = 0;

  function has() { return typeof DisputeNative !== 'undefined'; }

  function request(method, args) {
    return new Promise(function (resolve) {
      if (!has()) { resolve({ ok: false, error: 'Нативный мост недоступен' }); return; }
      var id = 'r' + (++seq);
      pending[id] = resolve;
      try {
        DisputeNative[method].apply(DisputeNative, [id].concat(args || []));
      } catch (e) {
        delete pending[id];
        resolve({ ok: false, error: String(e) });
      }
    });
  }

  window.__onNativeResult = function (id, payload) {
    var resolve = pending[id];
    if (resolve) { delete pending[id]; resolve(payload || { ok: false, error: 'нет ответа' }); }
  };

  return {
    available: has,
    chat: function (slot, messages) { return request('chat', [JSON.stringify(slot), JSON.stringify(messages)]); },
    test: function (slot) { return request('test', [JSON.stringify(slot)]); },
    loadSettings: function () { return has() ? DisputeNative.loadSettings() : ''; },
    saveSettings: function (json) { if (has()) DisputeNative.saveSettings(json); },
    loadHistory: function () { return has() ? DisputeNative.loadHistory() : ''; },
    saveHistory: function (json) { if (has()) DisputeNative.saveHistory(json); },
    clearApiKey: function (slotId) { if (has()) DisputeNative.clearApiKey(slotId); },
    clearSecrets: function () { if (has()) DisputeNative.clearSecrets(); },
    toast: function (text) { if (has()) DisputeNative.toast(text); }
  };
})();

function settingsSnapshot() {
  var slots = {};
  SLOT_IDS.forEach(function (id) {
    var s = State.slots[id];
    slots[id] = {
      id: id,
      name: s.name,
      enabled: !!s.enabled,
      provider: s.provider,
      baseUrl: s.baseUrl,
      model: s.model,
      apiKey: s.apiKey || '',
      hasApiKey: !!s.hasApiKey,
      system: s.system,
      temperature: Number(s.temperature),
      timeoutSec: Number(s.timeoutSec)
    };
  });
  return { slots: slots, rounds: State.rounds, ctxLimit: State.ctxLimit, nextId: State.nextId };
}

function saveState() {
  Native.saveSettings(JSON.stringify(settingsSnapshot()));
  SLOT_IDS.forEach(function (id) {
    var s = State.slots[id];
    if (s.apiKey) s.hasApiKey = true;
    s.apiKey = '';
  });
  Native.saveHistory(JSON.stringify(State.messages));
}

function ensureAttachmentIds() {
  State.messages.forEach(function (m) {
    (m.attachments || []).forEach(function (a) {
      if (!a.id) a.id = makeAttachmentId();
    });
  });
}

function loadState() {
  var raw = Native.loadSettings();
  if (raw) {
    try {
      var data = JSON.parse(raw);
      if (data.slots) {
        SLOT_IDS.forEach(function (id) {
          if (data.slots[id]) {
            State.slots[id] = Object.assign(defaultSlot(id, State.slots[id].name), data.slots[id]);
            State.slots[id].apiKey = '';
          }
        });
      }
      if (data.rounds) State.rounds = data.rounds;
      if (data.ctxLimit) State.ctxLimit = data.ctxLimit;
      if (data.nextId) State.nextId = data.nextId;
    } catch (e) {}
  }
  var hist = Native.loadHistory();
  if (hist) {
    try {
      var msgs = JSON.parse(hist);
      if (Array.isArray(msgs)) State.messages = msgs;
    } catch (e) {}
  }
  ensureAttachmentIds();
}

function resetAll() {
  Native.clearSecrets();
  State.slots = { a: defaultSlot('a', 'Модель A'), b: defaultSlot('b', 'Модель B') };
  State.messages = [];
  State.rounds = 3;
  State.ctxLimit = 40;
  State.lastAuthor = null;
  State.nextId = 1;
  State.pendingAttachments = [];
  saveState();
}

function makeAttachmentId() {
  return 'f' + Date.now().toString(36) + Math.random().toString(36).slice(2, 7);
}

function allAttachments() {
  var out = [];
  State.messages.forEach(function (m) {
    (m.attachments || []).forEach(function (a) {
      if (!a.id) a.id = makeAttachmentId();
      out.push(a);
    });
  });
  return out;
}

function findAttachment(id) {
  var list = allAttachments();
  for (var i = 0; i < list.length; i++) if (list[i].id === id) return list[i];
  return null;
}

function attachmentKindLabel(a) {
  if (a.kind === 'image') return 'изображение';
  if (a.kind === 'pdf') return 'PDF';
  return 'текстовый файл';
}

function $(id) { return document.getElementById(id); }

function slotName(id) {
  var s = State.slots[id];
  var n = (s && s.name || '').trim();
  return n || (id === 'a' ? 'Модель A' : 'Модель B');
}

function slotReady(id) {
  var s = State.slots[id];
  return !!(s && s.enabled && s.baseUrl && s.model);
}

function fmtTime(ts) {
  var d = new Date(ts);
  return String(d.getHours()).padStart(2, '0') + ':' + String(d.getMinutes()).padStart(2, '0');
}

function fmtDay(ts) {
  var d = new Date(ts);
  var today = new Date();
  if (d.toDateString() === today.toDateString()) return 'Сегодня';
  var y = new Date(today.getTime() - 86400000);
  if (d.toDateString() === y.toDateString()) return 'Вчера';
  return d.toLocaleDateString('ru-RU', { day: 'numeric', month: 'long' });
}

window.__setInsets = function (top, bottom) {
  document.documentElement.style.setProperty('--safe-top', Math.max(0, Number(top) || 0) + 'px');
  document.documentElement.style.setProperty('--safe-bottom', Math.max(0, Number(bottom) || 0) + 'px');
};

function status(text) { $('statusLine').textContent = text || ''; }
