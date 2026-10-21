---
license: apache-2.0
base_model: openbmb/MiniCPM-V-4.7-1B
tags:
  - litert
  - litert-lm
  - litertlm
  - on-device
  - edge
  - vlm
  - multimodal
  - vision-language
  - minicpm-v
pipeline_tag: image-text-to-text
library_name: litert-lm
base_model_relation: quantized
---

# MiniCPM-V-4.7-1B — LiteRT-LM (image + text)

[openbmb/MiniCPM-V-4.7-1B](https://huggingface.co/openbmb/MiniCPM-V-4.7-1B) converted to the **LiteRT-LM** (`.litertlm`) format for Google's [LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM) runtime, as an image + text bundle on its `fast_vlm` path. Tested on litert-lm 0.18.0 on a Mac M4 Max and on a Galaxy S26, a Pixel 8a and an iPhone 18 Pro: on the Galaxy S26 the image bundle returns the first token of an answer about a 448×448 photo 0.93 s after the request on the GPU, and decodes text at 36.5 tokens/s on the CPU (2026-10-13). Reproduction: [REPRODUCE.md](https://github.com/john-rocky/hf-to-litertlm/blob/main/REPRODUCE.md#2026-10-13--minicpm-v-47-1b-a-qwen35-text-hybrid-vlm-as-a-fast_vlm-bundle-the-window-merger-vit-re-authored-in-plain-torch-and-a-chat-template-whose-second-turn-matches-upstream-token-for-token) · machine-readable facts: [litertlm_manifest.json](https://huggingface.co/litert-community/MiniCPM-V-4.7-1B/blob/main/litertlm_manifest.json).

MiniCPM-V 4.7 (OpenBMB, released 2026-10-12) is a 1B vision-language model: a Qwen3.5-style hybrid text decoder (18 GatedDeltaNet linear-attention layers + 6 full-attention layers, hidden 1024, vocab 248,094) under a 27-layer SigLIP-style vision encoder that compresses visual tokens early, inside the encoder (a 2×2 window-attention merger after layer 6, then a 2×2 merger). OpenBMB positions it for single-image, multi-image and video understanding on phones. This bundle carries the single-image path in the model's "Instruct" mode (direct answers, thinking off).

| File | What is inside | Size | sha256 |
|---|---|---|---|
| `MiniCPM-V-4.7-1B-VL_int8.litertlm` | text decoder int8 (dynamic, per-channel; the 36 GatedDeltaNet decay/gate projections stay float) with fp32 activations declared for the GPU, int8 embedding table, vision encoder fp16, vision adapter fp16 | 2.21 GB | `4c9c30b24a5e9187c36d1a45773dbb2c7f32a8ee8cb5788df97b8d14453bb140` |
| `MiniCPM-V-4.7-1B-VL_int8vis.litertlm` | the same decoder, embedder and adapter; vision encoder int8 — for 8 GB Android phones with an Arm Mali GPU, where an fp16 vision encoder has rebooted the phone in earlier conversions | 1.70 GB | `20c73b5a1e48a315974cff1853535ff5d017100b6420d5ef221b9dd1b955dd7c` |
| `MiniCPM-V-4.7-1B_int8.litertlm` | text decoder only (no vision sections), the same int8 recipe, for text chat | 1.22 GB | `92d7d2e57b9d9bf9b3cba2cdcb2434578a0168721c06fa224dc75755701aad2a` |

Which file: `MiniCPM-V-4.7-1B-VL_int8.litertlm` on macOS, iPhone and 12 GB-class Android phones (the one measured on the Galaxy S26 and the iPhone 18 Pro); `MiniCPM-V-4.7-1B-VL_int8vis.litertlm` on the Pixel 8a (measured there on the CPU); `MiniCPM-V-4.7-1B_int8.litertlm` when no image is involved. All three share the decoder bytes, so the text behaviour is the same.

## What the image path does

LiteRT-LM's `fast_vlm` contract takes one image per message, resizes it to a fixed 448×448, runs the bundle's vision encoder (27 layers, window merger) and adapter, and splices **64 image tokens** into the prompt where the chat template puts `<image_id>0</image_id><image>…</image>` — the same layout, token ids and count the model's own processor produces for an image whose area is at most 448×448 (its single-thumbnail path). Two things differ from the Transformers pipeline and were measured on the fp32 model before conversion (three fixtures × three questions, 64 new tokens each):

- Larger images are resized to the 448×448 square instead of being tiled into a thumbnail plus up to nine slices. Against the tiled run the answers were paraphrases of the same content in 9 of 9 cases (no case changed what the image contains, none was identical).
- The runtime feeds plain 1-D positions, so the model's canvas M-RoPE collapses to ordinary RoPE on image tokens. Against the canvas-position run: paraphrases in 9 of 9 cases, none identical, none wrong.

The bundle therefore answers questions about a picture (describe, colours, where, what is written), at the model's 16× token compression, one image per conversation. Video, multi-image prompts and the 4× ("finer detail") mode are not in this bundle.

## Correctness

- Text decoder: teacher-forced logits of the float export against the fp32 model at 48 positions, top-1 identical at every position, Pearson 1.0000 (max |Δlogit| 5.3e-5); the int8 file 47 of 48 (one near-tie). The derived text checkpoint fed to the exporter reproduces the full model's text logits bit for bit.
- Vision: the re-authored tower against the model's `get_image_features` on four 448×448 inputs, correlation 0.99999999997 (fp32); the fp16 encoder + fp16 adapter end-to-end correlation 1.000000; the int8 encoder of the `_int8vis` file 0.9954 (worst per-token cosine 0.911).
- 8-question check (Mac M4 Max, litert-lm 0.18.0, `--temperature 0`, one `litert-lm run` process per question): CPU 8/8; GPU 8/8 and word-for-word the fp32 model's answers (the CPU differs on one rhyme: "blue" where the fp32 model says "white"). Prompt lengths 12–51 tokens each answered correctly in a fresh process (40/40, CPU). Image: three photos (two cats on a pink couch, two kitchens) × three questions, 9/9 grounded on the CPU and 9/9 on the GPU, vision on the GPU as well.
- On the phones: Pixel 8a `_int8vis` CPU 3/3 photos grounded; Galaxy S26 CPU and GPU grounded, text answers "Paris." on both; iPhone 18 Pro Metal and CPU answer the eight questions (Metal 8/8, CPU 7/8 — the CPU's "Four days are in a week." is the same text the Mac CPU gives on that combined prompt) and read a text probe image as "Yes … SAFETY, PROBE, TEXT" and a fractal as "No".

## Usage

```bash
litert-lm run ./MiniCPM-V-4.7-1B-VL_int8.litertlm --prompt "What is the capital of France? Answer in one sentence."
litert-lm run ./MiniCPM-V-4.7-1B-VL_int8.litertlm --prompt "What is in this image?" --attachment photo.png
litert-lm run ./MiniCPM-V-4.7-1B-VL_int8.litertlm --prompt "What is in this image?" --attachment photo.png --backend gpu --cache no
litert-lm benchmark ./MiniCPM-V-4.7-1B-VL_int8.litertlm -p 256 -d 256 --runs 3 --cache no --backend cpu
```

Without `--backend`, `run` uses the CPU; `--vision-backend gpu` also moves the vision encoder to the GPU. Without `--cache no`, the GPU run writes a compiled-weights cache next to the model. On Android, `litert_lm_advanced_main` takes the image inline: `--input_prompt='[image:/data/local/tmp/photo.png]What is in this image?' --vision_backend=cpu` (see [android-gpu.md](https://github.com/john-rocky/hf-to-litertlm/blob/main/docs/android-gpu.md)). On iOS, load the bundle with the vision modality enabled.

Create one Engine per conversation: on this hybrid model a Conversation opened on an Engine that already served another one continues from that conversation's recurrent state ([LiteRT-LM #3165](https://github.com/google-ai-edge/LiteRT-LM/issues/3165)); measured on the iPhone 18 Pro, the second and third conversations on one Engine gave different, broken answers to the same eight questions, a fresh Engine the same answers every time.

The chat template is a ChatML template that accepts both the string and the 0.18 content-parts form, renders an image item as `<image_id>N</image_id><image><image_soft_token></image>` and keeps the model's empty `<think>\n\n</think>` scaffold (thinking off); stops are `<|im_end|>` and `<|endoftext|>`, no start token (the model's `bos_token` field names `<|im_start|>`, which its own template already emits). A second turn prefills exactly the tokens the Transformers template adds (the newline after an assistant turn is placed at the start of the next turn, because the runtime keeps the stop token in the cache and prefills only the increment). Tool calls are not parsed (the model's `<function=…>` XML form is returned as text). The model's own template defaults to thinking; this bundle does not expose a switch — in a probe with a `<think>` prefill the model closed its thought and answered 9 of 9 times, so a thinking variant is possible as a metadata re-pack.

## Performance

Mac M4 Max, litert-lm 0.18.0, `benchmark … -p 256 -d 256 --runs 3 --cache no` (one warmup, mean of 3); GPU is the runtime's WebGPU backend on Apple silicon. Image rows are one cold `run` process (load + engine + vision + prefill + answer).

| Mac M4 Max | Prefill 256 (tok/s) | Decode 256 (tok/s) | TTFT (s) | Image 448 + question, cold process (s) |
|---|---|---|---|---|
| VL_int8 GPU | 2038.66 | 146.72 | 0.1348 | 25.89 (the engine compile dominates) |
| VL_int8 CPU | 624.72 | 42.65 | 0.4494 | 16.49 |

Phones, Android 16, prebuilt `litert_lm_advanced_main` published 2026-09-18 (sha256 adac974b…), `--benchmark=true --benchmark_prefill_tokens=1024 --benchmark_decode_tokens=256 --max_num_tokens=2048 --num_iterations=3`; the image rows are one process with the 448×448 photo and "What is in this image?", first token = vision encode + prefill of the 88-token prompt.

| Galaxy S26 (SM-S942Q, SM8850, Adreno) | Prefill 1024 (tok/s) | Decode 256 (tok/s) | TTFT (s) | Init (s) | Peak (MB) | Image: first token (s) / decode (tok/s) |
|---|---|---|---|---|---|---|
| VL_int8 CPU | 476.75 | 36.46 | 2.20 | 5.70 | 1,984 | 1.95 / 37.5 |
| VL_int8 GPU (vision CPU) | 663.61 | 23.56 | 1.65 | 53.3 | 3,710 | 2.11 / 33.0 |
| VL_int8 GPU (vision GPU) | — | — | — | 50.8 | 4,043 | **0.93** / 32.5 |

The CPU rows are sustained-run numbers: the phone lowered its CPU clock in 14 of 16 samples taken 2 s apart; the GPU clock limit fell from 1300 MHz in 13 of 44 samples. The GPU init is the cold number of this kit, which rebuilds its program cache on every process. The NPU path of litert-lm 0.18.0 takes static-range int8 and float graphs; this dynamic-range int8 decoder is rejected by the Qualcomm compiler (DynamicUpdateSlice) and the file runs on the CPU and GPU.

| Pixel 8a (Tensor G3, Mali, 7.75 GB) | Image: first token (s) | Decode (tok/s) | Peak (MB) |
|---|---|---|---|
| VL_int8vis CPU (vision CPU) | 2.2–3.0 (three photos) | 21.6–22.1 | 2,324–2,553 |
| VL_int8 CPU | 7.4–8.5 (the fp16 encoder unpacked on the CPU) | 20.0–20.5 | 3,927–4,314 |

The decoder's GPU engine did not build on this 8 GB phone: available memory fell below our 400,000 kB safety line while the fourth of seven signatures was initializing, and the harness stopped the process (one attempt, by rule). Read the Android GPU rows above as Galaxy S26 (Adreno) only: on this 8 GB Mali phone the decoder GPU engine does not build within that memory line, so use the CPU backend on 8 GB phones (the `_int8vis` file).

| iPhone 18 Pro (iOS 27.2, LiteRT-LM Swift runtime v0.16.0) | Decode (tok/s) | TTFT (s) | Peak (MB) | Engine load (s) |
|---|---|---|---|---|
| Text, Metal | 72.85 | 0.489 | 3,634 | 16.7 |
| Text, CPU | 43.14 | 0.602 | 642 | 3.8 |
| Image, CPU (vision CPU) | 42.12 | 1.380 | 1,243 | 3.6 |
| Image, Metal (vision Metal) | 73.65 | 4.641 | 5,998 | 16.8 |
| Image, Metal decoder + CPU vision | 73.19 | 2.481 | 3,517 | 16.5 |

Medians of three processes, one Engine per process (the 138-token eight-question prompt; the image probe is a 560×560 text image + question, 117 tokens). With one Engine per conversation the engine load counts once per conversation, so a short text chat completes sooner on the CPU; long answers are faster on Metal.

## Conversion notes

- **Source and stack:** openbmb/MiniCPM-V-4.7-1B at revision 984a89aa. Text decoder: a text-only checkpoint derived from the VLM weights (`model.language_model.*` → `model.*`, untied `lm_head`, `qwen3_5_text` config; logits bit-identical to the full model) exported with litert-torch at commit 115a136 plus the Qwen3.5 hybrid patch of [hf-to-litertlm](https://github.com/john-rocky/hf-to-litertlm) (`qwen35_work/`), transformers 5.14.1, ai-edge-quantizer 0.8.0; 7 signatures (prefill 1024, 256, 64, 16, 4, 1 and decode) over a 4096-token KV budget, embedder externalized for the `fast_vlm` path. Vision: the tower re-authored in plain PyTorch from the checkpoint weights (window attention as a block-diagonal mask in raster order, the two 2×2 merges as strided slices, LayerNorm inputs pre-scaled by calibrated powers of two for fp16), exported with litert-torch 0.9.3 / litert-converter 0.4.0 (no GATHER, FLEX or custom ops), reference = the model's `get_image_features` in transformers 5.19.0. Packed with litert-lm-builder 0.15.0 plus the ExecutorMetadata section the hybrid runtime binds its 48 state buffers with.
- **Quantization:** dynamic int8 on the decoder's linears and embedding; the 36 GatedDeltaNet decay/gate projections (`in_proj_a` / `in_proj_b`) stay float — on the CPU their run-time int8 activation quantization made the Qwen3.5 siblings loop on arithmetic. The vision adapter stays fp16: its int8 form lost correlation (0.9969 end to end) through the same CPU activation quantization. The GPU runs the decoder with fp32 activations (declared in the bundle; the family's fp16 default breaks on a layer-0 head).
- **One graph detail worth knowing:** the vocabulary (248,094) is not a multiple of 4, so the GPU sampler's top-k kernel logs an error and works on the first 248,092 logits — the two ids left out are the video `<timestamp>` tokens, which never appear in image or text answers. Padding the logits to 248,096 removed the message and changed the GPU decode speed by −0.5 % (within run-to-run noise), so the file ships unpadded.
- **Template:** metadata re-pack after the device runs (the newline placement for multi-turn prompts described under Usage); the TFLite sections, tokenizer and executor metadata are byte-identical to the measured files.

## License and changes

Apache-2.0, inherited from [openbmb/MiniCPM-V-4.7-1B](https://huggingface.co/openbmb/MiniCPM-V-4.7-1B). **Changes from the original work:** exported to the LiteRT-LM format, text decoder quantized to int8 and the vision encoder to fp16 (or int8 in the `_int8vis` file) as described above; the chat template is a dual-form ChatML template with thinking off; stop tokens `<|im_end|>` and `<|endoftext|>`. Problems with these files: open a discussion in this repo.
