/* Speech to Text — web UI.
 *
 * Long operations (transcriptions, model loads) are server-side jobs: the page
 * uploads the file, gets a job id back right away, then polls the job with
 * short requests. A network hiccup or a page reload no longer loses the work:
 * polling retries, and the last job is resumed when the page reopens.
 */
'use strict';

const $ = (id) => document.getElementById(id);
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
const FINISHED = ['done', 'error', 'cancelled'];
const SPEAKER_COLORS = ['#60a5fa', '#f472b6', '#4ade80', '#facc15', '#c084fc', '#fb923c', '#2dd4bf', '#f87171'];
const PREFS_KEY = 'stt.prefs';
const ACTIVE_JOB_KEY = 'stt.activeJob';
const LAST_RESULT_KEY = 'stt.lastResultJob';

// ============================================================
// Storage (may be unavailable: private mode, blocked cookies)
// ============================================================

const store = {
    get(key, fallback = null) {
        try {
            const raw = localStorage.getItem(key);
            return raw === null ? fallback : JSON.parse(raw);
        } catch { return fallback; }
    },
    set(key, value) {
        try { localStorage.setItem(key, JSON.stringify(value)); } catch { /* ignore */ }
    },
    remove(key) {
        try { localStorage.removeItem(key); } catch { /* ignore */ }
    },
};

// ============================================================
// Network layer
// ============================================================

class ApiError extends Error {
    constructor(message, { status = 0, network = false } = {}) {
        super(message);
        this.status = status;
        this.network = network;
    }
}

function errorDetail(status, data, text) {
    let detail = data && data.detail;
    if (Array.isArray(detail)) {  // FastAPI validation errors
        detail = detail.map((d) => `${(d.loc || []).slice(1).join('.')}: ${d.msg}`).join('; ');
    }
    if (detail) return String(detail);
    if (status === 413) return 'The file is too large for the server (or for a proxy in front of it).';
    if ([502, 503, 504].includes(status)) return `The server is unavailable (HTTP ${status}). It may be restarting.`;
    const snippet = (text || '').replace(/<[^>]+>/g, ' ').replace(/\s+/g, ' ').trim().slice(0, 200);
    return `HTTP ${status}${snippet ? ': ' + snippet : ''}`;
}

function parseResponse(status, text) {
    let data = null;
    if (text) {
        try { data = JSON.parse(text); } catch { /* not JSON */ }
    }
    if (status < 200 || status >= 300) throw new ApiError(errorDetail(status, data, text), { status });
    if (data === null && text) throw new ApiError(`Unexpected response from the server (HTTP ${status})`, { status });
    return data;
}

async function api(path, { method = 'GET', json, timeout = 30000 } = {}) {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), timeout);
    let resp;
    try {
        resp = await fetch(path, {
            method,
            cache: 'no-store',
            headers: json === undefined ? undefined : { 'Content-Type': 'application/json' },
            body: json === undefined ? undefined : JSON.stringify(json),
            signal: controller.signal,
        });
    } catch (e) {
        if (e.name === 'AbortError') {
            throw new ApiError(`The server did not answer within ${Math.round(timeout / 1000)} s.`, { network: true });
        }
        throw new ApiError('Cannot reach the server (network error). Check that it is still running.', { network: true });
    } finally {
        clearTimeout(timer);
    }
    const text = await resp.text();
    return parseResponse(resp.status, text);
}

/** POST a multipart form with upload progress (fetch cannot report it). */
function upload(path, formData, onProgress) {
    return new Promise((resolve, reject) => {
        const xhr = new XMLHttpRequest();
        xhr.open('POST', path);
        xhr.upload.onprogress = (e) => { if (e.lengthComputable) onProgress(e.loaded / e.total); };
        xhr.onload = () => {
            try { resolve(parseResponse(xhr.status, xhr.responseText)); } catch (e) { reject(e); }
        };
        xhr.onerror = () => reject(new ApiError('Upload failed: cannot reach the server (network error).', { network: true }));
        xhr.onabort = () => reject(new ApiError('Upload aborted.', { network: true }));
        xhr.send(formData);
    });
}

// ============================================================
// Connection state
// ============================================================

let connectionOk = true;

function setConnection(ok, message) {
    if (ok === connectionOk && ok) return;
    connectionOk = ok;
    $('connBanner').hidden = ok;
    if (!ok) $('connBannerText').textContent = message || 'Cannot reach the server — retrying…';
}

// ============================================================
// Small UI helpers
// ============================================================

function setStatus(el, message, type = '') {
    el.textContent = message || '';
    el.className = 'status' + (type ? ' ' + type : '');
}

function formatTime(seconds) {
    if (seconds == null || Number.isNaN(seconds)) return '';
    const total = Math.floor(seconds);
    const h = Math.floor(total / 3600);
    const m = Math.floor((total % 3600) / 60);
    const s = String(total % 60).padStart(2, '0');
    return h ? `${h}:${String(m).padStart(2, '0')}:${s}` : `${m}:${s}`;
}

