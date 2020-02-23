package it.unibz.vincent.pages

import com.carrotsearch.hppc.LongArrayList
import io.undertow.server.RoutingHandler
import io.undertow.util.StatusCodes
import it.unibz.vincent.AccountType
import it.unibz.vincent.Accounts
import it.unibz.vincent.QuestionnaireParticipants
import it.unibz.vincent.accountIdToGuestCode
import it.unibz.vincent.createSession
import it.unibz.vincent.guestCodeToAccountId
import it.unibz.vincent.util.GET
import it.unibz.vincent.util.pathString
import it.unibz.vincent.util.redirect
import it.unibz.vincent.util.secureRandomBytes
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.not
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory
import java.net.URLEncoder
import java.time.Instant
import java.util.*

private val LOG = LoggerFactory.getLogger("Guest")

fun createGuestAccounts(amount:Int):LongArrayList {
	val result = LongArrayList(amount)
	transaction {
		for (i in 0 until amount) {
			val loginCode = secureRandomBytes(Accounts.GUEST_LOGIN_CODE_SIZE)
			val accountId = Accounts.createGuestAccount(loginCode)
			result.add(accountId)
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

private const val PATH_ID = "id"
private const val PATH_CODE = "code"
private const val GUEST_LOGIN_PATH_TEMPLATE = "/guest/{$PATH_ID}/{$PATH_CODE}"
private const val GUEST_LOGIN_PATH = "/guest"

fun createGuestLoginUrl(accountId:Long, loginCode:ByteArray, scheme:String, host:String):CharSequence {
	val sb = StringBuilder()
	sb.append(scheme).append("://").append(host).append(GUEST_LOGIN_PATH)
			.append('/').append(URLEncoder.encode(accountIdToGuestCode(accountId), Charsets.UTF_8.name()))
			.append('/').append(Base64.getUrlEncoder().encodeToString(loginCode))
	return sb
}

fun RoutingHandler.setupGuestLoginRoutes() {
	GET(GUEST_LOGIN_PATH_TEMPLATE) { exchange ->
		val accountId = guestCodeToAccountId(exchange.pathString(PATH_ID))
		val loginCode = Base64.getUrlDecoder().decode(exchange.pathString(PATH_CODE))
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