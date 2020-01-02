package it.unibz.vincent.pages

import com.google.common.cache.CacheBuilder
import com.google.common.cache.CacheLoader
import io.undertow.server.HttpServerExchange
import io.undertow.server.RoutingHandler
import io.undertow.util.Headers
import io.undertow.util.StatusCodes
import it.unibz.vincent.AccountType
import it.unibz.vincent.Accounts
import it.unibz.vincent.createSession
import it.unibz.vincent.session
import it.unibz.vincent.util.HashedPassword
import it.unibz.vincent.util.checkPassword
import it.unibz.vincent.util.formString
import it.unibz.vincent.util.hashPassword
import it.unibz.vincent.util.languages
import it.unibz.vincent.util.toHumanReadableString
import kotlinx.html.FlowOrInteractiveOrPhrasingContent
import kotlinx.html.FormMethod
import kotlinx.html.div
import kotlinx.html.emailInput
import kotlinx.html.form
import kotlinx.html.h1
import kotlinx.html.h4
import kotlinx.html.id
import kotlinx.html.label
import kotlinx.html.li
import kotlinx.html.p
import kotlinx.html.passwordInput
import kotlinx.html.style
import kotlinx.html.submitInput
import kotlinx.html.textInput
import kotlinx.html.ul
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.collections.ArrayList

private val LOG = LoggerFactory.getLogger("WelcomePage")

private fun FlowOrInteractiveOrPhrasingContent.emailField(fieldId:String, preFillValue:String?) {
	label { htmlFor = fieldId; +"E-mail" }
	emailInput(classes = "u-full-width") {
		id = fieldId
		name = fieldId
		minLength = "3"
		maxLength = Accounts.MAX_EMAIL_LENGTH.toString()
		placeholder = "your-email@example.com"
		required = true
		if (preFillValue != null) {
			value = preFillValue
		}
	}
}

private fun FlowOrInteractiveOrPhrasingContent.passwordField(fieldId:String) {
	label { htmlFor = fieldId; +"Password" }
	passwordInput(classes = "u-full-width") {
		id = fieldId
		name = fieldId
		minLength = MIN_PASSWORD_LENGTH.toString()
		maxLength = MAX_PASSWORD_LENGTH.toString()
		//placeholder = "●●●●●●●●●●●●●●"
		required = true
	}
}

private fun FlowOrInteractiveOrPhrasingContent.fullNameField(fieldId:String, preFillValue:String?) {
	label { htmlFor = fieldId; +"Name" }
	textInput(classes = "u-full-width") {
		id = fieldId
		name = fieldId
		minLength = "1"
		maxLength = Accounts.MAX_NAME_LENGTH.toString()
		placeholder = "John Doe"
		required = true
		if (preFillValue != null) {
			value = preFillValue
		}
	}
}

private const val ID_LOGIN_EMAIL = "lE"
private const val ID_LOGIN_PASSWORD = "lP"
private const val MIN_PASSWORD_LENGTH = 8
private const val MAX_PASSWORD_LENGTH = 1000

private const val ID_REGISTER_EMAIL = "rE"
private const val ID_REGISTER_PASSWORD = "rP"
private const val ID_REGISTER_NAME = "rN"

/** Show initial page where user can log in and register. */
fun HttpServerExchange.loginRegister(problems:List<String> = emptyList(),
                                     /* Pre-filled values */
                                     loginEmail:String? = null,
                                     registerEmail:String? = null,
                                     registerName:String? = null) {
	responseHeaders.put(Headers.CONTENT_LOCATION, "/")
	sendBase("Welcome") { _, _ ->
		div("container") {
			style = "margin-top: 5%"

			div("row") {
				style = "margin-bottom: 5%"
				h1 { +"Vincent" }
				p("sub") { +"Patron of wine tasting" }
			}

			if (problems.isNotEmpty()) {
				div("row warning-box") {
					if (problems.size == 1) {
						+problems[0]
					} else {
						ul {
							for (problem in problems) {
								li { +problem }
							}
						}
					}
				}
			}

			div("row") {
				div("w6 column container") {
					h4 { +"Login" }
					form(action = "/", method = FormMethod.post) {
						div("row") { emailField(ID_LOGIN_EMAIL, loginEmail) }
						div("row") { passwordField(ID_LOGIN_PASSWORD) }
						div("row") {
							submitInput(classes = "u-full-width") { value = "Log in" }
						}
					}
				}

				div("w6 column container") {
					h4 { +"Register" }
					form(action = "/register", method = FormMethod.post) {
						div("row") { emailField(ID_REGISTER_EMAIL, registerEmail) }
						div("row") { passwordField(ID_REGISTER_PASSWORD) }
						div("row") { fullNameField(ID_REGISTER_NAME, registerName) }
						div("row") {
							submitInput(classes = "u-full-width") { value = "Register" }
						}
					}
				}
			}
		}
	}
}

