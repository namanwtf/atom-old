package io.github.namanwtf.atom.agent.auth

import io.github.namanwtf.atom.R
import io.github.namanwtf.atom.agent.runtime.AgentRunController
import io.github.namanwtf.atom.core.AppStrings
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal class LoopbackAuthorizationReceiver(port: Int = CodexOAuth.CALLBACK_PORT) : AutoCloseable {
    private val server = ServerSocket(port, 4, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 1_000 }
    val redirectUri: String = "http://127.0.0.1:${server.localPort}/auth/callback"

    fun awaitCode(state: String, controller: AgentRunController): String {
        val deadline = System.nanoTime() + 600_000_000_000L
        val binding = controller.register(server::close)
        try {
            while (System.nanoTime() < deadline) {
                controller.throwIfCancelled()
                val socket = try { server.accept() } catch (_: SocketTimeoutException) { continue }
                socket.use {
                    val socketBinding = controller.register(socket::close)
                    try {
                        val target = readRequest(socket)
                        val result = validateCallback(target, state)
                        try {
                            respond(
                                socket,
                                "200 OK",
                                AppStrings.get(R.string.auth_callback_success_html, "Authorization returned; return to Atom to finish signing in."),
                            )
                        } catch (_: java.io.IOException) {
                            // The browser may close the tab immediately after the callback; the verified authorization code is still handed to Atom for code exchange.
                            controller.throwIfCancelled()
                        }
                        return result
                    } catch (error: ProviderAuthException) {
                        try {
                            respond(
                                socket,
                                "400 Bad Request",
                                AppStrings.get(R.string.auth_callback_failed_html, "Authorization incomplete; return to Atom and retry."),
                            )
                        } catch (_: java.io.IOException) {
                            controller.throwIfCancelled()
                        }
                        if (error.code != "oauth_callback_invalid") throw error
                    } catch (_: java.io.IOException) {
                        controller.throwIfCancelled()
                    } finally {
                        socketBinding.close()
                    }
                }
            }
            throw ProviderAuthException(
                "oauth_login_expired",
                AppStrings.get(R.string.auth_err_login_timeout, "Sign-in timed out; please retry"),
            )
        } catch (_: java.io.IOException) {
            controller.throwIfCancelled()
            throw ProviderAuthException(
                "oauth_callback_unavailable",
                AppStrings.get(R.string.auth_err_callback_unavailable, "Unable to receive the sign-in callback; please retry"),
            )
        } finally {
            binding.close()
            close()
        }
    }

    internal fun validateCallback(target: String, state: String): String {
        fun invalid(): Nothing = throw ProviderAuthException(
            "oauth_callback_invalid",
            AppStrings.get(R.string.auth_err_callback_invalid, "Sign-in callback invalid"),
        )
        if (!target.startsWith("/auth/callback?")) invalid()
        val url = (redirectUri.substringBefore("/auth/callback") + target).toHttpUrlOrNull() ?: invalid()
        if (url.encodedPath != "/auth/callback" || url.fragment != null) invalid()
        fun value(name: String): String? {
            val values = url.queryParameterValues(name)
            if (values.size > 1) invalid()
            return values.singleOrNull()
        }
        val receivedState = value("state") ?: invalid()
        if (!MessageDigest.isEqual(state.toByteArray(Charsets.UTF_8), receivedState.toByteArray(Charsets.UTF_8))) invalid()
        value("iss")?.let { if (it != OAuthProvider.OPENAI.issuer) invalid() }
        value("error")?.let {
            throw ProviderAuthException(
                "oauth_access_denied",
                AppStrings.get(R.string.auth_err_access_denied, "Sign-in was not authorized"),
            )
        }
        val code = value("code")?.takeIf { it.isNotBlank() && it.length <= 8_192 } ?: invalid()
        return code
    }

    private fun readRequest(socket: Socket): String {
        socket.soTimeout = 1_000
        val deadline = System.nanoTime() + 3_000_000_000L
        val bytes = ByteArrayOutputStream()
        val input = socket.getInputStream()
        var ending = 0
        while (bytes.size() < 16_384 && System.nanoTime() < deadline) {
            val next = input.read()
            if (next < 0) break
            bytes.write(next)
            ending = (ending shl 8) or next
            if (ending == 0x0d0a0d0a) {
                val lines = bytes.toString(Charsets.US_ASCII.name()).split("\r\n")
                val request = lines.first().split(' ')
                val hosts = lines.drop(1).filter { it.startsWith("Host:", ignoreCase = true) }
                if (request.size == 3 && request[0] == "GET" && request[2] in setOf("HTTP/1.1", "HTTP/1.0") &&
                    hosts.size == 1 && hosts.single().substringAfter(':').trim() == "127.0.0.1:${server.localPort}") {
                    return request[1]
                }
                break
            }
        }
        throw ProviderAuthException(
            "oauth_callback_invalid",
            AppStrings.get(R.string.auth_err_callback_invalid, "Sign-in callback invalid"),
        )
    }

    private fun respond(socket: Socket, status: String, message: String) {
        val body = "<!doctype html><meta charset=utf-8><title>Atom</title><p>$message</p>".toByteArray(Charsets.UTF_8)
        val headers = "HTTP/1.1 $status\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\n" +
            "Cache-Control: no-store\r\nContent-Security-Policy: default-src 'none'\r\nConnection: close\r\n\r\n"
        socket.getOutputStream().apply { write(headers.toByteArray(Charsets.US_ASCII)); write(body); flush() }
    }

    override fun close() { server.close() }
}
