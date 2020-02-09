package it.unibz.vincent.pages

import io.undertow.server.HttpServerExchange
import io.undertow.server.RoutingHandler
import io.undertow.util.StatusCodes
import it.unibz.vincent.AccountType
import it.unibz.vincent.Accounts
import it.unibz.vincent.failedLoginAttemptLog
import it.unibz.vincent.session
import it.unibz.vincent.util.GET
import it.unibz.vincent.util.POST
import it.unibz.vincent.util.checkPassword
import it.unibz.vincent.util.formString
import it.unibz.vincent.util.hashPassword
import it.unibz.vincent.util.languages
import it.unibz.vincent.util.redirect
import it.unibz.vincent.util.toHumanReadableTime
import it.unibz.vincent.util.toRawPassword
import kotlinx.html.FormMethod
import kotlinx.html.div
import kotlinx.html.form
import kotlinx.html.h4
import kotlinx.html.submitInput
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory


private val LOG = LoggerFactory.getLogger("Profile")

const val PROFILE_PATH = "/profile"

private const val ACTION_CHANGE_PASSWORD = "change-password"

private const val FORM_CURRENT_PASSWORD = "current-password"
private const val FORM_NEW_PASSWORD = "new-password"

private fun showProfilePage(exchange: HttpServerExchange) {
	val session = exchange.session()!!

	exchange.sendBase("Profile") { _, locale ->
		div("page-container") {

			renderMessages(exchange)

			// Demography & Logout
			div("page-section button-container") {
				getButton(DEMOGRAPHY_PATH) { +"Personal info" }

				postButton(session, HOME_PATH, routeAction = "logout", classes = "dangerous") { +"Logout" }

				if (session.accountType >= AccountType.STAFF) {
					// Let's not confuse ordinary users with this
					postButton(session, HOME_PATH, routeAction = "logout-fully", classes = "dangerous") { +"Logout from all browsers" }
				}
			}

			if (session.accountType >= AccountType.NORMAL) {
				// Password change
				div("page-section") {
					h4 { +"Change password" }
					form(action = PROFILE_PATH, method = FormMethod.post) {
						session(session)
						routeAction(ACTION_CHANGE_PASSWORD)
						div("form-section") { passwordField(FORM_CURRENT_PASSWORD, "current-password", internalId = "c-pass-old", label = "Current password") }
						div("form-section") { passwordField(FORM_NEW_PASSWORD, "new-password", internalId = "c-pass-new", label = "New password") }
						div("form-section-last") {
							submitInput(classes = "u-full-width") { value = "Change" }
						}
					}
				}
			}
		}
	}

}

fun RoutingHandler.setupProfileRoutes() {
	GET(PROFILE_PATH, AccountType.NORMAL, requireCompletedDemography = false) { exchange ->
		showProfilePage(exchange)
	}

	POST(PROFILE_PATH, AccountType.NORMAL, ACTION_CHANGE_PASSWORD) { exchange ->
		val session = exchange.session()!!
		val lang = exchange.languages()

		val currentPassword = exchange.formString(FORM_CURRENT_PASSWORD)
		val newPassword = exchange.formString(FORM_NEW_PASSWORD)

		if (currentPassword == null || newPassword == null) {
			exchange.statusCode = StatusCodes.BAD_REQUEST
			showProfilePage(exchange)
			return@POST
		}

		// Check new password
		var hasProblems = false
		if (newPassword.length < MIN_PASSWORD_LENGTH) {
			exchange.messageWarning("The new password is too short - must be at least $MIN_PASSWORD_LENGTH characters long")
			hasProblems = true
		} else if (newPassword.length > MAX_PASSWORD_LENGTH) {
			exchange.messageWarning("The new password is too long - can't be longer than $MAX_PASSWORD_LENGTH characters")
			hasProblems = true
		}
		if (hasProblems) {
			exchange.redirect(PROFILE_PATH)
			return@POST
		}

		// Check current password
		val rateLimiter = failedLoginAttemptLog[session.userId]!!
		rateLimiter.attemptLogin({ waitUntil ->
			LOG.info("{} - Attempted password change too soon (password change, from {})", session.userId, exchange.sourceAddress)

			exchange.messageWarning("Too many failed login attempts, try again ${waitUntil.toHumanReadableTime(lang, session.timeZone, relative = true)}")
			exchange.redirect(PROFILE_PATH)
		}) { now ->
			val storedPassword = transaction {
				Accounts.slice(Accounts.password).select { Accounts.id eq session.userId }.limit(1).single()[Accounts.password]
			}

			val validPassword = checkPassword(currentPassword.toRawPassword(), storedPassword)

			if (!validPassword) {
				// Add penalty token
				LOG.info("{} - Failed login attempt (password change, from {})", session.userId, exchange.sourceAddress)
				rateLimiter.addPenalty(now)
				exchange.messageWarning("Provided current password is invalid")
				exchange.redirect(PROFILE_PATH)
			} else {
				// Store last login time
				LOG.info("{} - Successful login attempt (password change, from {})", session.userId, exchange.sourceAddress)

				val updated = transaction {
					Accounts.update(where = {(Accounts.id eq session.userId) and (Accounts.password eq storedPassword)}, limit = 1) {
						it[Accounts.password] = hashPassword(newPassword.toRawPassword())
					}
				}

				if (updated == 1) {
					LOG.info("{} - Successful password change attempt (from {})", session.userId, exchange.sourceAddress)
					exchange.messageInfo("Password changed successfully")
				} else {
					LOG.info("{} - Failed password change attempt (from {}, updated {} rows)", session.userId, exchange.sourceAddress, updated)
					exchange.messageWarning("Invalid password (already changed?)")
				}

				exchange.redirect(PROFILE_PATH)
			}
		}
	}
}