function formatSize(bytes) {
    if (bytes == null) return '';
    const units = ['B', 'KB', 'MB', 'GB'];
    let i = 0;
    while (bytes >= 1024 && i < units.length - 1) { bytes /= 1024; i++; }
    return `${bytes.toFixed(i ? 1 : 0)} ${units[i]}`;
}

// ============================================================
// Progress card
// ============================================================

const progress = {
    card: $('progressCard'),
    resetTimer: null,
    startedAt: null,
    elapsedTimer: null,

    start(message) {
        clearTimeout(this.resetTimer);
        this.card.classList.remove('complete', 'error');
        this.card.classList.add('active');
        this.set(0, message);
        this.startedAt = Date.now();
        clearInterval(this.elapsedTimer);
        this.elapsedTimer = setInterval(() => this.tick(), 1000);
        this.tick();
    },
    tick() {
        if (this.startedAt) $('progressElapsed').textContent = formatTime((Date.now() - this.startedAt) / 1000);
    },
    set(fraction, message) {
        const pct = Math.max(0, Math.min(100, Math.round((fraction || 0) * 100)));
        $('progressBarFill').style.width = pct + '%';
        $('progressPercent').textContent = pct + '%';
        if (message) $('progressStageText').textContent = message;
    },
    finish(ok, message) {
        clearInterval(this.elapsedTimer);
        this.startedAt = null;
        $('cancelBtn').hidden = true;
        this.card.classList.add(ok ? 'complete' : 'error');
        if (ok) this.set(1, message);
        else $('progressStageText').textContent = message;
        if (ok) {
            this.resetTimer = setTimeout(() => this.card.classList.remove('active', 'complete'), 4000);
        }
    },
};

// ============================================================
// Jobs
// ============================================================

let activeJob = null;  // {id, kind}

function setBusy(busy) {
    $('switchModelBtn').disabled = busy;
    $('ejectBtn').disabled = busy;
    $('recordBtn').disabled = busy && !isRecording;
    $('fileDrop').classList.toggle('disabled', busy);
}

/** Poll a job until it finishes, surviving temporary network failures. */
async function pollJob(jobId, onUpdate) {
    let failures = 0;
    for (;;) {
        let job;
        try {
            job = await api(`/jobs/${jobId}`, { timeout: 15000 });
            failures = 0;
            setConnection(true);
        } catch (e) {
            if (e.status === 404) {
                throw new ApiError('The server no longer knows this job (it was probably restarted).', { status: 404 });
            }
            if (!e.network) throw e;
            failures++;
            setConnection(false, `Cannot reach the server — retrying (${failures})… The job keeps running on the server.`);
            if (failures > 150) throw e;
            await sleep(Math.min(1000 * failures, 5000));
            continue;
        }
        onUpdate(job);
        if (FINISHED.includes(job.status)) return job;
        await sleep(job.status === 'queued' ? 1000 : 400);
    }
}

async function runJob(job, { onDone, onError }) {
    activeJob = { id: job.id, kind: job.kind };
    store.set(ACTIVE_JOB_KEY, activeJob);
    setBusy(true);
    $('cancelBtn').hidden = false;
    $('cancelBtn').disabled = false;
    try {
        const final = await pollJob(job.id, (j) => progress.set(j.progress, j.message));
        if (final.status === 'done') {
            progress.finish(true, final.message);
            onDone(final);
        } else if (final.status === 'cancelled') {
            progress.finish(false, 'Cancelled');
            onError(new ApiError('Cancelled'), final);
        } else {
            const message = final.error ? final.error.message : final.message;
            progress.finish(false, message);
            onError(new ApiError(message), final);
        }
    } catch (e) {
        progress.finish(false, e.message);
        onError(e, null);
    } finally {
        activeJob = null;
        store.remove(ACTIVE_JOB_KEY);
        setBusy(false);
        refreshHealth();
    }
}

$('cancelBtn').addEventListener('click', async () => {
    if (!activeJob) return;
    $('cancelBtn').disabled = true;
    try {
        await api(`/jobs/${activeJob.id}/cancel`, { method: 'POST' });
        $('progressStageText').textContent = 'Cancelling…';
    } catch (e) {
        $('cancelBtn').disabled = false;
        $('progressStageText').textContent = 'Cancel failed: ' + e.message;
    }
});

// ============================================================
// Transcription
// ============================================================

let currentMode = 'microphone';
let currentResult = null;
let currentJobId = null;

function captureStatusEl() {
    return currentMode === 'microphone' ? $('micStatus') : $('fileStatus');
}