// From https://developer.mozilla.org/en-US/docs/Web/HTML/Element/input/email#Validation
private val EMAIL_PATTERN = Regex("^[a-zA-Z0-9.!#$%&'*+\\/=?^_`{|}~-]+@[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(?:\\.[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*$")
private val INVALID_PASSWORD_CHARACTERS = Regex("[\n\r\u0000]")

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

private class PenaltyTokenBucket : ArrayDeque<Instant>(MAX_PENALTY_TOKENS){

	/** To prevent logging in from two people at the same time.
	 * Basically a non-blocking lock. */
	private val loginInProgress = AtomicBoolean(false)

	/** Attempt to begin logging in. If this returns `null`,
	 * login attempt may proceed and [loginInProgress] MUST be set to `false` later.
	 *
	 * Otherwise, this returns a time after which the attempt may be tried again.
	 * Do not change [loginInProgress] after this result! */
	private fun beginLogin(now:Instant):Duration? {
		if (!loginInProgress.compareAndSet(false, true)) {
			// Login already in process, try again in few seconds
			return SHORT_TIME_DURATION
		}
		cleanOldPenalties(now)
		if (size >= MAX_PENALTY_TOKENS) {
			// Too many penalty tokens!
			loginInProgress.set(false)
			val duration = Duration.between(now, peekFirst())
			if (duration < SHORT_TIME_DURATION) {
				return SHORT_TIME_DURATION
			}
			return duration
		}
		// Everything seems fine, proceed
		return null
	}

	private fun cleanOldPenalties(now:Instant) {
		while (isNotEmpty() && peekFirst().isBefore(now)) {
			removeFirst()
		}
	}

	fun attemptLogin(mayAttemptAfter:(Duration) -> Unit, mayAttempt:(now:Instant) -> Unit) {
		val now = Instant.now()
		val after = beginLogin(now)
		if (after == null) {
			mayAttempt(now)
		} else {
			try {
				mayAttemptAfter(after)
			} finally {
				loginInProgress.set(false)
			}
		}
	}

	fun addPenalty(now:Instant) {
		addLast(now.plus(PENALTY_TOKEN_DECAYS_AFTER))
	}
}

private val failedLoginAttemptLog = CacheBuilder.newBuilder()
		.expireAfterWrite(PENALTY_TOKEN_DECAYS_AFTER)
		.expireAfterAccess(PENALTY_TOKEN_DECAYS_AFTER).build(object : CacheLoader<Long, PenaltyTokenBucket>() {
			override fun load(key: Long): PenaltyTokenBucket = PenaltyTokenBucket()
		})

