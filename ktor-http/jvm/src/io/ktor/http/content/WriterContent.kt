/*
 * Copyright 2014-2019 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.http.content

import io.ktor.http.*
import io.ktor.util.cio.*
import io.ktor.utils.io.*
import java.io.*

/**
 * Represents a content that is produced by [body] function
 */
class WriterContent(
    private val body: suspend Writer.() -> Unit,
    override val contentType: ContentType,
    override val status: HttpStatusCode? = null
) : OutgoingContent.WriteChannelContent() {

    override suspend fun writeTo(channel: ByteWriteChannel) {
        val charset = contentType.charset() ?: Charsets.UTF_8
        channel.writer(charset).use {
            it.body()
        }
    }
}