function transcriptionOptions() {
    const form = new FormData();
    const diarize = $('diarizeToggle').checked && !$('diarizeToggle').disabled;
    form.append('diarize', diarize ? 'true' : 'false');
    if (diarize) {
        const min = $('minSpeakers').value;
        const max = $('maxSpeakers').value;
        if (min) form.append('min_speakers', min);
        if (max) form.append('max_speakers', max);
    }
    if ($('languageSelect').value) form.append('language', $('languageSelect').value);
    return form;
}

function validateOptions() {
    const min = parseInt($('minSpeakers').value, 10);
    const max = parseInt($('maxSpeakers').value, 10);
    if ($('diarizeToggle').checked && min && max && min > max) {
        return 'The minimum number of speakers must not exceed the maximum.';
    }
    return null;
}

async function sendAudio(blob, filename) {
    if (activeJob) return;
    const statusEl = captureStatusEl();
    const problem = validateOptions();
    if (problem) { setStatus(statusEl, problem, 'error'); return; }
    if (serverInfo && serverInfo.max_upload_mb && blob.size > serverInfo.max_upload_mb * 1024 * 1024) {
        setStatus(statusEl, `File too large (${formatSize(blob.size)}, limit ${serverInfo.max_upload_mb} MB).`, 'error');
        return;
    }

    const form = transcriptionOptions();
    form.append('file', blob, filename);
    clearResult('Transcribing…');
    progress.start(`Uploading '${filename}' (${formatSize(blob.size)})…`);
    activeJob = { id: null, kind: 'upload' };
    setBusy(true);

    let job;
    try {
        job = await upload('/jobs', form, (f) => progress.set(f * 0.99, `Uploading '${filename}'… ${Math.round(f * 100)}%`));
        setConnection(true);
    } catch (e) {
        activeJob = null;
        setBusy(false);
        progress.finish(false, e.message);
        showError(e.message);
        setStatus(statusEl, 'Transcription failed', 'error');
        return;
    }
    progress.set(0, job.message);
    await followTranscription(job);
}

async function followTranscription(job) {
    const statusEl = captureStatusEl();
    await runJob(job, {
        onDone(final) {
            showResult(final);
            const r = final.result;
            const parts = [`Language: ${r.language}`];
            if (r.speakers) parts.push(`Speakers: ${r.speakers.length}`);
            if (r.duration) parts.push(`Audio: ${formatTime(r.duration)}`);
            setStatus(statusEl, parts.join(' · '), 'success');
        },
        onError(e, final) {
            if (final && final.status === 'cancelled') {
                clearResult();
                setStatus(statusEl, 'Transcription cancelled', 'warn');
            } else {
                showError(e.message);
                setStatus(statusEl, 'Transcription failed', 'error');
            }
        },
    });
}

// ============================================================
// Result rendering
// ============================================================

function clearResult(message) {
    currentResult = null;
    currentJobId = null;
    const box = $('outputBox');
    box.className = 'output-box placeholder';
    $('output').textContent = message || 'Transcription will appear here...';
    $('resultActions').hidden = true;
    $('resultWarnings').hidden = true;
    setStatus($('resultMeta'), '');
}

function showError(message) {
    const box = $('outputBox');
    box.className = 'output-box error';
    $('output').textContent = `Error: ${message}`;
    $('resultActions').hidden = true;
}

function showResult(job) {
    currentResult = job.result;
    currentJobId = job.id;
    store.set(LAST_RESULT_KEY, job.id);
    $('outputBox').className = 'output-box';
    renderResult();

    const hasSegments = Array.isArray(currentResult.segments) && currentResult.segments.length > 0;
    for (const fmt of ['txt', 'srt', 'vtt', 'json']) {
        const link = $('dl' + fmt[0].toUpperCase() + fmt.slice(1));
        link.href = `/jobs/${job.id}/export?format=${fmt}`;
        link.hidden = (fmt === 'srt' || fmt === 'vtt') && !hasSegments;
    }
    $('timestampsToggle').parentElement.hidden = !hasSegments;
    $('resultActions').hidden = false;

    const meta = [];
    if (job.filename) meta.push(job.filename);
    if (job.started_at && job.finished_at) meta.push(`processed in ${formatTime(job.finished_at - job.started_at)}`);
    if (job.params && job.params.language) meta.push(`language forced to ${job.params.language}`);
    setStatus($('resultMeta'), meta.join(' · '));

    const warnings = currentResult.warnings || [];
    const list = $('resultWarnings');
    list.textContent = '';
    for (const w of warnings) {
        const li = document.createElement('li');
        li.textContent = '⚠️ ' + w;
        list.appendChild(li);
    }
    list.hidden = warnings.length === 0;
}

