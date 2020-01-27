package it.unibz.vincent.pages

import io.undertow.server.HttpServerExchange
import io.undertow.server.RoutingHandler
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
import it.unibz.vincent.template.fullTitle
import it.unibz.vincent.template.mainText
import it.unibz.vincent.util.GET
import it.unibz.vincent.util.POST
import it.unibz.vincent.util.formString
import it.unibz.vincent.util.formStrings
import it.unibz.vincent.util.merge
import it.unibz.vincent.util.pathString
import kotlinx.html.HTMLTag
import kotlinx.html.HtmlBlockTag
import kotlinx.html.TEXTAREA
import kotlinx.html.attributesMapOf
import kotlinx.html.div
import kotlinx.html.h1
import kotlinx.html.h2
import kotlinx.html.h3
import kotlinx.html.hiddenInput
import kotlinx.html.label
import kotlinx.html.numberInput
import kotlinx.html.p
import kotlinx.html.postForm
import kotlinx.html.radioInput
import kotlinx.html.style
import kotlinx.html.submitInput
import kotlinx.html.textInput
import kotlinx.html.unsafe
import kotlinx.html.visit
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.leftJoin
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory
import kotlin.math.max
import kotlin.reflect.KFunction2

private val LOG = LoggerFactory.getLogger("QuestionnaireAnswer")

private fun HttpServerExchange.questionnaireComplete() {
	sendBase("Questionnaire complete" /* TODO Localize */) { httpServerExchange, _ ->
		div("container") {
			style = "margin-top: 5%"

			div("row") {
				style = "margin-bottom: 3rem"
				h1 { +"Questionnaire complete" }
				p("sub") { +"Thank you!" }
			}

			renderMessages(httpServerExchange)

			div("u-centered") {
				getButton("/") { +"Home" }
			}
		}
	}
}

private const val PATH_QUESTIONNAIRE_ID = "qId"
private const val ACTION_SUBMIT_SECTION = "submit-section"
private const val FORM_PARAM_WINE_ASSIGNMENT_ID = "w"
private const val FORM_PARAM_QUESTION_RESPONSE_PREFIX = "r-"

private class QuestionnaireParticipation(val questionnaireId:Long,
                                         val questionnaireName:String,
                                         val participantId:Long,
                                         val currentSection:Int, val currentWineIndex:Int,
                                         val wineCount:Int,
                                         val wineId:Long,
                                         val wineParticipantAssignmentId:Long,
                                         val wineCode:Int,
                                         val state:QuestionnaireParticipationState,
                                         val template:QuestionnaireTemplate)

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
	var currentWineOrder = 0
	var templateId:Long? = null
	var questionnaireName = ""
	var questionnaireState: QuestionnaireState = QuestionnaireState.CLOSED

	val state: QuestionnaireParticipationState? = transaction {
		QuestionnaireParticipants
				.leftJoin(Questionnaires, { questionnaire }, { Questionnaires.id })
				.slice(QuestionnaireParticipants.currentSection, QuestionnaireParticipants.currentWineOrder, QuestionnaireParticipants.state, Questionnaires.template, Questionnaires.state, Questionnaires.name)
				.select { (QuestionnaireParticipants.participant eq session.userId) and (QuestionnaireParticipants.questionnaire eq questionnaireId) }
				.limit(1)
				.firstOrNull()?.let {
					currentSection = it[QuestionnaireParticipants.currentSection]
					currentWineOrder = it[QuestionnaireParticipants.currentWineOrder]
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

	val template = templateId?.let { QuestionnaireTemplates.parsed(it) }
	if (template == null) {
		// huh??
		statusCode = StatusCodes.NOT_FOUND
		messageWarning("The questionnaire has been cancelled")
		home(session)
		return null
	}

	var wineCode:Int = -1
	var wineId = 0L
	var wineParticipantAssignmentId = 0L
	var wineCount = 0

	transaction {
		wineCount = WineParticipantAssignment.select {
			(WineParticipantAssignment.questionnaire eq questionnaireId) and
					(WineParticipantAssignment.participant eq session.userId) }
				.count()

		WineParticipantAssignment
				.leftJoin(QuestionnaireWines, { wine }, { QuestionnaireWines.id })
				.slice(WineParticipantAssignment.id, WineParticipantAssignment.wine, WineParticipantAssignment.useAlternateWineCode, QuestionnaireWines.code1, QuestionnaireWines.code2)
				.select { (WineParticipantAssignment.questionnaire eq questionnaireId) and (WineParticipantAssignment.participant eq session.userId) }
				.limit(1).firstOrNull()?.let { row ->
					wineId = row[WineParticipantAssignment.wine]
					wineCode =  (if (row[WineParticipantAssignment.useAlternateWineCode]) row[QuestionnaireWines.code2] else row[QuestionnaireWines.code1])
					wineParticipantAssignmentId = row[WineParticipantAssignment.id].value
				}
	}

	if (wineCode == -1) {
		// huh??
		statusCode = StatusCodes.NOT_FOUND
		messageWarning("The questionnaire has been cancelled")
		home(session)
		return null
	}

	return QuestionnaireParticipation(questionnaireId, questionnaireName, session.userId,
			currentSection, currentWineOrder,
			wineCount, wineId,
			wineParticipantAssignmentId, wineCode,
			state, template)
}

/** Advance section and return new, updated participation.
 * Updates database. */
private fun QuestionnaireParticipation.advanceSection(): QuestionnaireParticipation? {
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
				it[currentSection] = 0
				it[currentWineOrder] = wineCount
			}
		}
		return QuestionnaireParticipation(questionnaireId, questionnaireName, participantId, 0, wineCount, wineCount, -1, -1, -1, QuestionnaireParticipationState.DONE, template)
	}

	var wineCode:Int = -1
	var wineParticipantAssignmentId = 0L

	transaction {
		QuestionnaireParticipants.update({ (QuestionnaireParticipants.participant eq participantId) and (QuestionnaireParticipants.questionnaire eq questionnaireId) }, 1) {
			it[currentSection] = newSection
			it[currentWineOrder] = newWineIndex
		}

		transaction {
			WineParticipantAssignment
					.leftJoin(QuestionnaireWines, { wine }, { QuestionnaireWines.id })
					.slice(WineParticipantAssignment.id, WineParticipantAssignment.useAlternateWineCode, QuestionnaireWines.code1, QuestionnaireWines.code2)
					.select { (WineParticipantAssignment.questionnaire eq questionnaireId) and (WineParticipantAssignment.participant eq participantId) }
					.limit(1).firstOrNull()?.let { row ->
						wineCode =  (if (row[WineParticipantAssignment.useAlternateWineCode]) row[QuestionnaireWines.code2] else row[QuestionnaireWines.code1])
						wineParticipantAssignmentId = row[WineParticipantAssignment.id].value
					}
		}
	}

	if (wineCode == -1) {
		return null
	}

	return QuestionnaireParticipation(questionnaireId, questionnaireName, participantId, newSection, newWineIndex, wineCount, wineId, wineParticipantAssignmentId, wineCode, state, template)
}

