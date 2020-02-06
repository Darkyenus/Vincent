package it.unibz.vincent

import com.carrotsearch.hppc.IntScatterSet
import com.google.common.cache.CacheBuilder
import com.google.common.cache.CacheLoader
import com.google.common.cache.LoadingCache
import io.undertow.server.HttpServerExchange
import io.undertow.server.handlers.Cookie
import io.undertow.server.handlers.CookieImpl
import io.undertow.util.AttachmentKey
import it.unibz.vincent.pages.hasUserFilledDemographyInfoSufficiently
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

private val LOG: Logger = LoggerFactory.getLogger("LoginRegister")

private val SESSION_COOKIE_NAME = if (VINCENT_UNSAFE_MODE) "vincent-session" else "__Host-vincent-session"
private val SESSION_VALIDITY = Duration.ofDays(1L)

private val activeSessions = CacheBuilder.newBuilder()
		.expireAfterWrite(SESSION_VALIDITY)
		.build<String, Session>()

private val csrfRandom = SecureRandom.getInstanceStrong()

const val CSRF_FORM_TOKEN_NAME = "csrf"
const val IDEMPOTENCY_FORM_TOKEN_NAME = "idmp"

/**
 * @param sessionId The ID of the session, stored in session cookie
 * @param userId database ID of the user whose session this is
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

	/** Each form contains an idempotency token, which is used to prevent doing something twice. */
	val nextIdempotencyToken = AtomicInteger(0)

	private val usedIdempotencyTokens = IntScatterSet()

	/** Attempt to use idempotency token.
	 * Each token can be only used once.
	 * @return true if used, false if invalid or already used */
	fun useIdempotencyToken(token:String?):Boolean {
		val id = token?.toIntOrNull() ?: return false
		if (id < 0 || id >= nextIdempotencyToken.get()) {
			// We didn't create this token, this makes it invalid
			return false
		}
		val usedIdempotencyTokens = usedIdempotencyTokens
		return synchronized(usedIdempotencyTokens) { usedIdempotencyTokens.add(id) }
	}

	private class Cache(val userName:String, val accountType:AccountType, val hasDemographyFilledOut:Boolean)

	private var _cache:Cache? = null
	private val cache:Cache
		get() {
			var cache = _cache
			if (cache == null) {
				var userName = ""
				var accountType = AccountType.GUEST
				var hasDemographyFilledOut = false
				transaction {
					Accounts.slice(Accounts.name, Accounts.accountType)
							.select { Accounts.id eq userId }.single().let {
								userName = it[Accounts.name]
								accountType = it[Accounts.accountType]
							}

					hasDemographyFilledOut = hasUserFilledDemographyInfoSufficiently(userId)
				}
				cache = Cache(userName, accountType, hasDemographyFilledOut)
				_cache = cache
			}
			return cache
		}

	val userName:String
		get() = cache.userName
	val accountType:AccountType
		get() = cache.accountType
	val hasDemographyFilledOut:Boolean
		get() = cache.hasDemographyFilledOut

	internal fun doFlushCache() {
		_cache = null
	}

	/** Call when underlying data for [userName], [accountType] or [hasDemographyFilledOut] changes. */
	fun flushCache() {
		doFlushCache() // Just in case this was ejected from the cache
		flushSessionCache(userId)
	}

	enum class MessageType {
		INFO,
		WARNING
	}
	private val messageStashes = Array(MessageType.values().size) { ArrayList<String>() }

	fun stashMessages(messages:List<String>, type:MessageType) {
		val stash = messageStashes[type.ordinal]
		synchronized(stash) {
			stash.addAll(messages)
		}
	}

	fun retrieveStashedMessages(type:MessageType):List<String> {
		val stash = messageStashes[type.ordinal]
		return synchronized(stash) {
			if (stash.isEmpty()) {
				emptyList()
			} else {
				val result = ArrayList(stash)
				stash.clear()
				result
			}
		}
	}
}

/** @see Sesstion.flushCache */
fun flushSessionCache(userId:Long) {
	for (session in activeSessions.asMap().values) {
		if (session.userId == userId) {
			session.doFlushCache()
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

	fun attemptLogin(mayAttemptAfter:(after:Instant) -> Unit, mayAttempt:(now: Instant) -> Unit) {
		val now = Instant.now()
		val attemptAfterWait:Instant? = if (!loginInProgress.compareAndSet(false, true)) {
			// Login already in process, try again in few seconds
			LOG.warn("Simultaneous logins!")
			now.plus(SHORT_TIME_DURATION)
		} else if (activePenalties(now) >= MAX_PENALTY_TOKENS) {
			// Too many failed logins, do not try to authenticate any further
			loginInProgress.set(false)
			peekFirst()
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
