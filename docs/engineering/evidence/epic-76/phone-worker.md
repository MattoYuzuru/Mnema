# Five-minute phone-stream processing check

On 2026-09-28, a real iPhone 6 MOV was downloaded from the open [VISION research
dataset](https://lesc.dinfo.unifi.it/VISION/dataset/D06_Apple_iPhone6/videos/outdoor/D06_V_outdoor_move_0001.mov)
outside the repository. Its SHA-256 was
`47187ae6398c3bf4554c3e865118febe2084e3a3fb532267e47ccb87f1d26811`;
`ffprobe` found Apple/iPhone 6 metadata, 1920×1080 H.264 video, AAC audio,
70.051678 seconds and 151,028,351 bytes. No dataset bytes are checked in.

To exercise the accepted five-minute bound with a phone-origin codec stream,
FFmpeg stream-copied repeated segments of that original MOV using
`-stream_loop 4 -t 299.9 -map 0:v:0 -map 0:a:0 -c copy -movflags +faststart`.
The resulting fixture was 299.915 seconds and 646,269,796 bytes. Repetition
changes the container and timing; this is not an uninterrupted camera take.

The local `mnema-media-worker:local` image processed it with no network,
read-only root, all capabilities dropped, 2 CPUs and 3 GiB memory. It returned:

| Artifact | Duration | Bytes | SHA-256 | Verification |
| --- | ---: | ---: | --- | --- |
| Source MOV | 299.915 s | 646,269,796 | `83424bb7f7d570a93299608f498893f8bf3607e8ade881cb24cedb729b834562` | Worker and independent Python hash |
| Playback MP4 | 299.909 s | 304,433,225 | `eb6e4b70932c3ccf1a4893c0ed971c6c15ba8bc6e3abd4f27faa87e31eb4d77f` | Independent hash/length and ffprobe H.264 High/AAC LC |
| Poster WebP | — | 105,064 | `82bab6ebdfb7eb6f9deb271ec46c44d915293b90c8dfeebc271eba2d4973fe17` | Independent hash/length |

The worker stayed below its resource envelope. This verifies processing and
output identity for a high-bitrate phone-derived file close to the duration
limit; it does not measure browser upload, a continuous five-minute recording,
Yandex Object Storage or a production processing SLA.