function renderResult() {
    const container = $('output');
    container.textContent = '';
    const r = currentResult;
    if (!r) return;
    const withTimes = $('timestampsToggle').checked;

    if (r.speakers && r.segments) {
        // Merge consecutive segments from the same speaker into one turn
        const turns = [];
        for (const seg of r.segments) {
            const text = (seg.text || '').trim();
            if (!text) continue;
            const speaker = seg.speaker || 'UNKNOWN';
            const last = turns[turns.length - 1];
            if (last && last.speaker === speaker) last.texts.push(text);
            else turns.push({ speaker, start: seg.start, texts: [text] });
        }
        for (const turn of turns) {
            const idx = r.speakers.indexOf(turn.speaker);
            const div = document.createElement('div');
            div.className = 'speaker-turn';
            const label = document.createElement('span');
            label.className = 'speaker-label';
            label.textContent = turn.speaker;
            label.style.background = idx >= 0 ? SPEAKER_COLORS[idx % SPEAKER_COLORS.length] : '#888';
            const time = document.createElement('span');
            time.className = 'speaker-time';
            time.textContent = formatTime(turn.start);
            div.append(label, time, document.createTextNode(turn.texts.join(' ')));
            container.appendChild(div);
        }
    } else if (withTimes && r.segments) {
        for (const seg of r.segments) {
            const text = (seg.text || '').trim();
            if (!text) continue;
            const div = document.createElement('div');
            div.className = 'segment';
            const time = document.createElement('span');
            time.className = 'speaker-time';
            time.textContent = `${formatTime(seg.start)} → ${formatTime(seg.end)}`;
            div.append(time, document.createTextNode(text));
            container.appendChild(div);
        }
    } else {
        container.textContent = r.text || '(no speech detected)';
    }
}

function copyableText() {
    if (!currentResult) return $('output').textContent;
    if (currentResult.text_with_speakers) return currentResult.text_with_speakers;
    if ($('timestampsToggle').checked && currentResult.segments) {
        return currentResult.segments
            .filter((s) => (s.text || '').trim())
            .map((s) => `[${formatTime(s.start)}] ${s.text.trim()}`).join('\n');
    }
    return currentResult.text || '';
}

async function copyText() {
    const text = copyableText();
    const btn = $('copyBtn');
    try {
        if (navigator.clipboard && window.isSecureContext) {
            await navigator.clipboard.writeText(text);
        } else {
            // Clipboard API needs HTTPS or localhost: fall back to a hidden textarea
            const area = document.createElement('textarea');
            area.value = text;
            area.style.position = 'fixed';
            area.style.opacity = '0';
            document.body.appendChild(area);
            area.select();
            const ok = document.execCommand('copy');
            area.remove();
            if (!ok) throw new Error('copy refused');
        }
        btn.textContent = '✅ Copied!';
    } catch {
        btn.textContent = '❌ Copy failed';
    }
    setTimeout(() => { btn.textContent = '📋 Copy'; }, 2000);
}

$('copyBtn').addEventListener('click', copyText);
$('timestampsToggle').addEventListener('change', () => { savePrefs(); renderResult(); });

// ============================================================
// Capture: mode switch
// ============================================================

document.querySelectorAll('.mode-btn').forEach((btn) => {
    btn.addEventListener('click', () => {
        document.querySelectorAll('.mode-btn').forEach((b) => b.classList.remove('active'));
        btn.classList.add('active');
        currentMode = btn.dataset.mode;
        $('microphoneMode').hidden = currentMode !== 'microphone';
        $('uploadMode').hidden = currentMode !== 'upload';
    });
});

// ============================================================
// Capture: microphone
// ============================================================

let isRecording = false;
let mediaRecorder = null;
let mediaStream = null;
let audioChunks = [];
let recordTimer = null;
let recordStarted = 0;
let audioContext = null;
let animFrame = null;

const RECORDER_TYPES = [
    ['audio/webm;codecs=opus', 'webm'],
    ['audio/webm', 'webm'],
    ['audio/ogg;codecs=opus', 'ogg'],
    ['audio/mp4', 'm4a'],
];

function pickRecorderType() {
    if (!window.MediaRecorder || !MediaRecorder.isTypeSupported) return ['', 'webm'];
    return RECORDER_TYPES.find(([type]) => MediaRecorder.isTypeSupported(type)) || ['', 'webm'];
}

function stopStream() {
    if (mediaStream) mediaStream.getTracks().forEach((t) => t.stop());
    mediaStream = null;
}

function setRecordingUi(recording) {
    isRecording = recording;
    $('recordBtn').classList.toggle('recording', recording);
    $('recordLabel').textContent = recording ? 'Stop' : 'Record';
    $('recordIcon').textContent = recording ? '⏹️' : '🎤';
    clearInterval(recordTimer);
    if (recording) {
        recordStarted = Date.now();
        recordTimer = setInterval(() => {
            setStatus($('micStatus'), `Recording… ${formatTime((Date.now() - recordStarted) / 1000)}`);
        }, 500);
    }
}

