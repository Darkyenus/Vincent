package it.unibz.vincent.pages

import io.undertow.server.HttpServerExchange
import io.undertow.server.RoutingHandler
import io.undertow.util.AttachmentKey
import io.undertow.util.StatusCodes
import it.unibz.vincent.AccountType
import it.unibz.vincent.Accounts
import it.unibz.vincent.QuestionnaireParticipants
import it.unibz.vincent.QuestionnaireResponses
import it.unibz.vincent.QuestionnaireState
import it.unibz.vincent.QuestionnaireTemplates
import it.unibz.vincent.Questionnaires
import it.unibz.vincent.Session
import it.unibz.vincent.session
import it.unibz.vincent.util.GET
import it.unibz.vincent.util.LocaleStack
import it.unibz.vincent.util.POST
import it.unibz.vincent.util.SQLErrorType
import it.unibz.vincent.util.formString
import it.unibz.vincent.util.languages
import it.unibz.vincent.util.pathString
import it.unibz.vincent.util.type
import kotlinx.html.FlowContent
import kotlinx.html.div
import kotlinx.html.h1
import kotlinx.html.h2
import kotlinx.html.p
import kotlinx.html.postForm
import kotlinx.html.submitInput
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.textInput
import kotlinx.html.th
import kotlinx.html.thead
import kotlinx.html.tr
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.leftJoin
import org.jetbrains.exposed.sql.lowerCase
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory

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
						.select { QuestionnaireParticipants.questionnaire eq questionnaire.id }) {
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

	postForm("/questionnaire/${questionnaire.id}/edit") {
		session(session)
		routeAction(ACTION_INVITE)
		textInput(name = PARAM_USERS) { required = true; placeholder = "ex@mp.le, 123, ..." }
		submitInput { value = "Invite" }
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
private fun FlowContent.questionnaireWines(session: Session, locale: LocaleStack, questionnaireId: Questionnaire) {

//TODO
}

/**
 * Matrix of:
 * - Left/row: Order (1 to wine count + 1)
 * - Up/column: Participant
 * - Content: Code of the wine + Name of the wine
 * Actions:
 * - Move up/down (?)
 */
private fun FlowContent.questionnaireWineParticipantAssociations(session: Session, locale: LocaleStack, questionnaireId: Questionnaire) {
//TODO
}

/**
 * Buttons
 * - CREATED: Open (& has participants & wines) | Delete
 * - RUNNING: Close
 * - CLOSED: Reopen | Download Results | Delete
 */
private fun FlowContent.questionnaireActions(session: Session, locale:LocaleStack, questionnaireId: Questionnaire) {
//TODO
}

private class Questionnaire(val id:Long, val name:String, val state:QuestionnaireState, val templateName:String)
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
					questionnaire = Questionnaire(questionnaireId, it[Questionnaires.name], it[Questionnaires.state], it[QuestionnaireTemplates.name])
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

private const val PARAM_USER_ID = "user"
private const val PARAM_USERS = "users"

fun RoutingHandler.setupQuestionnaireEditRoutes() {
	GET("/questionnaire/{$PATH_QUESTIONNAIRE_ID}/edit", AccountType.STAFF) { exchange ->
		exchange.editQuestionnairePage()
	}

	// Invite more
	POST("/questionnaire/{$PATH_QUESTIONNAIRE_ID}/edit", AccountType.STAFF, ACTION_INVITE) { exchange ->
		val questionnaire = exchange.questionnaire() ?: return@POST
		// No need for state check, people can be added any time
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
				var invited = 0
				for (userId in users) {
					try {
						QuestionnaireParticipants.insert {
							it[participant] = userId
							it[QuestionnaireParticipants.questionnaire] = questionnaire.id
						}
						invited++
					} catch (e:ExposedSQLException) {
						if (e.type() == SQLErrorType.DUPLICATE_KEY) {
							duplicates++
						} else {
							LOG.error("Failed to invite {}", userId, e)
							errors++
						}
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

				if (invited == 1) {
					exchange.messageInfo("Participant invited")
				} else if (invited > 1) {
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
}