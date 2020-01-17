package it.unibz.vincent.pages

import io.undertow.server.HttpServerExchange
import io.undertow.server.RoutingHandler
import io.undertow.util.StatusCodes
import it.unibz.vincent.AccountType
import it.unibz.vincent.QuestionnaireState
import it.unibz.vincent.QuestionnaireTemplates
import it.unibz.vincent.Questionnaires
import it.unibz.vincent.session
import it.unibz.vincent.util.GET
import it.unibz.vincent.util.LocaleStack
import it.unibz.vincent.util.languages
import it.unibz.vincent.util.pathString
import kotlinx.html.FlowContent
import kotlinx.html.div
import kotlinx.html.h1
import kotlinx.html.p
import org.jetbrains.exposed.sql.leftJoin
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction

private const val PATH_QUESTIONNAIRE_ID = "qId"

/**
 * List of:
 * - Participant name
 * - Participant email
 * - Participant code (?)
 * - Participant state
 * Actions:
 * - Kick
 * - Download results
 */
private fun FlowContent.questionnaireParticipants(locale:LocaleStack, questionnaireId:Long, editable:Boolean) {
//TODO
}

/**
 * List of:
 * - Wine name
 * - Assigned wine code (modifiable)
 * Actions:
 * - Save new code
 * - Remove
 */
private fun FlowContent.questionnaireWines(locale:LocaleStack, questionnaireId:Long, editable:Boolean) {
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
private fun FlowContent.questionnaireWineParticipantAssociations(locale:LocaleStack, questionnaireId:Long, editable:Boolean) {
//TODO
}


/** Page for editing questionnaires. */
private fun HttpServerExchange.editQuestionnairePage() {
	val session = session()!!
	val questionnaireId = try {
		pathString(PATH_QUESTIONNAIRE_ID).toLong()
	} catch (e:NumberFormatException) {
		statusCode = StatusCodes.NOT_FOUND
		messageWarning("That questionnaire does not exist")
		home(session)
		return
	}

	var questionnaireName:String? = null
	var questionnaireState: QuestionnaireState? = null
	var questionnaireTemplateName: String? = null
	transaction {
		Questionnaires
				.leftJoin(QuestionnaireTemplates, { template }, { QuestionnaireTemplates.id })
				.select { Questionnaires.id eq questionnaireId }.limit(1).firstOrNull()?.let {
			questionnaireName = it[Questionnaires.name]
			questionnaireState = it[Questionnaires.state]
			questionnaireTemplateName = it[QuestionnaireTemplates.name]
		}
	}
	if (questionnaireName == null || questionnaireState == null || questionnaireTemplateName == null) {
		statusCode = StatusCodes.NOT_FOUND
		messageWarning("That questionnaire does not exist")
		home(session)
		return
	}

	val locale = languages()

	val editable = questionnaireState == QuestionnaireState.CREATED
	sendBase { _, _ ->
		div("container") {

			h1 { +questionnaireName!! }
			p("sub") { +questionnaireTemplateName!! }

			renderMessages(this@editQuestionnairePage)

			// Participants
			div("row") {
				questionnaireParticipants(locale, questionnaireId, editable)
			}

			// Wines
			div("row") {
				questionnaireWines(locale, questionnaireId, editable)
			}

			// Wine-participant association
			div("row") {
				questionnaireWineParticipantAssociations(locale, questionnaireId, editable)
			}

			// Open/close
			div("row") {
				//TODO
			}

			// Download results
			div("row") {
				//TODO
			}
		}
	}
}

fun RoutingHandler.setupQuestionnaireEditRoutes() {
	GET("/questionnaire/{$PATH_QUESTIONNAIRE_ID}/edit", AccountType.STAFF, "") { exchange ->
		exchange.editQuestionnairePage()
	}
}