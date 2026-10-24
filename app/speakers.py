"""
Speaker post-processing for WhisperX results.

WhisperX's ``assign_word_speakers`` labels each word and gives each segment
its dominant speaker, but keeps the original segment boundaries. A single
Whisper segment often spans a change of speaker, so we split segments at
word level wherever the speaker changes.

Everything here is plain Python so it can be tested without loading models.
"""
import math

# Languages whose words are not separated by spaces (mirrors whisperx.alignment)
LANGUAGES_WITHOUT_SPACES = {"ja", "zh"}

UNKNOWN_SPEAKER = "UNKNOWN"


def _word_separator(language):
    return "" if language in LANGUAGES_WITHOUT_SPACES else " "


def _clean_number(value):
    """Convert numpy scalars to builtins and NaN to None (JSON-safe)."""
    if value is None:
        return None
    try:
        value = float(value)
    except (TypeError, ValueError):
        return value
    return None if math.isnan(value) else value


def to_builtin(obj):
    """Recursively convert a WhisperX result into JSON-serializable builtins."""
    if isinstance(obj, dict):
        return {k: to_builtin(v) for k, v in obj.items()}
    if isinstance(obj, (list, tuple)):
        return [to_builtin(v) for v in obj]
    if isinstance(obj, (str, bool)) or obj is None:
        return obj
    if isinstance(obj, int):
        return obj
    if hasattr(obj, "item"):  # numpy scalar
        obj = obj.item()
    if isinstance(obj, float):
        return _clean_number(obj)
    return obj


def _make_segment(words, speaker, fallback_start, fallback_end, separator):
    starts = [w["start"] for w in words if _clean_number(w.get("start")) is not None]
    ends = [w["end"] for w in words if _clean_number(w.get("end")) is not None]
    return {
        "start": starts[0] if starts else fallback_start,
        "end": ends[-1] if ends else fallback_end,
        "text": separator.join(w["word"].strip() for w in words).strip(),
        "speaker": speaker,
        "words": words,
    }


def split_segments_by_speaker(segments, language=None):
    """
    Split segments wherever the word-level speaker changes.

    Words without a speaker (e.g. numbers that could not be aligned) stay with
    the surrounding speaker. Segments with a single speaker are kept as is,
    so their original text is preserved exactly.
    """
    separator = _word_separator(language)
    result = []

    for seg in segments:
        words = seg.get("words") or []
        word_speakers = {w["speaker"] for w in words if w.get("speaker")}

        if len(word_speakers) <= 1:
            seg = dict(seg)
            if "speaker" not in seg and word_speakers:
                seg["speaker"] = word_speakers.pop()
            result.append(seg)
            continue

        groups = []  # [(speaker, [words])]
        for word in words:
            speaker = word.get("speaker")
            if groups and (speaker is None or speaker == groups[-1][0]):
                groups[-1][1].append(word)
            elif speaker is None:
                # Leading words with no speaker: attach to the first labelled word
                groups.append((None, [word]))
            elif groups and groups[-1][0] is None:
                groups[-1] = (speaker, groups[-1][1] + [word])
            else:
                groups.append((speaker, [word]))

        seg_start, seg_end = seg.get("start"), seg.get("end")
        for speaker, group_words in groups:
            result.append(_make_segment(group_words, speaker or seg.get("speaker"),
                                        seg_start, seg_end, separator))

    for i, seg in enumerate(result):
        seg["id"] = i
    return result


def list_speakers(segments):
    """Return speaker labels in order of first appearance."""
    speakers = []
    for seg in segments:
        speaker = seg.get("speaker")
        if speaker and speaker not in speakers:
            speakers.append(speaker)
    return speakers


def format_speaker_transcript(segments, language=None):
    """Render ``SPEAKER: text`` lines, merging consecutive same-speaker segments."""
    separator = _word_separator(language)
    lines = []  # [(speaker, [texts])]
    for seg in segments:
        text = (seg.get("text") or "").strip()
        if not text:
            continue
        speaker = seg.get("speaker") or UNKNOWN_SPEAKER
        if lines and lines[-1][0] == speaker:
            lines[-1][1].append(text)
        else:
            lines.append((speaker, [text]))
    return "\n".join(f"{speaker}: {separator.join(texts)}" for speaker, texts in lines)
