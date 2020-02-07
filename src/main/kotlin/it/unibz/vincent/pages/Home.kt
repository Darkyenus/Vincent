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
import it.unibz.vincent.util.redirect
import it.unibz.vincent.util.toHumanReadableTime
import kotlinx.html.FlowContent
import kotlinx.html.FormEncType
import kotlinx.html.div
import kotlinx.html.fileInput
import kotlinx.html.h1
import kotlinx.html.label
import kotlinx.html.p
import kotlinx.html.postForm
import kotlinx.html.span
import kotlinx.html.style
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
import java.time.ZoneId
import java.time.format.DateTimeFormatter

const val HOME_PATH = "/"

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

	var noInvitations = true

	table {
		thead {
			tr {
				th(classes = "grow") { +"Name" }
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
					noInvitations = false
					tr {
						td { +row[Questionnaires.name] }
						val state = row[QuestionnaireParticipants.state]
						td { +state.toString() /* TODO: Localize */ }
						val id = row[Questionnaires.id].value
						td {
							getButton(questionnaireAnswerPath(id)) {
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

	if (noInvitations) {
		div("table-no-elements") {
			+"You were not invited to any questionnaires yet"
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

	var noQuestionnaires = true

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
					noQuestionnaires = false
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
						td { getButton(questionnaireEditPath(questionnaireId)) { +"Detail" } }
					}
				}
			}
		}
	}

	if (noQuestionnaires) {
		div("table-no-elements") {
			+"There are no questionnaires yet"
		}
	}
}

const val ACTION_QUESTIONNAIRE_NEW = "questionnaire-new"
const val ACTION_QUESTIONNAIRE_DELETE = "questionnaire-delete"
const val ACTION_TEMPLATE_DOWNLOAD = "template-download"
const val ACTION_TEMPLATE_DELETE = "template-delete"
const val ACTION_TEMPLATE_NEW = "template-new"

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

	var noTemplates = true

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
					noTemplates = false
					tr {
						td { +row[QuestionnaireTemplates.name] }
						td { +row[Accounts.name] }
						td { +row[QuestionnaireTemplates.timeCreated].toHumanReadableTime(locale) }
						val templateId = row[QuestionnaireTemplates.id].toString()
						td { postButton(session, HOME_PATH, PARAM_TEMPLATE_ID to templateId, routeAction = ACTION_QUESTIONNAIRE_NEW) { +"Use" } }
						td { getButton(HOME_PATH, PARAM_TEMPLATE_ID to templateId, routeAction = ACTION_TEMPLATE_DOWNLOAD) { +"Download" } }
						td { postButton(session, HOME_PATH, PARAM_TEMPLATE_ID to templateId, routeAction = ACTION_TEMPLATE_DELETE, classes="dangerous", confirmation = "Are you sure? This will also delete all questionnaires that used this template!") { +"Delete" } }
					}
				}
			}
		}
	}

	if (noTemplates) {
		div("table-no-elements") {
			+"There are no templates yet"
		}
	}

	postForm(HOME_PATH, classes = "compact-form") {
		encType = FormEncType.multipartFormData
		session(session)
		routeAction(ACTION_TEMPLATE_NEW)
		label("main") {
			span("label") { +"Template file" }
			fileInput(name = TEMPLATE_NEW_TEMPLATE_XML) {
				style = "font-weight: normal;";
				required = true;
				accept = ".xml,application/xml,text/xml";
				multiple = false;
			}
		}
		submitInput { value="Upload new template" }
	}
}

/** Show home screen for logged-in users */
fun HttpServerExchange.home(session: Session) {
	val userLevel = session.accountType

	sendBase("") { _, locale ->
		div("page-container") {

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

			// Show registered user lists
			if (userLevel >= AccountType.STAFF) {
				div("page-section button-container") {
					getButton(ACCOUNT_LIST_PATH, parentClasses="column") { +"All accounts" }
					getButton(ACCOUNT_LIST_PATH, ACCOUNT_LIST_FILTER_PARAM to AccountListFilter.REGULAR.toString(), parentClasses="column") { +"Regular accounts" }
					getButton(ACCOUNT_LIST_PATH, ACCOUNT_LIST_FILTER_PARAM to AccountListFilter.GUEST.toString(), parentClasses="column") { +"Guest accounts" }
					getButton(ACCOUNT_LIST_PATH, ACCOUNT_LIST_FILTER_PARAM to AccountListFilter.RESERVED.toString(), parentClasses="column") { +"Reserved accounts" }
				}
			}
		}
	}
}

fun RoutingHandler.setupHomeRoutes() {
	val defaultNameDateFormatter = DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneId.systemDefault())

	POST(HOME_PATH, AccountType.STAFF, ACTION_QUESTIONNAIRE_NEW) { exchange ->
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
				it[name] = "New questionnaire ${defaultNameDateFormatter.format(Instant.now())}"
				it[createdBy] = session.userId
				it[template] = templateId
			}.value
		}

		// Redirect
		exchange.redirect(questionnaireEditPath(newQuestionnaireId))
	}

	POST(HOME_PATH, AccountType.STAFF, ACTION_QUESTIONNAIRE_DELETE) { exchange ->
		val questionnaireId = exchange.formString(PARAM_QUESTIONNAIRE_ID)?.toLongOrNull()

		val deleted = if (questionnaireId == null) 0 else transaction {
			Questionnaires.deleteWhere(limit = 1) { (Questionnaires.id eq questionnaireId) and (Questionnaires.state neq QuestionnaireState.RUNNING) }
		}

		if (deleted == 1) {
			exchange.messageInfo("Questionnaire deleted")
		}

		exchange.redirect(HOME_PATH)
	}

	GET(HOME_PATH, AccountType.STAFF, ACTION_TEMPLATE_DOWNLOAD, requireCompletedDemography = false) { exchange ->
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
					.slice(QuestionnaireTemplates.name, QuestionnaireTemplates.templateXml)
					.select { QuestionnaireTemplates.id eq template }
					.limit(1)
					.firstOrNull()?.let {
						templateName = it[QuestionnaireTemplates.name]
						it[QuestionnaireTemplates.templateXml].bytes
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

	POST(HOME_PATH, AccountType.STAFF, ACTION_TEMPLATE_DELETE) { exchange ->
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
			exchange.messageWarning("Template no longer exists")
		} else {
			exchange.messageInfo("Template deleted")
		}
		exchange.redirect(HOME_PATH)
	}

	POST(HOME_PATH, AccountType.STAFF, ACTION_TEMPLATE_NEW) { exchange ->
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
				it[templateXml as Column<InputStream>] = formFile.inputStream
			}.value
		}

		QuestionnaireTemplates.CACHE.put(templateId, parsed.result)

		exchange.redirect(HOME_PATH)
	}
}