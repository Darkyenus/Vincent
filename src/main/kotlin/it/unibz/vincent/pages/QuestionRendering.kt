package it.unibz.vincent.pages

import it.unibz.vincent.template.QuestionnaireTemplate
import it.unibz.vincent.template.TemplateLang
import it.unibz.vincent.template.fullTitle
import it.unibz.vincent.template.mainText
import kotlinx.html.HTMLTag
import kotlinx.html.HtmlBlockTag
import kotlinx.html.TEXTAREA
import kotlinx.html.attributesMapOf
import kotlinx.html.div
import kotlinx.html.h2
import kotlinx.html.h4
import kotlinx.html.id
import kotlinx.html.input
import kotlinx.html.label
import kotlinx.html.p
import kotlinx.html.radioInput
import kotlinx.html.span
import kotlinx.html.title
import kotlinx.html.unsafe
import kotlinx.html.visit
import kotlin.reflect.KFunction2

const val FORM_PARAM_QUESTION_RESPONSE_PREFIX = "r-"

fun renderTitle(title:List<QuestionnaireTemplate.Title>, lang: TemplateLang, tag: KFunction2<String?, (HTMLTag.() -> Unit), Unit>, mainClass:String? = null, altClass:String? = "alternate-lang") {
	val (main, alt) = title.fullTitle(lang) ?: return

	tag(mainClass) { unsafe { +main } }
	for (s in alt) {
		tag(altClass) { unsafe { +s } }
	}
}

fun renderText(text:List<QuestionnaireTemplate.Text>, lang: TemplateLang, tag: KFunction2<String?, (HTMLTag.() -> Unit), Unit>, mainClass:String? = null) {
	val html = text.mainText(lang) ?: return
	tag(mainClass) {
		unsafe {
			+html
		}
	}
}

private fun HtmlBlockTag.renderInput(name:String, type: QuestionnaireTemplate.InputType, required:Boolean, classes:String?, value:String?, placeholder:String?) {
	if (type.inputType != null) {
		input(name = name, type = type.inputType, classes=classes) {
			if (required) {
				this.required = true
			}
			if (value != null) {
				this.value = value
			}
			if (placeholder != null) {
				this.placeholder = placeholder
			}
			if (type == QuestionnaireTemplate.InputType.YEAR) {
				minLength = "4"
				//maxLength = "4" can be confusing when there is some random whitespace
				pattern = "\\s*\\d{4}\\s*" // Only digits (possibly surrounded by whitespace)
				title = "A full 4 digit year of the Gregorian calendar"
			}
		}
	} else {
		assert(type == QuestionnaireTemplate.InputType.PARAGRAPH)
		TEXTAREA(attributesMapOf(
				"name", name,
				"placeholder", placeholder,
				"class", classes), consumer).visit {
			if (required) {
				this.required = true
			}
			if (value != null) {
				+value
			}
		}
	}
}

fun HtmlBlockTag.renderSectionContent(content:List<QuestionnaireTemplate.SectionContent>, lang:TemplateLang, existingResponses:Map<String, String>, highlightMissingResponses:Boolean) {
	var idGeneratorNumber = 0
	val idGenerator: () -> String = {
		"q-${idGeneratorNumber++}"
	}

	for (sectionPart in content) {
		var missingRequired = false
		if (highlightMissingResponses && sectionPart is QuestionnaireTemplate.SectionContent.Question && sectionPart.required) {
			// Check if missing
			sectionPart.type.collectQuestionIds(sectionPart.id, true) { requiredId ->
				if (requiredId !in existingResponses) {
					missingRequired = true
				}
			}
		}

		div(if (missingRequired) "section-part section-part-required" else "section-part") {
			if (missingRequired) {
				span("question-required") { this.title="This question is mandatory"; +"Required" }
			} else {
				if (sectionPart is QuestionnaireTemplate.SectionContent.Question && !sectionPart.required) {
					span("question-optional") { this.title="You do not have to answer this question"; +"Optional" }
				}
			}

			renderTitle(sectionPart.title, lang, ::h2)
			renderText(sectionPart.text, lang, ::p)

			if (sectionPart is QuestionnaireTemplate.SectionContent.Question) {
				renderQuestion(sectionPart.id, sectionPart.required, sectionPart.type, lang, existingResponses, idGenerator)
			}
		}
	}
}

