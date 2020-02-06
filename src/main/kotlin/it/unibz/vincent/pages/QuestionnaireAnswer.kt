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
import it.unibz.vincent.template.QuestionnaireTemplate.Section
import it.unibz.vincent.template.TemplateLang
import it.unibz.vincent.util.GET
import it.unibz.vincent.util.POST
import it.unibz.vincent.util.formString
import it.unibz.vincent.util.formStrings
import it.unibz.vincent.util.merge
import it.unibz.vincent.util.pathString
import it.unibz.vincent.util.redirect
import it.unibz.vincent.util.toHumanReadableTime
import kotlinx.html.div
import kotlinx.html.h1
import kotlinx.html.h2
import kotlinx.html.hiddenInput
import kotlinx.html.id
import kotlinx.html.li
import kotlinx.html.ol
import kotlinx.html.p
import kotlinx.html.postForm
import kotlinx.html.span
import kotlinx.html.style
import kotlinx.html.submitInput
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.leftJoin
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import kotlin.math.max

private val LOG = LoggerFactory.getLogger("QuestionnaireAnswer")

private const val PATH_QUESTIONNAIRE_ID = "qId"
fun questionnaireAnswerPath(questionnaireId:Long):String {
	return "/questionnaire/$questionnaireId"
}
private const val QUESTIONNAIRE_ANSWER_PATH_TEMPLATE = "/questionnaire/{$PATH_QUESTIONNAIRE_ID}"

private const val ACTION_SUBMIT_SECTION = "submit-section"
private const val FORM_PARAM_WINE_SECTION_CHECKSUM = "section"

class WineSection(val sectionIndex:Int, val wineIndex:Int)

fun nextSection(currentSection:Int, currentWineIndex:Int, wineCount:Int, template:QuestionnaireTemplate):WineSection? {
	// Each wine defines one stage. If there are no wines, there is only one generic (wine-less) stage.
	val stageCount = max(1, wineCount)
	var firstSection = currentSection + 1

	// Wine index (stage) search
	for (wineIndex in currentWineIndex until stageCount) {
		val firstStage = wineIndex == 0
		val lastStage = wineIndex + 1 >= stageCount

		for (section in firstSection until template.sections.size) {
			val stage = template.sections[section].stage

			if (stage == Section.SectionStage.ALWAYS ||
					stage == Section.SectionStage.ONLY_FIRST && firstStage ||
					stage == Section.SectionStage.EXCEPT_FIRST && !firstStage ||
					stage == Section.SectionStage.ONLY_LAST && lastStage ||
					stage == Section.SectionStage.EXCEPT_LAST && !lastStage) {
				return WineSection(section, wineIndex)
			}
		}
		firstSection = 0
	}
	return null
}

