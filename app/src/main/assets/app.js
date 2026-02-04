// State
let selectedModel = '';
let isStreaming = false;
let conversationHistory = [];
let webSearchEnabled = false;
let braveApiKey = '';

// DOM Elements
const chatWindow = document.getElementById('chat-window');
const chatInput = document.getElementById('chat-input');
const sendButton = document.getElementById('send-button');
const modelButton = document.getElementById('model-button');
const modelName = document.getElementById('model-name');
const modelMenu = document.getElementById('model-menu');
const modelsPanel = document.getElementById('models-panel');
const modelsList = document.getElementById('models-list');
const ollamaRunner = document.getElementById('ollama-runner');
const statusButton = document.getElementById('status-button');
const statusMenu = document.getElementById('status-menu');
const statusDot = document.getElementById('status-dot');
const ollamaToggle = document.getElementById('ollama-toggle');
const braveToggle = document.getElementById('brave-toggle');
const settingsBtn = document.getElementById('settings-btn');
const menuBackdrop = document.getElementById('menu-backdrop');
const addModelTrigger = document.getElementById('add-model-trigger');
const addModelRow = document.getElementById('add-model-row');
const addModelInput = document.getElementById('add-model-input');
const pullModelBtn = document.getElementById('pull-model-btn');
const cancelPullBtn = document.getElementById('cancel-pull-btn');
const pullStatus = document.getElementById('pull-status');
const downloadProgress = document.getElementById('download-progress');

// Initialize
document.addEventListener('DOMContentLoaded', () => {
    setupEventListeners();
    loadModels();
    checkOllamaStatus();
    chatInput.focus();
});

// Load preferences from Android
window.loadPreferences = function(prefs) {
    if (prefs.model) {
        selectedModel = prefs.model;
        modelName.textContent = prefs.model.split(':')[0] || 'Select Model';
    }
    if (prefs.theme) {
        setTheme(prefs.theme);
    }
    if (prefs.braveApiKey) {
        braveApiKey = prefs.braveApiKey;
    }
    if (prefs.webSearchEnabled) {
        webSearchEnabled = prefs.webSearchEnabled;
        braveToggle.checked = prefs.webSearchEnabled;
    }
    updateStatusDot();
};

function setupEventListeners() {
    // Send message
    sendButton.addEventListener('click', sendMessage);
    chatInput.addEventListener('keydown', (e) => {
        if (e.key === 'Enter' && !e.shiftKey) {
            e.preventDefault();
            sendMessage();
        }
    });

    // Auto-resize textarea
    chatInput.addEventListener('input', () => {
        chatInput.style.height = 'auto';
        chatInput.style.height = Math.min(chatInput.scrollHeight, 120) + 'px';
    });

    // Model dropdown
    modelButton.addEventListener('click', (e) => {
        e.stopPropagation();
        toggleModelMenu();
    });

    ollamaRunner.addEventListener('mouseenter', () => {
        modelsPanel.classList.remove('hidden');
    });

    ollamaRunner.addEventListener('mouseleave', (e) => {
        if (!modelsPanel.contains(e.relatedTarget)) {
            modelsPanel.classList.add('hidden');
        }
    });

    modelsPanel.addEventListener('mouseleave', (e) => {
        if (!ollamaRunner.contains(e.relatedTarget)) {
            modelsPanel.classList.add('hidden');
        }
    });

    // Status dropdown
    statusButton.addEventListener('click', (e) => {
        e.stopPropagation();
        toggleStatusMenu();
    });

    // Ollama toggle
    ollamaToggle.addEventListener('change', () => {
        if (ollamaToggle.checked) {
            Android.startOllama();
        } else {
            Android.stopOllama();
        }
        setTimeout(checkOllamaStatus, 2000);
    });

    // Brave search toggle
    braveToggle.addEventListener('change', () => {
        webSearchEnabled = braveToggle.checked;
        Android.saveBoolPreference('web_search_enabled', webSearchEnabled);
        updateStatusDot();
    });

    // Settings button
    settingsBtn.addEventListener('click', () => {
        closeAllMenus();
        Android.openSettings();
    });

    // Add model
    addModelTrigger.addEventListener('click', () => {
        addModelTrigger.classList.add('hidden');
        addModelRow.classList.remove('hidden');
        addModelInput.focus();
    });

    cancelPullBtn.addEventListener('click', () => {
        addModelRow.classList.add('hidden');
        addModelTrigger.classList.remove('hidden');
        addModelInput.value = '';
        pullStatus.classList.add('hidden');
    });

    pullModelBtn.addEventListener('click', pullModel);

    addModelInput.addEventListener('keydown', (e) => {
        if (e.key === 'Enter') {
            pullModel();
        }
    });

    // Close menus on backdrop click
    menuBackdrop.addEventListener('click', closeAllMenus);

    // Close menus on outside click
    document.addEventListener('click', (e) => {
        if (!modelMenu.contains(e.target) && !modelButton.contains(e.target)) {
            modelMenu.classList.add('hidden');
            modelsPanel.classList.add('hidden');
        }
        if (!statusMenu.contains(e.target) && !statusButton.contains(e.target)) {
            statusMenu.classList.add('hidden');
        }
    });
}

