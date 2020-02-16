package it.unibz.vincent.pages

import com.carrotsearch.hppc.LongObjectHashMap
import com.ibm.icu.util.ULocale
import io.undertow.server.RoutingHandler
import io.undertow.util.Headers
import io.undertow.util.StatusCodes
import it.unibz.vincent.AccountType
import it.unibz.vincent.Accounts
import it.unibz.vincent.BRAND_NAME
import it.unibz.vincent.DemographyInfo
import it.unibz.vincent.QuestionnaireParticipants
import it.unibz.vincent.accountIdToGuestCode
import it.unibz.vincent.destroySessionsOf
import it.unibz.vincent.session
import it.unibz.vincent.template.TemplateLang
import it.unibz.vincent.util.CSVWriter
import it.unibz.vincent.util.GET
import it.unibz.vincent.util.POST
import it.unibz.vincent.util.Utf8ByteBufferWriter
import it.unibz.vincent.util.contentDispositionAttachment
import it.unibz.vincent.util.formString
import it.unibz.vincent.util.generateRandomPassword
import it.unibz.vincent.util.hashPassword
import it.unibz.vincent.util.languages
import it.unibz.vincent.util.pathString
import it.unibz.vincent.util.redirect
import it.unibz.vincent.util.toRawPassword
import kotlinx.html.div
import kotlinx.html.emailInput
import kotlinx.html.h1
import kotlinx.html.label
import kotlinx.html.numberInput
import kotlinx.html.postForm
import kotlinx.html.span
import kotlinx.html.submitInput
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.th
import kotlinx.html.thead
import kotlinx.html.tr
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.leftJoin
import org.jetbrains.exposed.sql.not
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter


private val LOG = LoggerFactory.getLogger("AccountList")

const val ACCOUNT_LIST_FILTER_PATH_PARAM = "filter"
const val ACCOUNT_LIST_PATH_TEMPLATE = "/account-list/{$ACCOUNT_LIST_FILTER_PATH_PARAM}"
fun accountListPath(filter:AccountListFilter):String = "/account-list/$filter"
const val ACCOUNT_LIST_DOWNLOAD_PATH_TEMPLATE = "/account-list/{$ACCOUNT_LIST_FILTER_PATH_PARAM}/download"
fun accountListDownloadPath(filter:AccountListFilter):String = "/account-list/$filter/download"

