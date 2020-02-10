package it.unibz.vincent.util

import com.google.common.cache.CacheBuilder
import com.google.common.cache.CacheLoader
import com.ibm.icu.util.ULocale
import io.undertow.predicate.Predicate
import io.undertow.server.DefaultResponseListener
import io.undertow.server.HttpHandler
import io.undertow.server.HttpServerExchange
import io.undertow.server.RoutingHandler
import io.undertow.server.handlers.form.EagerFormParsingHandler
import io.undertow.server.handlers.form.FormData
import io.undertow.server.handlers.form.FormDataParser
import io.undertow.server.handlers.form.FormEncodedDataDefinition
import io.undertow.server.handlers.form.FormParserFactory
import io.undertow.server.handlers.form.MultiPartParserDefinition
import io.undertow.util.AttachmentKey
import io.undertow.util.Headers
import io.undertow.util.Methods
import io.undertow.util.PathTemplateMatch
import io.undertow.util.StatusCodes
import it.unibz.vincent.AccountType
import it.unibz.vincent.BRAND_NAME
import it.unibz.vincent.CSRF_FORM_TOKEN_NAME
import it.unibz.vincent.IDEMPOTENCY_FORM_TOKEN_NAME
import it.unibz.vincent.pages.DEMOGRAPHY_PATH
import it.unibz.vincent.pages.base
import it.unibz.vincent.pages.loginRegister
import it.unibz.vincent.pages.messageWarning
import it.unibz.vincent.session
import kotlinx.html.ButtonType
import kotlinx.html.FlowContent
import kotlinx.html.HTML
import kotlinx.html.a
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.h1
import kotlinx.html.html
import kotlinx.html.img
import kotlinx.html.onClick
import kotlinx.html.p
import kotlinx.html.stream.appendHTML
import kotlinx.html.style
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.io.OutputStreamWriter
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.StandardCharsets

private val LOG: Logger = LoggerFactory.getLogger("UndertowUtil")

// RoutingHandler parameter extractors
/** Retrieve non-empty trimmed string from path parameter with given name.
 * Fails the request if such parameter does not exist.
 *
 * Only usable in [io.undertow.server.HttpHandler] which are used after
 * [io.undertow.server.RoutingHandler] has set path parameters.  */
fun HttpServerExchange.pathString(name: String): String {
	val attachment = getAttachment(PathTemplateMatch.ATTACHMENT_KEY)
	if (attachment == null) {
		LOG.error("pathString({}) - no attachment", name)
		throw HttpResponseException(StatusCodes.INTERNAL_SERVER_ERROR, "Failure to parse path")
	}
	return trimToNullAndShorten(attachment.parameters[name], Int.MAX_VALUE)
			?: throw HttpResponseException(StatusCodes.BAD_REQUEST, "Path part '$name' is missing")
}

/** Retrieve URL-encoded or query parameter with [name]. */
fun HttpServerExchange.formString(name: String): String? {
	queryParameters[name]?.firstOrNull()?.let { return it }

	return getAttachment(FormDataParser.FORM_DATA)?.get(name)?.find {
		it != null && !it.isFileItem
	}?.value
}

/** Retrieve [formString]s which start with given prefix.
 * Keys may appear multiple times.
 * @return list of those strings, <name without the prefix, content> */
fun HttpServerExchange.formStrings(prefix: String): List<Pair<String, String>> {
	val result = ArrayList<Pair<String, String>>()

	for ((k, v) in queryParameters) {
		if (k.startsWith(prefix, ignoreCase = true)) {
			val canonicalKey = k.substring(prefix.length)
			for (value in v) {
				result.add(canonicalKey to value)
			}
		}
	}

	val formDataAttachment = getAttachment(FormDataParser.FORM_DATA)
	if (formDataAttachment != null) {
		for (k in formDataAttachment) {
			if (k.startsWith(prefix, ignoreCase = true)) {
				val canonicalKey = k.substring(prefix.length)
				for (value in formDataAttachment.get(k)) {
					if (!value.isFileItem) {
						result.add(canonicalKey to value.value)
					}
				}
			}
		}
	}

	return result
}

