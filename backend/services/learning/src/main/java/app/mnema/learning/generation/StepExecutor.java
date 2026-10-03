package app.mnema.learning.generation;

import app.mnema.learning.ai.AiCapability;

/**
 * Runs one kind of step. A kind without an executor in this process is never claimed: the media steps that
 * {@code TEXT_DRAFT} creates stay READY until AI-09 and AI-10 register theirs.
 */
interface StepExecutor {
    /** The {@code generation_step.kind} this executor runs. */
    String kind();

    /**
     * The member a step's input must have for this executor to claim it, or null for every step of its kind. The Stub speech executor
     * runs the media turns of an exercise ({@code turnId}) and leaves the media steps of a material's slots alone.
     */
    default String requiredInput() { return null; }

    /** The provider capability whose per-instance permit the run takes. */
    AiCapability capability();

    /**
     * Runs a claimed step to a result, a failure or a cancellation, writing each through {@link SessionLifecycle}. It is
     * called outside any database transaction and must never open one around a provider call. A thrown exception leaves
     * the step RUNNING: its lease expires and the step is recovered.
     */
    void execute(StepClaim claim, StepControl control);

    /** What the dispatcher tells a running step: stop (cancelled) or forget it (lease lost). */
    interface StepControl {
        /** The owner cancelled the session; abort the provider call and record the step as cancelled. */
        boolean cancelled();

        /** The run is about to enter a provider call: only now may the dispatcher interrupt the thread to abort it. */
        void callStarted();

        /** The provider call is over: no interrupt may arrive afterwards, and one that is pending is discarded. */
        void callEnded();

        /** The lease is gone (expired and recovered, or the heartbeat cannot reach the database): write nothing. */
        boolean lost();
    }
}
