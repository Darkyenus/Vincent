package it.unibz.vincent.pages

import io.undertow.server.RoutingHandler
import io.undertow.server.handlers.resource.ClassPathResourceManager
import io.undertow.server.handlers.resource.ResourceHandler
import it.unibz.vincent.template.QuestionnaireTemplate
import it.unibz.vincent.util.GET
import kotlinx.html.div
import kotlinx.html.unsafe

const val TEMPLATE_INFO_PATH = "/template-info"
const val TEMPLATE_DTD_PATH = "/questionnaire.dtd"

private val templateInfoHtml:String by lazy {
	QuestionnaireTemplate::class.java.classLoader.getResourceAsStream("QuestionnaireDesign.html")!!.bufferedReader().readText()
}

fun RoutingHandler.setupTemplateInfoRoutes() {
	GET(TEMPLATE_INFO_PATH) { exchange ->
		exchange.sendBase("Template language manual") { _, locale ->
			div("page-container") {
				unsafe { +templateInfoHtml }
			}
		}
	}

	get(TEMPLATE_DTD_PATH, ResourceHandler(ClassPathResourceManager(QuestionnaireTemplate::class.java.classLoader, "public/")))
}