async function startRecording() {
    const status = $('micStatus');
    if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
        setStatus(status, window.isSecureContext
            ? 'This browser does not support audio recording. Use the Upload mode instead.'
            : `The browser only allows the microphone over HTTPS or on localhost. Open http://localhost:${location.port || 80} on the server machine, or use the Upload mode.`,
        'error');
        return;
    }
    if (!window.MediaRecorder) {
        setStatus(status, 'This browser does not support MediaRecorder. Use the Upload mode instead.', 'error');
        return;
    }
    try {
        mediaStream = await navigator.mediaDevices.getUserMedia({ audio: true });
    } catch (e) {
        const reason = {
            NotAllowedError: 'Microphone access denied — allow it in the browser site settings.',
            NotFoundError: 'No microphone found.',
            NotReadableError: 'The microphone is in use by another application.',
        }[e.name] || `Cannot open the microphone: ${e.message}`;
        setStatus(status, reason, 'error');
        return;
    }

    const [mimeType, extension] = pickRecorderType();
    try {
        mediaRecorder = new MediaRecorder(mediaStream, mimeType ? { mimeType } : undefined);
    } catch (e) {
        stopStream();
        setStatus(status, `Cannot start recording: ${e.message}`, 'error');
        return;
    }
    audioChunks = [];
    mediaRecorder.ondataavailable = (e) => { if (e.data && e.data.size) audioChunks.push(e.data); };
    mediaRecorder.onerror = (e) => {
        setStatus(status, `Recording error: ${(e.error && e.error.message) || 'unknown'}`, 'error');
        stopRecording(false);
    };
    mediaRecorder.onstop = () => {
        const type = mediaRecorder.mimeType || mimeType || 'audio/webm';
        const blob = new Blob(audioChunks, { type });
        stopStream();
        if (mediaRecorder.discard) return;
        if (!blob.size) {
            setStatus(status, 'The recording is empty — check the microphone.', 'error');
            return;
        }
        const duration = (Date.now() - recordStarted) / 1000;
        setStatus(status, `Recorded ${formatTime(duration)} (${formatSize(blob.size)})`);
        sendAudio(blob, `recording.${extension}`);
    };
    mediaRecorder.start(1000);  // gather data every second, so a crash loses little
    setRecordingUi(true);
    setStatus(status, 'Recording…');
    startVisualizer(mediaStream);
}

function stopRecording(send = true) {
    if (!mediaRecorder) return;
    mediaRecorder.discard = !send;
    setRecordingUi(false);
    stopVisualizer();
    if (mediaRecorder.state !== 'inactive') mediaRecorder.stop();
    else stopStream();
}

$('recordBtn').addEventListener('click', () => (isRecording ? stopRecording(true) : startRecording()));

// --- Audio visualizer ---
function startVisualizer(stream) {
    try {
        audioContext = new (window.AudioContext || window.webkitAudioContext)();
    } catch {
        return;  // visualizer is optional
    }
    const source = audioContext.createMediaStreamSource(stream);
    const analyser = audioContext.createAnalyser();
    analyser.fftSize = 256;
    source.connect(analyser);
    const canvas = $('visualizer');
    canvas.hidden = false;
    const ratio = window.devicePixelRatio || 1;
    canvas.width = canvas.clientWidth * ratio;
    canvas.height = 80 * ratio;
    const ctx = canvas.getContext('2d');
    const data = new Uint8Array(analyser.frequencyBinCount);
    const background = getComputedStyle(document.documentElement).getPropertyValue('--surface').trim() || '#1a1a2e';

    function draw() {
        animFrame = requestAnimationFrame(draw);
        analyser.getByteFrequencyData(data);
        ctx.fillStyle = background;
        ctx.fillRect(0, 0, canvas.width, canvas.height);
        const barWidth = (canvas.width / data.length) * 2.5;
        let x = 0;
        for (let i = 0; i < data.length; i++) {
            const barHeight = (data[i] / 255) * canvas.height;
            ctx.fillStyle = `rgb(${Math.min(255, barHeight / ratio + 50)},69,96)`;
            ctx.fillRect(x, canvas.height - barHeight, barWidth, barHeight);
            x += barWidth + ratio;
        }
    }
    draw();
}

function stopVisualizer() {
    if (animFrame) cancelAnimationFrame(animFrame);
    animFrame = null;
    if (audioContext) audioContext.close().catch(() => {});
    audioContext = null;
    $('visualizer').hidden = true;
}

// ============================================================
// Capture: upload
// ============================================================

const fileDrop = $('fileDrop');
const fileInput = $('fileInput');

function handleFile(file) {
    if (activeJob) {
        setStatus($('fileStatus'), 'Wait for the current job to finish.', 'warn');
        return;
    }
    setStatus($('fileStatus'), `Selected: ${file.name} (${formatSize(file.size)})`, 'success');
    sendAudio(file, file.name);
}

