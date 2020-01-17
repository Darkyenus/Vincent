package it.unibz.vincent.template

import com.ibm.icu.util.ULocale
import it.unibz.vincent.util.XmlBuilder
import it.unibz.vincent.util.appendList
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.Locator
import org.xml.sax.SAXParseException
import org.xml.sax.helpers.AttributesImpl
import org.xml.sax.helpers.DefaultHandler
import java.io.InputStream
import java.nio.CharBuffer
import java.time.Duration
import javax.xml.parsers.SAXParserFactory
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty

const val QUESTIONNAIRE_FPI = "-//UNIBZ//Vincent Questionnaire Template 1.0//EN"
const val QUESTIONNAIRE_FPI_URL = "vincent://questionnaire.dtd"

fun questionnaireDTDSource(): InputSource {
	val source = InputSource(QuestionnaireTemplate::class.java.classLoader.getResourceAsStream("questionnaire.dtd")!!)
	source.encoding = Charsets.UTF_8.name()
	source.publicId = QUESTIONNAIRE_FPI
	source.systemId = QUESTIONNAIRE_FPI_URL
	return source
}

/** Parse a questionnaire template from given input. */
fun parseTemplate(input: InputStream): ParsedXml<QuestionnaireTemplate> {
	return saxParse(input, RootParseState("questionnaire", QuestionnaireParseState()))
}

class ParsedXml<T>(val result:T, val warnings:List<String>, val errors:List<String>)

private fun localizeMessage(message:String?, lineNumber:Int, columnNumber:Int):String {
	var msg = message
	if (msg == null || msg.isBlank()) {
		msg = "Parsing problem"
	}

	return if (lineNumber >= 0 && columnNumber >= 0) {
		"$msg (at line $lineNumber, column $columnNumber)"
	} else if (lineNumber >= 0) {
		"$msg (at line $lineNumber)"
	} else {
		msg
	}
}

private fun SAXParseException.toPrettyMessage():String = localizeMessage(message, lineNumber, columnNumber)

private fun <T> saxParse(input: InputStream, rootState:ParserState<T>): ParsedXml<T> {
	val spf = SAXParserFactory.newInstance()
	spf.isNamespaceAware = false
	spf.isValidating = false
	val saxParser = spf.newSAXParser()

	val warnings = ArrayList<String>()
	val errors = ArrayList<String>()

	val stateTag = ArrayList<String>()
	val state = ArrayList<ParserState<*>?>()

	try {
		saxParser.parse(input, object : DefaultHandler(), ParserContext {

			private var locator: Locator? = null

			private val lineNumber: Int
				get() = locator?.lineNumber ?: -1

			private val columnNumber: Int
				get() = locator?.columnNumber ?: -1

			override fun warning(message: String) {
				warnings.add(localizeMessage(message, lineNumber, columnNumber))
			}

			override fun error(message: String) {
				errors.add(localizeMessage(message, lineNumber, columnNumber))
			}

			override fun startDocument() {
				state.add(rootState)
			}

			override fun endDocument() {
				assert(stateTag.isEmpty())
				assert(state.size == 1)
				assert(state[0] == rootState)
				state.clear()
			}

			private fun elementName(uri: String?, localName: String?, qName: String?): String {
				if (localName != null && localName.isNotBlank()) {
					return localName
				}
				if (qName != null && qName.isNotBlank()) {
					return qName
				}
				if (uri != null && uri.isNotBlank()) {
					return uri
				}
				return "unknown"
			}

			private val noAttributes = AttributesImpl()

			override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
				val element = elementName(uri, localName, qName)
				val elementState = state.last()?.stateFor(this, element, attributes ?: noAttributes)
				elementState?.begin(this, element, attributes ?: noAttributes)
				state.add(elementState)
				stateTag.add(element)
			}


			override fun characters(ch: CharArray?, start: Int, length: Int) {
				ch ?: return
				state.last()?.content(this, ch, start, length)
			}

			override fun endElement(uri: String?, localName: String?, qName: String?) {
				val element = elementName(uri, localName, qName)
				assert(stateTag.last() == element)
				stateTag.removeAt(stateTag.lastIndex)
				state.removeAt(state.lastIndex)?.end(this, element)
			}


			//region Messages
			override fun warning(e: SAXParseException?) {
				warnings.add(e?.toPrettyMessage() ?: return)
			}

			override fun error(e: SAXParseException?) {
				errors.add(e?.toPrettyMessage() ?: return)
			}

			override fun fatalError(e: SAXParseException?) {
				throw e ?: return
			}
			//endregion

			override fun setDocumentLocator(locator: Locator?) {
				this.locator = locator
			}

			override fun resolveEntity(publicId: String?, systemId: String?): InputSource? {
				if (publicId == QUESTIONNAIRE_FPI || systemId == QUESTIONNAIRE_FPI_URL) {
					return questionnaireDTDSource()
				}
				return null
			}
		})
	} catch (e: SAXParseException) {
		errors.add(e.toPrettyMessage())
	}

	return ParsedXml(rootState.result(), warnings, errors)
}

