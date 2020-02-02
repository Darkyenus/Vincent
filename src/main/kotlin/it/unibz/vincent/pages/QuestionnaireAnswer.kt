package it.unibz.vincent.pages

import io.undertow.server.HttpServerExchange
import io.undertow.server.RoutingHandler
import io.undertow.util.Headers
import io.undertow.util.StatusCodes
import it.unibz.vincent.AccountType
import it.unibz.vincent.QuestionnaireParticipants
import it.unibz.vincent.QuestionnaireParticipationState
import it.unibz.vincent.QuestionnaireResponses
import it.unibz.vincent.QuestionnaireState
import it.unibz.vincent.QuestionnaireTemplates
import it.unibz.vincent.QuestionnaireWines
import it.unibz.vincent.Questionnaires
import it.unibz.vincent.WineParticipantAssignment
import it.unibz.vincent.session
import it.unibz.vincent.template.QuestionnaireTemplate
import it.unibz.vincent.template.TemplateLang
import it.unibz.vincent.util.GET
import it.unibz.vincent.util.POST
import it.unibz.vincent.util.formString
import it.unibz.vincent.util.formStrings
import it.unibz.vincent.util.merge
import it.unibz.vincent.util.pathString
import kotlinx.html.div
import kotlinx.html.h1
import kotlinx.html.h2
import kotlinx.html.hiddenInput
import kotlinx.html.p
import kotlinx.html.postForm
import kotlinx.html.style
import kotlinx.html.submitInput
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.leftJoin
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory
import kotlin.math.max

private val LOG = LoggerFactory.getLogger("QuestionnaireAnswer")

private const val PATH_QUESTIONNAIRE_ID = "qId"
private const val ACTION_SUBMIT_SECTION = "submit-section"
private const val FORM_PARAM_WINE_SECTION_CHECKSUM = "section"

private class QuestionnaireParticipation(val questionnaireId:Long,
                                         val questionnaireName:String,
                                         val participantId:Long,
                                         val currentSection:Int, val currentWineIndex:Int,
                                         val wineCount:Int,
                                         val wineId:Long,
                                         val wineCode:Int,
                                         val template:QuestionnaireTemplate) {
	val sectionChecksum:Int
		get() = currentSection * max(wineCount, 1) + currentWineIndex
}

