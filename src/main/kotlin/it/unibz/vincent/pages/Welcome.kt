package it.unibz.vincent.pages

import io.undertow.server.HttpServerExchange
import io.undertow.server.RoutingHandler
import io.undertow.util.Headers
import io.undertow.util.StatusCodes
import it.unibz.vincent.AccountCodeReservations
import it.unibz.vincent.AccountType
import it.unibz.vincent.Accounts
import it.unibz.vincent.Accounts.findUniqueAccountCode
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
import it.unibz.vincent.util.toHumanReadableTime
import it.unibz.vincent.util.toRawPassword
import it.unibz.vincent.util.type
import kotlinx.html.FORM
import kotlinx.html.FlowOrInteractiveOrPhrasingContent
import kotlinx.html.FormMethod
import kotlinx.html.div
import kotlinx.html.emailInput
import kotlinx.html.form
import kotlinx.html.h1
import kotlinx.html.h4
import kotlinx.html.hiddenInput
import kotlinx.html.label
import kotlinx.html.p
import kotlinx.html.passwordInput
import kotlinx.html.style
import kotlinx.html.submitInput
import kotlinx.html.textInput
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory
import java.sql.SQLException
import java.time.Instant

private val LOG = LoggerFactory.getLogger("WelcomePage")

private fun FlowOrInteractiveOrPhrasingContent.emailField(fieldId:String, autoComplete:String, preFillValue:String?) {
	label {
		+"E-mail"
		emailInput(classes = "u-full-width") {
			name = fieldId
			minLength = "3"
			maxLength = Accounts.MAX_EMAIL_LENGTH.toString()
			placeholder = "your-email@example.com"
			required = true
			if (preFillValue != null) {
				value = preFillValue
			}
			attributes["autocomplete"] = autoComplete
		}
	}
}

private fun FlowOrInteractiveOrPhrasingContent.passwordField(fieldId:String, autoComplete:String) {
	label {
		+"Password"
		passwordInput(classes = "u-full-width") {
			name = fieldId
			minLength = MIN_PASSWORD_LENGTH.toString()
			maxLength = MAX_PASSWORD_LENGTH.toString()
			//placeholder = "●●●●●●●●●●●●●●"
			required = true
			attributes["autocomplete"] = autoComplete
			// TODO(jp): Unmask functionality
		}
	}
}

private fun FlowOrInteractiveOrPhrasingContent.fullNameField(fieldId:String, autoComplete:String, preFillValue:String?) {
	label {
		+"Name"
		textInput(classes = "u-full-width") {
			name = fieldId
			minLength = "1"
			maxLength = Accounts.MAX_NAME_LENGTH.toString()
			placeholder = "John Doe"
			required = true
			if (preFillValue != null) {
				value = preFillValue
			}
			attributes["autocomplete"] = autoComplete
		}
	}
}

private const val POST_LOGIN_REDIRECT = "post-login-redirect"
fun FORM.postLoginRedirect(exchange:HttpServerExchange) {
	val relativePath = exchange.relativePath ?: ""
	if (relativePath.isNotEmpty() && relativePath != "/") {
		hiddenInput(name=POST_LOGIN_REDIRECT) { value=relativePath }
	}
}

fun HttpServerExchange.handlePostLoginRedirect():Boolean {
	val redirectUrl = formString(POST_LOGIN_REDIRECT) ?: return false
	statusCode = StatusCodes.SEE_OTHER
	responseHeaders.put(Headers.LOCATION, redirectUrl)
	return true
}

private const val FORM_EMAIL = "e"
private const val FORM_PASSWORD = "p"
private const val FORM_FULL_NAME = "n"

private const val MIN_PASSWORD_LENGTH = 8
private const val MAX_PASSWORD_LENGTH = 1000

