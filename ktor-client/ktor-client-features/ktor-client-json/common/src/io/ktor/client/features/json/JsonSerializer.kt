/*
 * Copyright 2014-2019 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.client.features.json

import io.ktor.client.call.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.utils.io.core.*

/**
 * Client json serializer.
 */
interface JsonSerializer {
    /**
     * Convert data object to [OutgoingContent].
     */
    fun write(data: Any, contentType: ContentType): OutgoingContent

    /**
     * Convert data object to [OutgoingContent].
     */
    fun write(data: Any): OutgoingContent = write(data, ContentType.Application.Json)

    /**
     * Read content from response using information specified in [type].
     */
    fun read(type: TypeInfo, body: Input): Any
}