private fun renderTitle(title:List<QuestionnaireTemplate.Title>, lang:TemplateLang, tag: KFunction2<String?, (HTMLTag.() -> Unit), Unit>) {
	val (main, alt) = title.fullTitle(lang) ?: return

	tag(null) { unsafe { +main } }
	for (s in alt) {
		tag("alternate-lang") { unsafe { +s } }
	}
}

private fun renderText(text:List<QuestionnaireTemplate.Text>, lang:TemplateLang, tag: KFunction2<String?, (HTMLTag.() -> Unit), Unit>) {
	val html = text.mainText(lang) ?: return
	tag(null) {
		unsafe {
			+html
		}
	}
}

private fun HtmlBlockTag.renderQuestion(id:String, required:Boolean, type: QuestionnaireTemplate.QuestionType, lang:TemplateLang) {
	when (type) {
		is QuestionnaireTemplate.QuestionType.TimeVariable.OneOf -> renderQuestion(id, required, type, lang)
		is QuestionnaireTemplate.QuestionType.TimeVariable.Scale -> renderQuestion(id, required, type, lang)
		is QuestionnaireTemplate.QuestionType.FreeText -> renderQuestion(id, required, type, lang)
		is QuestionnaireTemplate.QuestionType.TimeProgression -> renderQuestion(id, required, type, lang)
	}
}

private fun HtmlBlockTag.renderQuestion(id:String, required:Boolean, type: QuestionnaireTemplate.QuestionType.FreeText, lang:TemplateLang) {
	when (type.type) {
		QuestionnaireTemplate.InputType.SENTENCE -> {
			numberInput(name = "$FORM_PARAM_QUESTION_RESPONSE_PREFIX$id") {
				this.required = required
				type.placeholder.mainText(lang)?.let { this.placeholder = it }
				type.default.mainText(lang)?.let { this.value = it }
			}
		}
		QuestionnaireTemplate.InputType.PARAGRAPH -> {
			TEXTAREA(attributesMapOf(
					"name", "$FORM_PARAM_QUESTION_RESPONSE_PREFIX$id",
					"required", required.toString(),
					"placeholder", type.placeholder.mainText(lang)), consumer).visit {
				type.default.mainText(lang)?.let { +it }
			}
		}
		QuestionnaireTemplate.InputType.NUMBER -> {
			textInput(name = "$FORM_PARAM_QUESTION_RESPONSE_PREFIX$id") {
				this.required = required
				type.placeholder.mainText(lang)?.let { this.placeholder = it }
				type.default.mainText(lang)?.let { this.value = it }
			}
		}
	}
}

private fun HtmlBlockTag.renderQuestion(id:String, required:Boolean, type: QuestionnaireTemplate.QuestionType.TimeProgression, lang:TemplateLang) {
	p { +"TimeVariable type not implemented yet" }
	//TODO
}

