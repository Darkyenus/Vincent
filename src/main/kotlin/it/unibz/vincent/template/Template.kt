package it.unibz.vincent.template

import it.unibz.vincent.util.Failable
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.Locator
import org.xml.sax.SAXParseException
import org.xml.sax.helpers.DefaultHandler
import java.io.InputStream
import javax.xml.parsers.SAXParserFactory




class QuestionnaireTemplate {}

const val QUESTIONNAIRE_FPI = "-//UNIBZ//Vincent Questionnaire Template 1.0//EN"
const val QUESTIONNAIRE_FPI_URL = "vincent://questionnaire.dtd"

fun questionnaireDTDSource():InputSource {
	val source = InputSource(QuestionnaireTemplate::class.java.classLoader.getResourceAsStream("questionnaire.dtd")!!)
	source.encoding = Charsets.UTF_8.name()
	source.publicId = QUESTIONNAIRE_FPI
	source.systemId = QUESTIONNAIRE_FPI_URL
	return source
}

/**
 *
 */
fun parseTemplate(input: InputStream): Failable<QuestionnaireTemplate, String> {
	val spf = SAXParserFactory.newInstance()
	spf.isNamespaceAware = false
	spf.isValidating = true
	val saxParser = spf.newSAXParser()
	saxParser.parse(input, object : DefaultHandler() {

	})

	return Failable.failure("Not implemented yet")
}