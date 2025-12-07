/**
 * Integration test suite for the Dynamic gRPC extension.
 * <p>
 * These tests use TestContainers to run a real Consul instance and verify end-to-end behavior
 * including service registration, discovery, channel caching, eviction, and performance traits.
 * </p>
 */
package ai.pipestream.quarkus.dynamicgrpc;
