package it.unibz.vincent.util

import com.google.common.cache.CacheBuilder
import com.google.common.cache.CacheLoader
import com.ibm.icu.util.ULocale
import io.undertow.server.DefaultResponseListener
import io.undertow.server.HttpHandler
import io.undertow.server.HttpServerExchange
import io.undertow.server.handlers.form.EagerFormParsingHandler
import io.undertow.server.handlers.form.FormDataParser
import io.undertow.server.handlers.form.FormEncodedDataDefinition
import io.undertow.server.handlers.form.FormParserFactory
import io.undertow.util.AttachmentKey
import io.undertow.util.Headers
import io.undertow.util.Methods
import io.undertow.util.PathTemplateMatch
import io.undertow.util.StatusCodes
import it.unibz.vincent.CSRF_FORM_TOKEN_NAME
import it.unibz.vincent.pages.base
import it.unibz.vincent.session
import kotlinx.html.HTML
import kotlinx.html.div
import kotlinx.html.h1
import kotlinx.html.html
import kotlinx.html.img
import kotlinx.html.stream.appendHTML
import kotlinx.html.style
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.io.OutputStreamWriter
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

private val LOG: Logger = LoggerFactory.getLogger("UndertowUtil")

// RoutingHandler parameter extractors
/** Retrieve non-empty trimmed string from path parameter with given name.
 * Fails the request if such parameter does not exist.
 *
 * Only usable in [io.undertow.server.HttpHandler] which are used after
 * [io.undertow.server.RoutingHandler] has set path parameters.  */
fun pathString(exchange: HttpServerExchange, name: String): String {
	val attachment = exchange.getAttachment(PathTemplateMatch.ATTACHMENT_KEY)
	if (attachment == null) {
		LOG.error("pathString({}) - no attachment", name)
		throw HttpResponseException(StatusCodes.INTERNAL_SERVER_ERROR, "Failure to parse path")
	}
	return trimToNullAndShorten(attachment.parameters[name], Int.MAX_VALUE)
			?: throw HttpResponseException(StatusCodes.BAD_REQUEST, "Path part '$name' is missing")
}

/** Retrieve integer path parameter.
 * @see pathString */
fun pathInt(exchange: HttpServerExchange, name: String): Int {
	return try {
		pathString(exchange, name).toInt()
	} catch (e: NumberFormatException) {
		throw HttpResponseException(StatusCodes.BAD_REQUEST, "Path part '$name' must be a number")
	}
}

/** Retrieve non-negative integer path parameter.
 * @see pathString */
fun pathIntNonNegative(exchange: HttpServerExchange, name: String): Int {
	val value = pathInt(exchange, name)
	if (value < 0) {
		throw HttpResponseException(StatusCodes.BAD_REQUEST, "Path part '$name' must be a non-negative number")
	}
	return value
}

/** Retrieve URL-encoded or query parameter with [name]. */
fun HttpServerExchange.formString(name: String): String? {
	queryParameters[name]?.firstOrNull()?.let { return it }

	return getAttachment(FormDataParser.FORM_DATA)?.get(name)?.find {
		it != null && !it.isFileItem
	}?.value
}

private val LanguageAttachment:AttachmentKey<LocaleStack> = AttachmentKey.create(List::class.java)
private val languageCache = CacheBuilder.newBuilder()
		.maximumSize(100)
		.build<String, ULocale>(object:CacheLoader<String, ULocale>() {
			override fun load(key: String): ULocale {
				return ULocale(key)
			}
		})

private fun HttpServerExchange.constructLanguages():LocaleStack {
	val languages = requestHeaders[Headers.ACCEPT_LANGUAGE]?.peekFirst()?.split(',') ?: return emptyList()
	class Weighted(val value:ULocale, val weight:Float) : Comparable<Weighted> {
		override fun compareTo(other: Weighted): Int = weight.compareTo(other.weight)
	}

	val weightedLanguages = ArrayList<Weighted>(languages.size)
	val weightSeparatorToken = ";q="

	for (language in languages) {
		val weightSeparator = language.indexOf(weightSeparatorToken[0])
		val rawLanguage:String
		var weight = 1f
		if (weightSeparator < 0) {
			rawLanguage = language
		} else {
			rawLanguage = language.substring(0, weightSeparator)
			try {
				weight = language.substring(weightSeparator + weightSeparatorToken.length).trim().toFloat()
			} catch (e:java.lang.NumberFormatException) {}
		}

		if ('*' in rawLanguage || weight <= 0f /* just in case */) {
			// Wildcard, we don't really care about that, not sure why is it even in the spec
			continue
		}

		val canonicalLanguage = ULocale.canonicalize(rawLanguage)
		if (canonicalLanguage.isEmpty()) {
			// Bogus language
			continue
		}

		weightedLanguages.add(Weighted(languageCache[canonicalLanguage], weight))
	}

	weightedLanguages.sortDescending()
	return weightedLanguages.map { it.value }
}

