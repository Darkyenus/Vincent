package it.unibz.vincent.pages

import com.ibm.icu.util.ULocale
import io.undertow.server.HttpServerExchange
import io.undertow.server.RoutingHandler
import it.unibz.vincent.AccountType
import it.unibz.vincent.DemographyInfo
import it.unibz.vincent.session
import it.unibz.vincent.template.QuestionnaireTemplate.*
import it.unibz.vincent.template.QuestionnaireTemplate.QuestionType.FreeText
import it.unibz.vincent.template.QuestionnaireTemplate.QuestionType.TimeVariable.OneOf
import it.unibz.vincent.template.QuestionnaireTemplate.SectionContent.Question
import it.unibz.vincent.template.TemplateLang
import it.unibz.vincent.template.collectQuestionIdsTo
import it.unibz.vincent.template.mainTitle
import it.unibz.vincent.util.GET
import it.unibz.vincent.util.POST
import it.unibz.vincent.util.formStrings
import it.unibz.vincent.util.merge
import it.unibz.vincent.util.redirect
import kotlinx.html.div
import kotlinx.html.h1
import kotlinx.html.p
import kotlinx.html.postForm
import kotlinx.html.submitInput
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory


private val LOG = LoggerFactory.getLogger("Demography")

const val DEMOGRAPHY_PATH = "/demography"

private const val YES = "yes"
private const val NO = "no"

const val QID_PHONE_NUMBER = "phone-number"
const val QID_GENDER = "gender"
const val QID_YEAR_OF_BIRTH = "year-of-birth"
const val QID_HOME_COUNTRY = "home-country"
const val QID_HOME_REGION = "home-region"
const val QID_EDUCATION = "education"
const val QID_SMOKING = "smoking"
const val QID_SMOKING_DETAIL = "$QID_SMOKING-detail-$YES"
const val QID_FOOD_INTOLERANCE = "food-intolerance"
const val QID_FOOD_INTOLERANCE_DETAIL = "$QID_FOOD_INTOLERANCE-detail-$YES"
const val QID_SULFITE_INTOLERANCE = "sulfite-intolerance"

private val demographyQuestions = listOf(
		Question(QID_PHONE_NUMBER, false,
				listOf(Title("Phone number")),
				listOf(Text("Only for emergencies")),
				FreeText(InputType.TELEPHONE, emptyList())),
		Question(QID_GENDER, true,
				listOf(Title("Gender")),
				emptyList(),
				OneOf(
						Option("female", Title("Female")),
						Option("male", Title("Male")),
						Option("other", Title("Other"))
				)),
		Question(QID_YEAR_OF_BIRTH, true,
				listOf(Title("Year of birth")),
				emptyList(),
				FreeText(InputType.YEAR, listOf(Text("1973")))),
		Question(QID_HOME_COUNTRY, true,
				listOf(Title("Home country")),
				emptyList(),
				FreeText(InputType.SENTENCE, listOf(Text("Italy")))),
		Question(QID_HOME_REGION, false,
				listOf(Title("Home country region")),
				listOf(Text("If commonly used.")),
				FreeText(InputType.SENTENCE, listOf(Text("Alto Adige")))),
		Question(QID_EDUCATION, true,
				listOf(Title("Highest completed education")),
				listOf(Text("See <a href=\"https://en.wikipedia.org/wiki/International_Standard_Classification_of_Education#2011_version\">Wikipedia</a> for more information about ISCED 2011 education levels.")),
				OneOf(
						Option("0-none", Title("None")),
						Option("1-primary", Title("Primary (ISCED 1, Scuola Elementare)")),
						Option("2-secondary", Title("Secondary (ISCED 2 or 3, Scuola Media/Superiore)")),
						Option("4-post-secondary", Title("Post-secondary (ISCED 4 or 5)")),
						Option("6-bachelor", Title("Bachelor or equivalent (ISCED 6, Laurea)")),
						Option("7-master", Title("Master or equivalent (ISCED 7, Laurea magistrale)")),
						Option("8-master", Title("Doctoral or equivalent (ISCED 8)")),
						Option("9-higher", Title("Higher"))
				)),
		Question(QID_SMOKING, true,
				listOf(Title("Do you smoke?")),
				listOf(),
				OneOf(
						Option(NO, Title("No")),
						Option(YES, true, InputType.SENTENCE, listOf(Title("Yes")), listOf(Text("How many times per day?")))
				)),
		Question(QID_FOOD_INTOLERANCE, true,
				listOf(Title("Do you have any <a href=\"https://en.wikipedia.org/wiki/Food_intolerance\">food intolerance</a> or <a href=\"https://en.wikipedia.org/wiki/Food_allergy\">food allergies</a>?")),
				emptyList(),
				OneOf(
						Option(NO, Title("No")),
						Option(YES, true, InputType.SENTENCE, listOf(Title("Yes")), listOf(Text("Which?")))
				)),
		Question(QID_SULFITE_INTOLERANCE, true,
				listOf(Title("Do you have sulfite intolerance?")),
				listOf(Text("Sulfites have a number of technological functions, including antioxidant, bleaching agent, flour treatment agent and preservative, and are used in a wide variety of applications in the food/wine industry. The Health World Organization committee established for sulfite of 0-0.7 mg/kg bw the limit. The level added in the wine it’s below this limit. For more information <a href=\"http://www.inchem.org/documents/jecfa/jecmono/v042je06.htm\">click here</a> or see <a href=\"https://en.wikipedia.org/wiki/Sulfite#Health_effects\">the Wikipedia article</a>.")),
				OneOf(
						Option(NO, Title("No")),
						Option(YES, Title("Yes"))
				)
		)
)

