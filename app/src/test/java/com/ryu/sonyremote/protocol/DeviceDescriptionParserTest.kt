package com.ryu.sonyremote.protocol

import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DeviceDescriptionParserTest {
    private val parser = DeviceDescriptionParser()

    @Test
    fun parsesSonyServiceEndpoints() {
        val device = parser.parse(DESCRIPTION_XML, URI("http://192.168.122.1:64321/dd.xml"))

        assertEquals("Sony ILCE-6300", device.friendlyName)
        assertEquals("ILCE-6300", device.modelName)
        assertEquals(
            URI("http://192.168.122.1:10000/sony/camera"),
            device.endpoint("camera"),
        )
        assertEquals(
            URI("http://192.168.122.1:10000/sony/avContent"),
            device.endpoint("avcontent"),
        )
    }

    @Test
    fun rejectsActionUrlOnAnotherHost() {
        val malicious = DESCRIPTION_XML.replace(
            "http://192.168.122.1:10000/sony",
            "http://10.0.0.4:10000/sony",
        )

        assertThrows(IllegalArgumentException::class.java) {
            parser.parse(malicious, URI("http://192.168.122.1:64321/dd.xml"))
        }
    }

    @Test
    fun rejectsDocumentTypesBeforeXmlParsing() {
        val xml = DESCRIPTION_XML.replace(
            "<root",
            "<!DOCTYPE root [<!ENTITY secret SYSTEM \"file:///etc/passwd\">]><root",
        )

        assertThrows(IllegalArgumentException::class.java) {
            parser.parse(xml, URI("http://192.168.122.1:64321/dd.xml"))
        }
    }

    private companion object {
        val DESCRIPTION_XML = """
            <?xml version="1.0"?>
            <root xmlns="urn:schemas-upnp-org:device-1-0" xmlns:av="urn:schemas-sony-com:av">
              <device>
                <friendlyName>Sony ILCE-6300</friendlyName>
                <modelName>ILCE-6300</modelName>
                <av:X_ScalarWebAPI_DeviceInfo>
                  <av:X_ScalarWebAPI_ServiceList>
                    <av:X_ScalarWebAPI_Service>
                      <av:X_ScalarWebAPI_ServiceType>camera</av:X_ScalarWebAPI_ServiceType>
                      <av:X_ScalarWebAPI_ActionList_URL>http://192.168.122.1:10000/sony</av:X_ScalarWebAPI_ActionList_URL>
                    </av:X_ScalarWebAPI_Service>
                    <av:X_ScalarWebAPI_Service>
                      <av:X_ScalarWebAPI_ServiceType>avContent</av:X_ScalarWebAPI_ServiceType>
                      <av:X_ScalarWebAPI_ActionList_URL>http://192.168.122.1:10000/sony</av:X_ScalarWebAPI_ActionList_URL>
                    </av:X_ScalarWebAPI_Service>
                  </av:X_ScalarWebAPI_ServiceList>
                </av:X_ScalarWebAPI_DeviceInfo>
              </device>
            </root>
        """.trimIndent()
    }
}
