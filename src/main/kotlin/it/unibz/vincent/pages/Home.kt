package it.unibz.vincent.pages

import com.ibm.icu.util.ULocale
import io.undertow.server.HttpServerExchange
import io.undertow.server.RoutingHandler
import io.undertow.util.Headers
import io.undertow.util.StatusCodes
import it.unibz.vincent.AccountType
import it.unibz.vincent.Accounts
import it.unibz.vincent.QuestionnaireParticipants
import it.unibz.vincent.QuestionnaireParticipationState
import it.unibz.vincent.QuestionnaireState
import it.unibz.vincent.QuestionnaireTemplates
import it.unibz.vincent.Questionnaires
import it.unibz.vincent.Session
import it.unibz.vincent.session
import it.unibz.vincent.template.TemplateLang
import it.unibz.vincent.template.mainTitle
import it.unibz.vincent.template.parseTemplate
import it.unibz.vincent.util.GET
import it.unibz.vincent.util.LocaleStack
import it.unibz.vincent.util.POST
import it.unibz.vincent.util.contentDispositionAttachment
import it.unibz.vincent.util.formFile
import it.unibz.vincent.util.formString
import it.unibz.vincent.util.languages
import it.unibz.vincent.util.toHumanReadableTime
import kotlinx.html.FlowContent
import kotlinx.html.FormEncType
import kotlinx.html.div
import kotlinx.html.fileInput
import kotlinx.html.h1
import kotlinx.html.p
import kotlinx.html.postForm
import kotlinx.html.submitInput
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.th
import kotlinx.html.thead
import kotlinx.html.tr
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.leftJoin
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.InputStream
import java.nio.ByteBuffer
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * Show table of all questionnaires that you can start (are invited to)/that are in progress.
 * Show per questionnaire:
 * - Name
 * - State (invited/in progress)
 * Actions per questionnaire:
 * - Start
 */
private fun FlowContent.questionnairesToAnswer(session:Session) {
	h1 { +"Questionnaire invitations" }

	table {
		thead {
			tr {
				th { +"Name" }
				th { +"State" }
				// Open detail page
			}
		}

		tbody {
			transaction {
				for (row in QuestionnaireParticipants
						.leftJoin(Questionnaires, { questionnaire }, { id })
						.slice(Questionnaires.id, Questionnaires.name, QuestionnaireParticipants.state)
						.select { (QuestionnaireParticipants.participant eq session.userId) and
								(QuestionnaireParticipants.state neq QuestionnaireParticipationState.DONE) and
								(Questionnaires.state eq QuestionnaireState.RUNNING)}
						.orderBy(QuestionnaireParticipants.state)) {
					tr {
						td { +row[Questionnaires.name] }
						val state = row[QuestionnaireParticipants.state]
						td { +state.toString() /* TODO: Localize */ }
						val id = row[Questionnaires.id].value
						td {
							getButton("/questionnaire/$id") {
								+if (state == QuestionnaireParticipationState.INVITED) {
									"Start"
								} else {
									"Continue"
								}
							}
						}
					}
				}
			}
		}
	}
}

/**
 * Show table of all questionnaires that are in any state.
 * Show per questionnaire:
 * - Name
 * - Who created it
 * - What template it follows
 * - When it was created
 * - How many participants it has / how many are in progress / finished
 * Actions per questionnaire:
 * - Open detail page
 */
private fun FlowContent.questionnairesToManage(locale: LocaleStack) {
	h1 { +"Questionnaires" }

	table {
		thead {
			tr {
				th { +"State" }
				th { +"Name" }
				th { +"Author" }
				th { +"Template" }
				th { +"Created" }
				th { +"Participants" }
				// Open detail page
			}
		}

		tbody {
			transaction {
				for (row in Questionnaires
						.leftJoin(Accounts, { createdBy }, { id })
						.leftJoin(QuestionnaireTemplates, { Questionnaires.template }, { id })
						.slice(Questionnaires.id, Questionnaires.state, Questionnaires.name, Accounts.name, QuestionnaireTemplates.name, Questionnaires.timeCreated)
						.selectAll()
						.orderBy(Questionnaires.timeCreated)) {
					tr {
						td { +row[Questionnaires.state].toString() /* TODO: Localize */ }
						td { +row[Questionnaires.name] }
						td {
							val creator = row.getOrNull(Accounts.name)
							if (creator == null) {
								p("unimportant") { +"deleted" }
							} else {
								+creator
							}
						}
						td { +row[QuestionnaireTemplates.name] }
						td { +row[Questionnaires.timeCreated].toHumanReadableTime(locale) }
						val questionnaireId = row[Questionnaires.id].value
						td {
							// Participants
							val participantCount = QuestionnaireParticipants.select { QuestionnaireParticipants.questionnaire eq questionnaireId }.count()
							+(participantCount.toString())
						}
						td { getButton("/questionnaire/$questionnaireId/edit") { +"Detail" } }
					}
				}
			}
		}
	}
}

