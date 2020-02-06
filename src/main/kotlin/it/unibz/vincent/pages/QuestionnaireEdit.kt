package it.unibz.vincent.pages

import com.carrotsearch.hppc.IntHashSet
import com.carrotsearch.hppc.IntIntHashMap
import io.undertow.server.HttpServerExchange
import io.undertow.server.RoutingHandler
import io.undertow.util.AttachmentKey
import io.undertow.util.Headers
import io.undertow.util.StatusCodes
import it.unibz.vincent.AccountType
import it.unibz.vincent.Accounts
import it.unibz.vincent.DemographyInfo
import it.unibz.vincent.GUEST_CODE_PREFIX
import it.unibz.vincent.QuestionnaireParticipants
import it.unibz.vincent.QuestionnaireResponses
import it.unibz.vincent.QuestionnaireState
import it.unibz.vincent.QuestionnaireTemplates
import it.unibz.vincent.QuestionnaireWines
import it.unibz.vincent.Questionnaires
import it.unibz.vincent.Session
import it.unibz.vincent.WineParticipantAssignment
import it.unibz.vincent.accountIdToGuestCode
import it.unibz.vincent.guestCodeToAccountId
import it.unibz.vincent.session
import it.unibz.vincent.util.CSVWriter
import it.unibz.vincent.util.GET
import it.unibz.vincent.util.LocaleStack
import it.unibz.vincent.util.POST
import it.unibz.vincent.util.SQLErrorType
import it.unibz.vincent.util.contentDispositionAttachment
import it.unibz.vincent.util.formString
import it.unibz.vincent.util.languages
import it.unibz.vincent.util.pathString
import it.unibz.vincent.util.redirect
import it.unibz.vincent.util.type
import kotlinx.html.FlowContent
import kotlinx.html.div
import kotlinx.html.h1
import kotlinx.html.h2
import kotlinx.html.hiddenInput
import kotlinx.html.label
import kotlinx.html.numberInput
import kotlinx.html.p
import kotlinx.html.postForm
import kotlinx.html.span
import kotlinx.html.style
import kotlinx.html.submitInput
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.textInput
import kotlinx.html.th
import kotlinx.html.thead
import kotlinx.html.tr
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.leftJoin
import org.jetbrains.exposed.sql.lowerCase
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory
import java.io.CharArrayWriter
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.sql.SQLException

private val LOG = LoggerFactory.getLogger("QuestionnaireEdit")

private const val PATH_QUESTIONNAIRE_ID = "qId"
fun questionnaireEditPath(questionnaireId:Long):String {
	return "/questionnaire/$questionnaireId/edit"
}
fun questionnaireResultsPath(questionnaireId:Long):String {
	return "/questionnaire/$questionnaireId/results"
}
private const val QUESTIONNAIRE_EDIT_PATH_TEMPLATE = "/questionnaire/{$PATH_QUESTIONNAIRE_ID}/edit"
private const val QUESTIONNAIRE_RESULTS_PATH_TEMPLATE = "/questionnaire/{$PATH_QUESTIONNAIRE_ID}/results"

private val intoleranceQuestionList = listOf(QID_SULFITE_INTOLERANCE, QID_FOOD_INTOLERANCE, QID_FOOD_INTOLERANCE_DETAIL)

/**
 * List of:
 * - Participant name
 * - Participant email
 * - Participant code
 * - Participant state
 * Actions:
 * - Kick
 *
 * Standalone actions:
 * - Add new
 */