interface ParserContext {
	fun warning(message:String)
	fun error(message:String)
}

private class VerbatimParserState : ParserState<CharSequence> {

	private val xmlBuilder = XmlBuilder(false)

	override fun stateFor(ctx:ParserContext, name: String, attributes: Attributes): VerbatimParserState = this@VerbatimParserState

	override fun begin(ctx:ParserContext, name: String, attributes: Attributes) {
		xmlBuilder.beginTag_begin(name)
		for (a in 0 until attributes.length) {
			xmlBuilder.beginTag_attr(attributes.getLocalName(a), attributes.getValue(a) ?: "")
		}
		xmlBuilder.beginTag_end()
	}

	override fun content(ctx:ParserContext, ch: CharArray, start: Int, length: Int) {
		xmlBuilder.content(CharBuffer.wrap(ch, start, length))
	}

	override fun end(ctx:ParserContext, element: String) {
		xmlBuilder.endTag()
	}

	fun hasContent():Boolean {
		return xmlBuilder.characters.isNotEmpty()
	}

	override fun result(): CharSequence = xmlBuilder.characters
}

private class TitleParserState : SequenceParserState<QuestionnaireTemplate.Title>() {

	private val lang:ULocale by langProperty("lang", ULocale.ROOT)
	private val always by boolProperty("always", false)
	private val content by fallbackVerbatim(true)

	override fun result(): QuestionnaireTemplate.Title {
		var lang:ULocale? = lang
		if (lang == ULocale.ROOT) {
			lang = null
		}

		return QuestionnaireTemplate.Title(content?.toString() ?: "", lang, always)
	}
}

private class TextParserState : SequenceParserState<QuestionnaireTemplate.Text>() {

	private val lang:ULocale by langProperty("lang", ULocale.ROOT)
	private val content by fallbackVerbatim(true)

	override fun result(): QuestionnaireTemplate.Text {
		var lang:ULocale? = lang
		if (lang == ULocale.ROOT) {
			lang = null
		}

		return QuestionnaireTemplate.Text(content?.toString() ?: "", lang)
	}
}

private abstract class AttributeParserState<R> : ParserState<R> {

	private interface Property<R, V> : ReadOnlyProperty<AttributeParserState<R>, V> {
		fun update(ctx:ParserContext, attributes:Attributes)
	}

	private val properties = ArrayList<Property<R, *>>()

	protected fun <T:Any> property(retriever:(ctx:ParserContext, Attributes) -> T): ReadOnlyProperty<AttributeParserState<R>, T> {
		val p = object : Property<R, T> {
			var result:T? = null

			override fun update(ctx:ParserContext, attributes: Attributes) {
				result = retriever(ctx, attributes)
			}

			override fun getValue(thisRef: AttributeParserState<R>, property: KProperty<*>): T {
				return result ?: throw AssertionError("Property not updated yet ($this)")
			}
		}
		properties.add(p)
		return p
	}

	protected fun langProperty(name:String, default:ULocale) : ReadOnlyProperty<AttributeParserState<R>, ULocale> {
		return property { ctx, attrs ->
			val text = attrs.get(name) ?: return@property default
			if (text.isBlank()) {
				ctx.warning("Blank attribute '$name' is ignored")
				return@property default
			}
			val canonical:ULocale = ULocale.createCanonical(text)
			if (canonical.name.isEmpty()) {
				ctx.warning("Language '$text' is invalid")
				return@property default
			}

			canonical
		}
	}

