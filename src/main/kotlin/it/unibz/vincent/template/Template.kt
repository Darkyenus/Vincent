package it.unibz.vincent.template

import com.ibm.icu.util.LocaleMatcher
import com.ibm.icu.util.ULocale
import it.unibz.vincent.util.LocaleStack
import it.unibz.vincent.util.XmlBuilder
import java.time.Duration

private typealias Detail = QuestionnaireTemplate.Text
private typealias Placeholder = QuestionnaireTemplate.Text
private typealias Min = QuestionnaireTemplate.Title
private typealias Max = QuestionnaireTemplate.Title

fun <T : MutableCollection<String>> List<QuestionnaireTemplate.SectionContent>.collectQuestionIdsTo(to:T, onlyRequired:Boolean):T {
	for (content in this) {
		if (content is QuestionnaireTemplate.SectionContent.Question) {
			if (onlyRequired && !content.required) {
				continue
			}
			content.type.collectQuestionIds(content.id, onlyRequired) { to.add(it) }
		}
	}
	return to
}

class QuestionnaireTemplate(val defaultLanguage: ULocale, val title:List<Title>, val sections:List<Section>) {

	class Title constructor(val text:String, val language:ULocale? = null, val always:Boolean = false)

	class Section(val minTimeSeconds:Int, val stage:SectionStage, val shownWine:ShownWine, val title:List<Title>, val content:List<SectionContent>) {

		val questionIds:List<String> by lazy(LazyThreadSafetyMode.PUBLICATION) {
			content.collectQuestionIdsTo(ArrayList(), false)
		}

		val requiredQuestionIds:Set<String> by lazy(LazyThreadSafetyMode.PUBLICATION) {
			content.collectQuestionIdsTo(HashSet(), true)
		}

		enum class SectionStage {
			ALWAYS,
			ONLY_FIRST,
			EXCEPT_FIRST,
			ONLY_LAST,
			EXCEPT_LAST
		}

		enum class ShownWine {
			CURRENT,
			NONE,
			ALL
		}
	}

	sealed class SectionContent(val title:List<Title>, val text:List<Text>) {
		class Info(title:List<Title>, text:List<Text>) : SectionContent(title, text)
		class Question(val id:String, val required:Boolean, title:List<Title>, text:List<Text>, val type:QuestionType) : SectionContent(title, text)
	}

	class Text(val text:String, val language:ULocale? = null)

	sealed class QuestionType {

		/**
		 * Collect through [collect] collector all question IDs (used as html input names and output column names).
		 * @param baseId derived from [SectionContent.Question.id] and possibly other factors
		 */
		open fun collectQuestionIds(baseId:String, onlyRequired:Boolean, collect:(String)->Unit) {
			collect(baseId)
		}

		/** Can be presented repeatedly by [TimeProgression]. */
		sealed class TimeVariable : QuestionType() {
			class OneOf(val categories:List<Category>) : TimeVariable() {

				constructor(vararg options:Option):this(listOf(Category(emptyList(), options.toList())))

				override fun collectQuestionIds(baseId: String, onlyRequired:Boolean, collect: (String) -> Unit) {
					super.collectQuestionIds(baseId, onlyRequired, collect)
					if (!onlyRequired) {
						for (category in categories) {
							for (option in category.options) {
								if (option.hasDetail) {
									collect("$baseId-detail-${option.value}")
								}
							}
						}
					}
				}
			}

			class Scale(val min:Int, val max:Int, val minLabel:List<Min>, val maxLabel:List<Max>) : TimeVariable()
		}

		class FreeText(val type:InputType, val placeholder:List<Placeholder>) : QuestionType()

		class TimeProgression(val interval: Duration, val repeats:Int, val base:TimeVariable) : QuestionType() {
			override fun collectQuestionIds(baseId: String, onlyRequired:Boolean, collect: (String) -> Unit) {
				for (i in 0 until repeats) {
					base.collectQuestionIds("$i-$baseId", onlyRequired, collect)
				}
			}
		}
	}

	enum class InputType(val inputType:kotlinx.html.InputType?) {
		SENTENCE(kotlinx.html.InputType.text),
		NUMBER(kotlinx.html.InputType.number),
		YEAR(kotlinx.html.InputType.text),
		TELEPHONE(kotlinx.html.InputType.tel),
		DATE(kotlinx.html.InputType.date),
		PARAGRAPH(null)
	}

	class Category(val title:List<Title>, val options:List<Option>)
	class Option(value:String, val hasDetail:Boolean, val detailType:InputType, val title:List<Title>, val detail:List<Detail>) {
		constructor(value:String, vararg title:Title):this(value, false, InputType.SENTENCE, title.toList(), emptyList())

		val value:String = sanitizeOptionValue(value)
	}

	val questionIds:List<String> by lazy(LazyThreadSafetyMode.PUBLICATION) {
		val result = ArrayList<String>()
		for (section in sections) {
			section.content.collectQuestionIdsTo(result, false)
		}
		result
	}

	override fun toString(): String {
		return XmlBuilder {
			build(this@QuestionnaireTemplate)
		}
	}
}

private val SANITIZE_WHITESPACE = Regex("\\s")
private val SANITIZE_FOREIGN = Regex("[\"\']")
private fun sanitizeOptionValue(value:String):String {
	return value.replace(SANITIZE_WHITESPACE, "-").replace(SANITIZE_FOREIGN, "")
}