const val ACTION_QUESTIONNAIRE_DELETE = "questionnaire-delete"
const val PARAM_QUESTIONNAIRE_ID = "questionnaire-id"

private const val PARAM_TEMPLATE_ID = "template"
private const val TEMPLATE_NEW_TEMPLATE_XML = "template-xml"

/**
 * Show table of questionnaire templates.
 * Show per template:
 * - Name of template
 * - Author of template
 * - Date of template creation
 * Actions per template:
 * - Create a new questionnaire using this template
 * - Delete the template
 * - Download the template
 * Standalone actions:
 * - Upload a new template (\w name)
 */
private fun FlowContent.questionnaireTemplates(locale:LocaleStack, session:Session) {
	h1 { +"Questionnaire templates" }

	table {
		thead {
			tr {
				th { +"Name" }
				th { +"Author" }
				th { +"Created" }
				//     Create new questionnaire
				//     Download
				//     Delete
			}
		}

		tbody {
			transaction {
				for (row in QuestionnaireTemplates
						.leftJoin(Accounts, { createdBy }, { id })
						.slice(QuestionnaireTemplates.id, QuestionnaireTemplates.name, Accounts.name, QuestionnaireTemplates.timeCreated)
						.selectAll()
						.orderBy(QuestionnaireTemplates.timeCreated)) {
					tr {
						td { +row[QuestionnaireTemplates.name] }
						td { +row[Accounts.name] }
						td { +row[QuestionnaireTemplates.timeCreated].toHumanReadableTime(locale) }
						val templateId = row[QuestionnaireTemplates.id].toString()
						td { postButton(session, "/", PARAM_TEMPLATE_ID to templateId, routeAction = "questionnaire-new") { +"Use" } }
						td { getButton("/", PARAM_TEMPLATE_ID to templateId, routeAction = "template-download") { +"Download" } }
						td { postButton(session, "/", PARAM_TEMPLATE_ID to templateId, routeAction = "template-delete", classes="dangerous", confirmation = "Are you sure? This will also delete all questionnaires that used this template!") { +"Delete" } }
					}
				}
			}
		}
	}

	postForm("/") {
		encType = FormEncType.multipartFormData
		session(session)
		routeAction("template-new")
		// TODO(jp): Client side size validation (https://developer.mozilla.org/en-US/docs/Web/Guide/HTML/HTML5/Constraint_validation)
		fileInput(name = TEMPLATE_NEW_TEMPLATE_XML) { required=true; accept=".xml,application/xml,text/xml"; multiple=false; }
		submitInput { value="Upload new template" }
	}
}

/** Show home screen for logged-in users */
fun HttpServerExchange.home(session: Session) {
	val userName = session.get(Accounts.name)
	val userLevel = session.get(Accounts.accountType)
	val locale = languages()

	sendBase { _, _ ->
		div("page-container") {
			h1 { +"Welcome $userName" }

			renderMessages(this@home)

			// Show available questionnaires to fill
			div("page-section") {
				questionnairesToAnswer(session)
			}

			// Show running questionnaires
			if (userLevel >= AccountType.STAFF) {
				div("page-section") {
					questionnairesToManage(locale)
				}
			}

			// Show questionnaire templates
			if (userLevel >= AccountType.STAFF) {
				div("page-section") {
					questionnaireTemplates(locale, session)
				}
			}

			div("page-section container") {
				val showLogoutFully = userLevel >= AccountType.STAFF

				div("column") {
					postButton(session, "/", routeAction = "logout", classes = "dangerous u-centered") { +"Logout" }
				}

				if (showLogoutFully) {
					// Let's not confuse ordinary users with this
					div("column") {
						postButton(session, "/", routeAction = "logout-fully", classes = "dangerous u-centered") { +"Logout from all browsers" }
					}
				}
			}
		}
	}
}

