package app.mnema.learning.generation;

import app.mnema.learning.ai.prompt.AssembledPrompt;
import app.mnema.learning.generation.mbm.MbmOptions;

/**
 * Everything one TEXT_DRAFT run needs from the database, read before any provider call: the assembled prompt, the options
 * the MBM compiler runs with, and the output bound.
 *
 * @param prompt segments in cache-friendly order and the prompt version recorded on the revision
 * @param options the compile options: the link allowlist of the session and the declared media bound
 * @param maxTokens the output bound of the provider call (from the effort and the remaining reservation)
 * @param temperature the sampling temperature of a material (0.7 to 0.9)
 */
record DraftContext(AssembledPrompt prompt, MbmOptions options, int maxTokens, double temperature) { }