private fun FlowContent.questionnaireParticipants(session: Session, locale:LocaleStack, questionnaire: Questionnaire) {
	h2 { +"Participants" }

	var empty = true
	table {
		thead {
			tr {
				th {+"#"}
				th { +"Name" }
				th { +"E-mail" }
				th { +"Code" }
				th { +"State" }
				th { +"Sulfite Intolerance" }
				th { +"Food Intolerance" }
				// if editable: Kick
			}
		}

		tbody {
			transaction {
				var index = 1
				for (row in QuestionnaireParticipants
						.leftJoin(Accounts, { participant }, { id })
						.slice(Accounts.id, Accounts.name, Accounts.email, Accounts.code, QuestionnaireParticipants.state)
						.select { QuestionnaireParticipants.questionnaire eq questionnaire.id }
						.orderBy(Accounts.name)
						.orderBy(Accounts.id)) {
					empty = false
					tr {
						val accountId = row[Accounts.id].value
						td { +(index++).toString() }
						td { +row[Accounts.name] }
						td { +row[Accounts.email] }
						td { +(row[Accounts.code]?.toString() ?: accountIdToGuestCode(accountId)) }
						td { +row[QuestionnaireParticipants.state].toString().toLowerCase().capitalize() /* TODO Localize */ }

						// Intolerances
						var sulfiteIntolerance:Boolean? = null
						var foodIntolerance:Boolean? = null
						var foodIntoleranceDetail:String? = null
						for (intoleranceRow in DemographyInfo
								.slice(DemographyInfo.questionId, DemographyInfo.response)
								.select { (DemographyInfo.user eq accountId) and (DemographyInfo.questionId inList intoleranceQuestionList) }) {
							val response = intoleranceRow[DemographyInfo.response]
							when (intoleranceRow[DemographyInfo.questionId]) {
								QID_SULFITE_INTOLERANCE -> sulfiteIntolerance = demographicYesNoToBool(response)
								QID_FOOD_INTOLERANCE -> foodIntolerance = demographicYesNoToBool(response)
								QID_FOOD_INTOLERANCE_DETAIL -> foodIntoleranceDetail = response
							}
						}

						if (sulfiteIntolerance == false) {
							td("al-cell-ok") { +"No" }
						} else {
							td("al-cell-bad") {
								if (sulfiteIntolerance == null) {
									+"?"
								} else {
									+"Yes"
								}
							}
						}

						if (foodIntolerance == false) {
							td("al-cell-ok") { +"No" }
						} else {
							td("al-cell-bad") {
								if (foodIntolerance == null) {
									+"?"
								} else {
									if (foodIntoleranceDetail == null || foodIntoleranceDetail.isBlank()) {
										+"Yes"
									} else {
										+foodIntoleranceDetail
									}
								}
							}
						}

						if (questionnaire.state != QuestionnaireState.CLOSED) {
							td {
								postButton(session, questionnaireEditPath(questionnaire.id),
										PARAM_USER_ID to accountId.toString(),
										routeAction = ACTION_UNINVITE, confirmation = "Are you sure? This will delete all responses from this participant as well!") {
									+"Uninvite"
								}
							}
						}
					}
				}
			}
		}
	}

	if (empty) {
		div("table-no-elements") {
			+"Add participants"
		}
	}

	if (questionnaire.state != QuestionnaireState.CLOSED) {
		postForm(questionnaireEditPath(questionnaire.id)) {
			session(session)
			routeAction(ACTION_INVITE)
			textInput(name = PARAM_USERS) { required = true; placeholder = "ex@mp.le, 123, ..." }
			submitInput { value = "Invite" }
		}
	}
}

/**
 * List of:
 * - Wine name
 * - Assigned wine code (modifiable)
 * Actions:
 * - Save new code
 * - Remove
 *
 * Standalone actions:
 * - Add new wine (only if not started yet)
 */
private fun FlowContent.questionnaireWines(session: Session, locale: LocaleStack, questionnaire: Questionnaire) {
	if (!questionnaire.hasWines) {
		// This is by default true and becomes false only after opening
		return
	}

	h2 { +"Wines" }

	class Wine(val id:Long, val name:String, val code:Int)

	val wines = ArrayList<Wine>()
	transaction {
		for (row in QuestionnaireWines
				.select { QuestionnaireWines.questionnaire eq questionnaire.id }
				.orderBy(QuestionnaireWines.name)
				.orderBy(QuestionnaireWines.id)) {
			val wineId = row[QuestionnaireWines.id].value
			val wineName = row[QuestionnaireWines.name]
			val wineCode = row[QuestionnaireWines.code]
			wines.add(Wine(wineId, wineName, wineCode))
		}
	}

	val duplicateCodes = IntHashSet().apply {
		val allCodes = IntHashSet(wines.size)
		for (wine in wines) {
			if (!allCodes.add(wine.code)) {
				add(wine.code)
			}
		}
	}

	table {
		thead {
			tr {
				th {+"#"}
				th(classes = "grow") { +"Name" }
				th { +"Code" }
				// if editable: Remove
			}
		}

		tbody {
			for ((index, wine) in wines.withIndex()) {
				val wineIdStr = wine.id.toString()

				tr {
					td { +(index + 1).toString() }
					td {
						postForm(questionnaireEditPath(questionnaire.id)) {
							session(session)
							routeAction(ACTION_RENAME_WINE)
							hiddenInput(name = PARAM_WINE_ID) { value = wineIdStr }
							textInput(name = PARAM_WINE_NAME) { required = true; value = wine.name }
						}
					}
					td(if (duplicateCodes.contains(wine.code)) "at-duplicate" else null) {
						if (questionnaire.state == QuestionnaireState.CREATED) {
							postForm(questionnaireEditPath(questionnaire.id)) {
								session(session)
								routeAction(ACTION_WINE_UPDATE_CODE)
								hiddenInput(name = PARAM_WINE_ID) { value = wineIdStr }
								numberInput(name = PARAM_WINE_CODE) { value = wine.code.toString() }
							}
						} else {
							+wine.code.toString()
						}
					}
					if (questionnaire.state == QuestionnaireState.CREATED) {
						td {
							postButton(session, questionnaireEditPath(questionnaire.id), PARAM_WINE_ID to wineIdStr, routeAction=ACTION_REMOVE_WINE, classes="dangerous") {
								icon(Icons.TRASH)
							}
						}
					}
				}
			}
		}
	}

	if (wines.isEmpty()) {
		div("table-no-elements") {
			if (questionnaire.state == QuestionnaireState.CREATED) {
				+"Add wines"
			} else {
				+"No wines"
			}
		}
	}

	if (questionnaire.state == QuestionnaireState.CREATED) {
		postForm(questionnaireEditPath(questionnaire.id)) {
			session(session)
			routeAction(ACTION_ADD_WINE)
			label {
				+"Wine name"
				textInput(name = PARAM_WINE_NAME) { required = true; placeholder = "Chardonnay" }
			}
			label {
				+"Code"
				numberInput(name = PARAM_WINE_CODE) { required = false; placeholder = "Random" }
			}
			submitInput { value = "Add Wine" }
		}
	}
}