private fun HtmlBlockTag.renderQuestion(id:String, required:Boolean, type: QuestionnaireTemplate.QuestionType.TimeVariable.OneOf, lang:TemplateLang) {
	val name = "$FORM_PARAM_QUESTION_RESPONSE_PREFIX$id"
	val detailName = "$FORM_PARAM_QUESTION_RESPONSE_PREFIX$id-detail"
	for (category in type.categories) {
		renderTitle(category.title, lang, ::h3)

		div("container") {
			for (option in category.options) {
				div("row") {
					label {
						renderTitle(option.title, lang, ::p)
						radioInput(name=name) { this.required = required; value=option.value }

						if (option.hasDetail) {
							div("one-of-detail") {
								renderText(option.detail, lang, ::p)

								when (option.detailType) {
									QuestionnaireTemplate.InputType.SENTENCE -> {
										numberInput(name = detailName) {}
									}
									QuestionnaireTemplate.InputType.PARAGRAPH -> {
										TEXTAREA(attributesMapOf("name", detailName), consumer).visit {}
									}
									QuestionnaireTemplate.InputType.NUMBER -> {
										textInput(name = detailName) {}
									}
								}
							}
						}
					}
				}
			}
		}
	}
}

private fun HtmlBlockTag.renderQuestion(id:String, required:Boolean, type: QuestionnaireTemplate.QuestionType.TimeVariable.Scale, lang:TemplateLang) {
	val name = "$FORM_PARAM_QUESTION_RESPONSE_PREFIX$id"
	val optionCount = max(0, type.max - type.min + 1)
	val hasMinLabel = type.minLabel.isNotEmpty()
	val hasMaxLabel = type.maxLabel.isNotEmpty()

	div("question-scale") {
		style="display:grid; grid-template-columns: ${if(hasMinLabel) "auto" else ""} repeat($optionCount, 1fr) ${if(hasMaxLabel) "auto" else ""};"

		if (hasMinLabel) {
			div {
				style = "grid-row-start: 1; grid-row-end: 3;"
				renderTitle(type.minLabel, lang, ::p)
			}
		}

		for (i in type.min .. type.max) {
			div {
				+i.toString()
			}
		}

		if (hasMaxLabel) {
			div {
				style = "grid-row-start: 1; grid-row-end: 3;"
				renderTitle(type.maxLabel, lang, ::p)
			}
		}

		for (i in type.min .. type.max) {
			div {
				radioInput(name = name) { this.required=required; value = i.toString() }
			}
		}
	}
}

private fun handleQuestionnaireShow(exchange:HttpServerExchange, participation:QuestionnaireParticipation?, highlightMissing:Boolean = false) {
	if (participation == null || participation.currentWineIndex >= participation.wineCount || participation.state == QuestionnaireParticipationState.DONE) {
		exchange.questionnaireComplete()
		return
	}

	exchange.sendBase(participation.questionnaireName) { _, locale ->
		val section = participation.template.sections[participation.currentSection]
		val lang = TemplateLang(participation.template.defaultLanguage, locale)

		// Render title
		renderTitle(section.title, lang, ::h1)

		p("sub") { +"Wine: ${participation.wineCode}" }


		// Render questions
		postForm(action = "/questionnaire/${participation.questionnaireId}") {
			session(exchange.session()!!)
			routeAction(ACTION_SUBMIT_SECTION)
			hiddenInput(name = FORM_PARAM_WINE_ASSIGNMENT_ID) { value = participation.wineParticipantAssignmentId.toString(16) }

			for (sectionPart in section.content) {
				div("container section-part") {
					renderTitle(sectionPart.title, lang, ::h2)
					renderText(sectionPart.text, lang, ::p)

					when (sectionPart) {
						is QuestionnaireTemplate.SectionContent.Info -> {
						}
						is QuestionnaireTemplate.SectionContent.Question -> {
							renderQuestion(sectionPart.id, sectionPart.required, sectionPart.type, lang)
						}
					}
				}
			}

			submitInput { value="Next" }
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
			val wineAssignmentId = exchange.formString(FORM_PARAM_WINE_ASSIGNMENT_ID)?.toLongOrNull(16)
			if (participation.wineParticipantAssignmentId != wineAssignmentId) {
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

			try {
				// Also, mark the questionnaire as started, if needed
				QuestionnaireParticipants.update({
					(QuestionnaireParticipants.participant eq participation.participantId) and
							(QuestionnaireParticipants.questionnaire eq participation.questionnaireId) and
							(QuestionnaireParticipants.state eq QuestionnaireParticipationState.INVITED)
				}) {
					it[QuestionnaireParticipants.state] = QuestionnaireParticipationState.STARTED
				}
			} catch (e:Exception) {
				LOG.error("Failed to update participant state", e)
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
			val newParticipation = participation.advanceSection()
			handleQuestionnaireShow(exchange, newParticipation)
		} else {
			handleQuestionnaireShow(exchange, participation, highlightMissing = true)
		}
	}
}