fun RoutingHandler.setupWelcomeRoutes() {
	get("/") { exchange ->
		val session = exchange.session()
		if (session != null) {
			exchange.home(session)
		} else {
			exchange.loginRegister()
		}
	}

	post("/") { exchange ->
		val l = exchange.languages()

		val email = exchange.formString(ID_LOGIN_EMAIL)
		val providedPassword = exchange.formString(ID_LOGIN_PASSWORD)?.replace(INVALID_PASSWORD_CHARACTERS, "")
		if (email == null || providedPassword == null) {
			exchange.statusCode = StatusCodes.BAD_REQUEST
			exchange.loginRegister(listOf("Incomplete login form"), loginEmail = email)
			return@post
		}

		val problems = ArrayList<String>()

		if (!EMAIL_PATTERN.matches(email)) {
			problems.add("Email is not valid")
		}
		if (providedPassword.length < MIN_PASSWORD_LENGTH) {
			problems.add("Password is too short - must be at least $MIN_PASSWORD_LENGTH characters long")
		} else if (providedPassword.length > MAX_PASSWORD_LENGTH) {
			problems.add("Password is too long - can't be longer than $MAX_PASSWORD_LENGTH characters")
		}

		if (problems.isNotEmpty()) {
			exchange.statusCode = StatusCodes.BAD_REQUEST
			exchange.loginRegister(problems, loginEmail = email)
			return@post
		}

		var accountId = 0L
		var storedPasswordN:HashedPassword? = null
		transaction {
			Accounts.select { Accounts.email eq email }.limit(1).singleOrNull()?.let {
				accountId = it[Accounts.id]
				storedPasswordN = it[Accounts.password]
			}
		}

		val storedPassword = storedPasswordN ?: run {
			exchange.statusCode = StatusCodes.UNAUTHORIZED
			exchange.loginRegister(listOf("User with this e-mail does not exist - you may register instead"), loginEmail = email, registerEmail = email)
			return@post
		}

		val rateLimiter = failedLoginAttemptLog[accountId]!!
		rateLimiter.attemptLogin({ wait ->
			LOG.info("{} - Attempted login too soon (from {})", accountId, exchange.sourceAddress)

			exchange.statusCode = StatusCodes.UNAUTHORIZED
			exchange.loginRegister(listOf("Too many login attempts, try again in ${wait.toHumanReadableString(l)}"), loginEmail = email, registerEmail = email)
		}) { now ->
			val validPassword = checkPassword(providedPassword.toByteArray(Charsets.UTF_8), storedPassword)

			if (!validPassword) {
				// Add penalty token
				LOG.info("{} - Failed login attempt (from {})", accountId, exchange.sourceAddress)
				rateLimiter.addPenalty(now)
				exchange.statusCode = StatusCodes.UNAUTHORIZED
				exchange.loginRegister(listOf("Invalid password"), loginEmail = email)
			} else {
				// Store last login time
				LOG.info("{} - Successful login attempt (from {})", accountId, exchange.sourceAddress)
				val updateCount = transaction { Accounts.update(where = { Accounts.id eq accountId }) { it[timeLastLogin] = now } }
				if (updateCount != 1) {
					LOG.warn("A successful login time update modified an unexpected amount of rows: {}", updateCount)
				}

				val session = exchange.createSession(accountId)
				exchange.home(session)
			}
		}
	}

	post("/register") { exchange ->
		val email = exchange.formString(ID_REGISTER_EMAIL)
		val password = exchange.formString(ID_REGISTER_PASSWORD)?.replace(INVALID_PASSWORD_CHARACTERS, "")
		val name = exchange.formString(ID_REGISTER_NAME)
		if (email == null || password == null || name == null) {
			exchange.statusCode = StatusCodes.BAD_REQUEST
			exchange.loginRegister(listOf("Incomplete registration form"), registerEmail=email, registerName=name)
			return@post
		}

		val problems = ArrayList<String>()

		if (!EMAIL_PATTERN.matches(email)) {
			problems.add("Email is not valid")
		}
		if (password.length < MIN_PASSWORD_LENGTH) {
			problems.add("Password is too short - must be at least $MIN_PASSWORD_LENGTH characters long")
		} else if (password.length > MAX_PASSWORD_LENGTH) {
			problems.add("Password is too long - can't be longer than $MAX_PASSWORD_LENGTH characters")
		}
		if (name.isEmpty()) {
			problems.add("Name must not be empty")
		}

		if (problems.isNotEmpty()) {
			exchange.statusCode = StatusCodes.BAD_REQUEST
			exchange.loginRegister(problems, registerEmail=email, registerName=name)
			return@post
		}

		var emailAlreadyUsed = false
		val newAccountId = transaction {
			try {
				Accounts.insert {
					it[Accounts.name] = name
					it[Accounts.email] = email
					it[Accounts.password] = hashPassword(password.toByteArray(Charsets.UTF_8))
					it[accountType] = AccountType.NORMAL
					val now = Instant.now()
					it[timeRegistered] = now
					it[timeLastLogin] = now
				} get Accounts.id
			} catch (e: ExposedSQLException) {
				// https://stackoverflow.com/questions/1988570/how-to-catch-a-specific-exception-in-jdbc
				if (e.sqlState.startsWith("23")) {
					emailAlreadyUsed = true
					-1L
				} else {
					throw e
				}
			}
		}

		if (emailAlreadyUsed) {
			exchange.statusCode = StatusCodes.CONFLICT
			exchange.loginRegister(listOf("Account with this email already exits, log in instead"), loginEmail=email, registerEmail=email, registerName=name)
			return@post
		}

		LOG.info("Successful registration of {} (from {})", newAccountId, exchange.sourceAddress)
		exchange.createSession(newAccountId)
		exchange.statusCode = StatusCodes.SEE_OTHER
		exchange.responseHeaders.put(Headers.LOCATION, "/")
	}
}