private fun HttpServerExchange.questionnaireParticipation():QuestionnaireParticipation? {
	val session = session()!!

	val questionnaireId: Long = try {
		pathString(PATH_QUESTIONNAIRE_ID).toLong()
	}  catch (e:NumberFormatException) {
		statusCode = StatusCodes.NOT_FOUND
		messageWarning("That questionnaire does not exist")
		home(session)
		return null
	}

	var currentSection = 0
	var currentWineIndex = 0
	var templateId:Long? = null
	var questionnaireName = ""
	var questionnaireState: QuestionnaireState = QuestionnaireState.CLOSED

	val state: QuestionnaireParticipationState? = transaction {
		QuestionnaireParticipants
				.leftJoin(Questionnaires, { questionnaire }, { Questionnaires.id })
				.slice(QuestionnaireParticipants.currentSection, QuestionnaireParticipants.currentWineIndex, QuestionnaireParticipants.state, Questionnaires.template, Questionnaires.state, Questionnaires.name)
				.select { (QuestionnaireParticipants.participant eq session.userId) and (QuestionnaireParticipants.questionnaire eq questionnaireId) }
				.limit(1)
				.firstOrNull()?.let {
					currentSection = it[QuestionnaireParticipants.currentSection]
					currentWineIndex = it[QuestionnaireParticipants.currentWineIndex]
					templateId = it[Questionnaires.template]
					questionnaireName = it.getOrNull(Questionnaires.name) ?: "Questionnaire"
					questionnaireState = it.getOrNull(Questionnaires.state) ?: QuestionnaireState.CLOSED

					it[QuestionnaireParticipants.state]
				}
	}

	if (state == null) {
		statusCode = StatusCodes.FORBIDDEN
		messageWarning("That questionnaire does not exist, or you weren't invited")
		home(session)
		return null
	}

	if (questionnaireState != QuestionnaireState.RUNNING) {
		statusCode = StatusCodes.FORBIDDEN
		messageWarning("That questionnaire is not open")
		home(session)
		return null
	}

	if (state == QuestionnaireParticipationState.DONE) {
		// Redirect home, this questionnaire is already done
		statusCode = StatusCodes.SEE_OTHER
		responseHeaders.put(Headers.LOCATION, "/")
		return null
	}

	if (state == QuestionnaireParticipationState.INVITED) {
		// Mark as started
		try {
			transaction {
				QuestionnaireParticipants.update({
					(QuestionnaireParticipants.participant eq session.userId) and
							(QuestionnaireParticipants.questionnaire eq questionnaireId) and
							(QuestionnaireParticipants.state eq QuestionnaireParticipationState.INVITED)
				}) {
					it[QuestionnaireParticipants.state] = QuestionnaireParticipationState.STARTED
				}
			}
		} catch (e:Exception) {
			LOG.error("Failed to update participant state", e)
		}
	}

	val template = templateId?.let { QuestionnaireTemplates.parsed(it) }
	if (template == null) {
		// huh??
		statusCode = StatusCodes.NOT_FOUND
		messageWarning("The questionnaire has been cancelled")
		home(session)
		return null
	}

	var wineCode:Int = -1
	var wineId:Long = -1L
	var wineCount = 0

	transaction {
		wineCount = WineParticipantAssignment.select {
			(WineParticipantAssignment.questionnaire eq questionnaireId) and
					(WineParticipantAssignment.participant eq session.userId) }
				.count()

		if (wineCount > 0) {
			WineParticipantAssignment
					.leftJoin(QuestionnaireWines, { wine }, { QuestionnaireWines.id })
					.slice(WineParticipantAssignment.wine, QuestionnaireWines.code)
					.select { (WineParticipantAssignment.questionnaire eq questionnaireId) and (WineParticipantAssignment.participant eq session.userId) and (WineParticipantAssignment.order eq currentWineIndex) }
					.limit(1).firstOrNull()?.let { row ->
						wineId = row[WineParticipantAssignment.wine]
						wineCode = row[QuestionnaireWines.code]
					}
		}
	}

	return QuestionnaireParticipation(questionnaireId, questionnaireName, session.userId,
			currentSection,
			currentWineIndex, wineCount, wineId, wineCode,
			template)
}

private val QuestionnaireParticipation.isLastSection:Boolean
	get() {
		var newWineIndex = currentWineIndex
		if (currentSection + 1 !in template.sections.indices) {
			newWineIndex += 1
		}
		return newWineIndex >= wineCount
	}

/** Advance section and return new, updated participation.
 * Updates database.
 * @return whether the whole questionnaire has been filled */
private fun QuestionnaireParticipation.advanceSection(): Boolean {
	var newSection = currentSection + 1
	var newWineIndex = currentWineIndex

	// Check if this segment is even valid
	if (newSection !in template.sections.indices) {
		// This wine is done (currentSection > max sections, currentSection < 0 is unlikely)
		newWineIndex += 1
		newSection = 0
	}

	if (newWineIndex >= wineCount) {
		// Done
		transaction {
			QuestionnaireParticipants.update({ (QuestionnaireParticipants.participant eq participantId) and (QuestionnaireParticipants.questionnaire eq questionnaireId) }, 1) {
				it[state] = QuestionnaireParticipationState.DONE
			}
		}
		return true
	}

	transaction {
		QuestionnaireParticipants.update({ (QuestionnaireParticipants.participant eq participantId) and (QuestionnaireParticipants.questionnaire eq questionnaireId) }, 1) {
			it[currentSection] = newSection
			it[currentWineIndex] = newWineIndex
		}
	}

	return false
}


