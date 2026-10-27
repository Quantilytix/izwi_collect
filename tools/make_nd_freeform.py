"""Derive the isiNdebele free-form pack from the Shona free-form pack.

Same English cue cards. Ids get the nd_ prefix, every "Shona" in a prompt
becomes "Ndebele", language is "nd", and session_start asks for a price
sentence described in English, to be said in Ndebele. Writes no Shona or
Ndebele text of its own; the Shona match sentence is dropped, not translated.
"""
import json
import sys

src_path, out_path = sys.argv[1], sys.argv[2]
src = json.load(open(src_path, encoding="utf-8"))
assert src["language"] == "sn" and src["script_version"] == "freeform-v1"

SESSION_START = (
    "Stay quiet for 5 seconds, then say this in Ndebele, in the same words every session:\n"
    "The price of three bales is fifty."
)

NOTE = (
    "Free-form collection script v1 for isiNdebele (2026-10-27), derived from freeform-v1, the Shona "
    "free-form script. The cue cards, topics and numbers are the same English prompts, in the same order. "
    "Ids carry the nd_ prefix, so they never collide with Shona ids. Every instruction that said Shona says "
    "Ndebele. Four categories, as in freeform-v1. free_form_cue (40): an English scenario plus key words; "
    "she speaks freely in Ndebele, mixing English as she naturally would, 30 to 90 s per take. own_words (20): "
    "an English topic; she replaces the prompt text with her own Ndebele through Edit prompt before the take, "
    "so the clip transcript is exactly what she read. free_form_numbers (10): amounts written the way a trader "
    "writes them; she says them in street register. session_start (5): 5 s of room tone, then one fixed price "
    "sentence, described here in English and said in Ndebele in the same words every session, for level, rate "
    "and pitch drift checks; calibration only, keep it out of training. This file holds no Ndebele text. For "
    "free_form_cue, free_form_numbers and session_start the stored transcript is the English prompt text and "
    "needs a verbatim Ndebele transcript (ASR draft plus native correction). own_words transcripts are her "
    "typed text."
)

prompts = []
for p in src["prompts"]:
    if p["category"] == "session_start":
        text = SESSION_START
    else:
        text = p["text"].replace("Shona", "Ndebele")
    prompts.append({"id": "nd_" + p["id"], "category": p["category"], "text": text})

out = {"script_version": "nd-freeform-v1", "language": "nd", "note": NOTE, "prompts": prompts}
with open(out_path, "w", encoding="utf-8", newline="\n") as f:
    json.dump(out, f, ensure_ascii=False, indent=2)
    f.write("\n")
print(len(prompts), "prompts written to", out_path)
