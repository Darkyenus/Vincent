package it.unibz.vincent.pages

import com.carrotsearch.hppc.LongObjectHashMap
import com.ibm.icu.util.ULocale
import io.undertow.server.HttpServerExchange
import io.undertow.server.RoutingHandler
import it.unibz.vincent.AccountType
import it.unibz.vincent.Accounts
import it.unibz.vincent.DemographyInfo
import it.unibz.vincent.session
import it.unibz.vincent.template.TemplateLang
import it.unibz.vincent.util.GET
import it.unibz.vincent.util.formString
import it.unibz.vincent.util.languages
import kotlinx.html.TR
import kotlinx.html.div
import kotlinx.html.h1
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.th
import kotlinx.html.thead
import kotlinx.html.tr
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.leftJoin
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val LOG = LoggerFactory.getLogger("AccountList")

const val ACCOUNT_LIST_URL = "/account-list"
const val ACCOUNT_LIST_FILTER_PARAM = "filter"
enum class AccountListFilter(val title:String, val hasQuestionnaire:Boolean) {
	ALL("All accounts", true),
	REGULAR("Regular accounts", true),
	GUEST("Guest accounts", false),
	RESERVED("Reserved codes", false);

	private val toString = name.toLowerCase()
	override fun toString(): String = toString
}

private class AccountInfo(
		val id:Long,
		val name:String,
		val email:String,
		val accountType:AccountType,
		val code:Int?,
		val timeRegistered: Instant?,
		val timeLastLogin:Instant?):Comparable<AccountInfo> {

	// Staff level questions
	var foodIntolerance:Boolean? = null
	var foodIntoleranceDetail:String? = null
	var sulfiteIntolerance:Boolean? = null

	// Admin level questions
	var phoneNumber:String? = null
	var gender:String? = null
	var yearOfBirth:String? = null
	var homeCountry:String? = null
	var homeRegion:String? = null
	var education:String? = null
	var smoking:Boolean? = null
	var smokingDetail:String? = null

	override fun compareTo(other: AccountInfo): Int {
		val nameComp = name.compareTo(other.name)
		if (nameComp == 0) {
			return id.compareTo(other.id)
		}
		return nameComp
	}
}

private val DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneId.systemDefault())

private fun TR.problemWithDetailTd(value:Boolean?, detail:String?, goodIs:Boolean) {
	val valueStr = when (value) {
		true -> "Yes"
		false -> "No"
		null -> "?"
	}

	val text = if (detail == null || value == goodIs) valueStr else "$valueStr - $detail"

	td(classes=if (value != goodIs) "account-list-problem-cell" else null) {
		+text
	}
}

