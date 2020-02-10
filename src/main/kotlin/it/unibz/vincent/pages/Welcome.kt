package it.unibz.vincent.pages

import io.undertow.server.HttpServerExchange
import io.undertow.server.RoutingHandler
import io.undertow.util.StatusCodes
import it.unibz.vincent.AccountType
import it.unibz.vincent.Accounts
import it.unibz.vincent.BRAND_NAME
import it.unibz.vincent.createSession
import it.unibz.vincent.destroySession
import it.unibz.vincent.failedLoginAttemptLog
import it.unibz.vincent.session
import it.unibz.vincent.util.GET
import it.unibz.vincent.util.HashedPassword
import it.unibz.vincent.util.POST
import it.unibz.vincent.util.SQLErrorType
import it.unibz.vincent.util.checkPassword
import it.unibz.vincent.util.formString
import it.unibz.vincent.util.hashPassword
import it.unibz.vincent.util.languages
import it.unibz.vincent.util.redirect
import it.unibz.vincent.util.toHumanReadableTime
import it.unibz.vincent.util.toRawPassword
import it.unibz.vincent.util.type
import kotlinx.html.FORM
import kotlinx.html.FormMethod
import kotlinx.html.div
import kotlinx.html.form
import kotlinx.html.h1
import kotlinx.html.h4
import kotlinx.html.hiddenInput
import kotlinx.html.p
import kotlinx.html.submitInput
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory
import java.sql.SQLException
import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

private val LOG = LoggerFactory.getLogger("Welcome")

private const val POST_LOGIN_REDIRECT = "post-login-redirect"
fun FORM.postLoginRedirect(exchange:HttpServerExchange) {
	val relativePath = exchange.relativePath ?: ""
	if (relativePath.isNotEmpty() && relativePath != HOME_PATH) {
		hiddenInput(name=POST_LOGIN_REDIRECT) { value=relativePath }
	}
}

fun HttpServerExchange.handlePostLoginRedirect():Boolean {
	val redirectUrl = formString(POST_LOGIN_REDIRECT) ?: return false
	redirect(redirectUrl)
	return true
}

private const val ACTION_LOGIN = "login"
private const val ACTION_REGISTER = "register"
const val ACTION_LOGOUT = "logout"
const val ACTION_LOGOUT_FULLY = "logout-fully"

private const val FORM_EMAIL = "e"
private const val FORM_PASSWORD = "p"
private const val FORM_FULL_NAME = "n"