/**
 * Matrix of:
 * - Left/row: Order (1 to wine count + 1)
 * - Up/column: Participant
 * - Content: Code of the wine + Name of the wine
 */
private fun FlowContent.questionnaireWineParticipantAssociations(session: Session, locale: LocaleStack, questionnaire: Questionnaire) {
	data class Entry(val participantName:String, val panelistCode:Int?, val accountId:Long, val wineName:String, val wineCode:Int)

	if (!questionnaire.hasWines) {
		// This is by default true and becomes false only after opening
		return
	}

	val entries = ArrayList<ArrayList<Entry>>()

	// Collect data
	transaction {
		var lastParticipantId = -1L

		for (row in WineParticipantAssignment
				.leftJoin(QuestionnaireWines, { wine }, { id })
				.leftJoin(Accounts, { WineParticipantAssignment.participant }, { id })
				.slice(Accounts.id, Accounts.name, Accounts.code, QuestionnaireWines.name, QuestionnaireWines.code)
				.select { WineParticipantAssignment.questionnaire eq questionnaire.id }
				.orderBy(Accounts.name)
				.orderBy(Accounts.id)
				.orderBy(WineParticipantAssignment.order)) {

			val participantId = row[Accounts.id].value
			val participantEntry:ArrayList<Entry>
			if (entries.isEmpty() || lastParticipantId != participantId) {
				participantEntry = ArrayList()
				entries.add(participantEntry)
			} else {
				participantEntry = entries.last()
			}
			lastParticipantId = participantId

			participantEntry.add(Entry(row[Accounts.name], row[Accounts.code], row[Accounts.id].value, row[QuestionnaireWines.name], row[QuestionnaireWines.code]))
		}
	}

	if (entries.isEmpty()) {
		return
	}

	val rounds = entries.minBy { it.size }?.size ?: 0
	if (entries.any { it.size != rounds }) {
		LOG.error("Assignment matrix is jagged!!! ({})", entries)
	}

	if (rounds <= 0) {
		return
	}

	val colorMap = IntIntHashMap(rounds)

	h2 { +"Participant Wine Assignment" }

	table {
		thead {
			tr {
				th {+"#"}
				for (entry in entries) {
					th {
						span("at-code") {
							val panelistCode = entry[0].panelistCode
							if (panelistCode != null) {
								+panelistCode.toString()
							} else {
								+accountIdToGuestCode(entry[0].accountId)
							}
						}
						span("at-title") { +entry[0].participantName }
					}
				}
			}
		}
		tbody {
			for (round in 0 until rounds) {
				tr {
					th { +((round+1).toString()) }
					for (entry in entries) {
						val wineCode = entry[round].wineCode

						var bg = colorMap.getOrDefault(wineCode, -1)
						if (bg < 0) {
							bg = colorMap.size()
							colorMap.put(wineCode, bg)
						}

						td("bg bg-p$bg") {
							span("at-code") { +wineCode.toString() }
							span("at-title") { +entry[round].wineName }
						}
					}
				}
			}
		}
	}
}

/**
 * Buttons
 * - CREATED: Open (& has participants & wines) | Delete
 * - RUNNING: Close
 * - CLOSED: Reopen | Download Results | Delete
 */
private fun FlowContent.questionnaireActions(session: Session, locale:LocaleStack, questionnaire: Questionnaire) {
	// Button to open
	if (questionnaire.state == QuestionnaireState.CREATED) {
		val warning = if (transaction { QuestionnaireWines.select { QuestionnaireWines.questionnaire eq questionnaire.id }.empty() }) {
			"Do you really want to create a questionnaire without any wines?"
		} else null
		postButton(session, questionnaireEditPath(questionnaire.id), routeAction = ACTION_QUESTIONNAIRE_OPEN, confirmation = warning, classes="u-full-width", parentClasses="column") { +"Open the questionnaire" }
	}

	// Button to close
	if (questionnaire.state == QuestionnaireState.RUNNING) {
		postButton(session, questionnaireEditPath(questionnaire.id), routeAction = ACTION_QUESTIONNAIRE_CLOSE, classes="u-full-width", parentClasses="column") { +"Close the questionnaire" }
	}

	// Button to download results
	if (questionnaire.state == QuestionnaireState.CLOSED) {
		getButton(questionnaireResultsPath(questionnaire.id), classes="u-full-width", parentClasses="column") { +"Download results" }
	}

	// Button to delete
	if (questionnaire.state != QuestionnaireState.RUNNING) {
		postButton(session, HOME_PATH, PARAM_QUESTIONNAIRE_ID to questionnaire.id.toString(), routeAction= ACTION_QUESTIONNAIRE_DELETE, classes="dangerous u-full-width", parentClasses="column", confirmation="Do you really want to delete this questionnaire? Even results will be deleted!") {
			+"Delete questionnaire"
		}
	}
}

