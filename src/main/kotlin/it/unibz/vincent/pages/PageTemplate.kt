package it.unibz.vincent.pages

import io.undertow.server.ExchangeCompletionListener
import io.undertow.server.HttpServerExchange
import io.undertow.util.AttachmentKey
import it.unibz.vincent.AccountType
import it.unibz.vincent.Accounts
import it.unibz.vincent.BRAND_LOGO
import it.unibz.vincent.BRAND_NAME
import it.unibz.vincent.CSRF_FORM_TOKEN_NAME
import it.unibz.vincent.IDEMPOTENCY_FORM_TOKEN_NAME
import it.unibz.vincent.Session
import it.unibz.vincent.session
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
import kotlinx.html.FlowOrInteractiveOrPhrasingContent
import kotlinx.html.FlowOrPhrasingContent
import kotlinx.html.FormMethod
import kotlinx.html.HTML
import kotlinx.html.HTMLTag
import kotlinx.html.HtmlBlockTag
import kotlinx.html.HtmlTagMarker
import kotlinx.html.TagConsumer
import kotlinx.html.a
import kotlinx.html.attributesMapOf
import kotlinx.html.body
import kotlinx.html.button
import kotlinx.html.checkBoxInput
import kotlinx.html.div
import kotlinx.html.emailInput
import kotlinx.html.form
import kotlinx.html.head
import kotlinx.html.hiddenInput
import kotlinx.html.id
import kotlinx.html.label
import kotlinx.html.lang
import kotlinx.html.li
import kotlinx.html.link
import kotlinx.html.meta
import kotlinx.html.passwordInput
import kotlinx.html.script
import kotlinx.html.span
import kotlinx.html.submitInput
import kotlinx.html.tabIndex
import kotlinx.html.textInput
import kotlinx.html.title
import kotlinx.html.ul
import kotlinx.html.unsafe
import kotlinx.html.visit
import org.slf4j.LoggerFactory

private val LOG = LoggerFactory.getLogger("PageTemplate")

