# نموذج كلمة التنبيه «يا نفّذ»

- النموذج: sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01 (رخصة Apache 2.0)
  من https://github.com/k2-fsa/sherpa-onnx/releases/tag/kws-models
- `keywords.txt`: نطق «يا نفّذ» بحروف إنجليزية مقسّمة لوحدات النموذج (BPE)، ولّدناها بـ:
  `sherpa-onnx-cli text2token --tokens tokens.txt --tokens-type bpe --bpe-model bpe.model raw.txt keywords.txt`
  الصيغة: `الوحدات :قوة_التحفيز #الحد @الاسم`. رفع الحد يقلل التشغيل الخاطئ، وتقليله يخليه يلتقط أسهل.