	protected fun boolProperty(name:String, default:Boolean) : ReadOnlyProperty<AttributeParserState<R>, Boolean> {
		return property { ctx, attrs ->
			val text = attrs.get(name) ?: return@property default
			if (text.equals("true", ignoreCase = true) || text.equals("yes", ignoreCase = true)) {
				return@property true
			}

			if (text.equals("false", ignoreCase = true) || text.equals("no", ignoreCase = true)) {
				return@property false
			}

			ctx.warning("Expected 'true' or 'false' for attribute '$name', but got '$text'. Using default: $default.")
			default
		}
	}

	protected fun intProperty(name:String, default:Int, min:Int = Int.MIN_VALUE, max:Int = Int.MAX_VALUE, required:Boolean = false) : ReadOnlyProperty<AttributeParserState<R>, Int> {
		return property { ctx, attrs ->
			val text = attrs.get(name) ?: return@property run {
				if (required) {
					ctx.error("Missing required parameter '$name'")
				}
				default
			}
			var number = try {
				text.toInt()
			} catch (e:NumberFormatException) {
				ctx.warning("Expected number for attribute '$name', but got '$text'. Using default: $default")
				return@property default
			}

			if (number < min) {
				number = min
				ctx.warning("Number for attribute '$name' can't be lesser than $min, using $min")
			} else if (number > max) {
				number = max
				ctx.warning("Number for attribute '$name' can't be greater than $max, using $max")
			}

			number
		}
	}

	protected fun <E:Enum<E>> enumProperty(name:String, default:E) : ReadOnlyProperty<AttributeParserState<R>, E> {
		return property { ctx, attrs ->
			val text = attrs.get(name) ?: return@property default
			try {
				return@property java.lang.Enum.valueOf(default.declaringClass, text.toUpperCase().replace(' ', '_').replace('-', '_').replace(" ", "")) as E
			} catch (e:IllegalArgumentException) {}

			ctx.error(StringBuilder()
					.append("Invalid value '").append(text).append("' of attribute ").append(name)
					.append(". Possible values are: ")
					.appendList(default.declaringClass.enumConstants.map { it.toString().toLowerCase() }, "'", "'", lastItemSeparator = " and ")
					.toString())

			default
		}
	}

	protected fun durationProperty(name:String, min:Duration, default: Duration, required:Boolean = false) : ReadOnlyProperty<AttributeParserState<R>, Duration> {
		return property { ctx, attrs ->
			val text = attrs.get(name) ?: run {
				if (required) {
					ctx.error("Missing required parameter '$name'")
				}
				return@property default
			}
			if (text.isBlank()) {
				ctx.warning("Blank attribute '$name' is ignored")
				return@property default
			}

			val number = try {
				text.toFloat()
			} catch (e:NumberFormatException) {
				ctx.warning("Expected number for attribute '$name', but got '$text'. Using default: $default")
				return@property default
			}

			if (!java.lang.Float.isFinite(number)) {
				ctx.warning("Non finite attribute '$name' is ignored")
				return@property default
			}

			val foundDuration = Duration.ofSeconds(number.toLong(), ((number % 1f) * 1000000000f).toLong())

			if (foundDuration < min) {
				ctx.warning("Duration specified in attribute '$name' is too short ($foundDuration), minimum is $min")
				return@property min
			}

			foundDuration
		}
	}


	protected fun stringProperty(name:String, default:String, required:Boolean = false) : ReadOnlyProperty<AttributeParserState<R>, String> {
		return property { ctx, attrs ->
			val text = attrs.get(name) ?: run {
				if (required) {
					ctx.error("Missing required parameter '$name'")
				}
				return@property default
			}
			if (text.isBlank()) {
				ctx.warning("Blank attribute '$name' is ignored")
				return@property default
			}
			text
		}
	}

	final override fun begin(ctx: ParserContext, name: String, attributes: Attributes) {
		for (property in properties) {
			property.update(ctx, attributes)
		}
	}

