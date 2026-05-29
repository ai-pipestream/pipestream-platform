package ai.pipestream.module.runtime.work;

import ai.pipestream.module.work.v1.ModuleWorkServiceGrpc;

/**
 * Shared engine transport for {@link ModuleWorkerLoop}. One {@link io.grpc.ManagedChannel}
 * is reused across concurrent virtual-thread workers (HTTP/2 stream multiplexing).
 *
 * <p><b>Do NOT call {@link #reconnect()} on a per-stream
 * {@link WorkStreamSession.Outcome#STREAM_ERROR}.</b> Because the channel is shared by
 * every worker, dropping it cancels all sibling workers' in-flight Work streams, which
 * cascades into a fleet-wide reconnect storm and can quarantine an in-flight document on
 * the engine. A single stream error is call-scoped; gRPC's {@code ManagedChannel}
 * already heals the transport itself (re-resolve + reconnect with backoff). Reserve
 * {@code reconnect()} for shutdown or a confirmed channel-level failure.
 */
public interface ModuleWorkEngineClient {

    /** Async stub on the shared channel — cheap to call each session. */
    ModuleWorkServiceGrpc.ModuleWorkServiceStub stub();

    /**
     * Force a full teardown of the current channel; the next {@link #stub()} opens a
     * fresh one. Cancels every in-flight call on the channel, so this is only safe for
     * shutdown or a channel that is confirmed dead — never as a per-stream-error retry
     * hook (see the type-level note).
     */
    void reconnect();
}