private class Questionnaire(val id:Long, val name:String, val state:QuestionnaireState, val templateId:Long, val templateName:String, val hasWines:Boolean)
private val QUESTIONNAIRE_KEY = AttachmentKey.create(Questionnaire::class.java)

private fun HttpServerExchange.dropQuestionnaire() {
	removeAttachment(QUESTIONNAIRE_KEY)
}
private fun HttpServerExchange.questionnaire():Questionnaire? {
	getAttachment(QUESTIONNAIRE_KEY)?.let { return it }

	val session = session()!!
	val questionnaireId = try {
		pathString(PATH_QUESTIONNAIRE_ID).toLong()
	} catch (e:NumberFormatException) {
		statusCode = StatusCodes.NOT_FOUND
		messageWarning("That questionnaire does not exist")
		home(session)
		return null
	}

	var questionnaire:Questionnaire? = null
	transaction {
		Questionnaires
				.leftJoin(QuestionnaireTemplates, { template }, { QuestionnaireTemplates.id })
				.select { Questionnaires.id eq questionnaireId }.limit(1).firstOrNull()?.let {
					questionnaire = Questionnaire(questionnaireId, it[Questionnaires.name], it[Questionnaires.state], it[Questionnaires.template], it[QuestionnaireTemplates.name], it[Questionnaires.hasWines])
				}
	}

	questionnaire?.let {
		putAttachment(QUESTIONNAIRE_KEY, it)
		return it
	}

	statusCode = StatusCodes.NOT_FOUND
	messageWarning("That questionnaire does not exist")
	home(session)
	return null
}

/** Page for editing questionnaires. */
private fun HttpServerExchange.showEditQuestionnairePage() {
	val questionnaire = questionnaire() ?: return
	val session = session()!!

	val locale = languages()

	sendBase { _, _ ->
		div("page-container") {
			div("page-section") {
				h1 {
					postForm(questionnaireEditPath(questionnaire.id)) {
						session(session)
						routeAction(ACTION_QUESTIONNAIRE_RENAME)
						textInput(name = PARAM_QUESTIONNAIRE_NAME) {
							required = true
							style = "height: unset; width: 100%;"
							value = questionnaire.name
						}
					}
				}
				p("sub") { +questionnaire.templateName }
			}

			renderMessages(this@showEditQuestionnairePage)

			// Participants
			div("page-section") {
				questionnaireParticipants(session, locale, questionnaire)
			}

			// Wines
			div("page-section") {
				questionnaireWines(session, locale, questionnaire)
			}

			// Wine-participant association
			div("page-section") {
				questionnaireWineParticipantAssociations(session, locale, questionnaire)
			}

			// Actions
			div("page-section container") {
				questionnaireActions(session, locale, questionnaire)
			}
		}
	}
}

private const val ACTION_INVITE = "invite"
private const val ACTION_UNINVITE = "uninvite"
private const val ACTION_WINE_UPDATE_CODE = "update-code"
private const val ACTION_REMOVE_WINE = "remove-wine"
private const val ACTION_ADD_WINE = "add-wine"
private const val ACTION_RENAME_WINE = "rename-wine"
private const val ACTION_QUESTIONNAIRE_RENAME = "questionnaire-rename"
private const val ACTION_QUESTIONNAIRE_OPEN = "questionnaire-open"
private const val ACTION_QUESTIONNAIRE_CLOSE = "questionnaire-close"

private const val PARAM_USER_ID = "user"
private const val PARAM_USERS = "users"
private const val PARAM_WINE_ID = "wine"
private const val PARAM_WINE_CODE = "wine-code"
private const val PARAM_WINE_NAME = "wine-name"
private const val PARAM_QUESTIONNAIRE_NAME = "questionnaire-name"