fun HttpServerExchange.languages():LocaleStack {
	getAttachment(LanguageAttachment)?.let { return it }
	val result = constructLanguages()
	putAttachment(LanguageAttachment, result)
	return result
}

fun HttpServerExchange.sendHtml(generateHtml: HTML.() -> Unit) {
	val byteOut = object : ByteArrayOutputStream(50_000) {
		fun byteBuffer():ByteBuffer {
			return ByteBuffer.wrap(this.buf, 0, this.count)
		}
	}
	OutputStreamWriter(byteOut, Charsets.UTF_8).use {
		it.append("<!DOCTYPE html>\n")
		it.appendHTML(prettyPrint = false).html {
			generateHtml.invoke(this)
		}
	}

	val byteBuffer = byteOut.byteBuffer()

	val responseHeaders = responseHeaders
	responseHeaders.put(Headers.CONTENT_LENGTH, byteBuffer.remaining().toString())
	responseHeaders.put(Headers.CONTENT_TYPE, "text/html; charset=UTF-8")
	responseSender.send(byteBuffer)
}

/** Wrap [handler] for extra functionality:
 * - make "urlencoded" content accessible
 * - make [HttpResponseException] work
 * - serve nice error pages */
fun wrapRootHandler(handler:HttpHandler): HttpHandler {
	val formParserFactory = FormParserFactory.builder(false)
			.addParser(FormEncodedDataDefinition())
			.withDefaultCharset(StandardCharsets.UTF_8.name())
			.build()
	val formParsingHandler = EagerFormParsingHandler(formParserFactory)
	formParsingHandler.next = HttpHandler { exchange ->
		val session = exchange.session()
		if (Methods.POST == exchange.requestMethod && session != null) {
			if (session.csrfToken != exchange.formString(CSRF_FORM_TOKEN_NAME)) {
				LOG.warn("POST request to {} by {} without CSRF token set!", exchange.requestPath, session)
				throw HttpResponseException(StatusCodes.BAD_REQUEST, "Request is incomplete")
			}
		}
		handler.handleRequest(exchange)
	}

	val defaultHandler = DefaultResponseListener { exchange ->
		if (exchange.isResponseStarted || !exchange.isResponseChannelAvailable) {
			return@DefaultResponseListener false
		}
		val exception = exchange.getAttachment(DefaultResponseListener.EXCEPTION)
		var status = exchange.statusCode
		var message: String? = null
		if (exception is HttpResponseException) {
			status = exception.code
			message = exception.customMessage
		}
		message = message ?: StatusCodes.getReason(status)!!


		exchange.sendHtml {
			base(title="Vincent - $message") {
				div("container") {
					style = "padding: 5%"

					img(alt="Tomás Giner: St. Vincent, deacon and martyr, with a donor",
							src="/internal/vincent.jpg", classes="u-centered") {
						style = "height: 384px; margin-bottom: 2rem"
					}
					h1("u-centered") { +"$status - $message" }
				}
			}
		}

		true
	}

	return HttpHandler { exchange: HttpServerExchange ->
		exchange.addDefaultResponseListener(defaultHandler)
		// To prevent click-jacking through frames
		// https://cheatsheetseries.owasp.org/cheatsheets/Clickjacking_Defense_Cheat_Sheet.html
		exchange.responseHeaders.put(Headers.CONTENT_SECURITY_POLICY, "frame-ancestors 'none'")
		exchange.responseHeaders.put(Headers.X_FRAME_OPTIONS, "DENY")
		formParsingHandler.handleRequest(exchange)
	}
}

/**
 * Thrown by [HttpHandler.handleRequest]
 * and caught by a handler wrapped by [wrapRootHandler] to set content and code
 * to what was thrown.
 *
 * @see StatusCodes
 */
class HttpResponseException(val code: Int,
		val customMessage: String? = StatusCodes.getReason(code)) : Exception()