/** Build head and body. */
fun HTML.base(lang:String = "en", title:String = BRAND_NAME, description:String = "", createBody: BODY.() -> Unit) {
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

fun HTMLTag.unibzLogo(classes:String? = null) {
	unsafe {
		+"<svg class=\"${classes ?: ""}\" xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 65 52\"><path d=\"M38.9 0.2h25.7v3.6H38.9V0.2zM12.6 34.5H9v-1.3c-1 1-2.2 1.5-3.6 1.5 -1.4 0-2.6-0.4-3.5-1.3 -1-1-1.5-2.4-1.5-4.1V20h3.7v8.8c0 0.9 0.3 1.6 0.8 2.1 0.4 0.4 1 0.6 1.6 0.6 0.7 0 1.2-0.2 1.6-0.6 0.5-0.5 0.8-1.1 0.8-2.1V20h3.7V34.5M28.2 34.5h-3.7v-8.8c0-0.9-0.3-1.6-0.8-2.1 -0.4-0.4-1-0.6-1.6-0.6 -0.7 0-1.2 0.2-1.6 0.6 -0.5 0.5-0.8 1.2-0.8 2.1v8.8H16V20h3.6v1.3c1-1 2.2-1.5 3.7-1.5 1.4 0 2.6 0.4 3.5 1.3 1 1 1.5 2.4 1.5 4.1V34.5M31.7 14.6h3.7v2.9h-3.7V14.6zM31.7 20h3.7v14.5h-3.7V20zM51.2 27.2c0 1.5-0.1 2.6-0.2 3.3 -0.2 1.2-0.6 2.1-1.3 2.8 -0.9 0.9-2.1 1.3-3.6 1.3 -1.5 0-2.7-0.5-3.6-1.5v1.4h-3.5V14.7h3.7v6.6c0.9-1 2-1.4 3.5-1.4 1.5 0 2.7 0.4 3.5 1.3 0.7 0.6 1.1 1.6 1.3 2.8C51.1 24.7 51.2 25.8 51.2 27.2M47.5 27.2c0-1.3-0.1-2.2-0.3-2.8 -0.4-0.9-1.1-1.4-2.1-1.4 -1.1 0-1.8 0.5-2.1 1.4 -0.2 0.6-0.3 1.5-0.3 2.8 0 1.3 0.1 2.2 0.3 2.8 0.4 0.9 1.1 1.4 2.1 1.4 1.1 0 1.8-0.5 2.1-1.4C47.4 29.4 47.5 28.5 47.5 27.2M64.6 34.5H53.5v-2.7l6.5-8.5h-6.1V20h10.8v2.8L58 31.3h6.6V34.5M38.9 47.4h25.7V51H38.9V47.4z\"></path></svg>"
	}
}

fun HttpServerExchange.sendBase(title:String, showHeader:Boolean = true, createBody: BODY.(HttpServerExchange, LocaleStack) -> Unit) {
	val languages = languages()
	val session = session()
	// TODO(jp): Localize!
	sendHtml {
		base("en", if (title.isEmpty()) BRAND_NAME else "$title - $BRAND_NAME", "Wine evaluation questionnaires") {
			comment("Vincent - Copyright (c) 2019-2020 Jan Polák")
			if (showHeader) {
				// Header
				div("page-header") {
					a(href = HOME_PATH, classes = "header-button page-header-logo") {
						+BRAND_NAME
					}

					if (session != null) {
						if (session.accountType > AccountType.GUEST) {
							a(href = PROFILE_PATH, classes = "header-button page-header-profile") {
								+session.userName
							}
						} else {
							postButton(session, HOME_PATH, routeAction = ACTION_LOGOUT, classes = "header-button") { +"Logout" }
						}
					}
				}
				if (BRAND_LOGO) {
					unibzLogo("unibz-logo")
				}
			} else {
				if (BRAND_LOGO) {
					unibzLogo("unibz-logo absolute")
				}
			}

			createBody(this@sendBase, languages)
		}
	}
}

private class Messages {
	var warningMessages:ArrayList<String>? = null
	var infoMessages:ArrayList<String>? = null
}

private val messageAttachment = AttachmentKey.create(Messages::class.java)

private val MESSAGE_KEEPER: ExchangeCompletionListener = ExchangeCompletionListener { exchange, nextListener ->
	try {
		val messages: Messages? = exchange.getAttachment(messageAttachment)
		if (messages?.infoMessages.isNullOrEmpty() && messages?.warningMessages.isNullOrEmpty()) {
			// Nothing to save
			return@ExchangeCompletionListener
		}
		val session = exchange.session()
		if (session == null) {
			// Nowhere to save
			LOG.warn("Lost messages - no session ({}, {})", messages?.infoMessages, messages?.warningMessages)
			return@ExchangeCompletionListener
		}

		messages?.infoMessages?.let {
			if (it.isNotEmpty()) {
				session.stashMessages(it, Session.MessageType.INFO)
				it.clear()
			}
		}

		messages?.warningMessages?.let {
			if (it.isNotEmpty()) {
				session.stashMessages(it, Session.MessageType.WARNING)
				it.clear()
			}
		}
	} finally {
		nextListener.proceed()
	}
}

fun HttpServerExchange.messageWarning(text:String?) {
	text ?: return
	val messages = getAttachment(messageAttachment) ?: Messages().also {
		putAttachment(messageAttachment, it)
	}
	val list = messages.warningMessages ?: ArrayList<String>().also {
		messages.warningMessages = it
	}
	list.add(text)
	addExchangeCompleteListener(MESSAGE_KEEPER)
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
	addExchangeCompleteListener(MESSAGE_KEEPER)
}

private fun FlowContent.renderMessageBox(own:List<String>, stashed:List<String>, classes:String) {
	val totalCount = own.size + stashed.size
	if (totalCount <= 0) {
		return
	}

	div(classes) {
		if (totalCount == 1) {
			if (own.isNotEmpty()) {
				+own[0]
			} else {
				+stashed[0]
			}

		} else {
			ul {
				for (message in own) {
					li { +message }
				}
				for (message in stashed) {
					li { +message }
				}
			}
		}
	}
}

fun FlowContent.renderMessages(exchange:HttpServerExchange) {
	val messages: Messages? = exchange.getAttachment(messageAttachment)
	val session: Session? = exchange.session()

	val warningMessages = messages?.warningMessages
	val stashedWarningMessages = session?.retrieveStashedMessages(Session.MessageType.WARNING)
	renderMessageBox(warningMessages ?: emptyList(), stashedWarningMessages ?: emptyList(), "warning box")
	warningMessages?.clear()

	val infoMessages = messages?.infoMessages
	val stashedInfoMessages = session?.retrieveStashedMessages(Session.MessageType.INFO)
	renderMessageBox(infoMessages ?: emptyList(), stashedInfoMessages ?: emptyList(), "info box")
	infoMessages?.clear()
}

fun FlowOrInteractiveOrPhrasingContent.emailField(fieldId:String, autoComplete:String, preFillValue:String?) {
	label {
		+"E-mail"
		emailInput(classes = "u-full-width") {
			name = fieldId
			minLength = "3"
			maxLength = Accounts.MAX_EMAIL_LENGTH.toString()
			placeholder = "your-email@example.com"
			required = true
			if (preFillValue != null) {
				value = preFillValue
			}
			attributes["autocomplete"] = autoComplete
		}
	}
}

const val MIN_PASSWORD_LENGTH = 8
const val MAX_PASSWORD_LENGTH = 1000

fun FlowOrInteractiveOrPhrasingContent.passwordField(fieldId:String, autoComplete:String, internalId:String, label:String = "Password") {
	label {
		+label
		div("password-container") {
			passwordInput(classes = "u-full-width") {
				name = fieldId
				minLength = MIN_PASSWORD_LENGTH.toString()
				maxLength = MAX_PASSWORD_LENGTH.toString()
				required = true
				attributes["autocomplete"] = autoComplete
				id = internalId
			}

			label("password-mask-toggle-label hidden") { // This element does not work without JS, so make it visible in JS
				tabIndex = "0"
				attributes["title"] = "Toggle password visibility"
				attributes["aria-label"] = "Toggle password visibility"

				checkBoxInput(name = null, classes = "password-mask-toggle") {
					// The check box itself is never shown, hidden by the class
					attributes["password-field"] = internalId
					attributes["autocomplete"] = "off"
					checked = false
				}
				span("password-mask-toggle-icon password-mask-toggle-icon-plain ${Icons.EYE.cssClass}") {}
				span("password-mask-toggle-icon password-mask-toggle-icon-pass ${Icons.EYE_CLOSED.cssClass}") {}
			}
		}
	}
}

fun FlowOrInteractiveOrPhrasingContent.fullNameField(fieldId:String, autoComplete:String, preFillValue:String?) {
	label {
		+"Personal Name"
		textInput(classes = "u-full-width") {
			name = fieldId
			minLength = "1"
			maxLength = Accounts.MAX_NAME_LENGTH.toString()
			placeholder = if (System.currentTimeMillis() and 1L == 0L) "John Doe" else "Jane Doe"
			required = true
			if (preFillValue != null) {
				value = preFillValue
			}
			attributes["autocomplete"] = autoComplete
		}
	}
}

const val HIDDEN_TIMEZONE_INPUT_NAME = "timezone"
fun FlowOrInteractiveOrPhrasingContent.hiddenTimezoneInput() {
	hiddenInput(name=HIDDEN_TIMEZONE_INPUT_NAME, classes = "put-timezone-here") { this.value = "none" }
}

fun FORM.superCompactSaveButton() {
	div("super-compact-submit") {
		submitInput { this.value="Save" }
	}
}

enum class Icons(val cssClass:String) {
	TRASH("gg-trash"),
	INFO("gg-info"),
	EYE("gg-eye"),
	EYE_CLOSED("gg-eye-closed")
}

@HtmlTagMarker
fun FlowOrPhrasingContent.icon(icon:Icons) {
	span("icon ${icon.cssClass}") {}
}

@HtmlTagMarker
fun FlowContent.noscript(classes : String? = null, block : NOSCRIPT.() -> Unit = {}) : Unit = NOSCRIPT(attributesMapOf("class", classes), consumer).visit(block)

open class NOSCRIPT(initialAttributes : Map<String, String>, override val consumer : TagConsumer<*>) : HTMLTag("noscript", consumer, initialAttributes, null, false, false), HtmlBlockTag