fun RoutingHandler.setupHomeRoutes() {
	POST("/", AccountType.STAFF, "questionnaire-new") { exchange ->
		val session = exchange.session()!!

		val templateId = exchange.formString(PARAM_TEMPLATE_ID)?.toLongOrNull()
		if (templateId == null) {
			exchange.statusCode = StatusCodes.BAD_REQUEST
			exchange.messageWarning("No template specified")
			exchange.home(session)
			return@POST
		}

		val newQuestionnaireId = transaction {
			Questionnaires.insertAndGetId {
				it[name] = "New questionnaire ${Instant.now()}" // TODO(jp): Better default name
				it[createdBy] = session.userId
				it[template] = templateId
			}.value
		}

		// Redirect
		exchange.statusCode = StatusCodes.SEE_OTHER
		exchange.responseHeaders.put(Headers.LOCATION, "/questionnaire/$newQuestionnaireId/edit")
	}

	POST("/", AccountType.STAFF, ACTION_QUESTIONNAIRE_DELETE) { exchange ->
		val questionnaireId = exchange.formString(PARAM_QUESTIONNAIRE_ID)?.toLongOrNull()

		val deleted = if (questionnaireId == null) 0 else transaction {
			Questionnaires.deleteWhere(limit = 1) { (Questionnaires.id eq questionnaireId) and (Questionnaires.state neq QuestionnaireState.RUNNING) }
		}

		if (deleted == 1) {
			exchange.messageInfo("Questionnaire deleted")
		}

		exchange.home(exchange.session()!!)
	}

	GET("/", AccountType.STAFF, "template-download") { exchange ->
		val session = exchange.session()!!

		val template = exchange.formString(PARAM_TEMPLATE_ID)?.toLongOrNull()
		if (template == null) {
			exchange.statusCode = StatusCodes.BAD_REQUEST
			exchange.messageWarning("No template specified")
			exchange.home(session)
			return@GET
		}

		var templateName:String? = null
		val templateXmlBytes = transaction {
			QuestionnaireTemplates
					.slice(QuestionnaireTemplates.name, QuestionnaireTemplates.template_xml)
					.select { QuestionnaireTemplates.id eq template }
					.limit(1)
					.firstOrNull()?.let {
						templateName = it[QuestionnaireTemplates.name]
						it[QuestionnaireTemplates.template_xml].bytes
					}
		}

		if (templateXmlBytes == null) {
			exchange.statusCode = StatusCodes.NOT_FOUND
			exchange.messageWarning("Template no longer exists")
			exchange.home(session)
			return@GET
		}

		exchange.statusCode = StatusCodes.OK
		exchange.responseHeaders.put(Headers.CONTENT_DISPOSITION, contentDispositionAttachment("${templateName ?: "template"}.xml"))
		exchange.responseSender.send(ByteBuffer.wrap(templateXmlBytes))
	}

	POST("/", AccountType.STAFF, "template-delete") { exchange ->
		val session = exchange.session()!!

		val template = exchange.formString(PARAM_TEMPLATE_ID)?.toLongOrNull()
		if (template == null) {
			exchange.statusCode = StatusCodes.BAD_REQUEST
			exchange.messageWarning("No template specified")
			exchange.home(session)
			return@POST
		}

		// TODO(jp): This will fail if there are any existing questionnaires using this template!
		val deleted = transaction {
			QuestionnaireTemplates.deleteWhere(limit=1) { QuestionnaireTemplates.id eq template }
		}

		if (deleted == 0) {
			exchange.statusCode = StatusCodes.NOT_FOUND
			exchange.messageWarning("Template no longer exists")
			exchange.home(session)
			return@POST
		}

		exchange.statusCode = StatusCodes.OK
		exchange.messageInfo("Template deleted")
		exchange.home(session)
	}

	POST("/", AccountType.STAFF, "template-new") { exchange ->
		val session = exchange.session()!!

		val formFile = exchange.formFile(TEMPLATE_NEW_TEMPLATE_XML)
		val parsed = formFile?.let {
			parseTemplate(it.inputStream)
		}

		if (parsed == null) {
			exchange.statusCode = StatusCodes.BAD_REQUEST
			exchange.messageWarning("No template xml specified")
			exchange.home(session)
			return@POST
		}

		if (parsed.errors.isNotEmpty()) {
			exchange.statusCode = StatusCodes.BAD_REQUEST // UNPROCESSABLE_ENTITY would also be correct in some cases
			for (error in parsed.errors) {
				exchange.messageWarning(error)
			}
			exchange.home(session)
			return@POST
		}

		for (warning in parsed.warnings) {
			exchange.messageWarning(warning)
		}
		exchange.messageInfo("Questionnaire template added")

		val databaseName = parsed.result.run {
			title.mainTitle(TemplateLang(this.defaultLanguage, listOf(ULocale.ENGLISH))) ?: "Without a name (${DateTimeFormatter.ISO_INSTANT.format(Instant.now())})"
		}

		val templateId = transaction {
			QuestionnaireTemplates.insertAndGetId {
				it[createdBy] = session.userId
				it[name] = databaseName
				/* This is magic, but should work. Maybe. */
				@Suppress("UNCHECKED_CAST")
				it[template_xml as Column<InputStream>] = formFile.inputStream
			}.value
		}

		QuestionnaireTemplates.CACHE.put(templateId, parsed.result)

		exchange.statusCode = StatusCodes.CREATED
		exchange.home(session)
	}
}