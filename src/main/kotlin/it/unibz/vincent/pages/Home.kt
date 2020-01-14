package it.unibz.vincent.pages

import com.ibm.icu.util.ULocale
import io.undertow.server.HttpServerExchange
import io.undertow.server.RoutingHandler
import io.undertow.util.StatusCodes
import it.unibz.vincent.AccountType
import it.unibz.vincent.Accounts
import it.unibz.vincent.QuestionnaireTemplates
import it.unibz.vincent.Session
import it.unibz.vincent.session
import it.unibz.vincent.template.parseTemplate
import it.unibz.vincent.util.GET
import it.unibz.vincent.util.LocaleStack
import it.unibz.vincent.util.POST
import it.unibz.vincent.util.formFile
import it.unibz.vincent.util.languages
import it.unibz.vincent.util.toHumanReadableTime
import kotlinx.html.FlowContent
import kotlinx.html.FormEncType
import kotlinx.html.div
import kotlinx.html.fileInput
import kotlinx.html.h1
import kotlinx.html.p
import kotlinx.html.postForm
import kotlinx.html.style
import kotlinx.html.submitInput
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.th
import kotlinx.html.thead
import kotlinx.html.tr
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.leftJoin
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.InputStream
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
	h1 { +"Open questionnaires" }
	p("sub") { +"You have been invited to" }
	//TODO
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
private fun FlowContent.questionnairesToManage(session:Session) {
	//TODO
}

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
						.selectAll()) {
					tr {
						td { +row[QuestionnaireTemplates.name] }
						td { +row[Accounts.name] }
						td { +row[QuestionnaireTemplates.timeCreated].toHumanReadableTime(locale) }
						val templateId = row[QuestionnaireTemplates.id].toString()
						td { postButton(session, "/questionnaire-new", "template" to templateId) { +"Use" } }
						td { getButton("/template-download", "template" to templateId) { +"Download" } }
						td { postButton(session, "/template-delete", "template" to templateId, classes="dangerous") { +"Delete" } }
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
		div("container") {
			h1 { +"Welcome $userName" }

			renderMessages(this@home)

			// Show available questionnaires to fill
			div("column w12") {
				questionnairesToAnswer(session)
			}

			// Show running questionnaires
			if (userLevel >= AccountType.STAFF) {
				div("column w12") {
					questionnairesToManage(session)
				}
			}

			// Show questionnaire templates
			if (userLevel >= AccountType.STAFF) {
				div("column w12") {
					questionnaireTemplates(locale, session)
				}
			}
		}

		div("container") {
			style = "margin-top: 5rem"
			val showLogoutFully = userLevel >= AccountType.STAFF

			div("${if (showLogoutFully) "o3" else "o5"} w3 column") {
				postButton(session, "/", routeAction = "logout", classes="dangerous u-centered") { style = "min-width: 50%"; +"Logout" }
			}

			if (showLogoutFully) {
				// Let's not confuse ordinary users with this
				div("w3 column") {
					postButton(session, "/", routeAction = "logout-fully", classes = "dangerous u-centered") { style = "min-width: 50%"; +"Logout from all browsers" }
				}
			}
		}
	}
}

fun RoutingHandler.setupHomeRoutes() {
	POST("/", AccountType.STAFF, "questionnaire-new") { exchange ->
		TODO()
	}

	GET("/", AccountType.STAFF, "template-download") { exchange ->
		TODO()
	}

	POST("/", AccountType.STAFF, "template-delete") { exchange ->
		TODO()
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
			title.mainTitle(listOf(ULocale.ENGLISH)) ?: "Without a name (${DateTimeFormatter.ISO_INSTANT.format(Instant.now())})"
		}

		transaction {
			QuestionnaireTemplates.insert {
				it[createdBy] = session.userId
				it[name] = databaseName
				/* This is magic, but should work. Maybe. */
				@Suppress("UNCHECKED_CAST")
				it[template_xml as Column<InputStream>] = formFile.inputStream
			}
		}

		exchange.home(session)
	}
}