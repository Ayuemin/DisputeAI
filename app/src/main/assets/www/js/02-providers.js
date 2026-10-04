/**
 * Каталог поставщиков. Приложение само ничего не хостит: пользователь указывает
 * адрес провайдера, ключ и название модели. Два слота настраиваются независимо,
 * поэтому у них могут быть разные поставщики, модели и даже тарифы.
 */
'use strict';

var PROVIDERS = [
  {
    id: 'openai',
    label: 'OpenAI-совместимый',
    hint: 'OpenAI, Groq, Together, DeepSeek, Mistral, LM Studio, vLLM, llama.cpp, YandexGPT…',
    urlLabel: 'Базовый адрес',
    urlHint: 'например https://api.openai.com/v1 — к /chat/completions приложение добавит само',
    needsKey: true
  },
  {
    id: 'anthropic',
    label: 'Anthropic (Claude)',
    hint: 'Официальный API Claude.',
    urlLabel: 'Базовый адрес',
    urlHint: 'https://api.anthropic.com — используется /v1/messages',
    needsKey: true
  },
  {
    id: 'gemini',
    label: 'Google Gemini',
    hint: 'Официальный API Gemini, ключ передаётся в адресе.',
    urlLabel: 'Базовый адрес',
    urlHint: 'https://generativelanguage.googleapis.com — добавится /v1beta',
    needsKey: true
  },
  {
    id: 'ollama',
    label: 'Локальный сервер (Ollama / LM Studio / llama.cpp)',
    hint: 'Модель на этом же телефоне или в локальной сети.',
    urlLabel: 'Адрес сервера',
    urlHint: 'http://127.0.0.1:11434/v1 — для Ollama; LM Studio: http://127.0.0.1:1234/v1',
    needsKey: false
  }
];

function providerById(id) {
  for (var i = 0; i < PROVIDERS.length; i++) if (PROVIDERS[i].id === id) return PROVIDERS[i];
  return PROVIDERS[0];
}

var PRESETS = [
  { label: 'OpenRouter', apply: { provider: 'openai', baseUrl: 'https://openrouter.ai/api/v1' } },
  { label: 'OpenAI', apply: { provider: 'openai', baseUrl: 'https://api.openai.com/v1', model: 'gpt-4o-mini' } },
  { label: 'Groq', apply: { provider: 'openai', baseUrl: 'https://api.groq.com/openai/v1', model: 'llama-3.3-70b-versatile' } },
  { label: 'Together', apply: { provider: 'openai', baseUrl: 'https://api.together.xyz/v1', model: 'meta-llama/Llama-3.3-70B-Instruct-Turbo' } },
  { label: 'DeepSeek', apply: { provider: 'openai', baseUrl: 'https://api.deepseek.com/v1', model: 'deepseek-chat' } },
  { label: 'Mistral', apply: { provider: 'openai', baseUrl: 'https://api.mistral.ai/v1', model: 'mistral-small-latest' } },
  { label: 'Claude', apply: { provider: 'anthropic', baseUrl: 'https://api.anthropic.com', model: 'claude-sonnet-4-5' } },
  { label: 'Gemini', apply: { provider: 'gemini', baseUrl: 'https://generativelanguage.googleapis.com', model: 'gemini-2.0-flash' } },
  { label: 'YandexGPT', apply: { provider: 'openai', baseUrl: 'https://yandex.ru/foundation_models/v1', model: 'yandexgpt/latest' } },
  { label: 'Ollama', apply: { provider: 'ollama', baseUrl: 'http://127.0.0.1:11434/v1', model: 'llama3.2' } },
  { label: 'LM Studio', apply: { provider: 'ollama', baseUrl: 'http://127.0.0.1:1234/v1', model: 'local-model' } },
  { label: 'llama.cpp', apply: { provider: 'ollama', baseUrl: 'http://127.0.0.1:8080/v1', model: 'local-model' } }
];