/** Show initial page where user can log in and register. */
fun HttpServerExchange.loginRegister(/* Pre-filled values */
                                     loginEmail:String? = null,
                                     registerEmail:String? = null,
                                     registerName:String? = null) {
	sendBase("", showHeader = false) { exchange, _ ->
		div("page-container") {
			div("page-section") {
				h1 { +BRAND_NAME }
				@Suppress("ConstantConditionIf")
				if (BRAND_NAME == "Vincent") {
					p("sub") { +"Patron of wine tasting" }
				}
			}

			renderMessages(exchange)

			div("page-section container") {
				div("column") {
					h4 { +"Login" }
					form(action = HOME_PATH, method = FormMethod.post) {
						routeAction(ACTION_LOGIN)
						postLoginRedirect(exchange)
						hiddenTimezoneInput()
						div("form-section") { emailField(FORM_EMAIL, "on", loginEmail) }
						div("form-section") { passwordField(FORM_PASSWORD, "current-password", internalId="l-pass") }
						div("form-section-last") {
							submitInput(classes = "u-full-width") { value = "Log in" }
						}
					}
				}

				div("column") {
					h4 { +"Register" }
					form(action = HOME_PATH, method = FormMethod.post) {
						routeAction(ACTION_REGISTER)
						postLoginRedirect(exchange)
						hiddenTimezoneInput()
						div("form-section") { emailField(FORM_EMAIL, "off", registerEmail) }
						div("form-section") { passwordField(FORM_PASSWORD, "new-password", internalId="r-pass") }
						div("form-section") { fullNameField(FORM_FULL_NAME, "name", registerName) }
						div("form-section-last") {
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

private val defaultTimeZone = ZoneId.systemDefault()
fun getTimeZoneOffset(exchange:HttpServerExchange): ZoneId {
	val minuteOffset = exchange.formString(HIDDEN_TIMEZONE_INPUT_NAME)?.toIntOrNull() ?: return defaultTimeZone
	return try {
		ZoneOffset.ofTotalSeconds(minuteOffset * 60)
	} catch (e: DateTimeException) {
		defaultTimeZone
	}
}

fun RoutingHandler.setupWelcomeRoutes() {
	POST(HOME_PATH, routeAction = ACTION_LOGOUT, accessLevel=null) { exchange ->
		exchange.destroySession(false)
		exchange.redirect(HOME_PATH)
	}

	POST(HOME_PATH, routeAction = ACTION_LOGOUT_FULLY, accessLevel=null) { exchange ->
		exchange.destroySession(true)
		exchange.redirect(HOME_PATH)
	}

	GET(HOME_PATH, accessLevel=null, requireCompletedDemography = true) { exchange ->
		val session = exchange.session()
		if (session != null) {
			exchange.home(session)
		} else {
			exchange.loginRegister()
		}
	}

	POST(HOME_PATH, routeAction = ACTION_LOGIN, accessLevel=null) { exchange ->
		val l = exchange.languages()
		val timeZone = getTimeZoneOffset(exchange)

		val email = exchange.formString(FORM_EMAIL)
		val providedPassword = exchange.formString(FORM_PASSWORD)
		if (email == null || providedPassword == null) {
			exchange.statusCode = StatusCodes.BAD_REQUEST
			exchange.messageWarning("Incomplete login form")
			exchange.loginRegister(loginEmail = email)
			return@POST
		}

		var hasProblems = false
		if (!EMAIL_PATTERN.matches(email)) {
			exchange.messageWarning("Email is not valid")
			hasProblems = true
		}
		if (providedPassword.length < MIN_PASSWORD_LENGTH) {
			exchange.messageWarning("Password is too short - must be at least $MIN_PASSWORD_LENGTH characters long")
			hasProblems = true
		} else if (providedPassword.length > MAX_PASSWORD_LENGTH) {
			exchange.messageWarning("Password is too long - can't be longer than $MAX_PASSWORD_LENGTH characters")
			hasProblems = true
		}

		if (hasProblems) {
			exchange.statusCode = StatusCodes.BAD_REQUEST
			exchange.loginRegister(loginEmail = email)
			return@POST
		}

		var accountId = 0L
		var storedPasswordN:HashedPassword? = null
		transaction {
			Accounts.slice(Accounts.id, Accounts.password).select { Accounts.email eq email }.limit(1).singleOrNull()?.let {
				accountId = it[Accounts.id].value
				storedPasswordN = it[Accounts.password]
			}
		}

		val storedPassword = storedPasswordN ?: run {
			exchange.statusCode = StatusCodes.UNAUTHORIZED
			exchange.messageWarning("User with this e-mail does not exist - you may register instead")
			exchange.loginRegister(loginEmail = email, registerEmail = email)
			return@POST
		}

		val rateLimiter = failedLoginAttemptLog[accountId]!!
		rateLimiter.attemptLogin({ waitUntil ->
			LOG.info("{} - Attempted login too soon (from {})", accountId, exchange.sourceAddress)

			exchange.statusCode = StatusCodes.UNAUTHORIZED
			exchange.messageWarning("Too many failed login attempts, try again ${waitUntil.toHumanReadableTime(l, timeZone, relative = true)}")
			exchange.loginRegister(loginEmail = email, registerEmail = email)
		}) { now ->
			val validPassword = checkPassword(providedPassword.toRawPassword(), storedPassword)

			if (!validPassword) {
				// Add penalty token
				LOG.info("{} - Failed login attempt (from {})", accountId, exchange.sourceAddress)
				rateLimiter.addPenalty(now)
				exchange.statusCode = StatusCodes.UNAUTHORIZED
				exchange.messageWarning("Invalid password")
				exchange.loginRegister(loginEmail = email)
			} else {
				// Store last login time
				LOG.info("{} - Successful login attempt (from {})", accountId, exchange.sourceAddress)
				val updateCount = transaction { Accounts.update(where = { Accounts.id eq accountId }) { it[timeLastLogin] = now } }
				if (updateCount != 1) {
					LOG.warn("A successful login time update modified an unexpected amount of rows: {}", updateCount)
				}

				exchange.createSession(accountId, timeZone)
				if (!exchange.handlePostLoginRedirect()) {
					exchange.redirect(HOME_PATH)
				}
			}
		}
	}

	POST(HOME_PATH, routeAction = ACTION_REGISTER, accessLevel=null) { exchange ->
		val email = exchange.formString(FORM_EMAIL)
		// https://pages.nist.gov/800-63-3/sp800-63b.html#sec5
		val password = exchange.formString(FORM_PASSWORD)
		val name = exchange.formString(FORM_FULL_NAME)
		if (email == null || password == null || name == null) {
			exchange.statusCode = StatusCodes.BAD_REQUEST
			exchange.messageWarning("Incomplete registration form")
			exchange.loginRegister(registerEmail=email, registerName=name)
			return@POST
		}

		var hasProblems = false
		if (!EMAIL_PATTERN.matches(email)) {
			exchange.messageWarning("Email is not valid")
			hasProblems = true
		}
		if (password.length < MIN_PASSWORD_LENGTH) {
			exchange.messageWarning("Password is too short - must be at least $MIN_PASSWORD_LENGTH characters long")
			hasProblems = true
		} else if (password.length > MAX_PASSWORD_LENGTH) {
			exchange.messageWarning("Password is too long - can't be longer than $MAX_PASSWORD_LENGTH characters")
			hasProblems = true
		}
		if (name.isEmpty()) {
			exchange.messageWarning("Name must not be empty")
			hasProblems = true
		}

		if (hasProblems) {
			exchange.statusCode = StatusCodes.BAD_REQUEST
			exchange.loginRegister(registerEmail=email, registerName=name)
			return@POST
		}

		var emailAlreadyUsed = false
		val newAccountId = transaction {
			emailAlreadyUsed = false

			val reservedAccountId = try {
				Accounts.slice(Accounts.id).select { (Accounts.email eq email) and (Accounts.accountType eq AccountType.RESERVED) }.firstOrNull()?.let { it[Accounts.id].value }
			} catch (e:SQLException) {
				LOG.error("Failed to check for reservations", e)
				null
			}

			if (reservedAccountId != null) {
				// Update reservations
				val rows = Accounts.update(where={ (Accounts.id eq reservedAccountId) and (Accounts.accountType eq AccountType.RESERVED) }, limit=1) {
					it[Accounts.name] = name
					it[Accounts.password] = hashPassword(password.toRawPassword())
					it[accountType] = AccountType.NORMAL
					val now = Instant.now()
					it[timeRegistered] = now
					it[timeLastLogin] = now
				}
				if (rows != 1) {
					throw SQLException("Account id is no longer reserved")
				}
				reservedAccountId
			} else {
				// Create new
				try {
					Accounts.insertAndGetId {
						it[Accounts.name] = name
						it[Accounts.email] = email
						it[Accounts.password] = hashPassword(password.toRawPassword())
						it[accountType] = AccountType.NORMAL
						val now = Instant.now()
						it[timeRegistered] = now
						it[timeLastLogin] = now
					}.value
				} catch (e: SQLException) {
					if (e.type() == SQLErrorType.DUPLICATE_KEY) {
						emailAlreadyUsed = true
						-1L
					} else {
						throw e
					}
				}
			}
		}

		if (emailAlreadyUsed) {
			exchange.statusCode = StatusCodes.CONFLICT
			exchange.messageWarning("Account with this email already exits, log in instead")
			exchange.loginRegister(loginEmail=email, registerEmail=email, registerName=name)
			return@POST
		}

		val timeZone = getTimeZoneOffset(exchange)

		LOG.info("Successful registration of {} (from {})", newAccountId, exchange.sourceAddress)
		exchange.createSession(newAccountId, timeZone)
		if (!exchange.handlePostLoginRedirect()) {
			exchange.redirect(HOME_PATH)
		}
	}
}
