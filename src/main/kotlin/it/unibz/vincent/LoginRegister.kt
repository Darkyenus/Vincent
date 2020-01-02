package it.unibz.vincent

import com.google.common.cache.CacheBuilder
import io.undertow.server.HttpServerExchange
import io.undertow.server.handlers.CookieImpl
import io.undertow.util.AttachmentKey
import java.security.SecureRandom
import java.time.Duration
import java.util.*

private val SESSION_COOKIE_NAME = if (VINCENT_UNSAFE_MODE) "vincent-session" else "__Host-vincent-session"
private val SESSION_VALIDITY = Duration.ofDays(1L)

private val activeSessions = CacheBuilder.newBuilder()
		.expireAfterWrite(SESSION_VALIDITY)
		.build<String, Session>()

private val csrfRandom = SecureRandom.getInstanceStrong()

const val CSRF_FORM_TOKEN_NAME = "csrf_token"

class Session(
		/** ID of the user */
		val user:Long) {

	/** Token to be included in each fork the user posts to prevent CSRF attacks.
	 * Server automatically checks for the token to be set on each POST when the user is authenticated
	 * in [it.unibz.vincent.util.wrapRootHandler]. */
	val csrfToken:String = run {
		val bytes = ByteArray(16)
		synchronized(csrfRandom) {
			csrfRandom.nextBytes(bytes)
		}
		Base64.getUrlEncoder().encodeToString(bytes)
	}
}

private val SESSION_ATTACHMENT = AttachmentKey.create(Session::class.java)

/**
 * Retrieve the session associated with given exchange.
 * @return null if not logged in
 */
fun HttpServerExchange.session():Session? {
	getAttachment(SESSION_ATTACHMENT)?.let { return it }
	val cookieValue = requestCookies[SESSION_COOKIE_NAME]?.value ?: return null
	val session = activeSessions.getIfPresent(cookieValue)
	if (session != null) {
		putAttachment(SESSION_ATTACHMENT, session)
	}
	return session
}

private val sessionRandom = SecureRandom.getInstanceStrong()
private fun randomSessionId():String {
	val bytes = ByteArray(16)
	synchronized(sessionRandom) {
		sessionRandom.nextBytes(bytes)
	}
	return Base64.getUrlEncoder().encodeToString(bytes)
}

fun HttpServerExchange.createSession(user:Long):Session {
	var sessionId: String
	do {
		sessionId = randomSessionId()
	} while (activeSessions.getIfPresent(sessionId) != null) // Extremely unlikely

	val session = Session(user)
	activeSessions.put(sessionId, session)
	val cookie = CookieImpl(SESSION_COOKIE_NAME, sessionId)
	cookie.maxAge = SESSION_VALIDITY.seconds.toInt()
	cookie.path = "/"
	if (!VINCENT_UNSAFE_MODE) {
		cookie.isSecure = true
	}
	cookie.isHttpOnly = true
	cookie.sameSiteMode = "Strict"
	setResponseCookie(cookie)
	putAttachment(SESSION_ATTACHMENT, session)
	return session
}