package it.unibz.vincent

import com.google.common.cache.CacheBuilder
import com.google.common.cache.CacheLoader
import com.google.common.cache.LoadingCache
import io.undertow.server.HttpServerExchange
import io.undertow.server.handlers.Cookie
import io.undertow.server.handlers.CookieImpl
import io.undertow.util.AttachmentKey
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

private val LOG: Logger = LoggerFactory.getLogger("LoginRegister")

private val SESSION_COOKIE_NAME = if (VINCENT_UNSAFE_MODE) "vincent-session" else "__Host-vincent-session"
private val SESSION_VALIDITY = Duration.ofDays(1L)

private val activeSessions = CacheBuilder.newBuilder()
		.expireAfterWrite(SESSION_VALIDITY)
		.build<String, Session>()

private val csrfRandom = SecureRandom.getInstanceStrong()

const val CSRF_FORM_TOKEN_NAME = "csrf_token"

/**
 * @param sessionId The ID of the session, stored in session cookie
 * @param userId ID of the user whose session this is
 */
class Session(val sessionId:String, val userId:Long) {

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

	fun <T> get(column: Column<T>):T {
		return transaction {
			Accounts.slice(column).select { Accounts.id eq userId }.single()[column]
		}
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

/** Long, long time ago, in a cookie far away. */
private val EXPIRE_COOKIE_DATE = Date(0L)

private fun createSessionCookie(sessionId:String?): Cookie {
	val cookie = CookieImpl(SESSION_COOKIE_NAME, sessionId ?: "")
	if (sessionId == null) {
		cookie.expires = EXPIRE_COOKIE_DATE
	} else {
		cookie.maxAge = SESSION_VALIDITY.seconds.toInt()
	}
	cookie.path = "/"
	if (!VINCENT_UNSAFE_MODE) {
		cookie.isSecure = true
	}
	cookie.isHttpOnly = true
	cookie.sameSiteMode = "Strict"
	return cookie
}

fun HttpServerExchange.createSession(user:Long):Session {
	var sessionId: String
	do {
		sessionId = randomSessionId()
	} while (activeSessions.getIfPresent(sessionId) != null) // Extremely unlikely

	val session = Session(sessionId, user)
	activeSessions.put(sessionId, session)

	setResponseCookie(createSessionCookie(sessionId))
	putAttachment(SESSION_ATTACHMENT, session)
	return session
}

/** Destroy any session associated with this exchange, effectively logging the user out.
 * @param logoutFully to destroy all sessions of this user
 * @return how many sessions were discarded */
fun HttpServerExchange.destroySession(logoutFully:Boolean):Int {
	val session = session() ?: return 0
	removeAttachment(SESSION_ATTACHMENT)
	activeSessions.invalidate(session.sessionId)
	setResponseCookie(createSessionCookie(null))

	if (logoutFully) {
		val userId = session.userId
		var loggedOutFrom = 1

		val iterator = activeSessions.asMap().values.iterator()
		while (iterator.hasNext()) {
			val otherSession = iterator.next()
			if (otherSession.userId == userId) {
				iterator.remove()
				loggedOutFrom++
			}
		}

		return loggedOutFrom
	} else {
		return 1
	}
}


/*
Rate-limiting system
--------------------

Each failed login attempt (with a valid e-mail) earns the user a single penalty token.
Penalty tokens decay after some time.
If the user has too many penalty tokens (max), their login attempts will be rejected.
 */
private val PENALTY_TOKEN_DECAYS_AFTER = Duration.ofMinutes(30)
private const val MAX_PENALTY_TOKENS = 5
/** The shortest time duration that the user can wait. */
private val SHORT_TIME_DURATION = Duration.ofSeconds(5)

class PenaltyTokenBucket : ArrayDeque<Instant>(MAX_PENALTY_TOKENS){

	/** To prevent logging in from two people at the same time.
	 * Basically a non-blocking lock. */
	private val loginInProgress = AtomicBoolean(false)

	private fun activePenalties(now: Instant):Int {
		while (isNotEmpty() && peekFirst().isBefore(now)) {
			removeFirst()
		}
		return size
	}

	fun attemptLogin(mayAttemptAfter:(Duration) -> Unit, mayAttempt:(now: Instant) -> Unit) {
		val now = Instant.now()
		val attemptAfterWait:Duration? = if (!loginInProgress.compareAndSet(false, true)) {
			// Login already in process, try again in few seconds
			LOG.warn("Simultaneous logins!")
			SHORT_TIME_DURATION
		} else if (activePenalties(now) >= MAX_PENALTY_TOKENS) {
			// Too many failed logins, do not try to authenticate any further
			loginInProgress.set(false)
			Duration.between(now, peekFirst())
		} else null

		try {
			if (attemptAfterWait == null) {
				mayAttempt(now)
			} else {
				mayAttemptAfter(attemptAfterWait)
			}
		} finally {
			if (attemptAfterWait == null) {
				loginInProgress.set(false)
			}
		}
	}

	fun addPenalty(now: Instant) {
		addLast(now.plus(PENALTY_TOKEN_DECAYS_AFTER))
	}
}

val failedLoginAttemptLog: LoadingCache<Long, PenaltyTokenBucket> = CacheBuilder.newBuilder()
		.expireAfterWrite(PENALTY_TOKEN_DECAYS_AFTER)
		.expireAfterAccess(PENALTY_TOKEN_DECAYS_AFTER).build(object : CacheLoader<Long, PenaltyTokenBucket>() {
			override fun load(key: Long): PenaltyTokenBucket = PenaltyTokenBucket()
		})
