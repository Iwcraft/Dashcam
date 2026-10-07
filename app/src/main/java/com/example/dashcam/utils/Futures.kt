package com.example.dashcam.utils

import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/** CameraX returns ListenableFutures; this avoids pulling in a library just to await one. */
suspend fun <T> ListenableFuture<T>.await(executor: Executor): T =
    suspendCancellableCoroutine { continuation ->
        addListener(
            {
                try {
                    continuation.resume(get())
                } catch (e: ExecutionException) {
                    continuation.resumeWithException(e.cause ?: e)
                } catch (e: CancellationException) {
                    continuation.cancel(e)
                }
            },
            executor,
        )
        continuation.invokeOnCancellation { cancel(false) }
    }
