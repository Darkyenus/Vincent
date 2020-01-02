package it.unibz.vincent.pages

import io.undertow.server.HttpServerExchange
import io.undertow.util.Headers
import it.unibz.vincent.Session
import kotlinx.html.h1

/**
 *
 */
fun HttpServerExchange.home(session: Session) {
	responseHeaders.put(Headers.CONTENT_LOCATION, "/")
	sendBase { _, _ ->
		h1 { +"Welcome home user $session" }
	}
}
