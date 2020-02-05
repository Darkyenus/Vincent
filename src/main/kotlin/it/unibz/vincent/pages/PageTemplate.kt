package it.unibz.vincent.pages

import io.undertow.server.HttpServerExchange
import io.undertow.util.AttachmentKey
import it.unibz.vincent.CSRF_FORM_TOKEN_NAME
import it.unibz.vincent.IDEMPOTENCY_FORM_TOKEN_NAME
import it.unibz.vincent.Session
import it.unibz.vincent.util.LocaleStack
import it.unibz.vincent.util.ROUTE_ACTION_PARAM_NAME
import it.unibz.vincent.util.languages
import it.unibz.vincent.util.plusClass
import it.unibz.vincent.util.sendHtml
import kotlinx.html.BODY
import kotlinx.html.BUTTON
import kotlinx.html.ButtonType
import kotlinx.html.FORM
import kotlinx.html.FlowContent
import kotlinx.html.FlowOrPhrasingContent
import kotlinx.html.FormMethod
import kotlinx.html.HTML
import kotlinx.html.HTMLTag
import kotlinx.html.HtmlBlockTag
import kotlinx.html.HtmlTagMarker
import kotlinx.html.TagConsumer
import kotlinx.html.attributesMapOf
import kotlinx.html.body
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.form
import kotlinx.html.head
import kotlinx.html.hiddenInput
import kotlinx.html.lang
import kotlinx.html.li
import kotlinx.html.link
import kotlinx.html.meta
import kotlinx.html.script
import kotlinx.html.span
import kotlinx.html.title
import kotlinx.html.ul
import kotlinx.html.visit

/** Build head and body. */
fun HTML.base(lang:String = "en", title:String = "Vincent", description:String = "", createBody: BODY.() -> Unit) {
	this.lang = lang
	head {
		meta(charset = "UTF-8")
		title(title)
		if (description.isNotBlank()) {
			meta(name="description", content=description)
		}
		meta("viewport", "width=device-width, initial-scale=1")

		link("https://fonts.googleapis.com/css?family=Merriweather:400,700&display=swap&subset=latin-ext", rel="stylesheet", type="text/css") {
			attributes["referrerpolicy"] = "no-referrer"
		}
		link("/css/normalize.css", rel="stylesheet")
		link("/css/vincent.css", rel="stylesheet")
		link("/css/icons.css", rel="stylesheet")
		script(src="/js/vincent.js") { defer=true }

		// Current favicon generated by https://realfavicongenerator.net
		// from https://www.icofont.com (glass)

		comment("Generated by realfavicongenerator.net, icon under MIT license from www.icofont.com")
		link(rel="apple-touch-icon", href="/apple-touch-icon.png") { sizes = "180x180" }
		link(rel="icon", type="image/png", href="/favicon-32x32.png") { sizes = "32x32" }
		link(rel="icon", type="image/png", href="/favicon-16x16.png") { sizes = "16x16" }
		link(rel="manifest", href="/site.webmanifest")
		link(rel="mask-icon", href="/safari-pinned-tab.svg") { attributes["color"] ="#b00b10" }
		meta(name="msapplication-TileColor", content="#b91d47")
		meta(name="theme-color", content="#ffffff")
	}

	body {
		createBody.invoke(this)
	}
}

/** Include necessary extra data into the form (anti-CSRF token). */
fun FORM.session(session: Session) {
	hiddenInput(name = CSRF_FORM_TOKEN_NAME) { value = session.csrfToken }
	hiddenInput(name = IDEMPOTENCY_FORM_TOKEN_NAME) { value = session.nextIdempotencyToken.getAndIncrement().toString() }
}

fun FORM.routeAction(routeAction:String?) {
	if (routeAction != null) {
		hiddenInput(name= ROUTE_ACTION_PARAM_NAME) { value = routeAction }
	}
}