	protected companion object {
		fun Attributes.get(name:String):String? {
			var i = getIndex(name)
			if (i < 0) {
				i = getIndex("", name)
			}
			if (i >= 0) {
				return getValue(i)
			}
			return null
		}
	}
}

private abstract class SequenceParserState<R> : AttributeParserState<R>() {

	private val parts = ArrayList<Part<R, *>>()
	private var nextPart = 0
	private var partsEmpty = true

	private var fallbackVerbatimEnabled = false
	private var fallbackVerbatimEnabledExclusively = false
	private var fallbackVerbatimState = VerbatimParserState()

	private fun newPartState(part: Int, name: String): ParserState<*> {
		if (partsEmpty) {
			partsEmpty = false
		}
		return parts[part].addState(name)
	}

	protected fun <T> tag(tag:String, min:Int = 0, max:Int = Int.MAX_VALUE, generator:() -> ParserState<T>): ReadOnlyProperty<SequenceParserState<R>, List<T>> {
		val part = Part<R, T>(listOf(tag), min, max, listOf(generator), false)
		parts.add(part)
		return part
	}

	interface GroupBuilder<Base> {
		/** Parse [this] tag with [generator] generated parse state. */
		operator fun <T:Base> String.invoke(generator:() -> ParserState<T>)
	}

	/**
	 * @param exclusive Only one variant is possible, using one disables all others.
	 */
	protected fun <Base> group(min:Int = 0, max:Int = Int.MAX_VALUE, exclusive:Boolean = false, build:GroupBuilder<Base>.() -> Unit): ReadOnlyProperty<SequenceParserState<R>, List<Base>> {
		val tags = ArrayList<String>()
		val generators = ArrayList<() -> ParserState<Base>>()
		object : GroupBuilder<Base> {
			override fun <T : Base> String.invoke(generator: () -> ParserState<T>) {
				tags.add(this)
				generators.add(generator)
			}
		}.apply {
			build()
		}
		val part = Part<R, Base>(tags, min, max, generators, exclusive)
		parts.add(part)
		return part
	}

	/** When no other tag matches or content is encountered, collect everything "verbatim".
	 * Collects content and xml tags and converts it into a valid XML fragment [CharSequence].
	 * Result is `null` when no fallback content was encountered.
	 * @param exclusive if the fallback content is valid ONLY if there is no other content */
	protected fun fallbackVerbatim(exclusive:Boolean): ReadOnlyProperty<SequenceParserState<R>, CharSequence?> {
		fallbackVerbatimEnabled = true
		fallbackVerbatimEnabledExclusively = exclusive
		return object : ReadOnlyProperty<SequenceParserState<R>, CharSequence?> {
			override fun getValue(thisRef: SequenceParserState<R>, property: KProperty<*>): CharSequence? {
				return fallbackVerbatimState.result()
			}
		}
	}

	final override fun stateFor(ctx: ParserContext, name: String, attributes: Attributes): ParserState<*>? {
		// Find part to which this belongs
		// Scenarios:
		// 1. That part is of different type, we must put it further
		// 2. That part is full, we must put it further
		// When putting it further:
		// 1. There may be no good place for it at all
		// 2. There may be a good place for it, but it violates the min-constraint of some parts that were skipped
		if (nextPart >= parts.size) {
			if (fallbackVerbatimEnabled) {
				return fallbackVerbatimState
			}

			ctx.error("No more tags expected, but got <$name>")
			return null
		}

		if (parts[nextPart].goodFor(name) && parts[nextPart].canFitOneMore()) {
			// No problem at all, just put it here
			return newPartState(nextPart, name)
		}

		// Putting it further
		var newNextPart = nextPart + 1
		while (newNextPart < parts.size && !parts[newNextPart].goodFor(name)) {
			newNextPart++
		}

		if (newNextPart >= parts.size) {
			if (fallbackVerbatimEnabled) {
				return fallbackVerbatimState
			}

			val possibleParts = ArrayList<String>()
			for (i in nextPart until parts.size) {
				if (parts[i].canFitOneMore()) {
					possibleParts.addAll(parts[i].possibleTags())
				}
				if (!parts[i].isSufficientlyFull()) {
					break
				}
			}

			if (possibleParts.isEmpty()) {
				nextPart = parts.size

				ctx.error("No more tags expected, but got <$name>")
				return null
			}

			ctx.error(StringBuilder()
					.append("Unexpected tag <").append(name).append(">, waiting for: ")
					.appendList(possibleParts, "<", ">", lastItemSeparator = " or ")
					.toString())
			return null
		}

		// Check if all skipped tags are sufficiently filled
		checkIfSufficientlyFull(ctx, nextPart, newNextPart)

		// Create the part generator
		nextPart = newNextPart
		return newPartState(newNextPart, name)
	}