function toggleModelMenu() {
    const isHidden = modelMenu.classList.contains('hidden');
    closeAllMenus();
    if (isHidden) {
        modelMenu.classList.remove('hidden');
        menuBackdrop.classList.remove('hidden');
    }
}

function toggleStatusMenu() {
    const isHidden = statusMenu.classList.contains('hidden');
    closeAllMenus();
    if (isHidden) {
        statusMenu.classList.remove('hidden');
        menuBackdrop.classList.remove('hidden');
    }
}

function closeAllMenus() {
    modelMenu.classList.add('hidden');
    modelsPanel.classList.add('hidden');
    statusMenu.classList.add('hidden');
    menuBackdrop.classList.add('hidden');
}

function setTheme(theme) {
    document.body.dataset.theme = theme;
}

function checkOllamaStatus() {
    try {
        const isRunning = Android.checkOllamaStatus();
        ollamaToggle.checked = isRunning;
        updateStatusDot();
    } catch (e) {
        console.error('Failed to check Ollama status:', e);
    }
}

function updateStatusDot() {
    const ollamaRunning = ollamaToggle.checked;
    const searchEnabled = webSearchEnabled;
    const hasToken = braveApiKey && braveApiKey.length > 0;

    if (!ollamaRunning) {
        statusDot.className = 'status-dot status-red';
    } else if (searchEnabled && !hasToken) {
        statusDot.className = 'status-dot status-yellow';
    } else {
        statusDot.className = 'status-dot status-green';
    }
}

function loadModels() {
    modelsList.innerHTML = '<div class="loading-models">Loading...</div>';
    try {
        Android.getModels();
    } catch (e) {
        modelsList.innerHTML = '<div class="error-models">Failed to load models</div>';
    }
}

window.onModelsLoaded = function(jsonStr) {
    try {
        const data = JSON.parse(jsonStr);
        const models = data.models || [];

        if (models.length === 0) {
            modelsList.innerHTML = '<div class="no-models">No models installed</div>';
            return;
        }

        modelsList.innerHTML = models.map(model => {
            const name = model.name;
            const isCloud = name.includes(':cloud');
            const warning = isCloud ? '<span class="cloud-warning" title="Cloud models not supported at this time">⚠️</span>' : '';
            return `
                <div class="model-option-row">
                    <div class="model-option" data-model="${name}">${name}${warning}</div>
                    <button class="model-delete-btn" data-model="${name}" title="Delete model">🗑</button>
                </div>
            `;
        }).join('');

        // Add click handlers
        modelsList.querySelectorAll('.model-option').forEach(opt => {
            opt.addEventListener('click', () => {
                const model = opt.dataset.model;
                if (model.includes(':cloud')) {
                    alert('Cloud models are not supported at this time');
                    return;
                }
                selectModel(model);
            });
        });

        modelsList.querySelectorAll('.model-delete-btn').forEach(btn => {
            btn.addEventListener('click', (e) => {
                e.stopPropagation();
                const model = btn.dataset.model;
                if (confirm(`Delete model "${model}"?`)) {
                    deleteModel(model);
                }
            });
        });
    } catch (e) {
        console.error('Failed to parse models:', e);
        modelsList.innerHTML = '<div class="error-models">Failed to load models</div>';
    }
};

window.onModelsError = function(error) {
    modelsList.innerHTML = '<div class="error-models">Ollama not running</div>';
};

function selectModel(model) {
    selectedModel = model;
    modelName.textContent = model.split(':')[0];
    Android.savePreference('selected_model', model);
    closeAllMenus();
}

function deleteModel(model) {
    Android.deleteModel(model);
}

window.onModelDeleted = function() {
    loadModels();
};

window.onDeleteError = function(error) {
    alert('Failed to delete model: ' + error);
};

function pullModel() {
    const name = addModelInput.value.trim();
    if (!name) return;

    pullModelBtn.disabled = true;
    pullStatus.classList.remove('hidden');
    pullStatus.textContent = 'Starting download...';

    Android.pullModel(name);
}

window.onPullProgress = function(jsonStr) {
    try {
        const data = JSON.parse(jsonStr);
        if (data.status) {
            let status = data.status;
            if (data.completed && data.total) {
                const percent = Math.round((data.completed / data.total) * 100);
                status += ` ${percent}%`;
            }
            pullStatus.textContent = status;
        }
        if (data.status === 'success') {
            pullStatus.textContent = 'Download complete!';
            setTimeout(() => {
                addModelRow.classList.add('hidden');
                addModelTrigger.classList.remove('hidden');
                addModelInput.value = '';
                pullStatus.classList.add('hidden');
                pullModelBtn.disabled = false;
                loadModels();
            }, 1500);
        }
    } catch (e) {
        pullStatus.textContent = jsonStr;
    }
};