/** Show initial page where user can log in and register. */
fun HttpServerExchange.loginRegister(/* Pre-filled values */
                                     loginEmail:String? = null,
                                     registerEmail:String? = null,
                                     registerName:String? = null) {
	sendBase("Welcome") { exchange, _ ->
		div("container") {
			style = "margin-top: 5%"

			div("row") {
				style = "margin-bottom: 3rem"
				h1 { +"Vincent" }
				p("sub") { +"Patron of wine tasting" }
			}

			renderMessages(exchange)

			div("row") {
				div("w6 column container") {
					h4 { +"Login" }
					form(action = "/", method = FormMethod.post) {
						routeAction("login")
						postLoginRedirect(exchange)
						div("row") { emailField(FORM_EMAIL, "on", loginEmail) }
						div("row") { passwordField(FORM_PASSWORD, "current-password") }
						div("row") {
							submitInput(classes = "u-full-width") { value = "Log in" }
						}
					}
				}

				div("w6 column container") {
					h4 { +"Register" }
					form(action = "/", method = FormMethod.post) {
						routeAction("register")
						postLoginRedirect(exchange)
						div("row") { emailField(FORM_EMAIL, "off", registerEmail) }
						div("row") { passwordField(FORM_PASSWORD, "new-password") }
						div("row") { fullNameField(FORM_FULL_NAME, "name", registerName) }
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

private fun logoutMessage(sessionsDestroyed:Int):String? {
	return when {
		sessionsDestroyed == 1 -> {
			"Logged out successfully"
		}
		sessionsDestroyed == 2 -> {
			"Logged out successfully from this and 1 more browser"
		}
		sessionsDestroyed > 2 -> {
			"Logged out successfully from this and ${sessionsDestroyed - 1} more browsers"
		}
		else -> null
	}
}

fun RoutingHandler.setupWelcomeRoutes() {
	POST("/", routeAction = "logout", accessLevel=null) { exchange ->
		exchange.messageInfo(logoutMessage(exchange.destroySession(false)))
		exchange.loginRegister()
	}

	POST("/", routeAction = "logout-fully", accessLevel=null) { exchange ->
		exchange.messageInfo(logoutMessage(exchange.destroySession(true)))
		exchange.loginRegister()
	}

	GET("/", accessLevel=null) { exchange ->
		val session = exchange.session()
		if (session != null) {
			exchange.home(session)
		} else {
			exchange.loginRegister()
		}
	}

	POST("/", routeAction = "login", accessLevel=null) { exchange ->
		val l = exchange.languages()

		val email = exchange.formString(FORM_EMAIL)
		val providedPassword = exchange.formString(FORM_PASSWORD)
		if (email == null || providedPassword == null) {
			exchange.statusCode = StatusCodes.BAD_REQUEST
			exchange.messageWarning("Incomplete login form")
			exchange.loginRegister(loginEmail = email)
			return@POST
		}

		if (!EMAIL_PATTERN.matches(email)) {
			exchange.messageWarning("Email is not valid")
		}
		if (providedPassword.length < MIN_PASSWORD_LENGTH) {
			exchange.messageWarning("Password is too short - must be at least $MIN_PASSWORD_LENGTH characters long")
		} else if (providedPassword.length > MAX_PASSWORD_LENGTH) {
			exchange.messageWarning("Password is too long - can't be longer than $MAX_PASSWORD_LENGTH characters")
		}

		if (exchange.messageWarningCount() > 0) {
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
			exchange.messageWarning("Too many failed login attempts, try again ${waitUntil.toHumanReadableTime(l, relative = true)}")
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

				val session = exchange.createSession(accountId)
				if (!exchange.handlePostLoginRedirect()) {
					exchange.home(session)
				}
			}
		}
	}

	POST("/", routeAction = "register", accessLevel=null) { exchange ->
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

		if (!EMAIL_PATTERN.matches(email)) {
			exchange.messageWarning("Email is not valid")
		}
		if (password.length < MIN_PASSWORD_LENGTH) {
			exchange.messageWarning("Password is too short - must be at least $MIN_PASSWORD_LENGTH characters long")
		} else if (password.length > MAX_PASSWORD_LENGTH) {
			exchange.messageWarning("Password is too long - can't be longer than $MAX_PASSWORD_LENGTH characters")
		}
		if (name.isEmpty()) {
			exchange.messageWarning("Name must not be empty")
		}

		if (exchange.messageWarningCount() > 0) {
			exchange.statusCode = StatusCodes.BAD_REQUEST
			exchange.loginRegister(registerEmail=email, registerName=name)
			return@POST
		}

		var emailAlreadyUsed = false
		val newAccountId = transaction {
			val newCode = findUniqueAccountCode(email)
			val accountId = try {
				Accounts.insertAndGetId {
					it[Accounts.name] = name
					it[Accounts.email] = email
					it[Accounts.password] = hashPassword(password.toRawPassword())
					it[accountType] = AccountType.NORMAL
					it[code] = newCode
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

			try {
				// Drop reservation, if this code came from reservation
				AccountCodeReservations.deleteWhere(1) { AccountCodeReservations.code eq newCode }
			} catch (e: SQLException) {
				LOG.error("Failed to drop account reservation", e)
			}

			accountId
		}

		if (emailAlreadyUsed) {
			exchange.statusCode = StatusCodes.CONFLICT
			exchange.messageWarning("Account with this email already exits, log in instead")
			exchange.loginRegister(loginEmail=email, registerEmail=email, registerName=name)
			return@POST
		}

		LOG.info("Successful registration of {} (from {})", newAccountId, exchange.sourceAddress)
		exchange.createSession(newAccountId)
		if (!exchange.handlePostLoginRedirect()) {
			exchange.statusCode = StatusCodes.SEE_OTHER
			exchange.responseHeaders.put(Headers.LOCATION, "/")
		}
	}
}
