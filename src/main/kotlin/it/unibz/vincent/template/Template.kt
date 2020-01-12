package it.unibz.vincent.template

import com.ibm.icu.util.ULocale
import it.unibz.vincent.util.XmlBuilder
import java.time.Duration

private typealias Detail = QuestionnaireTemplate.Text
private typealias Placeholder = QuestionnaireTemplate.Text
private typealias Default = QuestionnaireTemplate.Text
private typealias Min = QuestionnaireTemplate.Title
private typealias Max = QuestionnaireTemplate.Title

class QuestionnaireTemplate(val defaultLanguage: ULocale, val title:List<Title>, val sections:List<Section>) {

	class Title(val text:String, val language:ULocale?, val always:Boolean)

	class Section(val title:List<Title>, val content:List<SectionContent>)

	sealed class SectionContent {
		class Info(val title:List<Title>, val text:List<Text>) : SectionContent()
		class Question(val id:String, val required:Boolean, val title:List<Title>, val text:List<Text>, val type:QuestionType) : SectionContent()
	}

	class Text(val text:String, val language:ULocale?)

	sealed class QuestionType {
		/** Can be presented repeatedly by [TimeProgression]. */
		abstract class TimeVariable : QuestionType()

		class OneOf(val categories:List<Category>) : TimeVariable()
		class FreeText(val type:InputType, val placeholder:List<Placeholder>, val default:List<Default>) : QuestionType()
		class Scale(val min:Int, val max:Int, val minLabel:List<Min>, val maxLabel:List<Max>) : TimeVariable()
		class TimeProgression(val interval: Duration, val repeats:Int, val base:TimeVariable) : QuestionType()
	}

	enum class InputType {
		SENTENCE,
		PARAGRAPH,
		NUMBER
	}

	class Category(val title:List<Title>, val options:List<Option>)
	class Option(val value:String, val hasDetail:Boolean, val detailType:InputType, val title:List<Title>, val detail:List<Detail>)

	override fun toString(): String {
		return XmlBuilder {
			build(this@QuestionnaireTemplate)
		}
	}
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
		is QuestionnaireTemplate.QuestionType.OneOf -> build(e)
		is QuestionnaireTemplate.QuestionType.FreeText -> build(e)
		is QuestionnaireTemplate.QuestionType.Scale -> build(e)
		is QuestionnaireTemplate.QuestionType.TimeProgression -> build(e)
	}
}

private fun XmlBuilder.build(e: QuestionnaireTemplate.QuestionType.OneOf) {
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
		for (text in e.default) {
			build(text, "default")
		}
	}
}

private fun XmlBuilder.build(e: QuestionnaireTemplate.QuestionType.Scale) {
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