window.onPullError = function(error) {
    pullStatus.textContent = 'Error: ' + error;
    pullModelBtn.disabled = false;
};

async function sendMessage() {
    const text = chatInput.value.trim();
    if (!text || isStreaming) return;

    if (!selectedModel) {
        alert('Please select a model first');
        return;
    }

    // Add user message
    addMessage(text, 'user');
    chatInput.value = '';
    chatInput.style.height = 'auto';

    // Build prompt with history
    conversationHistory.push({ role: 'user', content: text });
    let prompt = buildPrompt();

    // Web search if enabled
    if (webSearchEnabled && braveApiKey) {
        const searchQuery = extractSearchQuery(text);
        if (searchQuery) {
            addThinkingMessage();
            try {
                const searchResults = await performWebSearch(searchQuery);
                if (searchResults) {
                    prompt = `Web search results for "${searchQuery}":\n${searchResults}\n\nUser question: ${text}\n\nPlease answer based on the search results above.`;
                }
            } catch (e) {
                console.error('Search failed:', e);
            }
        }
    }

    // Start streaming
    isStreaming = true;
    sendButton.disabled = true;

    const aiMessage = addMessage('', 'ai');
    const thinkingEl = document.querySelector('.thinking');
    if (thinkingEl) thinkingEl.remove();

    currentAiMessage = aiMessage;
    currentAiText = '';

    Android.sendMessage(selectedModel, prompt);
}

let currentAiMessage = null;
let currentAiText = '';

window.onStreamChunk = function(text, done) {
    if (currentAiMessage) {
        currentAiText += text;
        renderMarkdown(currentAiMessage, currentAiText);
        scrollToBottom();
    }

    if (done) {
        isStreaming = false;
        sendButton.disabled = false;
        conversationHistory.push({ role: 'assistant', content: currentAiText });
        currentAiMessage = null;
        currentAiText = '';
        chatInput.focus();
    }
};

window.onStreamError = function(error) {
    isStreaming = false;
    sendButton.disabled = false;
    if (currentAiMessage) {
        currentAiMessage.innerHTML = '<span class="msg-prefix">AI:</span> <span style="color: var(--error);">[Error: ' + error + ']</span>';
    }
    currentAiMessage = null;
    currentAiText = '';
    chatInput.focus();
};

function addMessage(text, role) {
    const bubble = document.createElement('div');
    bubble.className = `chat-bubble ${role}-bubble`;

    if (role === 'user') {
        bubble.innerHTML = `<span class="msg-prefix">You:</span> ${escapeHtml(text)}`;
    } else {
        bubble.innerHTML = `<div class="ai-message-content"><span class="msg-prefix">AI:</span> <span class="markdown-content"></span></div>`;
    }

    chatWindow.appendChild(bubble);
    scrollToBottom();
    return bubble.querySelector('.markdown-content') || bubble;
}

function addThinkingMessage() {
    const bubble = document.createElement('div');
    bubble.className = 'chat-bubble ai-bubble thinking';
    bubble.innerHTML = `
        <span class="msg-prefix">AI:</span>
        <span class="thinking-dots">
            <span class="thinking-dot"></span>
            <span class="thinking-dot"></span>
            <span class="thinking-dot"></span>
        </span>
        <span>Searching...</span>
    `;
    chatWindow.appendChild(bubble);
    scrollToBottom();
    return bubble;
}

function renderMarkdown(element, text) {
    if (typeof marked !== 'undefined') {
        element.innerHTML = marked.parse(text);
    } else {
        element.textContent = text;
    }
}

function buildPrompt() {
    return conversationHistory.map(msg => {
        if (msg.role === 'user') return `User: ${msg.content}`;
        return `Assistant: ${msg.content}`;
    }).join('\n\n') + '\n\nAssistant:';
}

function extractSearchQuery(text) {
    // Simple heuristic: if question contains certain keywords, search
    const searchTriggers = ['what is', 'who is', 'how to', 'when did', 'where is', 'latest', 'news', 'current', 'today', '2024', '2025', '2026'];
    const lower = text.toLowerCase();
    for (const trigger of searchTriggers) {
        if (lower.includes(trigger)) {
            return text;
        }
    }
    return null;
}

function performWebSearch(query) {
    return new Promise((resolve) => {
        window.onBraveSearchResult = function(jsonStr) {
            try {
                const data = JSON.parse(jsonStr);
                const results = data.web?.results || [];
                const formatted = results.slice(0, 5).map((r, i) =>
                    `${i + 1}. ${r.title}\n   ${r.description}\n   URL: ${r.url}`
                ).join('\n\n');
                resolve(formatted || null);
            } catch (e) {
                resolve(null);
            }
        };
        window.onBraveSearchError = function() {
            resolve(null);
        };
        Android.braveSearch(query, braveApiKey);
    });
}

function scrollToBottom() {
    chatWindow.scrollTop = chatWindow.scrollHeight;
}

function escapeHtml(text) {
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
}

// Periodic status check
setInterval(checkOllamaStatus, 30000);