enum class AccountListFilter(
		val title:String,
		val hasQuestionnaire:Boolean,
		val hasName:Boolean,
		val hasAccountType:Boolean,
		val hasLoginTime:Boolean) {
	ALL("All accounts", true, true, true, true),
	REGULAR("Regular accounts", true, true, false, true),
	STAFF("Staff accounts", true, true, true, true),
	GUEST("Guest accounts", false, false, false, true),
	RESERVED("Reserved codes", false, false, false, false);

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

private class AccountListRow(val accountInfo:AccountInfo, columnCount:Int) {
	val values = ArrayList<String>(columnCount)
	val valueTypes = ArrayList<Boolean?>(columnCount)

	fun add(value:String) {
		values.add(value)
		valueTypes.add(null)
	}

	fun addWithDetail(value:Boolean?, detail:String?, goodIs:Boolean) {
		val valueStr = when (value) {
			true -> "Yes"
			false -> "No"
			null -> "?"
		}

		val text = if (detail == null || value == goodIs) valueStr else "$valueStr - $detail"

		values.add(text)
		valueTypes.add(value == goodIs)
	}

	inline fun forEach(action:(String, good:Boolean?) -> Unit) {
		val values = values
		val valueTypes = valueTypes
		for (i in 0 until values.size) {
			action(values[i], valueTypes[i])
		}
	}
}
private class AccountList(val headers:List<String>, val rows:List<AccountListRow>)

private fun getAccountList(filter:AccountListFilter, permissionLevel:AccountType, timeZone: ZoneId, lang:TemplateLang):AccountList {
	val accounts = LongObjectHashMap<AccountInfo>()
	val dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE.withZone(timeZone)

	transaction {
		for (row in Accounts
				.let { if (filter.hasQuestionnaire) it.leftJoin(DemographyInfo, { id }, { user }) else it }
				.select {
					when (filter) {
						AccountListFilter.ALL -> Accounts.accountType lessEq permissionLevel
						AccountListFilter.REGULAR -> Accounts.accountType eq AccountType.NORMAL
						AccountListFilter.STAFF -> (Accounts.accountType greater AccountType.NORMAL) and (Accounts.accountType lessEq permissionLevel)
						AccountListFilter.GUEST -> Accounts.accountType eq AccountType.GUEST
						AccountListFilter.RESERVED -> Accounts.accountType eq AccountType.RESERVED
					}
				}) {

			val userId = row[Accounts.id].value
			val accountInfo = accounts.get(userId) ?: run {
				val info = AccountInfo(
						row[Accounts.id].value,
						(row[Accounts.name] ?: "?"),
						(row[Accounts.email] ?: "?"),
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

			// Only admins have access to what comes next
			if (permissionLevel < AccountType.ADMIN) {
				continue
			}

			val questionId = row.getOrNull(DemographyInfo.questionId) ?: continue
			val response = row.getOrNull(DemographyInfo.response) ?: continue

			when (questionId) {
				QID_FOOD_INTOLERANCE -> accountInfo.foodIntolerance = demographicYesNoToBool(response)
				QID_FOOD_INTOLERANCE_DETAIL -> accountInfo.foodIntoleranceDetail = demographicOneOfResponseToHumanReadableLabel(questionId, response, lang) ?: response
				QID_SULFITE_INTOLERANCE -> accountInfo.sulfiteIntolerance = demographicYesNoToBool(response)
				QID_PHONE_NUMBER -> accountInfo.phoneNumber = response
				QID_GENDER -> accountInfo.gender = demographicOneOfResponseToHumanReadableLabel(questionId, response, lang) ?: response
				QID_YEAR_OF_BIRTH -> accountInfo.yearOfBirth = response
				QID_HOME_COUNTRY -> accountInfo.homeCountry = response
				QID_HOME_REGION -> accountInfo.homeRegion = response
				QID_EDUCATION -> accountInfo.education = demographicOneOfResponseToHumanReadableLabel(questionId, response, lang) ?: response
				QID_SMOKING -> accountInfo.smoking = demographicYesNoToBool(response)
				QID_SMOKING_DETAIL -> accountInfo.smokingDetail = response
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

	val headers = ArrayList<String>()
	val rows = ArrayList<AccountListRow>(sortedAccounts.size)

	if (filter.hasName) {
		headers.add("Name")
	}
	headers.add("E-mail")
	if (filter.hasAccountType) {
		headers.add("Account type")
	}
	headers.add("Code")
	if (permissionLevel >= AccountType.ADMIN) {
		if (filter == AccountListFilter.RESERVED) {
			headers.add("Reserved at")
		} else {
			headers.add("Registered at")
		}
		if (filter.hasLoginTime) {
			headers.add("Last login at")
		}

		if (filter.hasQuestionnaire) {
			headers.add("Food intolerant")
			headers.add("Sulfite intolerant")
			headers.add("Phone number")
			headers.add("Gender")
			headers.add("Year of birth")
			headers.add("Home country/region")
			headers.add("Education")
			headers.add("Smoking")
		}
	}

	for (info in sortedAccounts) {
		val row = AccountListRow(info, headers.size)
		if (filter.hasName) {
			row.add(info.name)
		}
		row.add(info.email)
		if (filter.hasAccountType) {
			row.add(info.accountType.toString().toLowerCase().capitalize())
		}
		if (info.accountType == AccountType.GUEST) {
			row.add(accountIdToGuestCode(info.id))
		} else {
			row.add((info.code?.toString() ?: "?"))
		}
		if (permissionLevel >= AccountType.ADMIN) {
			row.add((info.timeRegistered?.let { dateFormatter.format(it) } ?: "?"))
			if (filter.hasLoginTime) {
				row.add((info.timeLastLogin?.let { dateFormatter.format(it) } ?: "?"))
			}
			if (filter.hasQuestionnaire) {
				row.addWithDetail(info.foodIntolerance, info.foodIntoleranceDetail, false)
				row.addWithDetail(info.sulfiteIntolerance, null, false)
				row.add(info.phoneNumber ?: "?")
				row.add(info.gender ?: "?")
				row.add(info.yearOfBirth ?: "?")
				val base = info.homeCountry ?: "?"
				val homeRegion = info.homeRegion
				if (homeRegion != null) {
					row.add("$base - $homeRegion")
				} else {
					row.add(base)
				}
				row.add(info.education ?: "?")
				row.addWithDetail(info.smoking, info.smokingDetail, false)
			}
		}
		rows.add(row)
	}

	return AccountList(headers, rows)
}

private const val ACTION_DELETE_UNUSED_GUEST_ACCOUNTS = "delete-unused-guest-accounts"

private const val ACTION_DELETE_ACCOUNT = "delete-account"
private const val ACTION_RESET_ACCOUNT_PASSWORD = "reset-password"
private const val PARAM_ACCOUNT_ID = "account-id"

private const val ACTION_RESERVE_CODE = "reserve-code"
private const val PARAM_RESERVE_EMAIL = "email"

private const val PARAM_RESERVE_CODE = "code"

fun RoutingHandler.setupAccountListRoutes() {
	GET(ACCOUNT_LIST_PATH_TEMPLATE, accessLevel=AccountType.STAFF) { exchange ->
		val session = exchange.session()!!
		val ownAccountType = session.accountType
		val filter = exchange.pathString(ACCOUNT_LIST_FILTER_PATH_PARAM)
				.let { try { AccountListFilter.valueOf(it.toUpperCase()) } catch (e:IllegalArgumentException){ null } }
				?: AccountListFilter.ALL
		val accountList = getAccountList(filter, ownAccountType, session.timeZone, TemplateLang(ULocale.ENGLISH, exchange.languages()))

		exchange.sendBase(filter.title) { _, _ ->
			div("page-container-wide") {
				h1 { +filter.title }

				renderMessages(exchange)

				table {
					thead {
						tr {
							for (header in accountList.headers) {
								th { +header }
							}
						}
					}

					tbody {
						for (row in accountList.rows) {
							tr {
								row.forEach { value, good ->
									if (good == null) {
										td("copy") { +value }
									} else {
										td(if (good) "al-cell-ok" else "al-cell-bad") { +value }
									}
								}

								if (row.accountInfo.accountType >= AccountType.NORMAL && row.accountInfo.accountType < AccountType.STAFF && ownAccountType >= AccountType.ADMIN) {
									td {
										postButton(session,
												accountListPath(filter), PARAM_ACCOUNT_ID to row.accountInfo.id.toString(),
												routeAction = ACTION_RESET_ACCOUNT_PASSWORD, classes = "dangerous",
												confirmation = "Password of this account will be reset to a randomly generated password, which you then should provide back to them. Are you sure you want to do that?") {
											+"Reset lost password"
										}
									}
								}

								if (ownAccountType >= AccountType.ADMIN && (row.accountInfo.id != session.userId)) {
									td {
										postButton(session,
												accountListPath(filter), PARAM_ACCOUNT_ID to row.accountInfo.id.toString(),
												routeAction = ACTION_DELETE_ACCOUNT, classes = "dangerous",
												confirmation = "Do you really want to delete this account? The account can be deleted only if it does not participate in any questionnaires. All related account information (such as demographic data) will be lost forever!") {
											+"Delete account"
										}
									}
								}
							}
						}
					}
				}

				if (accountList.rows.isEmpty()) {
					div("table-no-elements") {
						+"No accounts fit this criteria"
					}
				}

			}

			div("page-container") {
				div("button-container") {
					getButton(accountListDownloadPath(filter), parentClasses = "column") { +"Download as CSV" }

					if (filter == AccountListFilter.ALL || filter == AccountListFilter.GUEST) {
						postButton(session, accountListPath(filter), routeAction = ACTION_DELETE_UNUSED_GUEST_ACCOUNTS, parentClasses = "column", classes = "dangerous", confirmation="Do you really want to delete all guest accounts which are currently not invited to any questionnaire?") {
							+"Delete unused guest accounts"
						}
					}
				}

				postForm(accountListPath(filter), classes = "compact-form") {
					session(session)
					routeAction(ACTION_RESERVE_CODE)
					label("main") {
						span("label") { +"E-mail" }
						emailInput(name = PARAM_RESERVE_EMAIL) {
							required = true
							placeholder = "mail@example.com"
						}
					}
					label {
						span("label") { +"Code" }
						numberInput(name = PARAM_RESERVE_CODE) {
							required = true
							min = "1"
							placeholder = "000"
						}
					}
					submitInput { value = "Assign code" }
				}
			}
		}
	}

	POST(ACCOUNT_LIST_PATH_TEMPLATE, accessLevel=AccountType.ADMIN, routeAction = ACTION_RESET_ACCOUNT_PASSWORD) { exchange ->
		val filter = exchange.pathString(ACCOUNT_LIST_FILTER_PATH_PARAM)
				.let { try { AccountListFilter.valueOf(it.toUpperCase()) } catch (e:IllegalArgumentException){ null } }
				?: AccountListFilter.ALL

		val accountId = exchange.formString(PARAM_ACCOUNT_ID)?.toLongOrNull()
		if (accountId != null) {
			val newPassword = generateRandomPassword()
			val hashedPassword = hashPassword(newPassword.toString().toRawPassword())

			val updated = transaction {
				val updated = Accounts.update(where = { (Accounts.id eq accountId) and (Accounts.accountType greaterEq AccountType.NORMAL) and (Accounts.accountType less AccountType.STAFF) }, limit=1) {
					it[Accounts.password] = hashedPassword
				}
				if (updated != 1) {
					rollback()
				}
				updated
			}

			if (updated == 1) {
				LOG.info("Account {} password has been reset", accountId)
				exchange.messageInfo("Password has been reset to: $newPassword")
				exchange.redirect(accountListPath(filter))
				return@POST
			}
		}

		exchange.messageWarning("Password could not be reset")
		exchange.redirect(accountListPath(filter))
	}

	POST(ACCOUNT_LIST_PATH_TEMPLATE, accessLevel=AccountType.STAFF, routeAction = ACTION_DELETE_UNUSED_GUEST_ACCOUNTS) { exchange ->
		val deleted = deleteUnusedGuestAccounts()

		val filter = exchange.pathString(ACCOUNT_LIST_FILTER_PATH_PARAM)
				.let { try { AccountListFilter.valueOf(it.toUpperCase()) } catch (e:IllegalArgumentException){ null } }
				?: AccountListFilter.ALL
		exchange.messageInfo("Deleted $deleted guest account(s)")
		exchange.redirect(accountListPath(filter))
	}

	POST(ACCOUNT_LIST_PATH_TEMPLATE, accessLevel=AccountType.ADMIN, routeAction = ACTION_DELETE_ACCOUNT) { exchange ->
		val filter = exchange.pathString(ACCOUNT_LIST_FILTER_PATH_PARAM)
				.let { try { AccountListFilter.valueOf(it.toUpperCase()) } catch (e:IllegalArgumentException){ null } }
				?: AccountListFilter.ALL

		val accountId = exchange.formString(PARAM_ACCOUNT_ID)?.toLongOrNull()

		if (accountId == null) {
			exchange.messageWarning("User must be specified")
			exchange.redirect(accountListPath(filter))
			return@POST
		}

		val deletions = transaction {
			Accounts.deleteWhere(limit=1) {
				(Accounts.id eq accountId) and
						(not (Accounts.id inSubQuery (QuestionnaireParticipants.slice(QuestionnaireParticipants.participant).selectAll())))
			}
		}

		if (deletions >= 1) {
			destroySessionsOf(accountId)
			exchange.messageInfo("User permanently deleted")
			exchange.redirect(accountListPath(filter))
		} else {
			exchange.messageWarning("User could not be deleted - perhaps it is still participating in a questionnaire?")
			exchange.redirect(accountListPath(filter))
		}
	}

	GET(ACCOUNT_LIST_DOWNLOAD_PATH_TEMPLATE, accessLevel=AccountType.STAFF) { exchange ->
		val session = exchange.session()!!
		val ownAccountType = session.accountType
		val filter = exchange.pathString(ACCOUNT_LIST_FILTER_PATH_PARAM)
				.let { try { AccountListFilter.valueOf(it.toUpperCase()) } catch (e:IllegalArgumentException){ null } }
				?: AccountListFilter.ALL
		val accountList = getAccountList(filter, ownAccountType, session.timeZone, TemplateLang(ULocale.ENGLISH, exchange.languages()))

		val writer = Utf8ByteBufferWriter()
		CSVWriter(writer).use { csv ->
			for (header in accountList.headers) {
				csv.item(header)
			}
			csv.row()

			for (row in accountList.rows) {
				row.forEach { value, _ ->
					csv.item(value)
				}
				csv.row()
			}
		}

		exchange.statusCode = StatusCodes.OK
		exchange.responseHeaders.put(Headers.CONTENT_DISPOSITION, contentDispositionAttachment("$BRAND_NAME-$filter-accounts.csv"))
		exchange.responseSender.send(writer.utf8Bytes())
	}

	POST(ACCOUNT_LIST_PATH_TEMPLATE, accessLevel=AccountType.ADMIN, routeAction = ACTION_RESERVE_CODE) { exchange ->
		val filter = exchange.pathString(ACCOUNT_LIST_FILTER_PATH_PARAM)
				.let { try { AccountListFilter.valueOf(it.toUpperCase()) } catch (e:IllegalArgumentException){ null } }
				?: AccountListFilter.ALL

		val email = exchange.formString(PARAM_RESERVE_EMAIL)
		val code = exchange.formString(PARAM_RESERVE_CODE)?.toIntOrNull()

		if (email == null || code == null) {
			exchange.messageWarning("Specify both email and code for the registration")
			exchange.redirect(accountListPath(filter))
			return@POST
		}

		when (val result = transaction { Accounts.assignCodeToEmail(email, code) }) {
			Accounts.CodeAssignResult.AlreadyHasThatCode -> exchange.messageInfo("User '$email' already has that code")
			is Accounts.CodeAssignResult.CodeNotFree -> exchange.messageWarning("Code already assigned to a user '${result.occupiedByEmail}'")
			Accounts.CodeAssignResult.SuccessChanged -> exchange.messageInfo("Code of user '$email' changed to $code")
			is Accounts.CodeAssignResult.FailureToChange -> exchange.messageWarning("Failed to change code of user '$email' from ${result.oldCode} to $code")
			Accounts.CodeAssignResult.SuccessReserved -> exchange.messageInfo("Reserved code $code for user with e-mail '$email'")
		}
		exchange.redirect(accountListPath(filter))
	}
}