fileDrop.addEventListener('click', () => { if (!activeJob) fileInput.click(); });
fileDrop.addEventListener('keydown', (e) => {
    if ((e.key === 'Enter' || e.key === ' ') && !activeJob) { e.preventDefault(); fileInput.click(); }
});
fileDrop.addEventListener('dragover', (e) => { e.preventDefault(); fileDrop.classList.add('dragover'); });
fileDrop.addEventListener('dragleave', () => fileDrop.classList.remove('dragover'));
fileDrop.addEventListener('drop', (e) => {
    e.preventDefault();
    fileDrop.classList.remove('dragover');
    if (e.dataTransfer.files.length) handleFile(e.dataTransfer.files[0]);
});
fileInput.addEventListener('change', () => {
    if (fileInput.files.length) handleFile(fileInput.files[0]);
    fileInput.value = '';  // allow selecting the same file again
});

// ============================================================
// Model switch / eject
// ============================================================

async function switchModel() {
    if (activeJob) return;
    const btn = $('switchModelBtn');
    const status = $('modelStatus');
    const model = $('modelSelect').value;
    const device = $('deviceSelect').value;
    setStatus(status, `Loading ${model} on ${device}…`);
    progress.start(`Switching to ${model} on ${device}…`);

    let response;
    try {
        response = await api('/switch-model', { method: 'POST', json: { model, device, wait: false } });
    } catch (e) {
        progress.finish(false, e.message);
        setStatus(status, '❌ ' + e.message, 'error');
        return;
    }
    await runJob(response.job, {
        onDone(final) {
            $('currentModelDisplay').textContent = `${final.result.model} (${final.result.device})`;
            setStatus(status, `✅ Switched to ${final.result.model} on ${final.result.device}`, 'success');
            btn.classList.add('success');
            setTimeout(() => btn.classList.remove('success'), 2000);
        },
        onError(e) {
            setStatus(status, '❌ ' + e.message, 'error');
        },
    });
}

async function ejectModel() {
    if (activeJob) return;
    const status = $('ejectStatus');
    setStatus(status, 'Ejecting models…');
    let response;
    try {
        response = await api('/eject?wait=false', { method: 'POST' });
    } catch (e) {
        setStatus(status, '❌ Eject failed: ' + e.message, 'error');
        return;
    }
    progress.start('Ejecting models…');
    await runJob(response.job, {
        onDone(final) {
            setStatus(status, final.result.ejected ? '✅ Models ejected — memory freed.' : '⚠️ No model was loaded',
                final.result.ejected ? 'success' : 'warn');
        },
        onError(e) {
            setStatus(status, '❌ Eject failed: ' + e.message, 'error');
        },
    });
}

$('switchModelBtn').addEventListener('click', switchModel);
$('ejectBtn').addEventListener('click', ejectModel);

// ============================================================
// Speaker identification & Hugging Face token
// ============================================================

let diarizeInitialized = false;
let diarizeAvailable = false;

function updateDiarizeOptions(available, isDefault) {
    const toggle = $('diarizeToggle');
    diarizeAvailable = available;
    if (!diarizeInitialized) {
        const prefs = store.get(PREFS_KEY, {});
        toggle.checked = available && (prefs.diarize ?? isDefault);
        diarizeInitialized = true;
    }
    toggle.disabled = !available;
    $('diarizeLabel').classList.toggle('disabled', !available);
    if (!available) toggle.checked = false;
    const hintsDisabled = !available || !toggle.checked;
    $('minSpeakers').disabled = hintsDisabled;
    $('maxSpeakers').disabled = hintsDisabled;

    const status = $('diarizeStatus');
    if (available) {
        setStatus(status, '');
    } else {
        status.className = 'status warn';
        status.textContent = 'Speaker identification needs a Hugging Face token. ';
        const link = document.createElement('button');
        link.type = 'button';
        link.className = 'link-btn';
        link.textContent = 'Add one below.';
        link.addEventListener('click', () => {
            $('hfPanel').open = true;
            $('hfTokenInput').focus();
        });
        status.appendChild(link);
    }
}

$('diarizeToggle').addEventListener('change', () => {
    updateDiarizeOptions(diarizeAvailable);
    savePrefs();
});

function renderHfStatus(info) {
    const badge = $('hfBadge');
    if (info.configured) {
        badge.textContent = info.source === 'env' ? `set by HF_TOKEN (${info.masked})` : `saved (${info.masked})`;
        badge.className = 'badge ok';
    } else {
        badge.textContent = 'not set';
        badge.className = 'badge missing';
    }
    $('hfRemoveBtn').disabled = info.source !== 'file';
    $('hfCheckBtn').disabled = !info.configured;
    if (info.model_url) {
        $('hfModelLink').href = info.model_url;
        $('hfModelLink').textContent = `${info.diarize_model} page`;
    }
}

async function loadHfStatus() {
    try {
        renderHfStatus(await api('/settings/hf-token'));
    } catch {
        $('hfBadge').textContent = 'unknown';
    }
}

