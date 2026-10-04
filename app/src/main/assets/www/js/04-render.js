/** Отрисовка истории и шапки. */
'use strict';

function escapeText(s) {
  return String(s == null ? '' : s);
}

function addMessage(author, text, meta) {
  var m = {
    id: State.nextId++,
    author: author,
    text: String(text == null ? '' : text),
    ts: Date.now()
  };
  if (meta) Object.keys(meta).forEach(function (k) { m[k] = meta[k]; });
  State.messages.push(m);
  State.lastAuthor = author;
  return m;
}

function makeFileChip(a) {
  var chip = document.createElement('div');
  chip.className = 'msg-file';
  var icon = a.kind === 'image' ? '🖼' : (a.kind === 'pdf' ? 'PDF' : '📄');
  chip.textContent = icon + '  ' + a.name;
  chip.title = 'ID: ' + a.id + '. Файл остаётся в истории и передаётся модели только по её запросу.';
  return chip;
}

function messageNode(m) {
  var wrap = document.createElement('div');
  wrap.className = 'msg' + (m.error ? ' err' : '');
  wrap.dataset.author = m.author;

  var who = document.createElement('div');
  who.className = 'who';
  who.textContent = m.author === 'user' ? 'Вы' : slotName(m.author);

  var bubble = document.createElement('div');
  bubble.className = 'bubble';
  bubble.textContent = escapeText(m.text);

  if (m.attachments && m.attachments.length) {
    var files = document.createElement('div');
    files.className = 'msg-files';
    m.attachments.forEach(function (a) { files.appendChild(makeFileChip(a)); });
    bubble.appendChild(files);
  }

  wrap.appendChild(who);
  wrap.appendChild(bubble);

  if (m.author !== 'user' && (m.ms || m.usage)) {
    var meta = document.createElement('div');
    meta.className = 'meta';
    var pieces = [];
    if (m.ms) pieces.push((m.ms / 1000).toFixed(1) + ' с');
    if (m.usage) {
      var total = m.usage.total_tokens || m.usage.totalTokenCount || m.usage.total_tokens_count;
      if (total) pieces.push(total + ' ток.');
    }
    meta.textContent = pieces.join(' · ');
    wrap.appendChild(meta);
  }
  return wrap;
}

function appendMessage(m) {
  var box = $('messages');
  var prev = State.messages.length > 1 ? State.messages[State.messages.length - 2] : null;
  if (!prev || fmtDay(prev.ts) !== fmtDay(m.ts)) {
    var sep = document.createElement('div');
    sep.className = 'daysep';
    sep.textContent = fmtDay(m.ts);
    box.appendChild(sep);
  }
  box.appendChild(messageNode(m));
  requestAnimationFrame(function () { box.scrollTop = box.scrollHeight; });
}

function renderAll() {
  var box = $('messages');
  box.innerHTML = '';
  var lastDay = '';
  State.messages.forEach(function (m) {
    var day = fmtDay(m.ts);
    if (day !== lastDay) {
      var sep = document.createElement('div');
      sep.className = 'daysep';
      sep.textContent = day;
      box.appendChild(sep);
      lastDay = day;
    }
    box.appendChild(messageNode(m));
  });
  requestAnimationFrame(function () { box.scrollTop = box.scrollHeight; });
}

function renderSpeakers() {
  document.querySelectorAll('.speaker').forEach(function (el) {
    var id = el.dataset.slot;
    el.querySelector('.nm').textContent = slotName(id);
    el.classList.toggle('not-ready', !slotReady(id));
    el.classList.toggle('busy', State.busy && currentRunningSlot === id);
  });
}

function showTyping(id) {
  currentRunningSlot = id;
  renderSpeakers();
  var wrap = document.createElement('div');
  wrap.className = 'msg typing-wrap';
  wrap.dataset.author = id;
  var who = document.createElement('div');
  who.className = 'who';
  who.textContent = slotName(id);
  var bubble = document.createElement('div');
  bubble.className = 'bubble';
  var t = document.createElement('span');
  t.className = 'typing';
  t.innerHTML = '<i></i><i></i><i></i>';
  bubble.appendChild(t);
  wrap.appendChild(who);
  wrap.appendChild(bubble);
  $('messages').appendChild(wrap);
  $('messages').scrollTop = $('messages').scrollHeight;
  return wrap;
}

function hideTyping(node) {
  if (node && node.parentNode) node.parentNode.removeChild(node);
  currentRunningSlot = null;
  renderSpeakers();
}

var currentRunningSlot = null;