fun RoutingHandler.setupQuestionnaireEditRoutes() {
	GET(QUESTIONNAIRE_EDIT_PATH_TEMPLATE, AccountType.STAFF) { exchange ->
		exchange.showEditQuestionnairePage()
	}

	GET(QUESTIONNAIRE_RESULTS_PATH_TEMPLATE, AccountType.STAFF) { exchange ->
		val questionnaire = exchange.questionnaire() ?: return@GET
		if (questionnaire.state != QuestionnaireState.CLOSED) {
			exchange.messageWarning("Close the questionnaire before downloading the results")
			exchange.showEditQuestionnairePage()
			return@GET
		}

		val template = QuestionnaireTemplates.parsed(questionnaire.templateId) ?: run {
			exchange.messageWarning("Template has been deleted") // probably
			exchange.showEditQuestionnairePage()
			return@GET
		}
		val questionIds = template.questionIds
		val questionIdToIndex = questionIds.mapIndexed { index: Int, qId: String -> qId to index }.toMap()

		class WineParticipant(val wineId:Long?, val wineName:String, val participantId:Long, val participantCode:String)

		val responses = LinkedHashMap<WineParticipant, Array<String?>>()

		transaction {
			var lastParticipant:WineParticipant? = null
			var lastParticipantResponses:Array<String?> = arrayOfNulls(questionIds.size)

			for (row in QuestionnaireResponses
					.leftJoin(QuestionnaireWines, { wine }, { QuestionnaireWines.id })
					.leftJoin(Accounts, { QuestionnaireResponses.participant }, { Accounts.id })
					.select { QuestionnaireResponses.questionnaire eq questionnaire.id }
					.orderBy(QuestionnaireResponses.wine)
					.orderBy(QuestionnaireResponses.participant)) {

				val wineId = row[QuestionnaireResponses.wine]
				val wineName = row[QuestionnaireWines.name]

				val participantId = row[QuestionnaireResponses.participant]
				if (lastParticipant == null) {
					lastParticipant = WineParticipant(wineId, wineName, participantId, row[Accounts.code].toString())
					responses[lastParticipant] = lastParticipantResponses
				} else if (lastParticipant.wineId != wineId || lastParticipant.participantId != participantId) {
					lastParticipant = WineParticipant(wineId, wineName, participantId, row[Accounts.code].toString())
					lastParticipantResponses = arrayOfNulls(questionIds.size)
					responses[lastParticipant] = lastParticipantResponses
				}

				val responseKey = row[QuestionnaireResponses.questionId]
				val responseValue = row[QuestionnaireResponses.response]
				val responseKeyIndex = questionIdToIndex.getOrDefault(responseKey, -1)
				if (responseKeyIndex < 0) {
					LOG.warn("Got response with unrecognized ID '{}': \"{}\"", responseKey, responseValue)
				} else {
					lastParticipantResponses[responseKeyIndex] = responseValue
				}
			}
		}

		val writer = object : CharArrayWriter(1_000_000) {
			fun utf8Bytes(): ByteBuffer {
				return Charsets.UTF_8.encode(CharBuffer.wrap(buf, 0, count))
			}
		}

		val hasWines = questionnaire.hasWines
		CSVWriter(writer).use { csv ->
			// Header
			csv.item("participant")
			if (hasWines) {
				csv.item("wine")
			}
			for (questionId in questionIds) {
				csv.item(questionId)
			}
			csv.row()

			// Body
			for ((key, value) in responses) {
				csv.item(key.participantCode)
				if (hasWines) {
					csv.item(key.wineName)
				}
				for (s in value) {
					csv.item(s)
				}
				csv.row()
			}
		}

		exchange.statusCode = StatusCodes.OK
		exchange.responseHeaders.put(Headers.CONTENT_DISPOSITION, contentDispositionAttachment("results-${questionnaire.name}.csv"))
		exchange.responseSender.send(writer.utf8Bytes())
	}

	// Invite more
	POST(QUESTIONNAIRE_EDIT_PATH_TEMPLATE, AccountType.STAFF, ACTION_INVITE) { exchange ->
		val questionnaire = exchange.questionnaire() ?: return@POST
		if (questionnaire.state == QuestionnaireState.CLOSED) {
			exchange.messageWarning("Can't invite more people after the questionnaire has closed")
			exchange.redirect(questionnaireEditPath(questionnaire.id))
			return@POST
		}

		transaction {
			var rejectedAny = false
			val users = exchange.formString(PARAM_USERS)
					?.split(',')
					?.mapNotNull { userSpecificationRaw ->
						val userSpecification = userSpecificationRaw.trim()
						if ('@' in userSpecification) {
							val id = Accounts.slice(Accounts.id).select { Accounts.email eq userSpecification }.limit(1).firstOrNull()?.let{ it[Accounts.id].value }
							if (id == null) {
								exchange.messageWarning("No user with e-mail $userSpecification found")
								rejectedAny = true
							}
							id
						} else if (userSpecification.all { it in '0'..'9' }) {
							val userSpecifiedCode = userSpecification.toIntOrNull()
							val id = if (userSpecifiedCode != null) {
								Accounts.slice(Accounts.id).select { Accounts.code eq userSpecifiedCode }.limit(1).firstOrNull()?.let{ it[Accounts.id].value }
							} else {
								null
							}
							if (id == null) {
								exchange.messageWarning("No user with code $userSpecification found")
								rejectedAny = true
							}
							id
						} else if (userSpecification.startsWith(GUEST_CODE_PREFIX)) {
							val accountId = guestCodeToAccountId(userSpecification)
							if (accountId == null) {
								exchange.messageWarning("$userSpecification is not a valid guest code")
								rejectedAny = true
								return@mapNotNull null
							}
							if (Accounts.select { (Accounts.id eq accountId) and (Accounts.accountType eq AccountType.GUEST) }.empty()) {
								exchange.messageWarning("Guest $userSpecification does not exist")
								rejectedAny = true
								return@mapNotNull null
							}
							accountId
						} else {
							var id = 0L
							var count = 0
							for (row in Accounts.slice(Accounts.id).select { Accounts.name.lowerCase() eq userSpecification.toLowerCase() }) {
								id = row[Accounts.id].value
								count++
							}

							if (count == 0) {
								exchange.messageWarning("No user named $userSpecification found")
								rejectedAny = true
								null
							} else if (count == 1) {
								id
							} else {
								exchange.messageWarning("Name $userSpecification is ambiguous - $count candidates found, specify differently")
								rejectedAny = true
								null
							}
						}
					} ?: emptyList()
			if (users.isNotEmpty()) {
				var duplicates = 0
				var errors = 0
				val invited = ArrayList<Long>()

				for (userId in users) {
					try {
						QuestionnaireParticipants.insert {
							it[participant] = userId
							it[QuestionnaireParticipants.questionnaire] = questionnaire.id
						}
						invited.add(userId)
					} catch (e:SQLException) {
						if (e.type() == SQLErrorType.DUPLICATE_KEY) {
							duplicates++
						} else {
							LOG.error("Failed to invite {}", userId, e)
							errors++
						}
					}
				}

				if (invited.size > 0) {
					try {
						transaction {
							val wineIds = questionnaireWineIds(questionnaire.id)
							for (invitedUserId in invited) {
								regenerateParticipantWineAssignment(questionnaire.id, invitedUserId, providedWineIds = wineIds)
							}
						}
					} catch (e:SQLException) {
						LOG.error("Failed to generate wine assignments for new participants", e)
					}
				}

				if (duplicates == 1) {
					exchange.messageWarning("One participant already invited")
				} else if (duplicates > 1) {
					exchange.messageWarning("$duplicates participants already invited")
				}

				if (errors > 0) {
					exchange.messageWarning("Failed to invite $errors participant(s) due to an error")
				}

				if (invited.size == 1) {
					exchange.messageInfo("Participant invited")
				} else if (invited.size > 1) {
					exchange.messageInfo("${users.size} participants invited")
				}
			} else if (!rejectedAny) {
				exchange.messageWarning("No valid users specified")
			}
		}

		exchange.redirect(questionnaireEditPath(questionnaire.id))
	}

	// Uninvite some (and delete responses)
	POST(QUESTIONNAIRE_EDIT_PATH_TEMPLATE, AccountType.STAFF, ACTION_UNINVITE) { exchange ->
		val questionnaire = exchange.questionnaire() ?: return@POST
		// No need for state check, people can be removed any time

		val userId = exchange.formString(PARAM_USER_ID)?.toLongOrNull() ?: run {
			exchange.messageWarning("No such user")
			exchange.showEditQuestionnairePage()
			return@POST
		}

		val deleted = transaction {
			QuestionnaireParticipants.deleteWhere(limit=1) { (QuestionnaireParticipants.questionnaire eq questionnaire.id) and (QuestionnaireParticipants.participant eq userId) }
		}

		if (deleted == 0) {
			exchange.messageWarning("This user was not invited")
			exchange.showEditQuestionnairePage()
			return@POST
		}

		val deletedResponses = transaction {
			// Delete assignments
			deleteParticipantWineAssignment(questionnaire.id, userId)

			// Delete responses
			QuestionnaireResponses.deleteWhere { (QuestionnaireResponses.participant eq userId) and (QuestionnaireResponses.questionnaire eq questionnaire.id) }
		}

		if (deletedResponses == 0) {
			exchange.messageInfo("Successfully uninvited")
		} else if (deletedResponses == 1) {
			exchange.messageInfo("Successfully uninvited and one response deleted")
		} else {
			exchange.messageInfo("Successfully uninvited and $deletedResponses responses deleted")
		}

		exchange.redirect(questionnaireEditPath(questionnaire.id))
	}

	POST(QUESTIONNAIRE_EDIT_PATH_TEMPLATE, AccountType.STAFF, ACTION_WINE_UPDATE_CODE) { exchange ->
		val questionnaire = exchange.questionnaire() ?: return@POST
		if (questionnaire.state != QuestionnaireState.CREATED) {
			exchange.messageWarning("Can't change wine code after the questionnaire has been opened")
			exchange.showEditQuestionnairePage()
			return@POST
		}

		val wineId = exchange.formString(PARAM_WINE_ID)?.toLongOrNull()
		val newCode = exchange.formString(PARAM_WINE_CODE)?.toIntOrNull()

		if (wineId == null || newCode == null) {
			exchange.messageWarning("Invalid change")
			exchange.showEditQuestionnairePage()
			return@POST
		}

		val updated = transaction {
			QuestionnaireWines.update(where = { QuestionnaireWines.id eq wineId }, limit=1) { it[QuestionnaireWines.code] = newCode }
		}

		if (updated == 0) {
			exchange.messageWarning("That wine no longer exists")
		} else {
			exchange.messageInfo("Wine code changed")
		}
		exchange.redirect(questionnaireEditPath(questionnaire.id))
	}

	POST(QUESTIONNAIRE_EDIT_PATH_TEMPLATE, AccountType.STAFF, ACTION_REMOVE_WINE) { exchange ->
		val questionnaire = exchange.questionnaire() ?: return@POST
		if (questionnaire.state != QuestionnaireState.CREATED) {
			exchange.messageWarning("Can't delete wine after the questionnaire has been opened")
			exchange.redirect(questionnaireEditPath(questionnaire.id))
			return@POST
		}

		val wineId = exchange.formString(PARAM_WINE_ID)?.toLongOrNull()

		val removed = if (wineId != null) {
			transaction {
				QuestionnaireWines.deleteWhere(limit=1) { QuestionnaireWines.id eq wineId }
			}
		} else 0

		if (removed == 0) {
			exchange.messageWarning("That wine no longer exists")
		} else {
			transaction {
				regenerateWineAssignments(questionnaire.id)
			}
			exchange.messageInfo("Wine removed")
		}
		exchange.redirect(questionnaireEditPath(questionnaire.id))
	}

	POST(QUESTIONNAIRE_EDIT_PATH_TEMPLATE, AccountType.STAFF, ACTION_RENAME_WINE) { exchange ->
		val questionnaire = exchange.questionnaire() ?: return@POST
		// Wine renaming is allowed always

		val wineName = exchange.formString(PARAM_WINE_NAME) ?: ""
		if (wineName.isBlank()) {
			exchange.messageWarning("Wine name can't be blank")
			exchange.redirect(questionnaireEditPath(questionnaire.id))
			return@POST
		}

		val wineId = exchange.formString(PARAM_WINE_ID)?.toLongOrNull()

		val updated = if (wineId != null) {
			transaction {
				QuestionnaireWines.update(where = { QuestionnaireWines.id eq wineId }, limit = 1) { it[name] = wineName }
			}
		} else 0

		if (updated == 0) {
			exchange.messageWarning("That wine no longer exists")
		} else {
			exchange.messageInfo("Wine name changed")
		}
		exchange.redirect(questionnaireEditPath(questionnaire.id))
	}

	POST(QUESTIONNAIRE_EDIT_PATH_TEMPLATE, AccountType.STAFF, ACTION_ADD_WINE) { exchange ->
		val questionnaire = exchange.questionnaire() ?: return@POST
		if (questionnaire.state != QuestionnaireState.CREATED) {
			exchange.messageWarning("Can't add new wines after the questionnaire was opened")
			exchange.redirect(questionnaireEditPath(questionnaire.id))
			return@POST
		}

		val wineName = exchange.formString(PARAM_WINE_NAME) ?: ""
		if (wineName.isBlank()) {
			exchange.messageWarning("Specify wine name first")
			exchange.redirect(questionnaireEditPath(questionnaire.id))
			return@POST
		}

		transaction {
			val code = exchange.formString(PARAM_WINE_CODE)?.toIntOrNull() ?: QuestionnaireWines.findUniqueCode(questionnaire.id)

			QuestionnaireWines.insert {
				it[name] = wineName
				it[QuestionnaireWines.questionnaire] = questionnaire.id
				it[QuestionnaireWines.code] = code
			}

			regenerateWineAssignments(questionnaire.id)
		}

		exchange.messageInfo("Wine added")
		exchange.redirect(questionnaireEditPath(questionnaire.id))
	}

	POST(QUESTIONNAIRE_EDIT_PATH_TEMPLATE, AccountType.STAFF, ACTION_QUESTIONNAIRE_RENAME) { exchange ->
		val questionnaire = exchange.questionnaire() ?: return@POST
		val newName = exchange.formString(PARAM_QUESTIONNAIRE_NAME)?.trim()?.takeUnless { it.isBlank() }

		if (newName != null) {
			transaction {
				Questionnaires.update(
						where = { (Questionnaires.id eq questionnaire.id) },
						limit = 1) { it[Questionnaires.name] = newName }
			}
			exchange.dropQuestionnaire()
		} else {
			exchange.messageWarning("Name of a questionnaire can't be blank")
		}

		exchange.redirect(questionnaireEditPath(questionnaire.id))
	}

	POST(QUESTIONNAIRE_EDIT_PATH_TEMPLATE, AccountType.STAFF, ACTION_QUESTIONNAIRE_OPEN) { exchange ->
		val questionnaire = exchange.questionnaire() ?: return@POST
		if (questionnaire.state != QuestionnaireState.CREATED) {
			exchange.messageWarning("Can't open this questionnaire - already opened")
			exchange.redirect(questionnaireEditPath(questionnaire.id))
			return@POST
		}

		val updated = transaction {
			val wineCodes = QuestionnaireWines
					.slice(QuestionnaireWines.code)
					.select { (QuestionnaireWines.questionnaire eq questionnaire.id) }
					.map { it[QuestionnaireWines.code] }
			val hasNoWines = wineCodes.isEmpty()

			if (hasNoWines) {
				// This is a wine-less questionnaire, generate null wine and hide it
				// This is required because the whole DB schema relies on foreign key guarantees and the wine also appears in PK.
				QuestionnaireWines.insert {
					it[QuestionnaireWines.name] = "none"
					it[QuestionnaireWines.questionnaire] = questionnaire.id
					it[QuestionnaireWines.code] = 0
				}

				// Regenerate assignments
				regenerateWineAssignments(questionnaire.id)
			} else {
				// Check for code duplicates
				val codeSet = IntHashSet(wineCodes.size)
				val codeDupes = IntHashSet()
				for (wineCode in wineCodes) {
					if (!codeSet.add(wineCode)) {
						if (codeDupes.isEmpty) {
							exchange.messageWarning("Can't open - wine codes must be unique")
						}
						if (codeDupes.add(wineCode)) {
							exchange.messageWarning("Duplicated wine code: $wineCode")
						}
					}
				}

				if (!codeDupes.isEmpty) {
					return@transaction -1
				}
			}

			Questionnaires.update(
					where={ (Questionnaires.id eq questionnaire.id) and (Questionnaires.state eq QuestionnaireState.CREATED) },
					limit=1) {
				it[state] = QuestionnaireState.RUNNING
				if (hasNoWines) {
					it[Questionnaires.hasWines] = false
				}
			}
		}
		if (updated == 1) {
			exchange.messageInfo("Questionnaire opened")
		}
		if (updated >= 0) {
			exchange.dropQuestionnaire()
		}
		exchange.redirect(questionnaireEditPath(questionnaire.id))
	}

	POST(QUESTIONNAIRE_EDIT_PATH_TEMPLATE, AccountType.STAFF, ACTION_QUESTIONNAIRE_CLOSE) { exchange ->
		val questionnaire = exchange.questionnaire() ?: return@POST
		val updated = transaction {
			Questionnaires.update(
					where={ (Questionnaires.id eq questionnaire.id) and (Questionnaires.state eq QuestionnaireState.RUNNING) },
					limit=1) { it[state] = QuestionnaireState.CLOSED }
		}
		if (updated == 1) {
			exchange.messageInfo("Questionnaire closed")
		}
		exchange.dropQuestionnaire()
		exchange.redirect(questionnaireEditPath(questionnaire.id))
	}
}

