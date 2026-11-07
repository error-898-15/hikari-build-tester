#!/usr/bin/env python3
"""
Turns the open Hindi/Indic Pocket TTS model (Thanushttk/pocket-tts-multilingual, CC-BY-4.0)
into the 7 files the Hikari app downloads for its cloned voices:

    lm_main.int8.onnx  lm_flow.int8.onnx  encoder.onnx  decoder.int8.onnx
    text_conditioner.onnx  vocab.json  token_scores.json

It is run by .github/workflows/clone-model.yml (never on the phone).

  convert.py export  --exporter DIR --src DIR       run the ONNX exporter
  convert.py collect --exporter DIR --src DIR --sherpa DIR --out DIR
                                                     pick the files, rename, bake the BOS frame,
                                                     convert the tokenizer
"""
import argparse
import json
import re
import shutil
import subprocess
import sys
import time
from pathlib import Path

ROLES = {
    # sherpa name            : names the exporters use (first one found wins)
    "lm_main.int8.onnx": ["lm_main.int8.onnx", "flow_lm_main_int8.onnx"],
    "lm_flow.int8.onnx": ["lm_flow.int8.onnx", "flow_lm_flow_int8.onnx"],
    "decoder.int8.onnx": ["decoder.int8.onnx", "mimi_decoder_int8.onnx"],
    "encoder.onnx": ["encoder.onnx", "mimi_encoder.onnx"],
    "text_conditioner.onnx": ["text_conditioner.onnx"],
}


def log(*a):
    print(*a, flush=True)


def run(cmd, cwd=None, check=True):
    log("+", " ".join(str(c) for c in cmd), "   (in %s)" % (cwd or "."))
    r = subprocess.run([str(c) for c in cmd], cwd=cwd)
    if check and r.returncode != 0:
        sys.exit("COMMAND FAILED (exit %d): %s" % (r.returncode, " ".join(str(c) for c in cmd)))
    return r.returncode


# ----------------------------------------------------------------------------- export
def do_export(exporter: Path, src: Path):
    for p in list(src.rglob("*.safetensors")) + list(src.rglob("*.model")) + list(src.rglob("*.yaml")):
        dest = exporter / p.name
        if not dest.exists():
            shutil.copy2(p, dest)
    for need in ("config.yaml", "model.safetensors", "tokenizer.model"):
        if not (exporter / need).exists():
            log("::warning::%s was not found in the downloaded model folder" % need)
    log("files in exporter folder:", sorted(p.name for p in exporter.iterdir())[:60])
    (exporter / ".export_started").write_text(str(time.time()))
    code = run([sys.executable, "export.py", "--config", "config.yaml", "--quantize"], cwd=exporter, check=False)
    if code != 0:
        sys.exit("export.py failed (exit %d)" % code)


# ----------------------------------------------------------------------------- collect
def find_bundle(exporter: Path):
    started = 0.0
    sf = exporter / ".export_started"
    if sf.exists():
        started = float(sf.read_text()) - 5
    dirs = {}
    for p in exporter.rglob("*.onnx"):
        try:
            if p.stat().st_mtime >= started:
                dirs.setdefault(p.parent, set()).add(p.name)
        except OSError:
            pass
    best = None
    for d, names in dirs.items():
        ok = all(any(c in names for c in cands) for cands in ROLES.values())
        if ok and (best is None or len(names) > len(dirs[best])):
            best = d
    if best is None:
        log("onnx files the exporter wrote:")
        for d, names in dirs.items():
            log("  ", d, sorted(names))
        sys.exit("Could not find a folder with all 5 onnx parts (main, flow, decoder, encoder, text conditioner).")
    return best


def pick(bundle: Path, cands):
    for c in cands:
        if (bundle / c).exists():
            return bundle / c
    raise FileNotFoundError(cands)


def bake_bos(encoder_in: Path, bos_npy: Path, encoder_out: Path):
    """Puts the model's learned BOS-before-voice frame in front of the encoder output (a Concat
    inside the graph). sherpa-onnx has no option for it and the model needs it."""
    import numpy as np
    import onnx
    from onnx import helper, numpy_helper

    bos = np.load(str(bos_npy)).astype("float32")
    if bos.ndim == 2:
        bos = bos[None, :, :]
    assert bos.ndim == 3 and bos.shape[0] == 1 and bos.shape[1] == 1, "unexpected BOS shape %s" % (bos.shape,)
    m = onnx.load(str(encoder_in))
    g = m.graph
    out = g.output[0]
    orig = out.name
    inner = orig + "__without_bos"
    for n in g.node:
        for i, o in enumerate(n.output):
            if o == orig:
                n.output[i] = inner
        for i, x in enumerate(n.input):
            if x == orig:
                n.input[i] = inner
    g.initializer.append(numpy_helper.from_array(bos, name="bos_before_voice_const"))
    g.node.append(helper.make_node("Concat", ["bos_before_voice_const", inner], [orig], axis=1))
    keep = [v for v in g.value_info if v.name != orig]
    del g.value_info[:]
    g.value_info.extend(keep)
    for d in out.type.tensor_type.shape.dim:   # the number of frames changes (+1): make it unknown
        d.Clear()
    onnx.checker.check_model(m)
    onnx.save(m, str(encoder_out))
    log("BOS frame baked into the encoder:", bos.shape, "->", encoder_out.name)