function describeCheck(check) {
    if (!check) return ['', ''];
    if (check.valid === false) return [check.message, 'error'];
    if (check.valid === null) return [check.message + ' Saved anyway.', 'warn'];
    if (check.model_access === false) return [check.message, 'warn'];
    if (check.model_access === null) return [check.message, 'warn'];
    return [check.message, 'success'];
}

$('hfForm').addEventListener('submit', async (e) => {
    e.preventDefault();
    const input = $('hfTokenInput');
    const token = input.value.trim();
    const status = $('hfStatus');
    if (!token) { setStatus(status, 'Paste a token first.', 'error'); return; }
    $('hfSaveBtn').disabled = true;
    setStatus(status, 'Verifying the token with Hugging Face…');
    try {
        const info = await api('/settings/hf-token', { method: 'PUT', json: { token }, timeout: 45000 });
        input.value = '';
        renderHfStatus(info);
        const [message, type] = describeCheck(info.check);
        setStatus(status, info.warning ? `${message} ${info.warning}` : `✅ Token saved. ${message}`,
            info.warning ? 'warn' : type);
        refreshHealth();
    } catch (err) {
        setStatus(status, '❌ ' + err.message, 'error');
    } finally {
        $('hfSaveBtn').disabled = false;
    }
});

$('hfShowBtn').addEventListener('click', () => {
    const input = $('hfTokenInput');
    input.type = input.type === 'password' ? 'text' : 'password';
});

$('hfCheckBtn').addEventListener('click', async () => {
    const status = $('hfStatus');
    setStatus(status, 'Checking the token with Hugging Face…');
    try {
        const info = await api('/settings/hf-token/check', { method: 'POST', timeout: 45000 });
        const [message, type] = describeCheck(info.check);
        setStatus(status, message, type);
    } catch (err) {
        setStatus(status, '❌ ' + err.message, 'error');
    }
});

$('hfRemoveBtn').addEventListener('click', async () => {
    if (!confirm('Remove the saved Hugging Face token from the server?')) return;
    const status = $('hfStatus');
    try {
        const info = await api('/settings/hf-token', { method: 'DELETE' });
        renderHfStatus(info);
        setStatus(status, info.warning || (info.removed ? 'Token removed.' : 'No saved token to remove.'),
            info.warning ? 'warn' : 'success');
        refreshHealth();
    } catch (err) {
        setStatus(status, '❌ ' + err.message, 'error');
    }
});

// ============================================================
// Preferences
// ============================================================

function savePrefs() {
    store.set(PREFS_KEY, {
        language: $('languageSelect').value,
        diarize: $('diarizeToggle').checked,
        minSpeakers: $('minSpeakers').value,
        maxSpeakers: $('maxSpeakers').value,
        timestamps: $('timestampsToggle').checked,
        mode: currentMode,
    });
}

function restorePrefs() {
    const prefs = store.get(PREFS_KEY, {});
    $('minSpeakers').value = prefs.minSpeakers || '';
    $('maxSpeakers').value = prefs.maxSpeakers || '';
    $('timestampsToggle').checked = !!prefs.timestamps;
    if (prefs.mode === 'upload') document.querySelector('.mode-btn[data-mode="upload"]').click();
}

['minSpeakers', 'maxSpeakers', 'languageSelect'].forEach((id) => $(id).addEventListener('change', savePrefs));
document.querySelectorAll('.mode-btn').forEach((b) => b.addEventListener('click', savePrefs));

// ============================================================
// Health
// ============================================================

let serverInfo = null;
let languagesLoaded = false;
let selectsInitialized = false;

function fillLanguages(languages) {
    if (languagesLoaded || !languages) return;
    const select = $('languageSelect');
    for (const [code, name] of Object.entries(languages)) {
        const option = document.createElement('option');
        option.value = code;
        option.textContent = `${name} (${code})`;
        select.appendChild(option);
    }
    const saved = store.get(PREFS_KEY, {}).language;
    if (saved && languages[saved]) select.value = saved;
    languagesLoaded = true;
}

