package it.unibz.vincent.pages

import io.undertow.server.HttpServerExchange
import io.undertow.server.RoutingHandler
import io.undertow.util.Headers
import io.undertow.util.StatusCodes
import it.unibz.vincent.AccountType
import it.unibz.vincent.Accounts
import it.unibz.vincent.createSession
import it.unibz.vincent.destroySession
import it.unibz.vincent.failedLoginAttemptLog
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
import java.time.Instant

private val LOG = LoggerFactory.getLogger("WelcomePage")

private fun FlowOrInteractiveOrPhrasingContent.emailField(fieldId:String, autoComplete:String, preFillValue:String?) {
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
		attributes["autocomplete"] = autoComplete
	}
}

private fun FlowOrInteractiveOrPhrasingContent.passwordField(fieldId:String, autoComplete:String) {
	label { htmlFor = fieldId; +"Password" }
	passwordInput(classes = "u-full-width") {
		id = fieldId
		name = fieldId
		minLength = MIN_PASSWORD_LENGTH.toString()
		maxLength = MAX_PASSWORD_LENGTH.toString()
		//placeholder = "●●●●●●●●●●●●●●"
		required = true
		attributes["autocomplete"] = autoComplete
	}
}

private fun FlowOrInteractiveOrPhrasingContent.fullNameField(fieldId:String, autoComplete:String, preFillValue:String?) {
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
		attributes["autocomplete"] = autoComplete
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
                                     info:String? = null,
                                     /* Pre-filled values */
                                     loginEmail:String? = null,
                                     registerEmail:String? = null,
                                     registerName:String? = null) {
	sendBase("Welcome") { _, _ ->
		div("container") {
			style = "margin-top: 5%"

			div("row") {
				style = "margin-bottom: 3rem"
				h1 { +"Vincent" }
				p("sub") { +"Patron of wine tasting" }
			}

			if (problems.isNotEmpty()) {
				div("row warning box") {
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

			if (info != null) {
				div("row info box") {
					+info
				}
			}

			div("row") {
				div("w6 column container") {
					h4 { +"Login" }
					form(action = "/", method = FormMethod.post) {
						div("row") { emailField(ID_LOGIN_EMAIL, "on", loginEmail) }
						div("row") { passwordField(ID_LOGIN_PASSWORD, "current-password") }
						div("row") {
							submitInput(classes = "u-full-width") { value = "Log in" }
						}
					}
				}

				div("w6 column container") {
					h4 { +"Register" }
					form(action = "/register", method = FormMethod.post) {
						div("row") { emailField(ID_REGISTER_EMAIL, "off", registerEmail) }
						div("row") { passwordField(ID_REGISTER_PASSWORD, "new-password") }
						div("row") { fullNameField(ID_REGISTER_NAME, "name", registerName) }
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

fun RoutingHandler.setupWelcomeRoutes() {
	get("/") { exchange ->
		val logout = when {
			exchange.formString("logout") != null -> {
				exchange.destroySession(false)
			}
			exchange.formString("logout-fully") != null -> {
				exchange.destroySession(true)
			}
			else -> 0
		}

		val session = exchange.session()
		if (session != null) {
			exchange.home(session)
		} else {
			val info = when {
				logout == 1 -> {
					"Logged out successfully"
				}
				logout == 2 -> {
					"Logged out successfully from this and 1 more browser"
				}
				logout > 2 -> {
					"Logged out successfully from this and ${logout - 1} more browsers"
				}
				else -> null
			}
			exchange.loginRegister(info=info)
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
			exchange.loginRegister(listOf("Too many failed login attempts, try again in ${wait.toHumanReadableString(l)}"), loginEmail = email, registerEmail = email)
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
