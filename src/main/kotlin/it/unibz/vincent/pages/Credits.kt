package it.unibz.vincent.pages

import io.undertow.server.RoutingHandler
import it.unibz.vincent.util.GET
import kotlinx.html.b
import kotlinx.html.div
import kotlinx.html.h3
import kotlinx.html.p
import kotlinx.html.style

const val CREDITS_PATH = "/credits"

fun RoutingHandler.setupCreditsRoutes() {
	GET(CREDITS_PATH) { exchange ->
		exchange.sendBase("Profile") { _, locale ->
			div("page-container") {
				style="text-align: center; margin-top: 15rem;"
				h3 { +"Vincent" }
				p("sub") { +"Wine-focused questionnaire evaluation software" }

				p { +"Jan Polák - developer" }
				p {+"Simone Poggesi - advisor" }
				p {	+"WineID – An Intersciplinary project (TN201-ID2019) supported by the Free University of Bozen-Bolzano" }
				p {	+"Emanuele Boselli, Marco Montali - scientific supervisors" }
				p {
					b { +"Faculty of Science and Technology" }
					+" and "
					b { +"Faculty of Computer Science" }
				}
			}
		}
	}
}