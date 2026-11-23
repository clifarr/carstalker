package com.carstalker.core.transport

/**
 * A byte pipe to an OBD-II adapter (Bluetooth LE, USB serial, or TCP).
 *
 * Implementations are used from a single IO dispatcher by [com.carstalker.core.elm.Elm327Client],
 * so they do not need to be thread-safe on their own, but must never block indefinitely:
 * [read] returns an empty array when the timeout elapses.
 */
interface Transport {
    val displayName: String

    suspend fun connect()

    suspend fun write(data: ByteArray)

    /** Reads currently available bytes, waiting up to [timeoutMs]. Empty result means "nothing arrived". */
    suspend fun read(timeoutMs: Long): ByteArray

    /**
     * Discards everything the adapter has already sent. Must not wait for new data:
     * anything still in flight after a response is stale and would desync the next exchange.
     */
    suspend fun discardPending()

    suspend fun close()
}