	private fun checkIfSufficientlyFull(ctx:ParserContext, from:Int, to:Int) {
		for (i in from until to) {
			if (!parts[i].isSufficientlyFull()) {
				val missing = parts[i].missing()
				ctx.error(StringBuilder()
						.append("Expected ").append(missing).append(" more ")
						.appendList(parts[i].possibleTags(), "<", ">", lastItemSeparator = " or ")
						.append(" tag").append(if (missing == 1) "" else "s")
						.toString())
			}
		}
	}

	final override fun content(ctx: ParserContext, ch: CharArray, start: Int, length: Int) {
		if (!fallbackVerbatimEnabled) {
			super.content(ctx, ch, start, length)
			return
		}

		fallbackVerbatimState.content(ctx, ch, start, length)
	}

	final override fun end(ctx: ParserContext, element: String) {
		if (fallbackVerbatimEnabledExclusively && !partsEmpty && fallbackVerbatimState.hasContent()) {
			val possibleTags = parts.flatMap { it.possibleTags() }.distinct()
			ctx.error(StringBuilder()
					.append("Mixing fallback content with tag").append(if(possibleTags.size == 1) "" else "s")
					.append(' ').appendList(possibleTags, "<", ">", lastItemSeparator = " or ")
					.append(" is not allowed")
					.toString())
		}

		if (fallbackVerbatimEnabledExclusively && fallbackVerbatimState.hasContent()) {
			return
		}

		checkIfSufficientlyFull(ctx, nextPart, parts.size)
	}

	private class Part<R, T>(private val tags:List<String>, private val min:Int, private val max:Int, private val generators:List<() -> ParserState<T>>, private val exclusive:Boolean) : ReadOnlyProperty<SequenceParserState<R>, List<T>> {
		private val states = ArrayList<ParserState<T>>()
		private var committedToIndex = -1

		private fun tagIndex(tag:String):Int = tags.indexOfFirst { it.equals(tag, ignoreCase = true) }

		fun possibleTags():List<String> {
			return if (committedToIndex < 0) {
				tags
			} else {
				listOf(tags[committedToIndex])
			}
		}

		fun addState(tag:String):ParserState<T> {
			val index = tagIndex(tag)
			if (exclusive) {
				committedToIndex = index
			}
			val newState = generators[index]()
			states.add(newState)
			return newState
		}

		fun goodFor(tagName:String):Boolean {
			val index = tagIndex(tagName)
			if (index < 0) {
				return false
			}
			if (committedToIndex >= 0 && index != committedToIndex) {
				// Already commited to something else
				return false
			}
			return true
		}

		fun canFitOneMore():Boolean {
			return states.size + 1 <= max
		}

		fun isSufficientlyFull():Boolean {
			return states.size >= min
		}

		fun missing():Int = maxOf(min - states.size, 0)

		override fun getValue(thisRef: SequenceParserState<R>, property: KProperty<*>): List<T> = states.map { it.result() }
	}
}

private class InfoParserState : SequenceParserState<QuestionnaireTemplate.SectionContent.Info>() {

	private val title by tag("title") { TitleParserState() }
	private val text by tag("text") { TextParserState() }

	override fun result():QuestionnaireTemplate.SectionContent.Info {
		return QuestionnaireTemplate.SectionContent.Info(title, text)
	}
}

private class QuestionParserState : SequenceParserState<QuestionnaireTemplate.SectionContent.Question>() {

	private val id:String by stringProperty("id", "invalid-id", required = true)
	private val required by boolProperty("required", true)

