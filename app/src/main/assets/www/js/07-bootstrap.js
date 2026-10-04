/** Инициализация интерфейса DisputeAI. */
'use strict';

(function () {
  function initCycleControls() {
    $('roundsInput').value = State.rounds;
    $('roundsInput').addEventListener('change', function () {
      State.rounds = Math.max(1, Math.min(100, Number(this.value) || 1));
      this.value = State.rounds;
      saveState();
    });
    $('btnPause').addEventListener('click', togglePause);
    $('btnStop').addEventListener('click', stopTurns);
    updateCycleUi();
  }

  function renderPendingAttachments() {
    var bar = $('attachmentBar');
    bar.innerHTML = '';
    State.pendingAttachments.forEach(function (a, i) {
      var chip = document.createElement('div');
      chip.className = 'attach-chip';
      var name = document.createElement('span');
      name.textContent = a.name;
      var x = document.createElement('button');
      x.type = 'button';
      x.textContent = '×';
      x.setAttribute('aria-label', 'Убрать ' + a.name);
      x.addEventListener('click', function () {
        State.pendingAttachments.splice(i, 1);
        renderPendingAttachments();
      });
      chip.appendChild(name);
      chip.appendChild(x);
      bar.appendChild(chip);
    });
    bar.hidden = !State.pendingAttachments.length;
  }

  function ext(name) {
    var p = String(name || '').toLowerCase().split('.');
    return p.length > 1 ? p.pop() : '';
  }

  function isTextFile(file) {
    var e = ext(file.name);
    return (file.type && file.type.indexOf('text/') === 0) || file.type === 'application/json' ||
      ['txt','md','markdown','csv','log','json','xml','yaml','yml','js','ts','py','java','kt','kts','c','cpp','h','hpp','css','html','htm','fb2'].indexOf(e) >= 0;
  }

  function addPickedFile(file) {
    var maxText = 5 * 1024 * 1024;
    var maxBinary = 20 * 1024 * 1024;
    var base = { id: makeAttachmentId(), name: file.name, type: file.type || 'application/octet-stream', size: file.size || 0 };

    if (isTextFile(file)) {
      if (file.size > maxText) { Native.toast('Текстовый файл слишком большой: ' + file.name); return; }
      var r = new FileReader();
      r.onload = function () {
        State.pendingAttachments.push(Object.assign(base, { kind: 'text', text: String(r.result || '') }));
        renderPendingAttachments();
      };
      r.onerror = function () { Native.toast('Не удалось прочитать ' + file.name); };
      r.readAsText(file);
      return;
    }
    if ((file.type || '').indexOf('image/') === 0) {
      if (file.size > maxBinary) { Native.toast('Изображение слишком большое: ' + file.name); return; }
      var ri = new FileReader();
      ri.onload = function () {
        State.pendingAttachments.push(Object.assign(base, { kind: 'image', dataUrl: String(ri.result || '') }));
        renderPendingAttachments();
      };
      ri.onerror = function () { Native.toast('Не удалось прочитать ' + file.name); };
      ri.readAsDataURL(file);
      return;
    }
    if (file.type === 'application/pdf' || ext(file.name) === 'pdf') {
      if (file.size > maxBinary) { Native.toast('PDF слишком большой: ' + file.name); return; }
      var rp = new FileReader();
      rp.onload = function () {
        State.pendingAttachments.push(Object.assign(base, { type: 'application/pdf', kind: 'pdf', dataUrl: String(rp.result || '') }));
        renderPendingAttachments();
      };
      rp.onerror = function () { Native.toast('Не удалось прочитать ' + file.name); };
      rp.readAsDataURL(file);
      return;
    }
    Native.toast('Поддерживаются текстовые файлы, изображения и PDF.');
  }

  function initComposer() {
    var input = $('input');
    var picker = $('fileInput');
    $('btnAttach').addEventListener('click', function () {
      if (!this.disabled) picker.click();
    });
    picker.addEventListener('change', function () {
      Array.prototype.slice.call(picker.files || []).forEach(addPickedFile);
      picker.value = '';
    });
    input.addEventListener('input', function () {
      this.style.height = 'auto';
      this.style.height = Math.min(140, this.scrollHeight) + 'px';
    });
    input.addEventListener('keydown', function (e) {
      if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); submit(); }
    });
    $('btnSend').addEventListener('click', submit);
  }

  function submit() {
    var input = $('input');
    var text = input.value;
    var attachments = State.pendingAttachments.slice();
    if (!text.trim() && !attachments.length) return;
    if (running || (cycleActive && !cyclePaused)) return;
    input.value = '';
    input.style.height = 'auto';
    State.pendingAttachments = [];
    renderPendingAttachments();
    sendUser(text, attachments);
  }

  function initSheets() {
    $('btnSettings').addEventListener('click', function () { openSheet('sheetSettings'); });
    $('btnMenu').addEventListener('click', function () { openSheet('sheetMenu'); });
    document.querySelectorAll('[data-close]').forEach(function (btn) {
      btn.addEventListener('click', function () { closeSheet(btn.dataset.close); });
    });
    document.querySelectorAll('.sheet').forEach(function (sheet) {
      sheet.addEventListener('click', function (e) { if (e.target === sheet) sheet.hidden = true; });
    });
    $('btnSaveSettings').addEventListener('click', function () {
      collectSettings();
      closeSheet('sheetSettings');
      renderAll();
      renderSpeakers();
    });
    $('btnResetAll').addEventListener('click', function () {
      resetAll();
      finishCycle('');
      closeSheet('sheetSettings');
      renderAll(); renderSpeakers(); renderPendingAttachments();
      $('roundsInput').value = State.rounds;
    });
    $('btnResetAll2').addEventListener('click', function () {
      resetAll();
      finishCycle('');
      closeSheet('sheetMenu');
      renderAll(); renderSpeakers(); renderPendingAttachments();
      $('roundsInput').value = State.rounds;
    });
    $('btnClear').addEventListener('click', function () {
      State.messages = [];
      State.pendingAttachments = [];
      finishCycle('');
      saveState();
      renderAll(); renderPendingAttachments();
      closeSheet('sheetMenu');
      status('Чат и вложения очищены.');
    });
    $('btnExport').addEventListener('click', function () {
      var text = State.messages.map(function (m) {
        var who = m.author === 'user' ? 'Пользователь' : slotName(m.author);
        var files = (m.attachments || []).map(function (a) { return '[Вложение: ' + a.name + ']'; }).join('\n');
        return '[' + fmtTime(m.ts) + '] ' + who + ':\n' + (m.text || '') + (files ? '\n' + files : '');
      }).join('\n\n');
      if (navigator.clipboard) navigator.clipboard.writeText(text).then(function () { Native.toast('Переписка скопирована'); });
      closeSheet('sheetMenu');
    });
  }

  function initSettingsForm() {
    $('settingsBody').addEventListener('click', function (e) {
      var clear = e.target.closest ? e.target.closest('[data-clear-key]') : null;
      if (clear) { clearSavedKey(clear.dataset.clearKey); return; }

      var testBtn = e.target.closest ? e.target.closest('[data-test]') : null;
      if (testBtn) { testSlot(testBtn.dataset.test); return; }

      var preset = e.target.closest ? e.target.closest('[data-preset]') : null;
      if (preset) {
        var id = preset.dataset.slot;
        var p = PRESETS[Number(preset.dataset.preset)];
        if (p) {
          collectSettings();
          Object.assign(State.slots[id], p.apply);
          saveState();
          renderSettings();
          renderSpeakers();
        }
      }
    });
    $('settingsBody').addEventListener('change', function (e) {
      var el = e.target;
      if (el.dataset && el.dataset.f === 'provider') {
        collectSettings(); renderSettings(); renderSpeakers();
      } else if (el.dataset && el.dataset.f && el.dataset.f !== 'apiKey') {
        collectSettings(); renderSpeakers();
      }
    });
  }

  function blockExternalNavigation() {
    document.addEventListener('click', function (e) {
      var a = e.target.closest ? e.target.closest('a[href]') : null;
      if (a) e.preventDefault();
    }, true);
  }

  function boot() {
    loadState();
    renderAll();
    renderSpeakers();
    initCycleControls();
    initComposer();
    renderPendingAttachments();
    initSheets();
    initSettingsForm();
    blockExternalNavigation();
    updateComposerLock();
    if (!Native.available()) status('Нативный мост недоступен.');
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', boot);
  else boot();
})();