def convert_tokenizer(sherpa: Path, tok_model: Path, out: Path):
    """vocab.json + token_scores.json. First the official sherpa-onnx script, then our own."""
    work = out.parent / "tokwork"
    shutil.rmtree(work, ignore_errors=True)
    work.mkdir(parents=True)
    script = sherpa / "scripts" / "pocket-tts" / "convert_tokenizer.py"
    got = False
    if script.exists():
        text = script.read_text(encoding="utf-8", errors="replace")
        log("---- official convert_tokenizer.py (first 2500 chars) ----")
        log(text[:2500])
        log("----------------------------------------------------------")
        opts = re.findall(r"add_argument\(\s*[\"']([^\"']+)[\"']", text)
        pos = [o for o in opts if not o.startswith("-")]
        flags = [o for o in opts if o.startswith("--")]
        attempts = []
        if pos:
            attempts.append([str(tok_model), str(work)][: len(pos)])
        in_flags = [f for f in flags if re.search(r"model|token|input|spm|sp$", f)]
        out_flags = [f for f in flags if re.search(r"out|dir|save|dest", f)]
        if in_flags:
            a = [in_flags[0], str(tok_model)]
            if out_flags:
                a += [out_flags[0], str(work)]
            attempts.append(a)
        attempts.append([str(tok_model)])
        for a in attempts:
            code = run([sys.executable, script] + a, cwd=work, check=False)
            if code != 0:
                continue
            vj = sorted(work.rglob("vocab.json")) + sorted(tok_model.parent.glob("vocab.json"))
            ts = sorted(work.rglob("token_scores.json")) + sorted(tok_model.parent.glob("token_scores.json"))
            if vj and ts:
                shutil.copy2(vj[0], out / "vocab.json")
                shutil.copy2(ts[0], out / "token_scores.json")
                got = True
                log("tokenizer converted with the official script")
                break
    if not got:
        log("::warning::official tokenizer script did not give vocab.json/token_scores.json - using the built-in converter")
        import sentencepiece as spm
        sp = spm.SentencePieceProcessor(model_file=str(tok_model))
        n = sp.get_piece_size()
        vocab = {sp.id_to_piece(i): i for i in range(n)}
        scores = {sp.id_to_piece(i): float(sp.get_score(i)) for i in range(n)}
        (out / "vocab.json").write_text(json.dumps(vocab, ensure_ascii=False), encoding="utf-8")
        (out / "token_scores.json").write_text(json.dumps(scores, ensure_ascii=False), encoding="utf-8")
        log("tokenizer written by the built-in converter, pieces:", n)


def do_collect(exporter: Path, src: Path, sherpa: Path, out: Path):
    shutil.rmtree(out, ignore_errors=True)
    out.mkdir(parents=True)
    bundle = find_bundle(exporter)
    log("bundle folder:", bundle, sorted(p.name for p in bundle.iterdir()))
    for sherpa_name, cands in ROLES.items():
        shutil.copy2(pick(bundle, cands), out / sherpa_name)
    # encoder with the BOS frame (preferred) and without (kept as a spare for the self test)
    bos = next(iter(list(bundle.rglob("bos_before_voice.npy")) + list(exporter.rglob("bos_before_voice.npy"))), None)
    if bos is not None:
        shutil.copy2(out / "encoder.onnx", out / "encoder.nobos.onnx")
        bake_bos(out / "encoder.nobos.onnx", bos, out / "encoder.onnx")
    else:
        log("::warning::no bos_before_voice.npy was written by the exporter - encoder is used as it is")
    tok = next(iter(list(bundle.glob("tokenizer.model")) + list(src.rglob("tokenizer.model")) + list(exporter.glob("tokenizer.model"))), None)
    if tok is None:
        sys.exit("tokenizer.model not found")
    convert_tokenizer(sherpa, tok, out)
    log("final files:")
    for p in sorted(out.iterdir()):
        log("  %-28s %12d bytes" % (p.name, p.stat().st_size))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("step", choices=["export", "collect"])
    ap.add_argument("--exporter", required=True)
    ap.add_argument("--src", required=True)
    ap.add_argument("--sherpa", default="")
    ap.add_argument("--out", default="")
    a = ap.parse_args()
    if a.step == "export":
        do_export(Path(a.exporter), Path(a.src))
    else:
        do_collect(Path(a.exporter), Path(a.src), Path(a.sherpa), Path(a.out))


if __name__ == "__main__":
    main()
