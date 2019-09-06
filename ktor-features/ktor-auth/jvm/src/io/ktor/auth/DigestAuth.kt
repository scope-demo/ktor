/*
 * Copyright 2014-2019 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.auth

import io.ktor.application.*
import io.ktor.http.*
import io.ktor.http.auth.*
import io.ktor.response.*
import io.ktor.util.*
import java.security.*

/**
 * Represents a Digest authentication provider
 * @property realm specifies value to be passed in `WWW-Authenticate` header
 * @property algorithmName Message digest algorithm to be used. Usually only `MD5` is supported by clients.
 */
class DigestAuthenticationProvider internal constructor(
    config: Configuration
) : AuthenticationProvider(config) {

    val realm: String = config.realm

    @KtorExperimentalAPI
    val algorithmName: String = config.algorithmName

    @KtorExperimentalAPI
    internal val nonceManager: NonceManager = config.nonceManager

    @KtorExperimentalAPI
    internal val userNameRealmPasswordDigestProvider: suspend (String, String) -> ByteArray? = config.digestProvider

    /**
     * Digest auth configuration
     */
    class Configuration internal constructor(name: String?) : AuthenticationProvider.Configuration(name) {
        internal var digestProvider : DigestProviderFunction = { userName, realm ->
            MessageDigest.getInstance(algorithmName).let { digester ->
                digester.reset()
                digester.update("$userName:$realm".toByteArray(Charsets.UTF_8))
                digester.digest()
            }
        }

        /**
         * Specifies realm to be passed in `WWW-Authenticate` header
         */
        var realm: String = "Ktor Server"

        /**
         * Message digest algorithm to be used. Usually only `MD5` is supported by clients.
         */
        var algorithmName: String = "MD5"

        /**
         * [NonceManager] to be used to generate nonce values
         */
        @KtorExperimentalAPI
        var nonceManager: NonceManager = GenerateOnlyNonceManager

        /**
         * username and password digest function
         */
        @Suppress("unused")
        @Deprecated("Use digestProvider { } function instead.", level = DeprecationLevel.ERROR)
        var userNameRealmPasswordDigestProvider: DigestProviderFunction
            get() = digestProvider
            set(newProvider) {
                digestProvider = newProvider
            }

        /**
         * Configures digest provider function that should fetch or compute message digest for the specified
         * `userName` and `realm`. A message digest is usually computed based on user name (login), realm and password
         * concatenated with colon character ':'. For example `"$userName:$realm:$password"`.
         */
        @KtorExperimentalAPI
        fun digestProvider(digest: suspend (userName: String, realm: String) -> ByteArray?) {
            digestProvider = digest
        }
    }
}

/**
 * Provides message digest for the specified username and realm or returns `null` if the user is missing.
 * This function could fetch digest from a database or compute it instead.
 */
typealias DigestProviderFunction = suspend (userName: String, realm: String) -> ByteArray?

/**
 * Installs Digest Authentication mechanism
 */
fun Authentication.Configuration.digest(
    name: String? = null,
    configure: DigestAuthenticationProvider.Configuration.() -> Unit
) {
    val provider = DigestAuthenticationProvider(DigestAuthenticationProvider.Configuration(name).apply(configure))

    provider.pipeline.intercept(AuthenticationPipeline.RequestAuthentication) { context ->
        val authorizationHeader = call.request.parseAuthorizationHeader()
        val credentials = authorizationHeader?.let { authHeader ->
            if (authHeader.authScheme == AuthScheme.Digest && authHeader is HttpAuthHeader.Parameterized) {
                authHeader.toDigestCredential()
            } else
                null
        }

        val principal = credentials?.let {
            if ((it.algorithm ?: "MD5") == provider.algorithmName
                && it.realm == provider.realm
                && provider.nonceManager.verifyNonce(it.nonce)
                && it.verifier(
                    call.request.local.method, MessageDigest.getInstance(provider.algorithmName),
                    provider.userNameRealmPasswordDigestProvider
                )
            )
                UserIdPrincipal(it.userName)
            else
                null
        }

        when (principal) {
            null -> {
                val cause = when (credentials) {
                    null -> AuthenticationFailedCause.NoCredentials
                    else -> AuthenticationFailedCause.InvalidCredentials
                }

                context.challenge(digestAuthenticationChallengeKey, cause) {
                    call.respond(
                        UnauthorizedResponse(
                            HttpAuthHeader.digestAuthChallenge(
                                provider.realm,
                                algorithm = provider.algorithmName,
                                nonce = provider.nonceManager.newNonce()
                            )
                        )
                    )
                    it.complete()
                }
            }
            else -> context.principal(principal)
        }
    }

    register(provider)
}