private fun questionnaireWineIds(questionnaireId:Long):List<Long> {
	return try {
		QuestionnaireWines.slice(QuestionnaireWines.id)
				.select { QuestionnaireWines.questionnaire eq questionnaireId }
				.map { it[QuestionnaireWines.id].value }
	} catch (e: SQLException) {
		LOG.error("questionnaireWineIds({}) failed", e)
		emptyList()
	}
}

/** Call in a transaction. */
private fun regenerateWineAssignments(questionnaireId:Long) {
	try {
		// Delete all assignments
		WineParticipantAssignment.deleteWhere {
			WineParticipantAssignment.questionnaire eq questionnaireId
		}
	} catch (e: SQLException) {
		LOG.error("regenerateWineAssignments delete all assignments failed", e)
	}

	val wines = questionnaireWineIds(questionnaireId)
	if (wines.isEmpty()) {
		return
	}

	try {
		for (row in QuestionnaireParticipants.slice(QuestionnaireParticipants.participant).select { QuestionnaireParticipants.questionnaire eq questionnaireId }) {
			regenerateParticipantWineAssignment(questionnaireId, row[QuestionnaireParticipants.participant])
		}
	} catch (e: SQLException) {
		LOG.error("regenerateWineAssignments generating assignments failed", e)
	}
}

