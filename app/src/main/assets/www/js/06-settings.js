/** Сворачиваемые настройки двух моделей и общие настройки. */
'use strict';

function openSheet(id) {
  if (id === 'sheetSettings') renderSettings();
  $(id).hidden = false;
}
function closeSheet(id) { $(id).hidden = true; }

function field(label, html, hint) {
  return '<div class="field"><label>' + label + '</label>' + html +
    (hint ? '<div class="hint">' + hint + '</div>' : '') + '</div>';
}

function esc(s) {
  return String(s == null ? '' : s).replace(/[&<>"]/g, function (c) {
    return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c];
  });
}

function keyField(id, s, p) {
  var saved = s.hasApiKey ? '<span class="key-saved">сохранён в Android Keystore</span>' : '<span class="key-empty">не сохранён</span>';
  var remove = s.hasApiKey ? '<button type="button" class="mini-danger" data-clear-key="' + id + '">Удалить ключ</button>' : '';
  return field('API-ключ' + (p.needsKey ? '' : ' (обычно не нужен)'),
    '<input type="password" autocomplete="off" autocapitalize="none" spellcheck="false" data-f="apiKey" data-slot="' + id + '" value="" placeholder="' + (s.hasApiKey ? '••••••••  (введите только для замены)' : 'sk-…') + '">' +
    '<div class="key-row">' + saved + remove + '</div>',
    'После сохранения ключ удаляется из WebView и хранится зашифрованно через Android Keystore. В историю и экспорт не попадает.');
}

function renderSettings() {
  var html = '<div class="security-note"><strong>Ключи защищены.</strong> После нажатия «Готово» API-ключ шифруется Android Keystore и больше не хранится в настройках WebView. Резервное копирование приложения отключено.</div>';

  SLOT_IDS.forEach(function (id) {
    var s = State.slots[id];
    var p = providerById(s.provider);
    var opts = PROVIDERS.map(function (x) {
      return '<option value="' + x.id + '"' + (x.id === s.provider ? ' selected' : '') + '>' + esc(x.label) + '</option>';
    }).join('');
    var presets = PRESETS.map(function (x, i) {
      return '<button type="button" class="preset" data-slot="' + id + '" data-preset="' + i + '">' + esc(x.label) + '</button>';
    }).join('');

    html += '<details class="slot-card" data-slot="' + id + '">';
    html += '<summary><span>' + (id === 'a' ? 'Модель A' : 'Модель B') + ' — ' + esc(slotName(id)) + '</span><span class="summary-state">' + (slotReady(id) ? 'готова' : 'не настроена') + '</span></summary>';
    html += '<div class="slot-content">';
    html += field('Название в чате', '<input type="text" data-f="name" data-slot="' + id + '" value="' + esc(s.name) + '">',
      'В шапке и сообщениях показывается только это имя. ID модели там не отображается.');
    html += field('Поставщик', '<select data-f="provider" data-slot="' + id + '">' + opts + '</select>', p.hint);
    html += '<div class="presets">' + presets + '</div>';
    html += field(p.urlLabel,
      '<input type="url" inputmode="url" autocomplete="off" autocapitalize="none" spellcheck="false" data-f="baseUrl" data-slot="' + id + '" value="' + esc(s.baseUrl) + '" placeholder="https://…">', p.urlHint);
    html += field('ID модели у поставщика',
      '<input type="text" autocomplete="off" autocapitalize="none" spellcheck="false" data-f="model" data-slot="' + id + '" value="' + esc(s.model) + '" placeholder="например deepseek/deepseek-v4.1-flash">');
    html += keyField(id, s, p);
    html += field('Системная инструкция',
      '<textarea data-f="system" data-slot="' + id + '">' + esc(s.system) + '</textarea>',
      'К ней автоматически добавляется описание участников и каталог доступных вложений.');
    html += '<div class="row2">' +
      field('Температура', '<input type="number" step="0.1" min="0" max="2" data-f="temperature" data-slot="' + id + '" value="' + esc(s.temperature) + '">') +
      field('Таймаут, с', '<input type="number" step="10" min="10" max="600" data-f="timeoutSec" data-slot="' + id + '" value="' + esc(s.timeoutSec) + '">') +
      '</div>';
    html += '<div class="switch"><input type="checkbox" data-f="enabled" data-slot="' + id + '"' + (s.enabled ? ' checked' : '') + '><span>Участвует в цикле</span></div>';
    html += '<div class="test-row"><button type="button" class="btn ghost" data-test="' + id + '">Проверить</button>' +
      '<span class="st" id="testStatus' + id + '"></span></div>';
    html += '</div></details>';
  });

  html += '<details class="common-card"><summary><span>Общие настройки</span></summary><div class="slot-content">';
  html += field('Сколько последних реплик отправлять моделям',
    '<input type="number" id="ctxLimit" min="4" max="500" step="2" value="' + (State.ctxLimit || 40) + '">',
    'Это ограничивает только повторную пересылку текста истории. Каталог всех вложений остаётся доступен до очистки чата.');
  html += '<div class="hint-block">Отдельного лимита длины ответа нет. Если поставщик сам завершает ответ по своему пределу, DisputeAI автоматически просит продолжение и склеивает части.</div>';
  html += '</div></details>';

  $('settingsBody').innerHTML = html;
}

function collectSettings() {
  $('settingsBody').querySelectorAll('[data-f]').forEach(function (el) {
    var id = el.dataset.slot;
    var key = el.dataset.f;
    var s = State.slots[id];
    if (!s) return;
    if (el.type === 'checkbox') s[key] = el.checked;
    else if (el.type === 'number') s[key] = Number(el.value);
    else s[key] = el.value;
  });
  var limit = $('ctxLimit');
  if (limit) State.ctxLimit = Math.max(4, Math.min(500, Number(limit.value) || 40));
  saveState();
  renderSpeakers();
}

function clearSavedKey(id) {
  Native.clearApiKey(id);
  State.slots[id].apiKey = '';
  State.slots[id].hasApiKey = false;
  saveState();
  renderSettings();
}

function testSlot(id) {
  collectSettings();
  renderSettings();
  var st = $('testStatus' + id);
  if (!st) return;
  var payload = buildSlotPayload(id);
  if (keyWouldUseCleartext(payload)) { st.textContent = 'С ключом используйте HTTPS'; return; }
  st.textContent = 'Проверяем…';
  Native.test(payload).then(function (res) {
    if (res && res.ok) st.textContent = 'Отвечает: ' + String(res.text).slice(0, 50);
    else st.textContent = (res && res.error) || 'Ошибка';
  });
}
