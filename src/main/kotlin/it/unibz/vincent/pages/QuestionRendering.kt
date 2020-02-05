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
import kotlinx.html.h4
import kotlinx.html.id
import kotlinx.html.label
import kotlinx.html.numberInput
import kotlinx.html.p
import kotlinx.html.radioInput
import kotlinx.html.span
import kotlinx.html.textInput
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

fun HtmlBlockTag.renderQuestion(id:String, required:Boolean, type: QuestionnaireTemplate.QuestionType, lang: TemplateLang, existingResponses:Map<String, String>, idGenerator:()->String) {
	when (type) {
		is QuestionnaireTemplate.QuestionType.TimeVariable.OneOf -> renderQuestion(id, required, type, lang, existingResponses, idGenerator)
		is QuestionnaireTemplate.QuestionType.TimeVariable.Scale -> renderQuestion(id, required, type, lang, existingResponses)
		is QuestionnaireTemplate.QuestionType.FreeText -> renderQuestion(id, required, type, lang, existingResponses)
		is QuestionnaireTemplate.QuestionType.TimeProgression -> renderQuestion(id, required, type, lang, existingResponses)
	}
}

private fun HtmlBlockTag.renderQuestion(id:String, required:Boolean, type: QuestionnaireTemplate.QuestionType.FreeText, lang: TemplateLang, existingResponses:Map<String, String>) {
	val name = "$FORM_PARAM_QUESTION_RESPONSE_PREFIX$id"
	val value = existingResponses[id] ?: ""

	when (type.type) {
		QuestionnaireTemplate.InputType.SENTENCE -> {
			textInput(name = name, classes="free-text") {
				this.required = required
				this.value = value
				type.placeholder.mainText(lang)?.let { this.placeholder = it }
			}
		}
		QuestionnaireTemplate.InputType.PARAGRAPH -> {
			TEXTAREA(attributesMapOf(
					"name", name,
					"required", required.toString(),
					"placeholder", type.placeholder.mainText(lang),
					"class", "free-text"), consumer).visit {
				+value
			}
		}
		QuestionnaireTemplate.InputType.NUMBER -> {
			numberInput(name = name, classes="free-text") {
				this.required = required
				this.value = value
				type.placeholder.mainText(lang)?.let { this.placeholder = it }
			}
		}
	}
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
						this.required = required
						if (option.hasDetail) {
							val newId = idGenerator()
							radioIds.add(newId)
							this.id = newId
						}
						this.value = option.value
						if (checked == name) {
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
				val detailValue = existingResponses[detailId] ?: ""
				val detailName = "$FORM_PARAM_QUESTION_RESPONSE_PREFIX$detailId"
				when (option.detailType) {
					QuestionnaireTemplate.InputType.SENTENCE -> {
						textInput(name = detailName, classes = "one-of-detail-input") { this.value = detailValue }
					}
					QuestionnaireTemplate.InputType.PARAGRAPH -> {
						TEXTAREA(attributesMapOf("name", detailName, "class", "one-of-detail-input"), consumer).visit { +detailValue }
					}
					QuestionnaireTemplate.InputType.NUMBER -> {
						numberInput(name = detailName, classes="one-of-detail-input") { this.value = detailValue }
					}
				}
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
					this.required = required
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