private class QuestionnaireParticipation(val questionnaireId:Long,
                                         val questionnaireName:String,
                                         val participantId:Long,
                                         val currentSection:Int, val currentWineIndex:Int,
                                         val canAdvanceSectionAfter:Instant,
                                         val wineCount:Int,
                                         val wineId:Long,
                                         val wineCode:Int,
                                         val template:QuestionnaireTemplate) {
	val sectionChecksum:Int
		get() = currentSection * max(wineCount, 1) + currentWineIndex

	fun nextSection():WineSection? {
		return nextSection(currentSection, currentWineIndex, wineCount, template)
	}

	override fun toString(): String {
		return "QuestionnaireParticipation(questionnaireId=$questionnaireId, questionnaireName='$questionnaireName', participantId=$participantId, currentSection=$currentSection, currentWineIndex=$currentWineIndex, wineCount=$wineCount, wineId=$wineId, wineCode=$wineCode)"
	}
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
	var currentSectionStartedAt = Instant.EPOCH
	var currentWineIndex = 0

	var wineCount = 0
	var templateId:Long? = null
	var questionnaireName = ""
	var questionnaireState: QuestionnaireState = QuestionnaireState.CLOSED

	val state: QuestionnaireParticipationState? = transaction {
		wineCount = WineParticipantAssignment.select {
			(WineParticipantAssignment.questionnaire eq questionnaireId) and
					(WineParticipantAssignment.participant eq session.userId) }
				.count()

		QuestionnaireParticipants
				.leftJoin(Questionnaires, { questionnaire }, { Questionnaires.id })
				.slice(QuestionnaireParticipants.currentSection, QuestionnaireParticipants.currentSectionStartedAt, QuestionnaireParticipants.currentWineIndex, QuestionnaireParticipants.state, Questionnaires.template, Questionnaires.state, Questionnaires.name)
				.select { (QuestionnaireParticipants.participant eq session.userId) and (QuestionnaireParticipants.questionnaire eq questionnaireId) }
				.limit(1)
				.firstOrNull()?.let {
					currentSection = it[QuestionnaireParticipants.currentSection]
					currentSectionStartedAt = it[QuestionnaireParticipants.currentSectionStartedAt]
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
		redirect(HOME_PATH)
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

	if (state == QuestionnaireParticipationState.INVITED) {
		// Mark as started and set appropriate section
		val wineSection = nextSection(-1, 0, wineCount, template)
		val now = Instant.now()

		transaction {
			QuestionnaireParticipants.update({
				(QuestionnaireParticipants.participant eq session.userId) and
						(QuestionnaireParticipants.questionnaire eq questionnaireId) and
						(QuestionnaireParticipants.state eq QuestionnaireParticipationState.INVITED)
			}) {
				it[QuestionnaireParticipants.state] = if (wineSection == null) QuestionnaireParticipationState.DONE else QuestionnaireParticipationState.STARTED
				if (wineSection != null) {
					it[QuestionnaireParticipants.currentSection] = wineSection.sectionIndex
					it[QuestionnaireParticipants.currentWineIndex] = wineSection.wineIndex
					it[QuestionnaireParticipants.currentSectionStartedAt] = now
				}
			}
		}

		if (wineSection == null) {
			messageWarning("The questionnaire has no questions")
			home(session)
			return null
		}

		currentSection = wineSection.sectionIndex
		currentSectionStartedAt = now
		currentWineIndex = wineSection.wineIndex
	}

	var wineCode:Int = -1
	var wineId:Long = -1L

	if (wineCount > 0) {
		transaction {
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

	val minTimeSeconds = template.sections[currentSection].minTimeSeconds
	val canAdvanceToNextSectionAfter = if (minTimeSeconds > 0) {
		currentSectionStartedAt.plusSeconds(minTimeSeconds.toLong())
	} else {
		Instant.EPOCH
	}


	return QuestionnaireParticipation(questionnaireId, questionnaireName, session.userId,
			currentSection, currentWineIndex, canAdvanceToNextSectionAfter,
			wineCount, wineId, wineCode,
			template)
}

/** Advance section and return new, updated participation.
 * Updates database.
 * @return whether the whole questionnaire has been filled */
private fun QuestionnaireParticipation.advanceSection(): Boolean {
	val nextSection = nextSection()

	if (nextSection == null) {
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
			it[currentSection] = nextSection.sectionIndex
			it[currentWineIndex] = nextSection.wineIndex
			it[currentSectionStartedAt] = Instant.now()
		}
	}

	return false
}

private fun handleQuestionnaireShow(exchange:HttpServerExchange, participation:QuestionnaireParticipation) {
	val existingResponses = HashMap<String, String>()
	try {
		transaction {
			for (row in QuestionnaireResponses
					.slice(QuestionnaireResponses.questionId, QuestionnaireResponses.response)
					.select {
						(QuestionnaireResponses.questionnaire eq participation.questionnaireId) and
								(QuestionnaireResponses.participant eq participation.participantId) and
								(QuestionnaireResponses.wine eq participation.wineId)
					}) {
				existingResponses[row[QuestionnaireResponses.questionId]] = row[QuestionnaireResponses.response]
			}
		}
	} catch (e:Exception) {
		LOG.error("Failed to retrieve existing responses for {}", participation, e)
	}

	// TODO(jp): Highlight missing required responses
	val highlightMissing = existingResponses.isNotEmpty()

	exchange.sendBase(participation.questionnaireName) { _, locale ->
		val section = participation.template.sections[participation.currentSection]
		val lang = TemplateLang(participation.template.defaultLanguage, locale)

		div("page-container") {

			// Render title
			div("page-section") {
				renderTitle(section.title, lang, ::h1)

				renderMessages(exchange)

				when (if (participation.wineCount > 0) section.shownWine else QuestionnaireTemplate.Section.ShownWine.NONE) {
					QuestionnaireTemplate.Section.ShownWine.CURRENT -> {
						div("section-part wine-list") {
							span("wine-list-title") { +"Wine: " }
							span("wine-list-element-single") { +"${participation.wineCode}" }
						}
					}
					QuestionnaireTemplate.Section.ShownWine.NONE -> {}
					QuestionnaireTemplate.Section.ShownWine.ALL -> {
						div("section-part wine-list") {
							span("wine-list-title") { +"Wines" }
							div("wine-list-elements") {
								transaction {
									var alreadyDone = true
									for (row in WineParticipantAssignment
											.leftJoin(QuestionnaireWines, { wine }, { QuestionnaireWines.id })
											.slice(WineParticipantAssignment.wine, QuestionnaireWines.code)
											.select { (WineParticipantAssignment.questionnaire eq participation.questionnaireId) and (WineParticipantAssignment.participant eq participation.participantId) }
											.orderBy(WineParticipantAssignment.order)) {
										val wineCode = row[QuestionnaireWines.code]
										if (wineCode == participation.wineCode) {
											alreadyDone = false
											span("wine-list-element current") { +"$wineCode" }
										} else if (alreadyDone) {
											span("wine-list-element past") { +"$wineCode" }
										} else {
											span("wine-list-element future") { +"$wineCode" }
										}
									}
								}
							}
						}
					}
				}
			}

			// Render questions
			postForm(action = questionnaireAnswerPath(participation.questionnaireId)) {
				session(exchange.session()!!)
				routeAction(ACTION_SUBMIT_SECTION)
				hiddenInput(name = FORM_PARAM_WINE_SECTION_CHECKSUM) { value = participation.sectionChecksum.toString() }

				renderSectionContent(section.content, lang, existingResponses)

				val now = Instant.now()
				val canAdvanceSectionAfter = participation.canAdvanceSectionAfter
				if (canAdvanceSectionAfter > now) {
					div("section-part ticker-section") {
						this.id="section-count-down-container"
						span { +"Please wait before continuing" }

						// Render waiting timer
						noscript {
							span { +"Next section available ${canAdvanceSectionAfter.toHumanReadableTime(locale, relative = true)}" }
						}

						val haveToWaitSeconds = Duration.between(now, canAdvanceSectionAfter).seconds
						div("section-buttons-count-down") {
							this.id="section-count-down-ticker"
							this.style = "display: none;" // Shown through javascript
							this.attributes["seconds"] = haveToWaitSeconds.toString()

							+"%02d:%02d".format(haveToWaitSeconds / 60, haveToWaitSeconds % 60)
						}
					}
				}

				div("section-buttons") {
					this.id="section-buttons"
					// Hidden through javascript, if needed

					if (participation.nextSection() == null) {
						submitInput { value = "Finish" }
					} else {
						submitInput { value = "Next" }
					}
				}
			}
		}
	}
}

fun RoutingHandler.setupQuestionnaireAnswerRoutes() {

	GET(QUESTIONNAIRE_ANSWER_PATH_TEMPLATE, AccountType.NORMAL) { exchange ->
		val participation = exchange.questionnaireParticipation() ?: return@GET
		handleQuestionnaireShow(exchange, participation)
	}

	// Sent on next section button press
	POST(QUESTIONNAIRE_ANSWER_PATH_TEMPLATE, AccountType.NORMAL, ACTION_SUBMIT_SECTION) { exchange ->
		val participation = exchange.questionnaireParticipation() ?: return@POST

		// Check that the answer belongs to a correct assignment
		run {
			val requestedWineIndex = exchange.formString(FORM_PARAM_WINE_SECTION_CHECKSUM)?.toInt()
			if (participation.sectionChecksum != requestedWineIndex) {
				// Wrong part!
				exchange.messageWarning("Questionnaire must be answered in order")
				exchange.redirect(questionnaireAnswerPath(participation.questionnaireId))
				return@POST
			}
		}

		val minTimeSeconds = participation.template.sections[participation.currentSection].minTimeSeconds
		var tooSoon = false

		// Collect answers & check if all required questions are answered
		val sectionQuestionIds = participation.template.sections[participation.currentSection].questionIds
		val requiredQuestionIds = participation.template.sections[participation.currentSection].requiredQuestionIds
		val responses = exchange.formStrings(FORM_PARAM_QUESTION_RESPONSE_PREFIX).groupBy { it.first }.mapValues { it.value.joinToString("\n\n") { p -> p.second } }
		val alreadyPresentResponses:Set<String> = transaction {
			// Insert required responses
			for (questionId in sectionQuestionIds) {
				val response = responses[questionId]?.takeUnless { it.isBlank() } ?: continue

				QuestionnaireResponses.merge {
					it[QuestionnaireResponses.participant] = participation.participantId
					it[QuestionnaireResponses.questionnaire] = participation.questionnaireId
					it[QuestionnaireResponses.wine] = participation.wineId
					it[QuestionnaireResponses.questionId] = questionId
					it[QuestionnaireResponses.response] = response
				}
			}

			// Check if it is too soon
			if (minTimeSeconds > 0 && (QuestionnaireParticipants
							.slice(QuestionnaireParticipants.currentSectionStartedAt)
							.select { (QuestionnaireParticipants.questionnaire eq participation.questionnaireId) and
									(QuestionnaireParticipants.participant eq participation.participantId) }
							.limit(1)
							.firstOrNull()
							?.let { it[QuestionnaireParticipants.currentSectionStartedAt] } ?: Instant.EPOCH)
							.plusSeconds(minTimeSeconds - 1L /* 1 sec tolerance */) > Instant.now()) {
				tooSoon = true
			}

			if (tooSoon) {
				emptySet()
			} else {
				// Check for those IDs that are sufficiently filled
				QuestionnaireResponses.slice(QuestionnaireResponses.questionId).select {
					(QuestionnaireResponses.participant eq participation.participantId) and
							(QuestionnaireResponses.questionnaire eq participation.questionnaireId) and
							(QuestionnaireResponses.wine eq participation.wineId)
				}.mapTo(HashSet()) { it[QuestionnaireResponses.questionId] }
			}
		}

		if (tooSoon) {
			exchange.messageInfo("Please wait before advancing to next section")
		} else {
			// Check if all required questions are answered
			val missingResponses = requiredQuestionIds - alreadyPresentResponses
			if (missingResponses.isEmpty()) {
				if (participation.advanceSection()) {
					// Done
					exchange.redirect(HOME_PATH)
					return@POST
				}
			}
		}

		exchange.redirect(questionnaireAnswerPath(participation.questionnaireId))
	}
}