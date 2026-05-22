package ai.pipestream.server.work;

import ai.pipestream.module.work.v1.ModuleWorkServiceGrpc;

/**
 * Shared engine transport for {@link ModuleWorkerLoop}. One {@link io.grpc.ManagedChannel}
 * is reused across concurrent virtual-thread workers (HTTP/2 stream multiplexing).
 * Call {@link #reconnect()} after {@link WorkStreamSession.Outcome#STREAM_ERROR} so
 * boot-order / resolver wedge issues get a fresh transport without paying TCP setup
 * on every work unit.
 */
public interface ModuleWorkEngineClient {

    /** Async stub on the shared channel — cheap to call each session. */
    ModuleWorkServiceGrpc.ModuleWorkServiceStub stub();

    /** Drop the current channel; next {@link #stub()} opens a new one. */
    void reconnect();
}