/**
 * Represents Digest credentials
 *
 * For details see [RFC2617](http://www.faqs.org/rfcs/rfc2617.html)
 *
 * @property realm digest auth realm
 * @property userName
 * @property digestUri may be an absolute URI or `*`
 * @property nonce
 * @property opaque a string of data that is passed through unchanged
 * @property nonceCount must be sent if [qop] is specified and must be `null` otherwise
 * @property algorithm digest algorithm name
 * @property response consist of 32 hex digits (digested password and other fields as per RFC)
 * @property cnonce must be sent if [qop] is specified and must be `null` otherwise. Should be passed through unchanged.
 * @property qop quality of protection sign
 */
data class DigestCredential(
    val realm: String,
    val userName: String,
    val digestUri: String,
    val nonce: String,
    val opaque: String?,
    val nonceCount: String?,
    val algorithm: String?,
    val response: String,
    val cnonce: String?,
    val qop: String?
) : Credential

/**
 * Retrieves [DigestCredential] from this call
 */
fun ApplicationCall.digestAuthenticationCredentials(): DigestCredential? {
    return request.parseAuthorizationHeader()?.let { authHeader ->
        if (authHeader.authScheme == AuthScheme.Digest && authHeader is HttpAuthHeader.Parameterized) {
            return authHeader.toDigestCredential()
        } else {
            null
        }
    }
}

private val digestAuthenticationChallengeKey: Any = "DigestAuth"

/**
 * Converts [HttpAuthHeader] to [DigestCredential]
 */
fun HttpAuthHeader.Parameterized.toDigestCredential(): DigestCredential = DigestCredential(
    parameter("realm")!!,
    parameter("username")!!,
    parameter("uri")!!,
    parameter("nonce")!!,
    parameter("opaque"),
    parameter("nc"),
    parameter("algorithm"),
    parameter("response")!!,
    parameter("cnonce"),
    parameter("qop")
)

/**
 * Verifies credentials are valid for given [method] and [digester] and [userNameRealmPasswordDigest]
 */
suspend fun DigestCredential.verifier(
    method: HttpMethod,
    digester: MessageDigest,
    userNameRealmPasswordDigest: suspend (String, String) -> ByteArray?
): Boolean {
    val userNameRealmPasswordDigestResult = userNameRealmPasswordDigest(userName, realm)
    val validDigest = expectedDigest(method, digester, userNameRealmPasswordDigestResult ?: ByteArray(0))

    val incoming: ByteArray = try {
        hex(response)
    } catch (e: NumberFormatException) {
        return false
    }

    // here we do null-check in the end because it should be always time-constant comparison due to security reasons
    return MessageDigest.isEqual(incoming, validDigest) && userNameRealmPasswordDigestResult != null
}

/**
 * Calculates expected digest bytes for this [DigestCredential]
 */
fun DigestCredential.expectedDigest(
    method: HttpMethod,
    digester: MessageDigest,
    userNameRealmPasswordDigest: ByteArray
): ByteArray {
    fun digest(data: String): ByteArray {
        digester.reset()
        digester.update(data.toByteArray(Charsets.ISO_8859_1))
        return digester.digest()
    }

    // H(A1) in the RFC
    val start = hex(userNameRealmPasswordDigest)

    // H(A2) in the RFC
    val end = hex(digest("${method.value.toUpperCase()}:$digestUri"))

    val hashParameters = when (qop) {
        null -> listOf(start, nonce, end)
        else -> listOf(
            start,
            nonce,
            nonceCount,
            cnonce,
            qop,
            end
        )
    }.joinToString(":")

    return digest(hashParameters)
}
