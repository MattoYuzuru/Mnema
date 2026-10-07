# Historical self-host model matrix

> **Status: legacy.** This matrix described the removed v1 self-host launcher and
> its local AI, audio and image gateways; it is not a current deployment recommendation.

The full [original matrix](https://github.com/MattoYuzuru/Mnema/blob/v1-apache-final/docs/deploy/model-matrix.md)
and gateway sources — [AI](https://github.com/MattoYuzuru/Mnema/tree/v1-apache-final/scripts/local-ai-gateway),
[audio](https://github.com/MattoYuzuru/Mnema/tree/v1-apache-final/scripts/local-audio-gateway),
[image](https://github.com/MattoYuzuru/Mnema/tree/v1-apache-final/scripts/local-image-gateway) — are retained in
[`v1-apache-final`](https://github.com/MattoYuzuru/Mnema/tree/v1-apache-final).
All three unused adapters are retired from the current checkout; recovery uses that tag.

Current Java Learning owns the [provider layer](../../backend/services/learning/guide.md#ai-provider-layer),
[image search](../../backend/services/learning/guide.md#image-search-296) and
[speech synthesis](../../backend/services/learning/guide.md#speech-synthesis-297), following the
[AI architecture](../architecture/ai-generation-platform.md#9-провайдеры-и-capabilities) and
[generation contract](../../contracts/generation/README.md). Image generation remains unavailable;
removing old gateways does not activate `IMAGE_GENERATE`.

For the supported private local runtime, use the [current local runbook](selfhost-local.md#supported-full-stack-launcher--220);
for provider flags and failure boundaries, use the [AI runbook](../operations/ai-runbook.md).