function renderGpuHealth(data) {
    const el = $('gpuHealthContent');
    el.textContent = '';
    const gpus = data.nvidia_gpu || [];
    if (!gpus.length) {
        el.textContent = 'No NVIDIA GPU detected — using CPU inference.';
        return;
    }
    const color = { green: 'var(--ok)', yellow: 'var(--warn)', red: 'var(--err)' }[data.status] || 'var(--ok)';
    for (const gpu of gpus) {
        const card = document.createElement('div');
        card.className = 'gpu-card';
        const title = document.createElement('div');
        const strong = document.createElement('strong');
        strong.textContent = `GPU ${gpu.index}: ${gpu.name}`;
        title.append(strong, document.createTextNode(
            gpu.utilization_pct == null ? '' : ` · Utilization ${gpu.utilization_pct}%`));
        const bar = document.createElement('div');
        bar.className = 'vram-bar';
        const fill = document.createElement('div');
        fill.className = 'vram-fill';
        fill.style.width = `${gpu.vram_total_mb ? (gpu.vram_used_mb / gpu.vram_total_mb) * 100 : 0}%`;
        fill.style.background = color;
        bar.appendChild(fill);
        const legend = document.createElement('div');
        legend.className = 'vram-legend';
        for (const [label, mb] of [['Used', gpu.vram_used_mb], ['Free', gpu.vram_free_mb], ['Total', gpu.vram_total_mb]]) {
            const span = document.createElement('span');
            span.textContent = `${label}: ${(mb / 1024).toFixed(1)} GB`;
            legend.appendChild(span);
        }
        card.append(title, bar, legend);
        el.appendChild(card);
    }
}

function updateDeviceOptions(data) {
    const cuda = data.torch_cuda || {};
    const option = $('cudaOption');
    option.disabled = !cuda.available;
    if (cuda.available) {
        option.textContent = `CUDA (GPU — ${(cuda.devices && cuda.devices[0] && cuda.devices[0].name) || 'GPU'})`;
    } else {
        option.textContent = (data.nvidia_gpu && data.nvidia_gpu.length)
            ? 'CUDA (GPU) — not available to PyTorch'
            : 'CUDA (GPU) — no GPU detected';
        if ($('deviceSelect').value === 'cuda') $('deviceSelect').value = 'cpu';
    }
}

async function refreshHealth() {
    let data;
    try {
        data = await api('/health', { timeout: 20000 });
        setConnection(true);
    } catch (e) {
        if (e.network) setConnection(false, 'Cannot reach the server — retrying… (is it still running?)');
        $('gpuStatus').classList.add('loading');
        $('gpuTitle').textContent = e.network ? 'Server unreachable' : 'Server error';
        $('gpuDetail').textContent = e.message;
        return;
    }
    serverInfo = data;

    $('gpuStatus').classList.remove('loading');
    $('gpuDot').className = 'gpu-dot ' + data.status;
    $('gpuTitle').textContent = data.recommendation;
    const detail = [`Model: ${data.model} on ${data.device} (${data.model_loaded ? 'loaded' : 'not loaded'})`];
    if (data.busy) detail.push(data.queued ? `busy, ${data.queued} job(s) queued` : 'busy');
    if (!data.ffmpeg_available) detail.push('⚠️ ffmpeg not found on the server');
    $('gpuDetail').textContent = detail.join(' · ');

    $('currentModelDisplay').textContent = `${data.model} (${data.device})`;
    updateDeviceOptions(data);
    if (!selectsInitialized) {
        $('modelSelect').value = data.model;
        $('deviceSelect').value = data.device;
        selectsInitialized = true;
    }
    fillLanguages(data.languages);
    updateDiarizeOptions(!!data.diarization_available, !!data.diarize_default);
    renderGpuHealth(data);
}

// ============================================================
// Startup
// ============================================================

async function restoreLastResult() {
    const jobId = store.get(LAST_RESULT_KEY);
    if (!jobId) return;
    try {
        const job = await api(`/jobs/${jobId}`);
        if (job.status === 'done' && job.result) showResult(job);
    } catch {
        store.remove(LAST_RESULT_KEY);  // expired or server restarted
    }
}

async function resumeActiveJob() {
    const saved = store.get(ACTIVE_JOB_KEY);
    if (!saved || !saved.id) return restoreLastResult();
    let job;
    try {
        job = await api(`/jobs/${saved.id}`);
    } catch {
        store.remove(ACTIVE_JOB_KEY);
        return;
    }
    if (job.kind !== 'transcribe') {
        if (!FINISHED.includes(job.status)) {
            progress.start(job.message);
            await runJob(job, { onDone() {}, onError() {} });
        } else {
            store.remove(ACTIVE_JOB_KEY);
        }
        return;
    }
    if (FINISHED.includes(job.status)) {
        store.remove(ACTIVE_JOB_KEY);
        if (job.status === 'done') {
            showResult(job);
            setStatus(captureStatusEl(), `Restored the last transcription (${job.filename || 'audio'})`, 'success');
        }
        return;
    }
    clearResult('Transcribing…');
    progress.start(job.message);
    setStatus(captureStatusEl(), `Resumed: ${job.filename || 'transcription'}`);
    await followTranscription(job);
}

restorePrefs();
refreshHealth().then(resumeActiveJob);
loadHfStatus();
setInterval(() => { if (!document.hidden) refreshHealth(); }, 10000);
document.addEventListener('visibilitychange', () => { if (!document.hidden) refreshHealth(); });
window.addEventListener('beforeunload', (e) => {
    if (isRecording) { e.preventDefault(); e.returnValue = ''; }
});