private fun XmlBuilder.build(e: QuestionnaireTemplate.Title, tagName:String = "title") {
	tagName("lang" to e.language, "always" to e.always.takeIf { it }) {
		content(e.text, escape=false)
	}
}

private fun XmlBuilder.build(e: QuestionnaireTemplate.Text, tagName:String = "text") {
	tagName("lang" to e.language) {
		content(e.text, escape=false)
	}
}

private fun XmlBuilder.build(e: QuestionnaireTemplate.Section) {
	"section"{
		for (title in e.title) {
			build(title)
		}
		for (content in e.content) {
			build(content)
		}
	}
}

private fun XmlBuilder.build(e: QuestionnaireTemplate.SectionContent) {
	when (e) {
		is QuestionnaireTemplate.SectionContent.Info -> build(e)
		is QuestionnaireTemplate.SectionContent.Question -> build(e)
	}
}

private fun XmlBuilder.build(e: QuestionnaireTemplate.SectionContent.Info) {
	"info" {
		for (title in e.title) {
			build(title)
		}
		for (text in e.text) {
			build(text)
		}
	}
}

private fun XmlBuilder.build(e: QuestionnaireTemplate.SectionContent.Question) {
	"question"("id" to e.id, "required" to e.required) {
		for (title in e.title) {
			build(title)
		}
		for (text in e.text) {
			build(text)
		}
		build(e.type)
	}
}

private fun XmlBuilder.build(e: QuestionnaireTemplate.QuestionType) {
	when (e) {
		is QuestionnaireTemplate.QuestionType.TimeVariable.OneOf -> build(e)
		is QuestionnaireTemplate.QuestionType.TimeVariable.Scale -> build(e)
		is QuestionnaireTemplate.QuestionType.FreeText -> build(e)
		is QuestionnaireTemplate.QuestionType.TimeProgression -> build(e)
	}
}

private fun XmlBuilder.build(e: QuestionnaireTemplate.QuestionType.TimeVariable.OneOf) {
	"one-of" {
		for (category in e.categories) {
			build(category)
		}
	}
}

private fun XmlBuilder.build(e: QuestionnaireTemplate.QuestionType.FreeText) {
	"free-text"("type" to e.type) {
		for (text in e.placeholder) {
			build(text, "placeholder")
		}
	}
}

private fun XmlBuilder.build(e: QuestionnaireTemplate.QuestionType.TimeVariable.Scale) {
	"scale"("min" to e.min, "max" to e.max) {
		for (title in e.minLabel) {
			build(title, "min")
		}
		for (title in e.maxLabel) {
			build(title, "max")
		}
	}
}

private fun XmlBuilder.build(e: QuestionnaireTemplate.QuestionType.TimeProgression) {
	"time-progression"("interval" to e.interval.seconds.toFloat() + e.interval.nano.toFloat() / 1_000_000_000f, "repeats" to e.repeats) {
		build(e.base)
	}
}

private fun XmlBuilder.build(e: QuestionnaireTemplate.Category) {
	"category" {
		for (title in e.title) {
			build(title)
		}
		for (option in e.options) {
			build(option)
		}
	}
}

private fun XmlBuilder.build(e: QuestionnaireTemplate.Option) {
	"option"("value" to e.value, "detail" to e.hasDetail, "detail-type" to e.detailType) {
		for (title in e.title) {
			build(title)
		}
		for (text in e.detail) {
			build(text, "detail")
		}
	}
}

private fun XmlBuilder.build(e:QuestionnaireTemplate) {
	"questionnaire"("default-lang" to e.defaultLanguage) {
		for (title in e.title) {
			build(title)
		}
		for (section in e.sections) {
			build(section)
		}
	}
}

class TemplateLang(val default:ULocale, val preferred:LocaleStack)

fun List<QuestionnaireTemplate.Title>.mainTitle(languages:TemplateLang):String? {
	if (this.isEmpty()) {
		return null
	}
	val builder = LocaleMatcher.builder()
	for (title in this) {
		builder.addSupportedULocale(title.language ?: languages.default)
	}
	builder.setDefaultULocale(languages.default)
	val bestMatch = builder.build().getBestMatch(languages.preferred)
	return this.find { (it.language ?: languages.default) == bestMatch }?.text ?: this.first().text
}

fun List<QuestionnaireTemplate.Text>.mainText(languages:TemplateLang):String? {
	if (this.isEmpty()) {
		return null
	}
	val builder = LocaleMatcher.builder()
	for (title in this) {
		builder.addSupportedULocale(title.language ?: languages.default)
	}
	builder.setDefaultULocale(languages.default)
	val bestMatch = builder.build().getBestMatch(languages.preferred)
	return this.find { (it.language ?: languages.default) == bestMatch }?.text ?: this.first().text
}

fun List<QuestionnaireTemplate.Title>.fullTitle(languages:TemplateLang):Pair<String, List<String>>? {
	if (this.isEmpty()) {
		return null
	}
	val builder = LocaleMatcher.builder()
	for (title in this) {
		builder.addSupportedULocale(title.language ?: languages.default)
	}
	builder.setDefaultULocale(languages.default)
	val bestMatch = builder.build().getBestMatch(languages.preferred)
	val main = this.find { (it.language ?: languages.default) == bestMatch }?.text ?: this.first().text
	val always = this.mapNotNull { if (it.always && it.text != main) it.text else null }
	return main to always
}