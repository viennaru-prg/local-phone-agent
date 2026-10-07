---
license: apache-2.0
language:
- ko
pipeline_tag: automatic-speech-recognition
tags:
- icefall
---

See https://github.com/k2-fsa/icefall/pull/1651

# icefall-asr-ksponspeech-pruned-transducer-stateless7-streaming-2024-06-12

KsponSpeech is a large-scale spontaneous speech corpus of Korean.
This corpus contains 969 hours of open-domain dialog utterances,
spoken by about 2,000 native Korean speakers in a clean environment.

All data were constructed by recording the dialogue of two people
freely conversing on a variety of topics and manually transcribing the utterances.

The transcription provides a dual transcription consisting of orthography and pronunciation,
and disfluency tags for spontaneity of speech, such as filler words, repeated words, and word fragments.

The original audio data has a pcm extension.
During preprocessing, it is converted into a file in the flac extension and saved anew.

KsponSpeech is publicly available on an open data hub site of the Korea government.
The dataset must be downloaded manually.

For more details, please visit:

 - Dataset: https://aihub.or.kr/aihubdata/data/view.do?currMenu=115&topMenu=100&aihubDataSe=realm&dataSetSn=123
 - Paper: https://www.mdpi.com/2076-3417/10/19/6936

### Streaming Zipformer-Transducer (Pruned Stateless Transducer + Streaming Zipformer)

Number of model parameters: 79,022,891, i.e., 79.02 M

#### Training on KsponSpeech (with MUSAN)

The CERs are:

| decoding method      | chunk size | eval_clean | eval_other | comment             | decoding mode        |
|----------------------|------------|------------|------------|---------------------|----------------------|
| greedy search        | 320ms      | 10.21      | 11.07      | --epoch 30 --avg 9  | simulated streaming  |
| greedy search        | 320ms      | 10.22      | 11.07      | --epoch 30 --avg 9  | chunk-wise           |
| fast beam search     | 320ms      | 10.21      | 11.04      | --epoch 30 --avg 9  | simulated streaming  |
| fast beam search     | 320ms      | 10.25      | 11.08      | --epoch 30 --avg 9  | chunk-wise           |
| modified beam search | 320ms      | 10.13      | 10.88      | --epoch 30 --avg 9  | simulated streaming  |
| modified beam search | 320ms      | 10.1       | 10.93      | --epoch 30 --avg 9  | chunk-size           |
| greedy search        | 640ms      | 9.94       | 10.82      | --epoch 30 --avg 9  | simulated streaming  |
| greedy search        | 640ms      | 10.04      | 10.85      | --epoch 30 --avg 9  | chunk-wise           |
| fast beam search     | 640ms      | 10.01      | 10.81      | --epoch 30 --avg 9  | simulated streaming  |
| fast beam search     | 640ms      | 10.04      | 10.7       | --epoch 30 --avg 9  | chunk-wise           |
| modified beam search | 640ms      | 9.91       | 10.72      | --epoch 30 --avg 9  | simulated streaming  |
| modified beam search | 640ms      | 9.92       | 10.72      | --epoch 30 --avg 9  | chunk-size           |

Note: `simulated streaming` indicates feeding full utterance during decoding using `decode.py`,
while `chunk-size` indicates feeding certain number of frames at each time using `streaming_decode.py`.