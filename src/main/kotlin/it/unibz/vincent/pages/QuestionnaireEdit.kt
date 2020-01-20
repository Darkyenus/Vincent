package it.unibz.vincent.pages

import io.undertow.server.HttpServerExchange
import io.undertow.server.RoutingHandler
import io.undertow.util.AttachmentKey
import io.undertow.util.Headers
import io.undertow.util.StatusCodes
import it.unibz.vincent.AccountType
import it.unibz.vincent.Accounts
import it.unibz.vincent.QuestionnaireParticipants
import it.unibz.vincent.QuestionnaireResponses
import it.unibz.vincent.QuestionnaireState
import it.unibz.vincent.QuestionnaireTemplates
import it.unibz.vincent.QuestionnaireWines
import it.unibz.vincent.Questionnaires
import it.unibz.vincent.Session
import it.unibz.vincent.WineParticipantAssignment
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
import it.unibz.vincent.util.type
import kotlinx.html.FlowContent
import kotlinx.html.colTh
import kotlinx.html.div
import kotlinx.html.h1
import kotlinx.html.h2
import kotlinx.html.hiddenInput
import kotlinx.html.label
import kotlinx.html.numberInput
import kotlinx.html.p
import kotlinx.html.postForm
import kotlinx.html.rowTh
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
import org.jetbrains.exposed.sql.Column
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
import kotlin.random.Random

private val LOG = LoggerFactory.getLogger("QuestionnaireEdit")

