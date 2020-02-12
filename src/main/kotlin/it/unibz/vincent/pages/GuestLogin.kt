package it.unibz.vincent.pages

import io.undertow.server.RoutingHandler
import io.undertow.util.StatusCodes
import it.unibz.vincent.AccountType
import it.unibz.vincent.Accounts
import it.unibz.vincent.AllTables
import it.unibz.vincent.QuestionnaireParticipants
import it.unibz.vincent.accountIdToGuestCode
import it.unibz.vincent.createSession
import it.unibz.vincent.guestCodeToAccountId
import it.unibz.vincent.util.GET
import it.unibz.vincent.util.formString
import it.unibz.vincent.util.redirect
import it.unibz.vincent.util.secureRandomBytes
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.not
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory
import java.net.URLEncoder
import java.time.Instant
import java.util.*
import kotlin.collections.ArrayList

private val LOG = LoggerFactory.getLogger("Guest")

class GuestAccountCredentials(val accountId:Long, val loginCode:ByteArray)

private val NO_PASSWORD = ByteArray(0)

fun createGuestAccounts(amount:Int):List<GuestAccountCredentials> {
	val result = ArrayList<GuestAccountCredentials>(amount)
	transaction {
		for (i in 0 until amount) {
			val loginCode = secureRandomBytes(Accounts.GUEST_LOGIN_CODE_SIZE)
			val accountId = Accounts.insertAndGetId {
				it[Accounts.name] = null
				it[Accounts.email] = null
				it[Accounts.password] = NO_PASSWORD
				it[accountType] = AccountType.GUEST
				it[timeRegistered] = Instant.now()
				it[timeLastLogin] = Instant.EPOCH
				it[Accounts.guestLoginCode] = loginCode
			}.value
			result.add(GuestAccountCredentials(accountId, loginCode))
		}
	}
	return result
}

fun deleteUnusedGuestAccounts():Int {
	return transaction {
		Accounts.deleteWhere {
			(Accounts.accountType eq AccountType.GUEST) and
					(not (Accounts.id inSubQuery (QuestionnaireParticipants.slice(QuestionnaireParticipants.participant).selectAll())))
		}
	}
}

const val GUEST_LOGIN_PATH = "/guest"
const val FORM_GUEST_ID = "g"
const val FORM_GUEST_LOGIN_CODE = "auth"

fun createGuestLoginUrl(accountId:Long, loginCode:ByteArray, scheme:String, host:String):CharSequence {
	val sb = StringBuilder()
	sb.append(scheme).append("://").append(host).append(GUEST_LOGIN_PATH)
			.append("?").append(FORM_GUEST_ID).append('=').append(URLEncoder.encode(accountIdToGuestCode(accountId), Charsets.UTF_8.name()))
			.append('&').append(FORM_GUEST_LOGIN_CODE).append('=').append(Base64.getUrlEncoder().encodeToString(loginCode))
	return sb
}

fun RoutingHandler.setupGuestLoginRoutes() {
	GET(GUEST_LOGIN_PATH) { exchange ->
		val accountId = exchange.formString(FORM_GUEST_ID)?.let { guestCodeToAccountId(it) }
		val loginCode = exchange.formString(FORM_GUEST_LOGIN_CODE)?.let { Base64.getUrlDecoder().decode(it) }
		if (accountId == null || loginCode == null) {
			exchange.redirect(HOME_PATH, StatusCodes.TEMPORARY_REDIRECT)
			return@GET
		}

		val valid = transaction {
			!Accounts.select { (Accounts.id eq accountId) and
					(Accounts.accountType eq AccountType.GUEST) and
					(Accounts.guestLoginCode eq loginCode) }.empty()
		}

		if (!valid) {
			LOG.info("Failed guest login of {} (from {})", accountId, exchange.sourceAddress)
			exchange.messageWarning("Invalid guest login URL")
			exchange.loginRegister()
			return@GET
		}

		LOG.info("Successful guest login of {} (from {})", accountId, exchange.sourceAddress)
		transaction { Accounts.update(where = { Accounts.id eq accountId }) { it[timeLastLogin] = Instant.now() } }
		exchange.createSession(accountId, getTimeZoneOffset(exchange))
		exchange.redirect(HOME_PATH, StatusCodes.TEMPORARY_REDIRECT)
	}
}