/** Retrieve uploaded file with [name]. */
fun HttpServerExchange.formFile(name:String): FormData.FileItem? {
	return getAttachment(FormDataParser.FORM_DATA)?.get(name)?.find {
		it != null && it.isFileItem
	}?.fileItem
}

private const val MB = 1L shl 20
const val MAX_UPLOAD_FILE_SIZE = 500 * MB
private const val UPLOAD_FILE_MEMORY_THRESHOLD = 20 * MB

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

private fun HttpServerExchange.sendPageOfDisapproval(code:Int, title:String, message: FlowContent.() -> Unit) {
	statusCode = code
	sendHtml {
		base(title="$BRAND_NAME - $title") {
			div("page-container") {
				style = "padding: 5%"

				a(href="https://en.wikipedia.org/wiki/Vincent_of_Saragossa") {
					img(alt="Tomás Giner: St. Vincent, deacon and martyr, with a donor",
							src="/internal/vincent.jpg", classes="u-centered") {
						style = "height: 384px; margin-bottom: 2rem"
					}
				}

				h1("u-centered") {
					p { message() }
					button(type = ButtonType.button) { onClick="javascript:history.back()"; +"Back" }
				}
			}
		}
	}
}

fun HttpServerExchange.redirect(path:String, code:Int = StatusCodes.SEE_OTHER) {
	statusCode = code
	responseHeaders.put(Headers.LOCATION, path)
}

/** Wrap [handler] for extra functionality:
 * - make "urlencoded" content accessible
 * - make [HttpResponseException] work
 * - serve nice error pages */