private fun handleQuestionnaireShow(exchange:HttpServerExchange, participation:QuestionnaireParticipation, highlightMissing:Boolean = false) {
	exchange.sendBase(participation.questionnaireName) { _, locale ->
		val section = participation.template.sections[participation.currentSection]
		val lang = TemplateLang(participation.template.defaultLanguage, locale)

		// Render title
		div {
			style="text-align: center; margin-top: 3rem;"
			renderTitle(section.title, lang, ::h1)
			p("sub") { +"Wine: ${participation.wineCode}" }
		}

		// Render questions
		postForm(action = "/questionnaire/${participation.questionnaireId}") {
			session(exchange.session()!!)
			routeAction(ACTION_SUBMIT_SECTION)
			hiddenInput(name = FORM_PARAM_WINE_SECTION_CHECKSUM) { value = participation.sectionChecksum.toString() }

			var idGeneratorNumber = 0
			val idGenerator:()->String = {
				"q-${idGeneratorNumber++}"
			}

			for (sectionPart in section.content) {
				div("container section-part") {
					renderTitle(sectionPart.title, lang, ::h2)
					renderText(sectionPart.text, lang, ::p)

					if (sectionPart is QuestionnaireTemplate.SectionContent.Question) {
						renderQuestion(sectionPart.id, sectionPart.required, sectionPart.type, lang, idGenerator)
					}
				}
			}

			div {
				style="text-align: center; margin-bottom: 6rem;"
				if (participation.isLastSection) {
					submitInput { value = "Finish" }
				} else {
					submitInput { value = "Next" }
				}
			}
		}
	}
}

fun RoutingHandler.setupQuestionnaireAnswerRoutes() {

	GET("/questionnaire/{$PATH_QUESTIONNAIRE_ID}", AccountType.NORMAL) { exchange ->
		val participation = exchange.questionnaireParticipation() ?: return@GET
		handleQuestionnaireShow(exchange, participation)
	}

	// Sent on next section button press
	POST("/questionnaire/{$PATH_QUESTIONNAIRE_ID}", AccountType.NORMAL, ACTION_SUBMIT_SECTION) { exchange ->
		val participation = exchange.questionnaireParticipation() ?: return@POST

		// Check that the answer belongs to a correct assignment
		run {
			val requestedWineIndex = exchange.formString(FORM_PARAM_WINE_SECTION_CHECKSUM)?.toInt()
			if (participation.sectionChecksum != requestedWineIndex) {
				// Wrong part!
				exchange.messageWarning("Questionnaire must be answered in order")
				handleQuestionnaireShow(exchange, participation)
				return@POST
			}
		}

		// Collect answers & check if all required questions are answered
		val sectionQuestionIds = participation.template.sections[participation.currentSection].questionIds
		val requiredQuestionIds = participation.template.sections[participation.currentSection].requiredQuestionIds
		val responses = exchange.formStrings(FORM_PARAM_QUESTION_RESPONSE_PREFIX).groupBy { it.first }
		val alreadyPresentResponses = transaction {
			for (questionId in sectionQuestionIds) {
				val response = responses[questionId]?.joinToString("\n\n")?.takeUnless { it.isBlank() } ?: continue

				QuestionnaireResponses.merge {
					it[QuestionnaireResponses.participant] = participation.participantId
					it[QuestionnaireResponses.questionnaire] = participation.questionnaireId
					it[QuestionnaireResponses.wine] = participation.wineId
					it[QuestionnaireResponses.questionId] = questionId
					it[QuestionnaireResponses.response] = response
				}
			}

			QuestionnaireResponses.slice(QuestionnaireResponses.questionId).select {
				(QuestionnaireResponses.participant eq participation.participantId) and
						(QuestionnaireResponses.questionnaire eq participation.questionnaireId) and
						(QuestionnaireResponses.wine eq participation.wineId)
			}.mapTo(HashSet()) { it[QuestionnaireResponses.questionId] }
		}

		// Check if all required questions are answered
		val missingResponses = requiredQuestionIds - alreadyPresentResponses

		if (missingResponses.isEmpty()) {
			if (participation.advanceSection()) {
				// Done
				exchange.statusCode = StatusCodes.SEE_OTHER
				exchange.responseHeaders.put(Headers.LOCATION, "/")
			} else {
				handleQuestionnaireShow(exchange, exchange.questionnaireParticipation() ?: return@POST)
			}
		} else {
			handleQuestionnaireShow(exchange, participation, highlightMissing = true)
		}
	}
}