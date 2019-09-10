/*
 * Copyright 2014-2019 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.http.websocket

import io.ktor.util.*
import io.ktor.utils.io.charsets.*
import io.ktor.utils.io.core.*


private const val WEBSOCKET_SERVER_ACCEPT_TAIL = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

/**
 * Calculates `Sec-WebSocket-Accept` header value
 */
@KtorExperimentalAPI
fun websocketServerAccept(nonce: String): String =
    sha1("${nonce.trim()}$WEBSOCKET_SERVER_ACCEPT_TAIL".toByteArray(Charset.forName("ISO_8859_1"))).encodeBase64()
