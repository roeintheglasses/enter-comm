package com.entercomm.bikeintercom.util

/**
 * Sealed class for consistent error handling across the app.
 * Provides a unified way to represent success/failure states.
 */
sealed class Result<out T> {
    data class Success<T>(val data: T) : Result<T>()
    data class Error(val exception: Throwable? = null, val message: String) : Result<Nothing>()
    data object Loading : Result<Nothing>()

    val isSuccess: Boolean get() = this is Success
    val isError: Boolean get() = this is Error
    val isLoading: Boolean get() = this is Loading

    fun getOrNull(): T? = when (this) {
        is Success -> data
        else -> null
    }

    fun getOrDefault(default: @UnsafeVariance T): T = when (this) {
        is Success -> data
        else -> default
    }

    fun <R> map(transform: (T) -> R): Result<R> = when (this) {
        is Success -> Success(transform(data))
        is Error -> this
        is Loading -> Loading
    }

    fun onSuccess(action: (T) -> Unit): Result<T> {
        if (this is Success) action(data)
        return this
    }

    fun onError(action: (String, Throwable?) -> Unit): Result<T> {
        if (this is Error) action(message, exception)
        return this
    }

    companion object {
        fun <T> success(data: T): Result<T> = Success(data)
        fun error(message: String, exception: Throwable? = null): Result<Nothing> = Error(exception, message)
        fun loading(): Result<Nothing> = Loading

        inline fun <T> runCatching(block: () -> T): Result<T> {
            return try {
                Success(block())
            } catch (e: Exception) {
                Error(e, e.message ?: "Unknown error")
            }
        }
    }
}

/**
 * Mesh-specific error types for better error categorization
 */
sealed class MeshError(val message: String, val cause: Throwable? = null) {
    class ConnectionFailed(message: String, cause: Throwable? = null) : MeshError(message, cause)
    class PermissionDenied(message: String) : MeshError(message)
    class NetworkUnavailable(message: String) : MeshError(message)
    class AudioError(message: String, cause: Throwable? = null) : MeshError(message, cause)
    class ServiceError(message: String, cause: Throwable? = null) : MeshError(message, cause)
    class Timeout(message: String) : MeshError(message)
    class Unknown(message: String, cause: Throwable? = null) : MeshError(message, cause)
}
