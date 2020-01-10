package it.unibz.vincent.template

import org.junit.jupiter.api.Test
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.Locator
import org.xml.sax.SAXParseException
import org.xml.sax.helpers.DefaultHandler
import javax.xml.parsers.SAXParserFactory

/**
 *
 */
class TemplateTest {

	@Test
	fun saxParserEventExploration() {
		val spf = SAXParserFactory.newInstance()
		spf.isNamespaceAware = false
		spf.isValidating = false
		val saxParser = spf.newSAXParser()
		TemplateTest::class.java.classLoader.getResourceAsStream("questionnaire-test.xml").use {
			saxParser.parse(it, object : DefaultHandler() {

				override fun endElement(uri: String?, localName: String?, qName: String?) {
					println("endElement($uri, $localName, $qName)")
					super.endElement(uri, localName, qName)
				}

				override fun processingInstruction(target: String?, data: String?) {
					println("processingInstruction($target, $data)")
					super.processingInstruction(target, data)
				}

				override fun startPrefixMapping(prefix: String?, uri: String?) {
					println("startPrefixMapping($prefix, $uri)")
					super.startPrefixMapping(prefix, uri)
				}

				override fun ignorableWhitespace(ch: CharArray?, start: Int, length: Int) {
					//println("ignorableWhitespace(${ch?.copyOfRange(start, start + length)?.let { String(it) }})")
					super.ignorableWhitespace(ch, start, length)
				}

				override fun notationDecl(name: String?, publicId: String?, systemId: String?) {
					println("notationDecl($name, $publicId, $systemId)")
					super.notationDecl(name, publicId, systemId)
				}

				override fun error(e: SAXParseException?) {
					println("error($e)")
					super.error(e)
				}

				override fun characters(ch: CharArray?, start: Int, length: Int) {
					println("characters(${ch?.copyOfRange(start, start + length)?.let { String(it) }})")
					super.characters(ch, start, length)
				}

				override fun endDocument() {
					println("endDocument()")
					super.endDocument()
				}

				override fun resolveEntity(publicId: String?, systemId: String?): InputSource {
					println("resolveEntity($publicId, $systemId)")
					if (publicId == QUESTIONNAIRE_FPI || systemId == QUESTIONNAIRE_FPI_URL) {
						return questionnaireDTDSource()
					}
					return super.resolveEntity(publicId, systemId)
				}

				override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
					println("startElement($uri, $localName, $qName, $attributes)")
					super.startElement(uri, localName, qName, attributes)
				}

				override fun skippedEntity(name: String?) {
					println("skippedEntity($name)")
					super.skippedEntity(name)
				}

				override fun warning(e: SAXParseException?) {
					println("warning($e)")
					super.warning(e)
				}

				override fun unparsedEntityDecl(name: String?, publicId: String?, systemId: String?, notationName: String?) {
					println("unparsedEntityDecl($name, $publicId, $systemId, $notationName)")
					super.unparsedEntityDecl(name, publicId, systemId, notationName)
				}

				override fun setDocumentLocator(locator: Locator?) {
					println("setDocumentLocator($locator)")
					super.setDocumentLocator(locator)
				}

				override fun endPrefixMapping(prefix: String?) {
					println("endPrefixMapping($prefix)")
					super.endPrefixMapping(prefix)
				}

				override fun startDocument() {
					println("startDocument()")
					super.startDocument()
				}

				override fun fatalError(e: SAXParseException?) {
					println("fatalError($e)")
					super.fatalError(e)
				}
			})
		}
	}

}