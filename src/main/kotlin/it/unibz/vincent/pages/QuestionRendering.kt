package it.unibz.vincent.pages

import it.unibz.vincent.template.QuestionnaireTemplate
import it.unibz.vincent.template.TemplateLang
import it.unibz.vincent.template.fullTitle
import it.unibz.vincent.template.mainText
import it.unibz.vincent.template.mainTitle
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
import kotlinx.html.span
import kotlinx.html.radioInput
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

fun HtmlBlockTag.renderQuestion(id:String, required:Boolean, type: QuestionnaireTemplate.QuestionType, lang: TemplateLang,  idGenerator:()->String) {
	when (type) {
		is QuestionnaireTemplate.QuestionType.TimeVariable.OneOf -> renderQuestion(id, required, type, lang, idGenerator)
		is QuestionnaireTemplate.QuestionType.TimeVariable.Scale -> renderQuestion(id, required, type, lang)
		is QuestionnaireTemplate.QuestionType.FreeText -> renderQuestion(id, required, type, lang)
		is QuestionnaireTemplate.QuestionType.TimeProgression -> renderQuestion(id, required, type, lang)
	}
}

private fun HtmlBlockTag.renderQuestion(id:String, required:Boolean, type: QuestionnaireTemplate.QuestionType.FreeText, lang: TemplateLang) {
	when (type.type) {
		QuestionnaireTemplate.InputType.SENTENCE -> {
			textInput(name = "$FORM_PARAM_QUESTION_RESPONSE_PREFIX$id", classes="free-text") {
				this.required = required
				type.placeholder.mainText(lang)?.let { this.placeholder = it }
				type.default.mainText(lang)?.let { this.value = it }
			}
		}
		QuestionnaireTemplate.InputType.PARAGRAPH -> {
			TEXTAREA(attributesMapOf(
					"name", "$FORM_PARAM_QUESTION_RESPONSE_PREFIX$id",
					"required", required.toString(),
					"placeholder", type.placeholder.mainText(lang),
					"class", "free-text"), consumer).visit {
				type.default.mainText(lang)?.let { +it }
			}
		}
		QuestionnaireTemplate.InputType.NUMBER -> {
			numberInput(name = "$FORM_PARAM_QUESTION_RESPONSE_PREFIX$id", classes="free-text") {
				this.required = required
				type.placeholder.mainText(lang)?.let { this.placeholder = it }
				type.default.mainText(lang)?.let { this.value = it }
			}
		}
	}
}

private fun HtmlBlockTag.renderQuestion(id:String, required:Boolean, type: QuestionnaireTemplate.QuestionType.TimeProgression, lang: TemplateLang) {
	p { +"TimeVariable type not implemented yet" }
	//TODO
}

private fun HtmlBlockTag.renderQuestion(id:String, required:Boolean, type: QuestionnaireTemplate.QuestionType.TimeVariable.OneOf, lang: TemplateLang, idGenerator:()->String) {
	val name = "$FORM_PARAM_QUESTION_RESPONSE_PREFIX$id"
	val detailName = "$FORM_PARAM_QUESTION_RESPONSE_PREFIX$id-detail"
	for (category in type.categories) {
		renderTitle(category.title, lang, ::h4, mainClass = "one-of-category-title")
		val radioIds = ArrayList<String>()
		div("one-of-category") {
			for (option in category.options) {
				label("one-of-item") {
					renderTitle(option.title, lang, ::span, "one-of-item-title", "one-of-item-title-alt")
					radioInput(name=name, classes="one-of-detail-radio") {
						this.required = required
						if (option.hasDetail) {
							val newId = idGenerator()
							radioIds.add(newId)
							this.id = newId
						}
						value = option.value
					}
				}
			}
		}

		// Details
		var detailRadioIdI = 0
		for ((index, option) in category.options.withIndex()) {
			if (!option.hasDetail) {
				continue
			}

			div("one-of-detail") {
				attributes["oneOfDetailFor"] = radioIds[detailRadioIdI++]
				noscript("one-of-detail-noscript-when") {
					val main = option.title.mainTitle(lang) ?: "Option #$index"
					+"If you have picked "
					unsafe { +main }
				}
				renderText(option.detail, lang, ::span, "one-of-detail-title")

				when (option.detailType) {
					QuestionnaireTemplate.InputType.SENTENCE -> {
						textInput(name = detailName, classes = "one-of-detail-input") {}
					}
					QuestionnaireTemplate.InputType.PARAGRAPH -> {
						TEXTAREA(attributesMapOf("name", detailName, "class", "one-of-detail-input"), consumer).visit {}
					}
					QuestionnaireTemplate.InputType.NUMBER -> {
						numberInput(name = detailName, classes="one-of-detail-input") {}
					}
				}
			}
		}
	}
}

private fun HtmlBlockTag.renderQuestion(id:String, required:Boolean, type: QuestionnaireTemplate.QuestionType.TimeVariable.Scale, lang: TemplateLang) {
	val name = "$FORM_PARAM_QUESTION_RESPONSE_PREFIX$id"

	div("scale-parent") {
		if (type.minLabel.isNotEmpty()) {
			div("scale-min") {
				renderTitle(type.minLabel, lang, ::p)
			}
		}

		for (i in type.min .. type.max) {
			label("scale-item") {
				p {
					+i.toString()
				}
				radioInput(name = name) { this.required=required; value = i.toString() }
			}
		}

		if (type.maxLabel.isNotEmpty()) {
			div("scale-max") {
				renderTitle(type.maxLabel, lang, ::p)
			}
		}
	}
}