package app.mnema.learning.speech;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * The housekeeping of speech inputs, in every runtime role (an {@code api} process has no {@link SpeechInputWorker} but still holds the rows and the
 * audio it admitted): fails the inputs past their deadline ({@code UNAVAILABLE}, a crashed or absent worker included), deletes any audio left behind a
 * terminal input and purges the rows past {@code expires_at}. Every statement is idempotent and bounded, so every instance may run it.
 */
@Component
class SpeechInputSweeper {
    private static final Logger LOG = LoggerFactory.getLogger(SpeechInputSweeper.class);
    private static final int PURGE_BATCH = 200;

    private final SpeechInputRepository repository;
    private final MeterRegistry meters;

    SpeechInputSweeper(SpeechInputRepository repository, MeterRegistry meters) {
        this.repository = repository;
        this.meters = meters;
    }

    @Scheduled(initialDelayString = "${learning.speech.sweep-interval:PT2S}", fixedDelayString = "${learning.speech.sweep-interval:PT2S}")
    void sweep() {
        try {
            for (UUID overdue : repository.failOverdue()) {
                meters.counter("mnema_stt_inputs_total", "outcome", "UNAVAILABLE").increment();
                LOG.info("speech_input_done speech_input_id={} outcome=UNAVAILABLE reason=deadline", overdue);
            }
            repository.dropFinishedAudio();
            repository.purge(PURGE_BATCH);
        } catch (RuntimeException failure) {
            LOG.warn("speech_input_sweep_failed error_type={}", failure.getClass().getSimpleName());
        }
    }
}