private const val PATH_QUESTIONNAIRE_ID = "qId"

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
				th { +"Name" }
				th { +"E-mail" }
				th { +"Code" }
				th { +"State" }
				// if editable: Kick
			}
		}

		tbody {
			transaction {
				for (row in QuestionnaireParticipants
						.leftJoin(Accounts, { participant }, { id })
						.slice(Accounts.id, Accounts.name, Accounts.email, Accounts.code, QuestionnaireParticipants.state)
						.select { QuestionnaireParticipants.questionnaire eq questionnaire.id }
						.orderBy(Accounts.name)) {
					empty = false
					tr {
						td { +row[Accounts.name] }
						td { +row[Accounts.email] }
						td { +"%04d".format(row[Accounts.code]) }
						td { +row[QuestionnaireParticipants.state].toString() /* TODO Localize */ }

						td {
							postButton(session, "/questionnaire/${questionnaire.id}/edit",
									PARAM_USER_ID to row[Accounts.id].toString(),
									routeAction= ACTION_UNINVITE, confirmation="Are you sure? This will delete all responses from this participant as well!") {
								+"Uninvite"
							}
						}
					}
				}
			}
		}
	}

	if (empty) {
		div("u-full-width unimportant") {
			style="min-height: 200px; background: #DDD"
			+"Add participants"
		}
	}

	if (questionnaire.state != QuestionnaireState.CLOSED) {
		postForm("/questionnaire/${questionnaire.id}/edit") {
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
	h2 { +"Wines" }

	var empty = true

	table {
		thead {
			tr {
				th { +"Name" }
				th { +"Code 1" }
				th { +"Code 2" }
				// if editable: Remove
			}
		}

		tbody {
			transaction {
				for (row in QuestionnaireWines
						.select { QuestionnaireWines.questionnaire eq questionnaire.id }
						.orderBy(QuestionnaireWines.name)) {
					empty = false
					val wineId = row[QuestionnaireWines.id].value
					val wineName = row[QuestionnaireWines.name]
					val wineCode1 = row[QuestionnaireWines.code1]
					val wineCode2 = row[QuestionnaireWines.code2]

					tr {
						td {
							+wineName
							postForm("/questionnaire/${questionnaire.id}/edit") {
								session(session)
								routeAction(ACTION_RENAME_WINE)
								hiddenInput(name=PARAM_WINE_ID) { value=wineId.toString() }
								textInput(name=PARAM_WINE_NAME) { required=true; value=wineName }
							}
						}
						td {
							postForm("/questionnaire/${questionnaire.id}/edit") {
								session(session)
								routeAction(ACTION_WINE_UPDATE_CODE_1)
								hiddenInput(name=PARAM_WINE_ID) { value=wineId.toString() }
								numberInput(name=PARAM_WINE_CODE) { value=wineCode1.toString() }
							}
						}
						td {
							postForm("/questionnaire/${questionnaire.id}/edit") {
								session(session)
								routeAction(ACTION_WINE_UPDATE_CODE_2)
								hiddenInput(name=PARAM_WINE_ID) { value=wineId.toString() }
								numberInput(name=PARAM_WINE_CODE) { value=wineCode2.toString() }
							}
						}
						if (questionnaire.state != QuestionnaireState.RUNNING) {
							td {
								postButton(session, "/questionnaire/${questionnaire.id}/edit", PARAM_WINE_ID to wineId.toString(), routeAction=ACTION_REMOVE_WINE, classes="dangerous") {
									icon(Icons.TRASH)
								}
							}
						}
					}
				}
			}
		}
	}

	if (empty) {
		div("u-full-width unimportant") {
			style="min-height: 200px; background: #DDD"
			+"Add wines"
		}
	}

	if (questionnaire.state == QuestionnaireState.CREATED) {
		postForm("/questionnaire/${questionnaire.id}/edit") {
			session(session)
			routeAction(ACTION_ADD_WINE)
			label {
				+"Wine name"
				textInput(name = PARAM_WINE_NAME) { required = true; placeholder = "Chardonnay" }
			}
			label {
				+"Code 1"
				numberInput(name = PARAM_WINE_CODE_1) { required = false; placeholder = "Random" }
			}
			label {
				+"Code 2"
				numberInput(name = PARAM_WINE_CODE_2) { required = false; placeholder = "Random" }
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
	data class Entry(val participantName:String, val participantCode:Int, val wineName:String, val wineCode:Int)

	val entries = ArrayList<ArrayList<Entry>>()

	// Collect data
	transaction {
		var lastParticipantId = -1L

		for (row in WineParticipantAssignment
				.leftJoin(QuestionnaireWines, { wine }, { id })
				.leftJoin(Accounts, { WineParticipantAssignment.participant }, { id })
				.slice(Accounts.id, Accounts.name, Accounts.code, QuestionnaireWines.name, QuestionnaireWines.code1, QuestionnaireWines.code2)
				.select { WineParticipantAssignment.questionnaire eq questionnaire.id }
				.orderBy(Accounts.name)
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

			val code1 = row[QuestionnaireWines.code1]
			val code2 = row[QuestionnaireWines.code2]
			val code = if (participantEntry.any { it.wineCode == code1 }) {
				if (participantEntry.any { it.wineCode == code2 }) {
					LOG.warn("Duplicate code2 in wine assignment matrix")
				}
				code2
			} else code1
			participantEntry.add(Entry(row[Accounts.name], row[Accounts.code], row[QuestionnaireWines.name], code))
		}
	}

	h2 { +"Participant Wine Assignment" }

	if (entries.isEmpty()) {
		div("u-full-width unimportant") {
			style="min-height: 200px; background: #DDD"
			+"Add participants"
		}
		return
	}

	val rounds = entries.minBy { it.size }?.size ?: 0
	if (entries.any { it.size != rounds }) {
		LOG.error("Assignment matrix is jagged!!! ({})", entries)
	}

	if (rounds <= 0) {
		div("u-full-width unimportant") {
			style="min-height: 200px; background: #DDD"
			+"Add wines"
		}
		return
	}


	table {
		thead {
			tr {
				colTh {+"#"}
				for (entry in entries) {
					colTh {
						span("at-code") { +entry[0].participantCode.toString() }
						span("at-title") { +entry[0].participantName }
					}
				}
			}
		}
		tbody {
			for (round in 0 until rounds) {
				tr {
					rowTh { +((round+1).toString()) }
					for (entry in entries) {
						td {
							span("at-code") { +entry[round].wineCode.toString() }
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
	if (questionnaire.state == QuestionnaireState.CREATED && transaction { questionnaireHasEnoughWineToOpen(questionnaire.id) }) {
		postButton(session, "/questionnaire/{$PATH_QUESTIONNAIRE_ID}/edit", routeAction = ACTION_QUESTIONNAIRE_OPEN) { +"Open the questionnaire" }
	}

	// Button to close
	if (questionnaire.state == QuestionnaireState.RUNNING) {
		postButton(session, "/questionnaire/{$PATH_QUESTIONNAIRE_ID}/edit", routeAction = ACTION_QUESTIONNAIRE_CLOSE) { +"Close the questionnaire" }
	}

	// Button to download results
	if (questionnaire.state == QuestionnaireState.CLOSED) {
		getButton("/questionnaire/{$PATH_QUESTIONNAIRE_ID}/results") { +"Download results" }
	}

	// Button to delete
	if (questionnaire.state != QuestionnaireState.RUNNING) {
		postButton(session, "/", PARAM_QUESTIONNAIRE_ID to questionnaire.id.toString(), routeAction= ACTION_QUESTIONNAIRE_DELETE, classes="dangerous", confirmation="Do you really want to delete this questionnaire? Even results will be deleted!") {
			+"Delete questionnaire"
		}
	}
}

private class Questionnaire(val id:Long, val name:String, val state:QuestionnaireState, val templateId:Long, val templateName:String)
private val QUESTIONNAIRE_KEY = AttachmentKey.create(Questionnaire::class.java)

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
					questionnaire = Questionnaire(questionnaireId, it[Questionnaires.name], it[Questionnaires.state], it[Questionnaires.template], it[QuestionnaireTemplates.name])
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
private fun HttpServerExchange.editQuestionnairePage() {
	val questionnaire = questionnaire() ?: return
	val session = session()!!

	val locale = languages()

	sendBase { _, _ ->
		div("container") {

			h1 { +questionnaire.name }
			p("sub") { +questionnaire.templateName }

			renderMessages(this@editQuestionnairePage)

			// Participants
			div("row") {
				questionnaireParticipants(session, locale, questionnaire)
			}

			// Wines
			div("row") {
				questionnaireWines(session, locale, questionnaire)
			}

			// Wine-participant association
			div("row") {
				questionnaireWineParticipantAssociations(session, locale, questionnaire)
			}

			// Actions
			div("row") {
				questionnaireActions(session, locale, questionnaire)
			}
		}
	}
}

private const val ACTION_INVITE = "invite"
private const val ACTION_UNINVITE = "uninvite"
private const val ACTION_WINE_UPDATE_CODE_1 = "update-code-1"
private const val ACTION_WINE_UPDATE_CODE_2 = "update-code-2"
private const val ACTION_REMOVE_WINE = "remove-wine"
private const val ACTION_ADD_WINE = "remove-wine"
private const val ACTION_RENAME_WINE = "rename-wine"
private const val ACTION_QUESTIONNAIRE_OPEN = "questionnaire-open"
private const val ACTION_QUESTIONNAIRE_CLOSE = "questionnaire-close"

private const val PARAM_USER_ID = "user"
private const val PARAM_USERS = "users"
private const val PARAM_WINE_ID = "wine"
private const val PARAM_WINE_CODE = "wine-code"
private const val PARAM_WINE_NAME = "wine-name"
private const val PARAM_WINE_CODE_1 = "wine-code-1"
private const val PARAM_WINE_CODE_2 = "wine-code-2"

fun RoutingHandler.setupQuestionnaireEditRoutes() {
	GET("/questionnaire/{$PATH_QUESTIONNAIRE_ID}/edit", AccountType.STAFF) { exchange ->
		exchange.editQuestionnairePage()
	}

	GET("/questionnaire/{$PATH_QUESTIONNAIRE_ID}/results", AccountType.STAFF) { exchange ->
		val questionnaire = exchange.questionnaire() ?: return@GET
		if (questionnaire.state != QuestionnaireState.CLOSED) {
			exchange.messageWarning("Close the questionnaire before downloading the results")
			exchange.editQuestionnairePage()
			return@GET
		}

		val template = QuestionnaireTemplates.parsed(questionnaire.templateId) ?: run {
			exchange.messageWarning("Template has been deleted") // probably
			exchange.editQuestionnairePage()
			return@GET
		}
		val questionIds = template.collectQuestionIds()
		val questionIdToIndex = questionIds.mapIndexed { index: Int, qId: String -> qId to index }.toMap()

		class WineParticipant(val wineId:Long, val wineName:String, val participantId:Long, val participantCode:String)

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
				val participantId = row[QuestionnaireResponses.participant]
				if (lastParticipant == null) {
					lastParticipant = WineParticipant(wineId, row[QuestionnaireWines.name], participantId, row[Accounts.code].toString())
					responses[lastParticipant] = lastParticipantResponses
				} else if (lastParticipant.wineId != wineId || lastParticipant.participantId != participantId) {
					lastParticipant = WineParticipant(wineId, row[QuestionnaireWines.name], participantId, row[Accounts.code].toString())
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
		CSVWriter(writer).use { csv ->
			// Header
			csv.item("participant")
			csv.item("wine")
			for (questionId in questionIds) {
				csv.item(questionId)
				csv.row()
			}

			// Body
			for ((key, value) in responses) {
				csv.item(key.participantCode)
				csv.item(key.wineName)
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
	POST("/questionnaire/{$PATH_QUESTIONNAIRE_ID}/edit", AccountType.STAFF, ACTION_INVITE) { exchange ->
		val questionnaire = exchange.questionnaire() ?: return@POST
		if (questionnaire.state == QuestionnaireState.CLOSED) {
			exchange.messageWarning("Can't invite more people after the questionnaire has closed")
			exchange.editQuestionnairePage()
			return@POST
		}

		transaction {
			val users = exchange.formString(PARAM_USERS)
					?.split(',')
					?.mapNotNull { userSpecificationRaw ->
						val userSpecification = userSpecificationRaw.trim()
						if ('@' in userSpecification) {
							val id = Accounts.slice(Accounts.id).select { Accounts.email eq userSpecification }.limit(1).firstOrNull()?.let{ it[Accounts.id].value }
							if (id == null) {
								exchange.messageWarning("No user with e-mail $userSpecification found")
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
							}
							id
						} else {
							var id = 0L
							var count = 0
							for (row in Accounts.slice(Accounts.id).select { Accounts.name.lowerCase() eq userSpecification.toLowerCase() }) {
								id = row[Accounts.id].value
								count++
							}

							if (count == 0) {
								exchange.messageWarning("No user named $userSpecification found")
								null
							} else if (count == 1) {
								id
							} else {
								exchange.messageWarning("Name $userSpecification is ambiguous - $count candidates found, specify differently")
								null
							}
						}
					} ?: emptyList()
			if (users.isEmpty()) {
				exchange.messageWarning("No valid users specified")
			} else {
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
			}
		}

		exchange.editQuestionnairePage()
	}

	// Uninvite some (and delete responses)
	POST("/questionnaire/{$PATH_QUESTIONNAIRE_ID}/edit", AccountType.STAFF, ACTION_UNINVITE) { exchange ->
		val questionnaire = exchange.questionnaire() ?: return@POST
		// No need for state check, people can be removed any time

		val userId = exchange.formString(PARAM_USER_ID)?.toLongOrNull() ?: run {
			exchange.messageWarning("No such user")
			exchange.editQuestionnairePage()
			return@POST
		}

		val deleted = transaction {
			QuestionnaireParticipants.deleteWhere(limit=1) { (QuestionnaireParticipants.questionnaire eq questionnaire.id) and (QuestionnaireParticipants.participant eq userId) }
		}

		if (deleted == 0) {
			exchange.messageWarning("This user was not invited")
			exchange.editQuestionnairePage()
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
		exchange.editQuestionnairePage()
	}

	fun updateWineCode(exchange:HttpServerExchange, column: Column<Int>) {
		val questionnaire = exchange.questionnaire() ?: return
		if (questionnaire.state == QuestionnaireState.RUNNING) {
			exchange.messageWarning("Can't change wine code while the questionnaire is open")
			exchange.editQuestionnairePage()
			return
		}

		val wineId = exchange.formString(PARAM_WINE_ID)?.toLongOrNull()
		val newCode = exchange.formString(PARAM_WINE_CODE)?.toIntOrNull()

		if (wineId == null || newCode == null) {
			exchange.messageWarning("Invalid change")
			exchange.editQuestionnairePage()
			return
		}

		val updated = transaction {
			QuestionnaireWines.update(where = { QuestionnaireWines.id eq wineId }, limit=1) { it[column] = newCode }
		}

		if (updated == 0) {
			exchange.messageWarning("That wine no longer exists")
		} else {
			exchange.messageInfo("Wine code changed")
		}
		exchange.editQuestionnairePage()
	}

	POST("/questionnaire/{$PATH_QUESTIONNAIRE_ID}/edit", AccountType.STAFF, ACTION_WINE_UPDATE_CODE_1) { exchange ->
		updateWineCode(exchange, QuestionnaireWines.code1)
	}

	POST("/questionnaire/{$PATH_QUESTIONNAIRE_ID}/edit", AccountType.STAFF, ACTION_WINE_UPDATE_CODE_2) { exchange ->
		updateWineCode(exchange, QuestionnaireWines.code2)
	}

	POST("/questionnaire/{$PATH_QUESTIONNAIRE_ID}/edit", AccountType.STAFF, ACTION_REMOVE_WINE) { exchange ->
		val questionnaire = exchange.questionnaire() ?: return@POST
		if (questionnaire.state == QuestionnaireState.RUNNING) {
			exchange.messageWarning("Can't delete wine while the questionnaire is open")
			exchange.editQuestionnairePage()
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
		exchange.editQuestionnairePage()
	}

	POST("/questionnaire/{$PATH_QUESTIONNAIRE_ID}/edit", AccountType.STAFF, ACTION_RENAME_WINE) { exchange ->
		exchange.questionnaire() ?: return@POST
		// Wine renaming is allowed always

		val wineName = exchange.formString(PARAM_WINE_NAME) ?: ""
		if (wineName.isBlank()) {
			exchange.messageWarning("Wine name can't be blank")
			exchange.editQuestionnairePage()
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
		exchange.editQuestionnairePage()
	}

	POST("/questionnaire/{$PATH_QUESTIONNAIRE_ID}/edit", AccountType.STAFF, ACTION_ADD_WINE) { exchange ->
		val questionnaire = exchange.questionnaire() ?: return@POST
		if (questionnaire.state != QuestionnaireState.CREATED) {
			exchange.messageWarning("Can't add new wines after the questionnaire was opened")
			exchange.editQuestionnairePage()
			return@POST
		}

		val wineName = exchange.formString(PARAM_WINE_NAME) ?: ""
		if (wineName.isBlank()) {
			exchange.messageWarning("Specify wine name first")
			exchange.editQuestionnairePage()
			return@POST
		}

		transaction {
			val code1 = exchange.formString(PARAM_WINE_CODE_1)?.toIntOrNull() ?: QuestionnaireWines.findUniqueCode(questionnaire.id)
			val code2 = exchange.formString(PARAM_WINE_CODE_2)?.toIntOrNull() ?: QuestionnaireWines.findUniqueCode(questionnaire.id, code1)

			QuestionnaireWines.insert {
				it[name] = wineName
				it[QuestionnaireWines.questionnaire] = questionnaire.id
				it[QuestionnaireWines.code1] = code1
				it[QuestionnaireWines.code2] = code2
			}

			regenerateWineAssignments(questionnaire.id)
		}

		exchange.messageInfo("Wine added")
		exchange.editQuestionnairePage()
	}

	POST("/questionnaire/{$PATH_QUESTIONNAIRE_ID}/edit", AccountType.STAFF, ACTION_QUESTIONNAIRE_OPEN) { exchange ->
		val questionnaire = exchange.questionnaire() ?: return@POST
		if (questionnaire.state != QuestionnaireState.CREATED) {
			exchange.messageWarning("Can't open this questionnaire")
			exchange.editQuestionnairePage()
			return@POST
		}

		if (!transaction { questionnaireHasEnoughWineToOpen(questionnaire.id) }) {
			exchange.messageWarning("Can't open - add more wines")
			exchange.editQuestionnairePage()
			return@POST
		}

		val updated = transaction {
			Questionnaires.update(
					where={ (Questionnaires.id eq questionnaire.id) and (Questionnaires.state eq QuestionnaireState.CREATED) },
					limit=1) { it[state] = QuestionnaireState.RUNNING }
		}
		if (updated == 1) {
			exchange.messageInfo("Questionnaire opened")
		}
		exchange.editQuestionnairePage()
	}

	POST("/questionnaire/{$PATH_QUESTIONNAIRE_ID}/edit", AccountType.STAFF, ACTION_QUESTIONNAIRE_CLOSE) { exchange ->
		val questionnaire = exchange.questionnaire() ?: return@POST
		val updated = transaction {
			Questionnaires.update(
					where={ (Questionnaires.id eq questionnaire.id) and (Questionnaires.state eq QuestionnaireState.RUNNING) },
					limit=1) { it[state] = QuestionnaireState.CLOSED }
		}
		if (updated == 1) {
			exchange.messageInfo("Questionnaire opened")
		}
		exchange.editQuestionnairePage()
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

	val random = Random.Default
	val shuffledWineIds = ArrayList(wineIds)
	shuffledWineIds.shuffle(random)
	val repeatId = wineIds.random(random)
	val repeatPosition = random.nextInt(wineIds.size + 1)
	shuffledWineIds.add(repeatPosition, repeatId)

	try {
		WineParticipantAssignment.batchInsert(shuffledWineIds.withIndex()) { wine ->
			this[WineParticipantAssignment.questionnaire] = questionnaireId
			this[WineParticipantAssignment.participant] = participantAccountId
			this[WineParticipantAssignment.wine] = wine.value
			this[WineParticipantAssignment.order] = wine.index
		}
	} catch (e: SQLException) {
		LOG.error("regenerateParticipantWineAssignment inserting assignments failed", e)
	}
}

/** Call in a transaction. */
private fun questionnaireHasEnoughWineToOpen(questionnaireId:Long):Boolean {
	return !QuestionnaireWines.select { QuestionnaireWines.questionnaire eq questionnaireId }.empty()
}