fun wrapRootHandler(handler:HttpHandler): HttpHandler {
	val formParserFactory = FormParserFactory.builder(false)
			.withDefaultCharset(StandardCharsets.UTF_8.name())
			.addParser(FormEncodedDataDefinition())
			.addParser(MultiPartParserDefinition(null).apply {
				maxIndividualFileSize = MAX_UPLOAD_FILE_SIZE
				setFileSizeThreshold(UPLOAD_FILE_MEMORY_THRESHOLD)
			})
			.build()
	val formParsingHandler = EagerFormParsingHandler(formParserFactory)
	formParsingHandler.next = handler

	val defaultHandler = DefaultResponseListener { exchange ->
		if (exchange.isResponseStarted || !exchange.isResponseChannelAvailable) {
			return@DefaultResponseListener false
		}
		val exception = exchange.getAttachment(DefaultResponseListener.EXCEPTION)
		var status = exchange.statusCode
		if (status/100 == 3 && exchange.responseHeaders.contains(Headers.LOCATION)) {
			// All that is needed
			return@DefaultResponseListener false
		}

		var message: String? = null
		if (exception is HttpResponseException) {
			status = exception.code
			message = exception.customMessage
		}
		message = message ?: StatusCodes.getReason(status)!!

		exchange.sendPageOfDisapproval(status, message) { +"$status - $message" }

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

const val ROUTE_ACTION_PARAM_NAME = "action"

private fun KHttpHandler.handleAuthenticated(accessLevel: AccountType?, checkCsrf: Boolean):HttpHandler {
	if (accessLevel == null) {
		return HttpHandler { this(it) }
	} else {
		return HttpHandler { exchange ->
			val session = exchange.session()
			if (session == null) {
				exchange.statusCode = StatusCodes.FORBIDDEN
				exchange.messageWarning("Please log-in first")
				exchange.loginRegister()
			} else if (session.accountType < accessLevel) {
				// This guy is fired
				exchange.sendPageOfDisapproval(StatusCodes.FORBIDDEN, "Forbidden") {
					+"You don't have a permission to do that"
				}
			} else if (checkCsrf && session.csrfToken != exchange.formString(CSRF_FORM_TOKEN_NAME)) {
				// This guy brought shame and disgrace on his family name for generations to come
				LOG.warn("POST request to {} by {} without CSRF token set!", exchange.requestPath, session)
				exchange.sendPageOfDisapproval(StatusCodes.FORBIDDEN, "Forbidden") {
					+"You really don't have a permission to do that"
				}
			} else {
				this(exchange)
			}
		}
	}
}

private fun routeActionPredicate(action:String):Predicate {
	return Predicate { exchange ->
		action.equals(exchange.formString(ROUTE_ACTION_PARAM_NAME), ignoreCase = true)
	}
}

// SAM Conversion crutch, TODO: Remove after upgrading to Kotlin 1.4
typealias KHttpHandler = (HttpServerExchange) -> Unit

/**
 * Handle a GET request.
 *
 * @param requireCompletedDemography if the request is authenticated, and the user has not filled the demography survey yet, redirect to that page instead
 */
@Suppress("FunctionName")
fun RoutingHandler.GET(template:String, accessLevel: AccountType? = null, routeAction:String? = null, requireCompletedDemography:Boolean = accessLevel != null, handler:KHttpHandler) {
	val authHandler = handler.handleAuthenticated(accessLevel, false)
	val demographyHandler = if (requireCompletedDemography) {
		HttpHandler { exchange ->
			val session = exchange.session()
			if (session != null && session.accountType == AccountType.NORMAL && !session.hasDemographyFilledOut) {
				exchange.redirect(DEMOGRAPHY_PATH)
			} else {
				authHandler.handleRequest(exchange)
			}
		}
	} else authHandler
	if (routeAction == null) {
		get(template, demographyHandler)
	} else {
		get(template, routeActionPredicate(routeAction), demographyHandler)
	}
}

/**
 * Handle a POST request.
 *
 * If this request does not fail in such a way that it would make sense to repeat the request (bad request, bad state, etc.),
 * redirect instead. Especially on success.
 */
@Suppress("FunctionName")
fun RoutingHandler.POST(template:String, accessLevel: AccountType? = null, routeAction:String? = null, handler:KHttpHandler) {
	val idempotentHandler:KHttpHandler = if (accessLevel == null) handler else { exchange ->
		val session = exchange.session()!!
		if (session.useIdempotencyToken(exchange.formString(IDEMPOTENCY_FORM_TOKEN_NAME))) {
			handler(exchange)
		} else {
			// Failed idempotency check, demote to GET
			exchange.requestMethod = Methods.GET
			this@POST.handleRequest(exchange)
		}
	}
	val authHandler = idempotentHandler.handleAuthenticated(accessLevel, true)
	if (routeAction == null) {
		post(template, authHandler)
	} else {
		post(template, routeActionPredicate(routeAction), authHandler)
	}
}

fun contentDispositionAttachment(fileName:String):String {
	val sb = StringBuilder()
	sb.append("attachment; filename=\"")
	var fileNameNonAscii = false
	for (c in fileName) {
		if (c > '\u0127') {
			fileNameNonAscii = true
			sb.append('_')
		} else if (c == '\"') {
			sb.append("\\\"")
		} else if (c == '\\') {
			sb.append("\\\\")
		} else {
			sb.append(c)
		}
	}
	sb.append('"')

	if (fileNameNonAscii) {
		sb.append("; filename*=UTF-8''")

		val codePoint = CharArray(2)
		val codePointBuf = CharBuffer.wrap(codePoint)
		var i = 0
		val length = fileName.length
		chars@while (i < length) {
			val c1 = fileName[i++]
			if (!Character.isHighSurrogate(c1) || i >= length) {
				when (c1) {
					in 'a'..'z',
					in 'A'..'Z',
					in '0'..'9',
					'!', '#', '$', '&',
					'+', '-', '.', '^',
					'_', '`', '|', '~' -> {
						sb.append(c1)
						continue@chars
					}
					else -> {
						codePoint[0] = c1
						codePointBuf.limit(1)
					}
				}
			} else {
				val c2 = fileName[i]
				if (Character.isLowSurrogate(c2)) {
					i++;
					codePoint[0] = c1
					codePoint[1] = c2
				} else {
					codePoint[0] = c1
					codePointBuf.limit(1)
				}
			}

			val utf8Bytes = Charsets.UTF_8.encode(codePointBuf)
			codePointBuf.clear()

			while (utf8Bytes.hasRemaining()) {
				val byte = utf8Bytes.get()
				sb.append('%').appendHex(byte)
			}
		}
	}

	return sb.toString()
}