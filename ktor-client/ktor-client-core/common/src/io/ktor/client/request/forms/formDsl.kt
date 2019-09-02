/*
 * Copyright 2014-2019 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.client.request.forms

import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.util.*
import kotlinx.io.core.*
import kotlin.contracts.*

/**
 * Multipart form item. Use it to build form in client.
 *
 * @param key multipart name
 * @param value content, could be [String], [Number] or [Input]
 * @param headers part headers, note that some servers may fail if an unknown header provided
 */
data class FormPart<T : Any>(val key: String, val value: T, val headers: Headers = Headers.Empty)

/**
 * Build multipart form from [values].
 */
fun formData(vararg values: FormPart<*>): List<PartData> {
    val result = mutableListOf<PartData>()

    values.forEach { (key, value, headers) ->
        val partHeaders = HeadersBuilder().apply {
            append(HttpHeaders.ContentDisposition, "form-data; name=$key")
            appendAll(headers)
        }
        val part = when (value) {
            is String -> PartData.FormItem(value, {}, partHeaders.build())
            is Number -> PartData.FormItem(value.toString(), {}, partHeaders.build())
            is ByteArray -> {
                partHeaders.append(HttpHeaders.ContentLength, value.size.toString())
                PartData.BinaryItem({ ByteReadPacket(value) }, {}, partHeaders.build())
            }
            is ByteReadPacket -> {
                partHeaders.append(HttpHeaders.ContentLength, value.remaining.toString())
                PartData.BinaryItem({ value.copy() }, { value.close() }, partHeaders.build())
            }
            is InputProvider -> {
                val size = value.size
                if (size != null) {
                    partHeaders.append(HttpHeaders.ContentLength, size.toString())
                }
                PartData.BinaryItem(value.block, {}, partHeaders.build())
            }
            is Input -> error("Can't use [Input] as part of form: $value. Consider using [InputProvider] instead.")
            else -> error("Unknown form content type: $value")
        }

        result += part
    }

    return result
}

/**
 * Build multipart form using [block] function.
 */
fun formData(block: FormBuilder.() -> Unit): List<PartData> =
    formData(*FormBuilder().apply(block).build().toTypedArray())

/**
 * Form builder type used in [formData] builder function.
 */
class FormBuilder internal constructor() {
    private val parts = mutableListOf<FormPart<*>>()

    /**
     * Append a pair [key]:[value] with optional [headers].
     */
    @InternalAPI
    fun <T : Any> append(key: String, value: T, headers: Headers = Headers.Empty) {
        parts += FormPart(key, value, headers)
    }

    /**
     * Append a pair [key]:[value] with optional [headers].
     */
    fun append(key: String, value: String, headers: Headers = Headers.Empty) {
        parts += FormPart(key, value, headers)
    }

    /**
     * Append a pair [key]:[value] with optional [headers].
     */
    fun append(key: String, value: Number, headers: Headers = Headers.Empty) {
        parts += FormPart(key, value, headers)
    }

    /**
     * Append a pair [key]:[value] with optional [headers].
     */
    fun append(key: String, value: ByteArray, headers: Headers = Headers.Empty) {
        parts += FormPart(key, value, headers)
    }

    /**
     * Append a pair [key]:[value] with optional [headers].
     */
    fun append(key: String, value: InputProvider, headers: Headers = Headers.Empty) {
        parts += FormPart(key, value, headers)
    }

    /**
     * Append a pair [key]:[InputProvider(block)] with optional [headers].
     */
    fun appendInput(key: String, headers: Headers = Headers.Empty, size: Long? = null, block: () -> Input) {
        parts += FormPart(key, InputProvider(size, block), headers)
    }

    /**
     * Append a pair [key]:[value] with optional [headers].
     */
    fun append(key: String, value: ByteReadPacket, headers: Headers = Headers.Empty) {
        parts += FormPart(key, value, headers)
    }

    /**
     * Append a pair [key]:[value] with optional [headers].
     */
    @Deprecated(
        "Input is not reusable. Please use [InputProvider] instead.",
        level = DeprecationLevel.ERROR,
        replaceWith = ReplaceWith("appendInput(key, headers) { /* create fresh input here */ }")
    )
    fun append(key: String, value: Input, headers: Headers = Headers.Empty) {
    }

    /**
     * Append a form [part].
     */
    fun <T : Any> append(part: FormPart<T>) {
        parts += part
    }

    internal fun build(): List<FormPart<*>> = parts
}

/**
 * Append a form part with the specified [key] using [bodyBuilder] for it's body.
 */
@UseExperimental(ExperimentalContracts::class)
inline fun FormBuilder.append(
    key: String,
    headers: Headers = Headers.Empty,
    size: Long? = null,
    crossinline bodyBuilder: BytePacketBuilder.() -> Unit
) {
    contract {
        callsInPlace(bodyBuilder, InvocationKind.EXACTLY_ONCE)
    }
    append(FormPart(key, InputProvider(size) { buildPacket { bodyBuilder() } }, headers))
}

/**
 * Reusable [Input] form entry.
 *
 * @param block: content generator
 */
@KtorExperimentalAPI
class InputProvider(val size: Long? = null, val block: () -> Input)


/**
 * Append a form part with the specified [key], [filename] and optional [contentType] using [bodyBuilder] for it's body.
 */
@UseExperimental(ExperimentalContracts::class)
fun FormBuilder.append(
    key: String,
    filename: String,
    contentType: ContentType? = null,
    size: Long? = null,
    bodyBuilder: BytePacketBuilder.() -> Unit
) {
    contract {
        callsInPlace(bodyBuilder, InvocationKind.EXACTLY_ONCE)
    }

    val headersBuilder = HeadersBuilder()
    headersBuilder[HttpHeaders.ContentDisposition] = "filename=$filename"
    contentType?.run { headersBuilder[HttpHeaders.ContentType] = this.toString() }
    val headers = headersBuilder.build()

    append(key, headers, size, bodyBuilder)
}
