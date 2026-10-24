"""Export a transcription result as plain text, SRT or WebVTT subtitles, or JSON."""
import json

EXPORT_FORMATS = {
    "txt": "text/plain; charset=utf-8",
    "srt": "application/x-subrip; charset=utf-8",
    "vtt": "text/vtt; charset=utf-8",
    "json": "application/json",
}


def _timestamp(seconds, separator):
    millis = max(int(round((seconds or 0.0) * 1000)), 0)
    hours, millis = divmod(millis, 3_600_000)
    minutes, millis = divmod(millis, 60_000)
    secs, millis = divmod(millis, 1000)
    return f"{hours:02d}:{minutes:02d}:{secs:02d}{separator}{millis:03d}"


def _cues(result):
    """(start, end, text) for each non-empty segment, prefixed by the speaker when known."""
    cues = []
    previous_end = 0.0
    for seg in result.get("segments") or []:
        text = (seg.get("text") or "").strip()
        if not text:
            continue
        start = seg.get("start")
        end = seg.get("end")
        start = previous_end if start is None else start
        end = start + 1.0 if end is None or end < start else end
        if seg.get("speaker"):
            text = f"[{seg['speaker']}] {text}"
        cues.append((start, end, text))
        previous_end = end
    return cues


def to_txt(result):
    text = result.get("text_with_speakers") or result.get("text") or ""
    return text.strip() + "\n"


def to_srt(result):
    blocks = [
        f"{i}\n{_timestamp(start, ',')} --> {_timestamp(end, ',')}\n{text}\n"
        for i, (start, end, text) in enumerate(_cues(result), 1)
    ]
    return "\n".join(blocks)


def to_vtt(result):
    blocks = [f"{_timestamp(start, '.')} --> {_timestamp(end, '.')}\n{text}\n" for start, end, text in _cues(result)]
    return "WEBVTT\n\n" + "\n".join(blocks)


def to_json(result):
    return json.dumps(result, ensure_ascii=False, indent=2)


def export(result, fmt):
    return {"txt": to_txt, "srt": to_srt, "vtt": to_vtt, "json": to_json}[fmt](result)