fun FlowContent.getButton(url:String, vararg extraParams:Pair<String, String>, routeAction:String? = null, classes:String? = null, parentClasses:String? = null, block : BUTTON.() -> Unit) {
	form(url, method=FormMethod.get, classes = parentClasses) {
		button(type= ButtonType.submit, classes=classes){ block() }
		routeAction(routeAction)
		for ((k, v) in extraParams) {
			hiddenInput(name= k) { value = v }
		}
	}
}

private const val CONFIRMATION_CLASS = "confirmed-submit"
private const val CONFIRMATION_MESSAGE = "confirmation-message"

fun FlowContent.postButton(session:Session, url:String, vararg extraParams:Pair<String, String>, routeAction:String? = null, classes:String? = null, parentClasses:String? = null, confirmation:String? = null, block : BUTTON.() -> Unit) {
	form(url, method=FormMethod.post, classes=confirmation?.let { CONFIRMATION_CLASS } plusClass parentClasses) {
		confirmation?.let { attributes[CONFIRMATION_MESSAGE] = it }
		button(type= ButtonType.submit, classes=classes){ block() }
		routeAction(routeAction)
		session(session)
		for ((k, v) in extraParams) {
			hiddenInput(name= k) { value = v }
		}
	}
}

fun HttpServerExchange.sendBase(title:String = "", createBody: BODY.(HttpServerExchange, LocaleStack) -> Unit) {
	val languages = languages()
	// TODO(jp): Localize!
	sendHtml {
		base("en", title, "Wine evaluation questionnaires") {
			// TODO(jp): Header with logged-in-as and logout buttons?
			createBody(this@sendBase, languages)
		}
	}
}

private class Messages {
	var warningMessages:ArrayList<String>? = null
	var infoMessages:ArrayList<String>? = null
}

private val messageAttachment = AttachmentKey.create(Messages::class.java)

fun HttpServerExchange.messageWarning(text:String?) {
	text ?: return
	val messages = getAttachment(messageAttachment) ?: Messages().also {
		putAttachment(messageAttachment, it)
	}
	val list = messages.warningMessages ?: ArrayList<String>().also {
		messages.warningMessages = it
	}
	list.add(text)
}

fun HttpServerExchange.messageWarningCount():Int {
	val messages = getAttachment(messageAttachment) ?: return 0
	val list = messages.warningMessages ?: return 0
	return list.size
}

fun HttpServerExchange.messageInfo(text:String?) {
	text ?: return
	val messages = getAttachment(messageAttachment) ?: Messages().also {
		putAttachment(messageAttachment, it)
	}
	val list = messages.infoMessages ?: ArrayList<String>().also {
		messages.infoMessages = it
	}
	list.add(text)
}

fun FlowContent.renderMessages(exchange:HttpServerExchange) {
	val messages = exchange.getAttachment(messageAttachment) ?: return

	messages.warningMessages?.let {
		if (it.isNotEmpty()) {
			div("warning box") {
				if (it.size == 1) {
					+it[0]
				} else {
					ul {
						for (message in it) {
							li { +message }
						}
					}
				}
			}
		}
	}
	messages.warningMessages?.clear()

	messages.infoMessages?.let {
		if (it.isNotEmpty()) {
			div("info box") {
				if (it.size == 1) {
					+it[0]
				} else {
					ul {
						for (message in it) {
							li { +message }
						}
					}
				}
			}
		}
	}
	messages.infoMessages?.clear()
}

enum class Icons(val cssClass:String) {
	TRASH("gg-trash")
}

@HtmlTagMarker
fun FlowOrPhrasingContent.icon(icon:Icons) {
	span("icon ${icon.cssClass}") {}
}

@HtmlTagMarker
fun FlowContent.noscript(classes : String? = null, block : NOSCRIPT.() -> Unit = {}) : Unit = NOSCRIPT(attributesMapOf("class", classes), consumer).visit(block)

open class NOSCRIPT(initialAttributes : Map<String, String>, override val consumer : TagConsumer<*>) : HTMLTag("noscript", consumer, initialAttributes, null, false, false), HtmlBlockTag
