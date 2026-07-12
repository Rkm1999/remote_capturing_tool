package com.ryu.sonyremote.protocol

import com.ryu.sonyremote.model.SonyCameraDevice
import com.ryu.sonyremote.network.PrivateNetworkPolicy
import java.io.StringReader
import java.net.URI
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource

class DeviceDescriptionParser {
    fun parse(xml: String, descriptionUri: URI): SonyCameraDevice {
        PrivateNetworkPolicy.requireCameraUri(descriptionUri)
        require(!xml.contains("<!DOCTYPE", ignoreCase = true)) {
            "Camera device description must not contain a document type"
        }
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeatureSafely("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeatureSafely("http://xml.org/sax/features/external-general-entities", false)
            setFeatureSafely("http://xml.org/sax/features/external-parameter-entities", false)
            setAttributeSafely("http://javax.xml.XMLConstants/property/accessExternalDTD", "")
            setAttributeSafely("http://javax.xml.XMLConstants/property/accessExternalSchema", "")
        }
        val document = factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
        val elements = document.documentElement.descendantElements()
        val endpoints = elements
            .filter { it.localNameOrTag() == "X_ScalarWebAPI_Service" }
            .mapNotNull { service ->
                val advertisedServiceType = service.descendantElements()
                    .firstText("X_ScalarWebAPI_ServiceType")
                    ?: return@mapNotNull null
                val serviceType = advertisedServiceType.lowercase()
                val actionList = service.descendantElements()
                    .firstText("X_ScalarWebAPI_ActionList_URL")
                    ?: return@mapNotNull null
                val baseUri = descriptionUri.resolve(actionList)
                PrivateNetworkPolicy.requireCameraUri(baseUri, descriptionUri.host)
                val path = baseUri.path.trimEnd('/')
                val endpoint = if (path.endsWith("/$advertisedServiceType")) {
                    baseUri
                } else {
                    URI(
                        baseUri.scheme,
                        baseUri.userInfo,
                        baseUri.host,
                        baseUri.port,
                        "$path/$advertisedServiceType",
                        null,
                        null,
                    )
                }
                serviceType to endpoint
            }
            .toMap()

        require("camera" in endpoints) { "Device description contains no camera service" }
        return SonyCameraDevice(
            friendlyName = elements.firstText("friendlyName") ?: "Sony camera",
            modelName = elements.firstText("modelName") ?: "Unknown model",
            descriptionUri = descriptionUri,
            endpoints = endpoints,
        )
    }

    private fun DocumentBuilderFactory.setFeatureSafely(name: String, value: Boolean) {
        runCatching { setFeature(name, value) }
    }

    private fun DocumentBuilderFactory.setAttributeSafely(name: String, value: String) {
        runCatching { setAttribute(name, value) }
    }

    private fun Element.descendantElements(): List<Element> = buildList {
        fun visit(node: Node) {
            if (node is Element) add(node)
            val children = node.childNodes
            for (index in 0 until children.length) visit(children.item(index))
        }
        visit(this@descendantElements)
    }

    private fun List<Element>.firstText(name: String): String? =
        firstOrNull { it.localNameOrTag() == name }
            ?.textContent
            ?.trim()
            ?.takeIf(String::isNotEmpty)

    private fun Element.localNameOrTag(): String = localName ?: tagName.substringAfter(':')
}
