package it.unibz.vincent.pages

import io.undertow.server.HttpServerExchange
import io.undertow.server.RoutingHandler
import io.undertow.util.StatusCodes
import it.unibz.vincent.AccountType
import it.unibz.vincent.QuestionnaireParticipants
import it.unibz.vincent.session
import it.unibz.vincent.util.GET
import it.unibz.vincent.util.pathString
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction

private const val PATH_QUESTIONNAIRE_ID = "qId"

private fun HttpServerExchange.questionnaireIdIfParticipant():Long? {
	val session = session()!!
	try {
		val questionnaireId = pathString(PATH_QUESTIONNAIRE_ID).toLong()
		val isParticipant = transaction {
			QuestionnaireParticipants
					.select { (QuestionnaireParticipants.participant eq session.userId) and (QuestionnaireParticipants.questionnaire eq questionnaireId) }
					.empty()
		}
		if (isParticipant) {
			return questionnaireId
		}
	} catch (e:NumberFormatException) {}

	statusCode = StatusCodes.FORBIDDEN
	messageWarning("You have not been invited to that questionnaire (if it exists)")
	home(session)
	return null
}

fun RoutingHandler.setupQuestionnaireAnswerRoutes() {

	GET("/questionnaire/{$PATH_QUESTIONNAIRE_ID}", AccountType.NORMAL, "") { exchange ->
		val questionnaireId = exchange.questionnaireIdIfParticipant() ?: return@GET

		TODO()
	}

}