	private val title by tag("title") { TitleParserState() }
	private val text by tag("text") { TextParserState() }
	private val body by group<QuestionnaireTemplate.QuestionType>(min = 1, max = 1) {
		"one-of" { OneOfParserState() }
		"free-text" { FreeTextParserState() }
		"scale" { ScaleParserState() }
		"time-progression" { TimeProgressionParserState() }
	}

	override fun result():QuestionnaireTemplate.SectionContent.Question {
		return QuestionnaireTemplate.SectionContent.Question(id, required, title, text, body.firstOrNull()
				?: QuestionnaireTemplate.QuestionType.FreeText(QuestionnaireTemplate.InputType.SENTENCE, emptyList(), emptyList()))
	}
}

private class OptionParserState : SequenceParserState<QuestionnaireTemplate.Option>() {
	private val value by stringProperty("value", "")
	private val detailEnabled by boolProperty("detail", false)
	private val detailType by enumProperty("detail-type", QuestionnaireTemplate.InputType.SENTENCE)

	private val title by tag("title", min=1) { TitleParserState() }
	private val detailPrompt by tag("detail") { TextParserState() }

	private val titleFallback:CharSequence? by fallbackVerbatim(true)

	override fun result(): QuestionnaireTemplate.Option {
		val title = if (title.isNotEmpty()) {
			title
		} else titleFallback?.let { listOf(QuestionnaireTemplate.Title(it.toString(), null, false)) } ?: emptyList()

		val value = this.value.takeUnless<String?> { it.isNullOrBlank() }
				?: title.find { it.language == null }?.text
				?: title.firstOrNull()?.text
				?: "invalid-value"

		return QuestionnaireTemplate.Option(value, detailEnabled, detailType, title, detailPrompt)
	}
}

private class CategoryParserState : SequenceParserState<QuestionnaireTemplate.Category>() {
	private val title by tag("title") { TitleParserState() }
	private val option by tag("option", min=1) { OptionParserState() }

	override fun result(): QuestionnaireTemplate.Category {
		return QuestionnaireTemplate.Category(title, option)
	}
}

private class OneOfParserState : SequenceParserState<QuestionnaireTemplate.QuestionType.TimeVariable.OneOf>() {

	private val categories by group<Any>(min = 1, exclusive = true) {
		"category" { CategoryParserState() }
		"option" { OptionParserState() }
	}

	override fun result(): QuestionnaireTemplate.QuestionType.TimeVariable.OneOf {
		val categoriesOrOptions = categories
		if (categoriesOrOptions.isEmpty()) {
			return QuestionnaireTemplate.QuestionType.TimeVariable.OneOf(emptyList())
		}

		val fallbackCategoryOptions = ArrayList<QuestionnaireTemplate.Option>()
		val categories = ArrayList<QuestionnaireTemplate.Category>()

		for (categoryOrOption in categoriesOrOptions) {
			when (categoryOrOption) {
				is QuestionnaireTemplate.Category -> categories.add(categoryOrOption)
				is QuestionnaireTemplate.Option -> fallbackCategoryOptions.add(categoryOrOption)
				else -> throw AssertionError("Broken logic: got $categoryOrOption")
			}
		}

		if (fallbackCategoryOptions.isNotEmpty()) {
			categories.add(QuestionnaireTemplate.Category(emptyList(), fallbackCategoryOptions))
		}

		return QuestionnaireTemplate.QuestionType.TimeVariable.OneOf(categories)
	}
}

private class FreeTextParserState : SequenceParserState<QuestionnaireTemplate.QuestionType.FreeText>() {

	private val type by enumProperty("type", QuestionnaireTemplate.InputType.SENTENCE)

	private val placeholder by tag("placeholder") { TextParserState() }
	private val default by tag("default") { TextParserState() }

	override fun result(): QuestionnaireTemplate.QuestionType.FreeText {
		return QuestionnaireTemplate.QuestionType.FreeText(type, placeholder, default)
	}
}

private class MinMaxParserState : SequenceParserState<List<QuestionnaireTemplate.Title>?>() {

	private val title by tag("title") { TitleParserState() }
	private val fallback by fallbackVerbatim(true)