private val oneOfDemographyQuestions: Map<String, OneOf> = demographyQuestions.mapNotNull { q ->
	if (q.type !is OneOf) {
		null
	} else {
		q.id to q.type
	}
}.toMap()

fun demographicOneOfResponseToHumanReadableLabel(questionId:String, result:String, lang:TemplateLang):String? {
	return oneOfDemographyQuestions[questionId]?.let { oneOf ->
		for (category in oneOf.categories) {
			for (option in category.options) {
				if (option.value == result) {
					return@let option.title.mainTitle(lang)
				}
			}
		}
		null
	}
}

fun demographicYesNoToBool(result:String):Boolean? {
	return if (result.equals(YES, ignoreCase = true)) {
		true
	} else if (result.equals(NO, ignoreCase = true)) {
		false
	} else {
		LOG.warn("Got invalid YES/NO result '{}'", result)
		null
	}
}

private val demographyQuestionIds: List<String> = demographyQuestions.collectQuestionIdsTo(ArrayList(), false)
private val demographyRequiredQuestionIds: Set<String> = demographyQuestions.collectQuestionIdsTo(HashSet(), true)

/**
 *
 */
private fun showDemographyQuestionnaire(exchange: HttpServerExchange) {
	val session = exchange.session()!!

	val existingResponses: Map<String, String> = transaction {
		DemographyInfo
				.slice(DemographyInfo.questionId, DemographyInfo.response)
				.select { DemographyInfo.user eq session.userId }
				.associateByTo(HashMap(), { it[DemographyInfo.questionId] }, { it[DemographyInfo.response] })
	}

	exchange.sendBase("Demography info") { _, locale ->
		div("page-container") {

			div ("page-section") {
				if (existingResponses.isEmpty()) {
					h1 { +"Hello!" }
					p { +"Before we start, please fill out the following questions." }
				} else {
					h1 { +"Personal info" }
				}
			}

			renderMessages(exchange)

			div ("page-section") {
				postForm(action = DEMOGRAPHY_PATH) {
					session(session)
					renderSectionContent(demographyQuestions, TemplateLang(ULocale.ENGLISH, locale), existingResponses, existingResponses.isNotEmpty())

					div("section-buttons") {
						if (existingResponses.isEmpty()) {
							submitInput { value = "Finish" }
						} else {
							submitInput { value = "Save" }
						}
					}
				}
			}
		}
	}
}

/** Check if the given user has filled demography info sufficiently.
 * CALL IN A TRANSACTION
 * @see it.unibz.vincent.Session.hasDemographyFilledOut for cached version */
fun hasUserFilledDemographyInfoSufficiently(userId:Long):Boolean {
	val presentResponses = DemographyInfo
			.slice(DemographyInfo.questionId)
			.select { (DemographyInfo.user eq userId) }
			.mapTo(HashSet()) { it[DemographyInfo.questionId] }
	val missingResponses = demographyRequiredQuestionIds - presentResponses
	return missingResponses.isEmpty()
}

fun RoutingHandler.setupDemographyRoutes() {
	GET(DEMOGRAPHY_PATH, AccountType.NORMAL, requireCompletedDemography = false) { exchange ->
		showDemographyQuestionnaire(exchange)
	}

	POST(DEMOGRAPHY_PATH, AccountType.NORMAL) { exchange ->
		val session = exchange.session()!!

		// Save responses
		val responses = exchange.formStrings(FORM_PARAM_QUESTION_RESPONSE_PREFIX)
				.groupBy { it.first }
				.mapValues { it.value.joinToString("\n\n") { p -> p.second } }

		val presentResponses:Set<String> = transaction {
			// Insert required responses
			for (questionId in demographyQuestionIds) {
				val response = responses[questionId]?.takeUnless { it.isBlank() } ?: continue
				DemographyInfo.merge {
					it[DemographyInfo.user] = session.userId
					it[DemographyInfo.questionId] = questionId
					it[DemographyInfo.response] = response
				}
			}

			// Check for those IDs that are sufficiently filled
			DemographyInfo.slice(DemographyInfo.questionId).select { (DemographyInfo.user eq session.userId) }.mapTo(HashSet()) { it[DemographyInfo.questionId] }
		}

		val missingResponses = demographyRequiredQuestionIds - presentResponses
		if (missingResponses.isEmpty()) {
			// Everything is filled, redirect home
			session.flushCache()
			exchange.redirect(HOME_PATH)
		} else {
			// Some questions are missing
			exchange.messageWarning("Please fill out all required questions")
			exchange.redirect(DEMOGRAPHY_PATH)
		}
	}
}