/**
 * Контекст и вызов модели.
 * Тяжёлое содержимое вложений не включается в обычный контекст. Модель видит
 * каталог и при необходимости запрашивает конкретные файлы командой
 * [[DISPUTEAI_FILE:<id>]].
 */
'use strict';

function keyWouldUseCleartext(slot) {
  return /^http:\/\//i.test(String(slot.baseUrl || '').trim()) && !!slot.hasApiKey;
}

function buildSlotPayload(id) {
  var s = State.slots[id];
  return {
    id: id,
    provider: s.provider,
    baseUrl: (s.baseUrl || '').trim(),
    model: (s.model || '').trim(),
    hasApiKey: !!s.hasApiKey,
    system: buildSystem(id),
    temperature: Number(s.temperature),
    timeoutSec: Number(s.timeoutSec)
  };
}

function attachmentCatalog() {
  var list = allAttachments();
  if (!list.length) return '';
  var lines = list.map(function (a) {
    return '- ID=' + a.id + ' · ' + a.name + ' · ' + attachmentKindLabel(a);
  });
  return '\n\nВ этом чате есть вложения. Их содержимое НЕ передаётся тебе автоматически.\n' +
    'Доступные вложения:\n' + lines.join('\n') + '\n' +
    'Если для ответа действительно нужно содержимое файла, ответь ТОЛЬКО командой ' +
    '[[DISPUTEAI_FILE:ID]]. Для нескольких файлов: [[DISPUTEAI_FILE:ID1,ID2]]. ' +
    'Приложение передаст только запрошенные вложения на один следующий вызов, после чего ты продолжишь ответ.';
}

function buildSystem(id) {
  var s = State.slots[id];
  var otherId = id === 'a' ? 'b' : 'a';
  var header = 'Ты — ' + slotName(id) + '. В одном чате участвуют пользователь и ' +
    slotName(otherId) + '. Это общий разговор. Все пользовательские реплики и ответы второй модели в истории видны тебе. ' +
    'Не выдумывай реплики за других: отвечай только от себя.';
  var own = (s.system || '').trim();
  return header + (own ? '\n\n' + own : '') + attachmentCatalog();
}

function attachmentMeta(a) {
  return '[Вложение: ' + a.name + '; ID=' + a.id + '; ' + attachmentKindLabel(a) + '; содержимое доступно по запросу]';
}

function buildMessages(id, limit) {
  var msgs = State.messages.filter(function (m) {
    return !m.error && ((m.text && m.text.trim()) || (m.attachments && m.attachments.length));
  });
  if (limit && msgs.length > limit) msgs = msgs.slice(msgs.length - limit);

  var out = [];
  msgs.forEach(function (m) {
    var role = m.author === id ? 'assistant' : 'user';
    var who = m.author === 'user' ? 'Пользователь' : slotName(m.author);
    var prefix = m.author === id ? '' : (who + ': ');
    var text = prefix + (m.text || '');
    if (m.attachments && m.attachments.length) {
      var metas = m.attachments.map(attachmentMeta).join('\n');
      text += (text ? '\n' : prefix) + metas;
    }

    var last = out[out.length - 1];
    if (last && last.role === role && typeof last.content === 'string') last.content += '\n\n' + text;
    else out.push({ role: role, content: text });
  });

  return out.length ? out : [{ role: 'user', content: 'Начни разговор.' }];
}

function parseAttachmentRequest(text) {
  var ids = [];
  var re = /\[\[DISPUTEAI_FILE:([^\]]+)\]\]/gi;
  var m;
  while ((m = re.exec(String(text || '')))) {
    m[1].split(',').forEach(function (x) {
      x = x.trim();
      if (x && ids.indexOf(x) < 0) ids.push(x);
    });
  }
  return ids;
}

function requestedAttachmentMessage(ids) {
  var content = [{ type: 'text', text: 'Вот содержимое запрошенных вложений. Используй его для ответа. Не повторяй команду вызова файла, если других файлов больше не требуется.' }];
  ids.forEach(function (id) {
    var a = findAttachment(id);
    if (!a) {
      content.push({ type: 'text', text: '\n[Вложение ID=' + id + ' не найдено]' });
      return;
    }
    if (a.kind === 'text') {
      content.push({ type: 'text', text: '\n\n[Файл ' + a.name + '; ID=' + a.id + ']\n' + (a.text || '') });
    } else if (a.kind === 'image' && a.dataUrl) {
      content.push({ type: 'text', text: '\n[Изображение ' + a.name + '; ID=' + a.id + ']' });
      content.push({ type: 'image_url', image_url: { url: a.dataUrl } });
    } else if (a.kind === 'pdf' && a.dataUrl) {
      content.push({ type: 'text', text: '\n[PDF ' + a.name + '; ID=' + a.id + ']' });
      content.push({ type: 'file', file: { filename: a.name, file_data: a.dataUrl } });
    }
  });
  return { role: 'user', content: content };
}

function finishWasLength(reason) {
  reason = String(reason || '').toLowerCase();
  return reason === 'length' || reason === 'max_tokens' || reason === 'max_output_tokens' || reason === 'max_tokens_reached';
}

function mergeUsage(total, next) {
  if (!next || typeof next !== 'object') return total;
  total = total || {};
  Object.keys(next).forEach(function (k) {
    if (typeof next[k] === 'number') total[k] = (Number(total[k]) || 0) + next[k];
    else if (total[k] == null) total[k] = next[k];
  });
  return total;
}

function askModel(id) {
  var slot = buildSlotPayload(id);
  if (!slot.baseUrl || !slot.model) {
    return Promise.resolve({ ok: false, error: slotName(id) + ' не настроена: нужен адрес и ID модели.' });
  }
  if (keyWouldUseCleartext(slot)) {
    return Promise.resolve({ ok: false, error: 'API-ключ не будет отправлен по HTTP. Для адреса с ключом нужен HTTPS.' });
  }

  var started = Date.now();
  var usage = {};
  var base = buildMessages(id, State.ctxLimit);

  function call(messages, accumulated, fileHops, continuationHops) {
    return Native.chat(slot, messages).then(function (res) {
      if (!res || !res.ok) return res || { ok: false, error: 'Нет ответа API' };
      usage = mergeUsage(usage, res.usage);
      var text = String(res.text || '');
      var requested = parseAttachmentRequest(text);

      if (requested.length) {
        if (fileHops >= 12) return { ok: false, error: 'Модель слишком много раз подряд запрашивает вложения.' };
        var next = messages.slice();
        next.push({ role: 'assistant', content: text });
        next.push(requestedAttachmentMessage(requested));
        return call(next, accumulated, fileHops + 1, continuationHops);
      }

      var full = accumulated ? (accumulated + (text ? '\n' + text : '')) : text;
      if (finishWasLength(res.finishReason)) {
        if (continuationHops >= 128) {
          return { ok: true, text: full, ms: Date.now() - started, usage: usage, finishReason: 'safety_stop' };
        }
        var cont = messages.slice();
        cont.push({ role: 'assistant', content: text });
        cont.push({ role: 'user', content: 'Продолжи ответ точно с места обрыва. Не повторяй уже написанное.' });
        return call(cont, full, fileHops, continuationHops + 1);
      }

      return { ok: true, text: full, ms: Date.now() - started, usage: usage, finishReason: res.finishReason };
    });
  }

  return call(base, '', 0, 0);
}