	override fun result(): List<QuestionnaireTemplate.Title>? {
		val title = title
		if (title.isNotEmpty()) {
			return title
		}
		val fallback = fallback?.toString()?.trim()
		if (fallback == null || fallback.isEmpty()) {
			return null
		}
		return listOf(QuestionnaireTemplate.Title(fallback, null, false))
	}
}

private class ScaleParserState : SequenceParserState<QuestionnaireTemplate.QuestionType.TimeVariable.Scale>() {
	private val min by intProperty("min", 1)
	private val max by intProperty("max", 7)

	private val minDescription by tag("min", min=0, max=1) { MinMaxParserState() }
	private val maxDescription by tag("max", min=0, max=1) { MinMaxParserState() }

	override fun result(): QuestionnaireTemplate.QuestionType.TimeVariable.Scale {
		return QuestionnaireTemplate.QuestionType.TimeVariable.Scale(min, max, minDescription.filterNotNull().flatten(), maxDescription.filterNotNull().flatten())
	}
}

private val MIN_INTERVAL = Duration.ofSeconds(1L)
private val DEFAULT_INTERVAL = Duration.ofSeconds(10L)

private class TimeProgressionParserState : SequenceParserState<QuestionnaireTemplate.QuestionType.TimeProgression>() {
	private val interval by durationProperty("interval", MIN_INTERVAL, DEFAULT_INTERVAL, required = true)
	private val repeats by intProperty("repeats", 10, min = 2, required=true)

	private val base by group<QuestionnaireTemplate.QuestionType.TimeVariable>(min=1, max=1) {
		"one-of" { OneOfParserState() }
		"scale" { ScaleParserState() }
	}

	override fun result(): QuestionnaireTemplate.QuestionType.TimeProgression {
		return QuestionnaireTemplate.QuestionType.TimeProgression(interval, repeats, base.firstOrNull()
				?: QuestionnaireTemplate.QuestionType.TimeVariable.Scale(1, 3, emptyList(), emptyList()))
	}
}

private class SectionParserState : SequenceParserState<QuestionnaireTemplate.Section>() {

	private val titles by tag("title", min=1) { TitleParserState() }
	private val body by group<QuestionnaireTemplate.SectionContent>(min=1) {
		"info" { InfoParserState() }
		"question" { QuestionParserState() }
	}

	override fun result(): QuestionnaireTemplate.Section {
		return QuestionnaireTemplate.Section(titles, body)
	}
}

private class QuestionnaireParseState : SequenceParserState<QuestionnaireTemplate>() {

	private val defaultLang: ULocale by langProperty("default-lang", ULocale.ENGLISH)
	private val titles: List<QuestionnaireTemplate.Title> by tag("title", min=1) { TitleParserState() }
	private val sections: List<QuestionnaireTemplate.Section> by tag("section", min=1) { SectionParserState() }

	override fun result(): QuestionnaireTemplate {
		return QuestionnaireTemplate(defaultLang, titles, sections)
	}
}

private class RootParseState<T>(private val rootTag:String, private val rootParser:ParserState<T>) : ParserState<T> {

	override fun stateFor(ctx: ParserContext, name: String, attributes: Attributes): ParserState<*>? {
		if (name.equals(rootTag, ignoreCase = true)) {
			return rootParser
		}

		ctx.error("Invalid root tag <$name>, expected <$rootTag>")
		return null
	}

	override fun begin(ctx: ParserContext, name: String, attributes: Attributes) {
		throw AssertionError("Root should not begin")
	}

	override fun end(ctx: ParserContext, element: String) {
		throw AssertionError("Root should not end")
	}

	override fun result(): T = rootParser.result()
}


private interface ParserState<out T> {

	fun stateFor(ctx:ParserContext, name:String, attributes: Attributes):ParserState<*>? {
		ctx.warning("Tag <$name> ignored")
		return null
	}

	fun begin(ctx:ParserContext, name:String, attributes: Attributes) {}

	fun content(ctx:ParserContext, ch: CharArray, start: Int, length: Int) {
		ctx.warning("Text ignored")
	}

	fun end(ctx:ParserContext, element: String) {}

	fun result():T
}