private fun showAccountList(exchange: HttpServerExchange) {
	val session = exchange.session()!!
	val ownAccountType = session.accountType
	val lang = TemplateLang(ULocale.ENGLISH, exchange.languages())

	val filter = exchange.formString(ACCOUNT_LIST_FILTER_PARAM)
			?.let { try { AccountListFilter.valueOf(it.toUpperCase()) } catch (e:IllegalArgumentException){ null } }
			?: AccountListFilter.ALL

	val accounts = LongObjectHashMap<AccountInfo>()

	transaction {
		for (row in Accounts
				.let { if (filter.hasQuestionnaire) it.leftJoin(DemographyInfo, { id }, { user }) else it }
				.select {
					when (filter) {
						AccountListFilter.ALL -> Accounts.accountType lessEq ownAccountType
						AccountListFilter.REGULAR -> (Accounts.accountType greaterEq AccountType.NORMAL) and (Accounts.accountType lessEq ownAccountType)
						AccountListFilter.GUEST -> Accounts.accountType eq AccountType.GUEST
						AccountListFilter.RESERVED -> Accounts.accountType eq AccountType.RESERVED
					}
				}) {

			val userId = row[Accounts.id].value
			val accountInfo = accounts.get(userId) ?: run {
				val info = AccountInfo(
						row[Accounts.id].value,
						row[Accounts.name],
						row[Accounts.email],
						row[Accounts.accountType],
						row[Accounts.code],
						row[Accounts.timeRegistered].takeUnless { it == Instant.EPOCH },
						row[Accounts.timeLastLogin].takeUnless { it == Instant.EPOCH })
				accounts.put(userId, info)
				info
			}

			if (!filter.hasQuestionnaire) {
				continue
			}

			val questionId = row.getOrNull(DemographyInfo.questionId) ?: continue
			val response = row.getOrNull(DemographyInfo.response) ?: continue

			when (questionId) {
				QID_FOOD_INTOLERANCE -> {
					when (response.toLowerCase()) {
						"yes" -> accountInfo.foodIntolerance = true
						"no" -> accountInfo.foodIntolerance = false
						else -> LOG.warn("Invalid food intolerance response: {}", response)
					}
				}
				"$QID_FOOD_INTOLERANCE-detail" -> accountInfo.foodIntoleranceDetail = demographicOneOfResponseToHumanReadableLabel(questionId, response, lang) ?: response
				QID_SULFITE_INTOLERANCE -> {
					when (response.toLowerCase()) {
						"yes" -> accountInfo.sulfiteIntolerance = true
						"no" -> accountInfo.sulfiteIntolerance = false
						else -> LOG.warn("Invalid sulfite intolerance response: {}", response)
					}
				}
			}

			// Only admins have access to what comes next
			if (ownAccountType < AccountType.ADMIN) {
				continue
			}

			when (questionId) {
				QID_PHONE_NUMBER -> accountInfo.phoneNumber = response
				QID_GENDER -> accountInfo.gender = demographicOneOfResponseToHumanReadableLabel(questionId, response, lang) ?: response
				QID_YEAR_OF_BIRTH -> accountInfo.yearOfBirth = response
				QID_HOME_COUNTRY -> accountInfo.homeCountry = response
				QID_HOME_REGION -> accountInfo.homeRegion = response
				QID_EDUCATION -> accountInfo.education = demographicOneOfResponseToHumanReadableLabel(questionId, response, lang) ?: response
				QID_SMOKING -> {
					when (response.toLowerCase()) {
						"yes" -> accountInfo.smoking = true
						"no" -> accountInfo.smoking = false
						else -> LOG.warn("Invalid smoking response: {}", response)
					}
				}
				"$QID_SMOKING-detail" -> accountInfo.smokingDetail = response
			}
		}
	}

	val sortedAccounts: List<AccountInfo> = accounts.values().let {
		val result = ArrayList<AccountInfo>(it.size())
		for (cursor in it) {
			result.add(cursor.value)
		}
		result.sort()
		result
	}

	exchange.sendBase("${filter.title} - Vincent") { _, locale ->
		div("page-container") {
			h1 { +filter.title }

			table {
				thead {
					tr {
						th { +"Name" }
						th { +"E-mail" }
						th { +"Account type" }
						th { +"Code" }
						th { +"Registered at" }
						th { +"Last login at" }

						if (filter.hasQuestionnaire) {
							th { +"Food intolerant" }
							th { +"Sulfite intolerant" }

							if (ownAccountType >= AccountType.ADMIN) {
								th { +"Phone number" }
								th { +"Gender" }
								th { +"Year of birth" }
								th { +"Home country/region" }
								th { +"Education" }
								th { +"Smoking" }
							}
						}
					}
				}

				tbody {
					for (info in sortedAccounts) {
						tr {
							td { +info.name }
							td { +info.email }
							td { +info.accountType.toString().toLowerCase().capitalize() }
							td { +(info.code?.toString() ?: "?") }
							td { +(info.timeRegistered?.let { DATE_FORMATTER.format(it) } ?: "?") }
							td { +(info.timeLastLogin?.let { DATE_FORMATTER.format(it) } ?: "?") }
							if (filter.hasQuestionnaire) {
								problemWithDetailTd(info.foodIntolerance, info.foodIntoleranceDetail, false)
								problemWithDetailTd(info.sulfiteIntolerance, null, false)

								if (ownAccountType >= AccountType.ADMIN) {
									td { +(info.phoneNumber ?: "?") }
									td { +(info.gender ?: "?") }
									td { +(info.yearOfBirth ?: "?") }
									td {
										val base = info.homeCountry ?: "?"
										val homeRegion = info.homeRegion
										+if (homeRegion != null) {
											"$base - $homeRegion"
										} else {
											base
										}
									}
									td { +(info.education ?: "?") }
									problemWithDetailTd(info.smoking, info.smokingDetail, false)
								}
							}
						}
					}
				}
			}

			if (sortedAccounts.isEmpty()) {
				div("table-no-elements") {
					+"No accounts fit this criteria"
				}
			}
		}
	}
}


fun RoutingHandler.setupAccountListRoutes() {
	GET(ACCOUNT_LIST_URL, accessLevel=AccountType.STAFF) { exchange ->
		showAccountList(exchange)
	}
}