/** Call in a transaction. */
private fun deleteParticipantWineAssignment(questionnaireId:Long, participantAccountId:Long) {
	try {
		WineParticipantAssignment.deleteWhere {
			(WineParticipantAssignment.questionnaire eq questionnaireId) and
					(WineParticipantAssignment.participant eq participantAccountId)
		}
	} catch (e: SQLException) {
		LOG.error("deleteParticipantWineAssignment failed", e)
	}
}

/** Call in a transaction. */
private fun regenerateParticipantWineAssignment(questionnaireId:Long, participantAccountId:Long, providedWineIds:List<Long>? = null) {
	val wineIds = providedWineIds ?: questionnaireWineIds(questionnaireId)
	if (wineIds.isEmpty()) {
		return
	}

	// Shuffle wines
	val shuffledWines = wineIds.shuffled()

	try {
		WineParticipantAssignment.batchInsert(shuffledWines.withIndex()) { (index, wineId) ->
			this[WineParticipantAssignment.questionnaire] = questionnaireId
			this[WineParticipantAssignment.participant] = participantAccountId
			this[WineParticipantAssignment.wine] = wineId
			this[WineParticipantAssignment.order] = index
		}
	} catch (e: SQLException) {
		LOG.error("regenerateParticipantWineAssignment inserting assignments failed", e)
	}
}