fun HtmlBlockTag.renderQuestion(id:String, required:Boolean, type: QuestionnaireTemplate.QuestionType, lang: TemplateLang, existingResponses:Map<String, String>, idGenerator:()->String) {
	when (type) {
		is QuestionnaireTemplate.QuestionType.TimeVariable.OneOf -> renderQuestion(id, required, type, lang, existingResponses, idGenerator)
		is QuestionnaireTemplate.QuestionType.TimeVariable.Scale -> renderQuestion(id, required, type, lang, existingResponses)
		is QuestionnaireTemplate.QuestionType.FreeText -> renderQuestion(id, required, type, lang, existingResponses)
		is QuestionnaireTemplate.QuestionType.TimeProgression -> renderQuestion(id, required, type, lang, existingResponses)
	}
}

private fun HtmlBlockTag.renderQuestion(id:String, required:Boolean, type: QuestionnaireTemplate.QuestionType.FreeText, lang: TemplateLang, existingResponses:Map<String, String>) {
	renderInput("$FORM_PARAM_QUESTION_RESPONSE_PREFIX$id",
			type.type, required, "free-text",
			existingResponses[id], type.placeholder.mainText(lang))
}

private fun HtmlBlockTag.renderQuestion(id:String, required:Boolean, type: QuestionnaireTemplate.QuestionType.TimeProgression, lang: TemplateLang, existingResponses:Map<String, String>) {
	p { +"TimeVariable type not implemented yet" }
	//TODO
}

private fun HtmlBlockTag.renderQuestion(id:String, required:Boolean, type: QuestionnaireTemplate.QuestionType.TimeVariable.OneOf, lang: TemplateLang, existingResponses:Map<String, String>, idGenerator:()->String) {
	val name = "$FORM_PARAM_QUESTION_RESPONSE_PREFIX$id"
	val checked = existingResponses[id]

	for (category in type.categories) {
		renderTitle(category.title, lang, ::h4, mainClass = "one-of-category-title")
		val radioIds = ArrayList<String>()
		div("one-of-category") {
			for (option in category.options) {
				label("one-of-item") {
					val (main, alt) = option.title.fullTitle(lang) ?: "Option" to emptyList()

					span("one-of-item-title") {
						unsafe { +main }
						if (option.hasDetail) {
							noscript("one-of-item-noscript-index") {
								+"#${radioIds.size}"
							}
						}
					}
					for (s in alt) {
						span("one-of-item-title-alt") { unsafe { +s } }
					}

					radioInput(name=name, classes="one-of-detail-radio") {
						if (required) {
							this.required = true
						}
						if (option.hasDetail) {
							val newId = idGenerator()
							radioIds.add(newId)
							this.id = newId
						}
						this.value = option.value
						if (checked == option.value) {
							this.checked = true
						}
					}
				}
			}
		}

		// Details
		if (radioIds.isEmpty()) {
			continue
		}

		var detailRadioIdI = 0
		for (option in category.options) {
			if (!option.hasDetail) {
				continue
			}

			div("one-of-detail") {
				attributes["oneOfDetailFor"] = radioIds[detailRadioIdI++]
				noscript("one-of-detail-noscript-index") {
					+"#$detailRadioIdI"
				}
				renderText(option.detail, lang, ::span, "one-of-detail-title")

				val detailId = "$id-detail-${option.value}"
				val existingDetail = existingResponses[detailId]
				renderInput("$FORM_PARAM_QUESTION_RESPONSE_PREFIX$detailId",
						option.detailType, false, "one-of-detail-input",
						existingDetail, null)
			}
		}
	}
}

private fun HtmlBlockTag.renderQuestion(id:String, required:Boolean, type: QuestionnaireTemplate.QuestionType.TimeVariable.Scale, lang: TemplateLang, existingResponses:Map<String, String>) {
	val picked = existingResponses[id]
	val name = "$FORM_PARAM_QUESTION_RESPONSE_PREFIX$id"

	div("scale-parent") {
		if (type.minLabel.isNotEmpty()) {
			div("scale-min") {
				renderTitle(type.minLabel, lang, ::p)
			}
		}

		for (i in type.min .. type.max) {
			val value = i.toString()
			label("scale-item") {
				p { +value }
				radioInput(name = name) {
					if (required) {
						this.required = true
					}
					this.value = value
					if (picked == value) {
						this.checked = true
					}
				}
			}
		}

		if (type.maxLabel.isNotEmpty()) {
			div("scale-max") {
				renderTitle(type.maxLabel, lang, ::p)
			}
		}
	}
}