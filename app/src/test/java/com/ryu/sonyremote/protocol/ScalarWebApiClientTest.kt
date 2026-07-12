package com.ryu.sonyremote.protocol

import com.ryu.sonyremote.model.CameraSettingId
import com.ryu.sonyremote.model.CameraCaptureEventKind
import com.ryu.sonyremote.model.PostviewSizePreference
import java.net.URI
import java.net.SocketTimeoutException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScalarWebApiClientTest {
    private val endpoint = URI("http://192.168.122.1:10000/sony/camera")

    @Test
    fun requestsOriginalPostviewAndAwaitsLongExposure() = runBlocking {
        val transport = QueueTransport(
            """{"id":1,"result":["2M",["2M","Original"]]}""",
            """{"id":2,"result":[0]}""",
            """{"id":3,"result":["Original",["2M","Original"]]}""",
            """{"id":4,"error":[40403,"Still capturing"]}""",
            """{"id":5,"result":[["http://192.168.122.1:60152/postview.jpg"]]}""",
        )
        val client = ScalarWebApiClient(endpoint, transport)

        val result = client.takePicture(
            setOf(
                "getAvailablePostviewImageSize",
                "setPostviewImageSize",
                "actTakePicture",
            ),
        )

        assertTrue(result.originalSizeRequested)
        assertEquals(URI("http://192.168.122.1:60152/postview.jpg"), result.remoteUri)
        assertEquals(
            listOf(
                "getAvailablePostviewImageSize",
                "setPostviewImageSize",
                "getAvailablePostviewImageSize",
                "actTakePicture",
                "awaitTakePicture",
            ),
            transport.methods,
        )
        assertEquals("Original", transport.paramsAt(1).first())
    }

    @Test
    fun selectsAndConfirmsFastPreviewBeforeTakingPicture() = runBlocking {
        val transport = QueueTransport(
            """{"id":1,"result":["Original",["Original","2M"]]}""",
            """{"id":2,"result":[0]}""",
            """{"id":3,"result":["2M",["Original","2M"]]}""",
            """{"id":4,"result":[["http://192.168.122.1:60152/preview.jpg"]]}""",
        )
        val client = ScalarWebApiClient(endpoint, transport)

        val result = client.takePicture(
            setOf(
                "getAvailablePostviewImageSize",
                "setPostviewImageSize",
                "actTakePicture",
            ),
            PostviewSizePreference.FastPreview,
        )

        assertEquals("2M", result.postviewSize)
        assertEquals(
            listOf(
                "getAvailablePostviewImageSize",
                "setPostviewImageSize",
                "getAvailablePostviewImageSize",
                "actTakePicture",
            ),
            transport.methods,
        )
        assertEquals("2M", transport.paramsAt(1).first())
    }

    @Test
    fun negotiatesV12AndParsesContinuousCaptureEvents() = runBlocking {
        val transport = QueueTransport(
            """{"id":1,"result":[["1.0","1.1","1.2"]]}""",
            """{"id":2,"results":[["getEvent",["bool"],["object*"],"1.2"]]}""",
            """{
                "id":3,
                "result":[
                    {"type":"availableApiList","names":["getEvent","stopContShooting"]},
                    {"type":"cameraStatus","cameraStatus":"StillSaving"},
                    {"type":"zoomInformation","zoomPosition":37,"zoomNumberBox":3,"zoomIndexCurrentBox":1,"zoomPositionCurrentBox":12},
                    {"type":"zoomSetting","zoom":"On:Digital Zoom","candidate":["Optical Zoom Only","On:Clear Image Zoom","On:Digital Zoom"]},
                    {"type":"contShooting","contShootingUrl":[
                        {
                            "postviewUrl":"http://192.168.122.1:60152/continuous-1.jpg",
                            "thumbnailUrl":"http://192.168.122.1:60152/thumb-1.jpg"
                        },
                        {
                            "postviewUrl":"http://192.168.122.1:60152/continuous-2.jpg",
                            "thumbnailUrl":"http://192.168.122.1:60152/thumb-2.jpg"
                        }
                    ]}
                ]
            }""".trimIndent(),
        )
        val client = ScalarWebApiClient(endpoint, transport)

        assertEquals("1.2", client.negotiateEventVersion())
        val event = client.getEvent(longPolling = true)

        assertEquals(
            listOf(
                URI("http://192.168.122.1:60152/continuous-1.jpg"),
                URI("http://192.168.122.1:60152/continuous-2.jpg"),
            ),
            event.bodyCaptureUris,
        )
        assertEquals(
            listOf(
                URI("http://192.168.122.1:60152/thumb-1.jpg"),
                URI("http://192.168.122.1:60152/thumb-2.jpg"),
            ),
            event.remoteCaptures.map { it.thumbnailUri },
        )
        assertEquals(setOf(CameraCaptureEventKind.Continuous), event.captureKinds)
        assertEquals("StillSaving", event.cameraStatus)
        assertEquals(37, event.zoomPosition)
        assertEquals(3, event.zoomBoxCount)
        assertEquals(1, event.zoomBoxIndex)
        assertEquals("On:Digital Zoom", event.zoomSetting)
        assertEquals(setOf("getEvent", "stopContShooting"), event.availableApis)
        assertEquals("1.2", event.eventVersion)
        assertEquals(listOf("getVersions", "getMethodTypes", "getEvent"), transport.methods)
        assertEquals("1.2", transport.paramsAt(1).single())
        assertEquals(listOf("1.0", "1.0", "1.2"), transport.requests.indices.map(transport::versionAt))
    }

    @Test
    fun fallsBackToV10OnlyWhenV12IsRejectedAsUnsupported() = runBlocking {
        val transport = QueueTransport(
            """{"id":1,"result":[["1.0","1.2"]]}""",
            """{"id":2,"results":[["getEvent",["bool"],["object*"],"1.2"]]}""",
            """{"id":3,"error":[14,"Unsupported Version"]}""",
            """{"id":4,"result":[{"type":"cameraStatus","cameraStatus":"IDLE"}]}""",
        )
        val client = ScalarWebApiClient(endpoint, transport)

        assertEquals("1.2", client.negotiateEventVersion())
        val event = client.getEvent(longPolling = false)

        assertEquals("1.0", event.eventVersion)
        assertEquals("IDLE", event.cameraStatus)
        assertEquals(listOf("1.0", "1.0", "1.2", "1.0"), transport.requests.indices.map(transport::versionAt))
    }

    @Test
    fun negotiatesV10WhenVersionDiscoveryIsUnavailableOnAnOlderCamera() = runBlocking {
        val transport = QueueTransport(
            """{"id":1,"error":[12,"No Such Method"]}""",
            """{"id":2,"result":[{"type":"cameraStatus","cameraStatus":"IDLE"}]}""",
        )
        val client = ScalarWebApiClient(endpoint, transport)

        assertEquals("1.0", client.negotiateEventVersion())
        val event = client.getEvent(longPolling = false)

        assertEquals("1.0", event.eventVersion)
        assertEquals(listOf("getVersions", "getEvent"), transport.methods)
    }

    @Test
    fun startsAndStopsContinuousShootingWithBoundedStopTimeout() = runBlocking {
        val transport = QueueTransport(
            """{"id":1,"result":[]}""",
            """{"id":2,"result":[]}""",
        )
        val client = ScalarWebApiClient(endpoint, transport)

        client.startContinuousShooting()
        client.stopContinuousShooting(readTimeoutMillis = 2_000)

        assertEquals(listOf("startContShooting", "stopContShooting"), transport.methods)
        assertEquals(listOf(5_000, 2_000), transport.readTimeouts)
    }

    @Test
    fun recursivelyParsesEveryCameraBodyCaptureUrl() = runBlocking {
        val transport = QueueTransport(
            """{
                "id":1,
                "result":[
                    {"type":"cameraStatus","cameraStatus":"IDLE"},
                    [[
                        {"type":"takePicture","takePictureUrl":[
                            "http://192.168.122.1:60152/first.jpg",
                            "http://192.168.122.1:60152/second.jpg"
                        ]}
                    ]],
                    {"futureEvent":{"type":"takePicture","takePictureUrl":[
                        "http://192.168.122.1:60152/third.jpg"
                    ]}},
                    {"type":"unknown","takePictureUrl":["not-an-event"]}
                ]
            }""".trimIndent(),
        )
        val client = ScalarWebApiClient(endpoint, transport)

        val event = client.getEvent(longPolling = false)

        assertEquals(
            listOf(
                URI("http://192.168.122.1:60152/first.jpg"),
                URI("http://192.168.122.1:60152/second.jpg"),
                URI("http://192.168.122.1:60152/third.jpg"),
            ),
            event.bodyCaptureUris,
        )
        assertEquals(setOf(CameraCaptureEventKind.Single), event.captureKinds)
        assertEquals(listOf("getEvent"), transport.methods)
        assertEquals(listOf("false"), transport.paramsAt(0))
        assertEquals("1.0", transport.versionAt(0))
        assertEquals(5_000, transport.readTimeouts.single())
    }

    @Test
    fun usesExtendedReadTimeoutForLongPollingEvents() = runBlocking {
        val transport = QueueTransport("""{"id":1,"result":[null,[]]}""")
        val client = ScalarWebApiClient(endpoint, transport)

        val event = client.getEvent(longPolling = true)

        assertTrue(event.bodyCaptureUris.isEmpty())
        assertEquals(listOf("true"), transport.paramsAt(0))
        assertEquals(70_000, transport.readTimeouts.single())
    }

    @Test
    fun rejectsMalformedCameraBodyCaptureEvents() = runBlocking {
        val malformedEvents = listOf(
            """{"id":1,"result":[{"type":"takePicture"}]}""",
            """{"id":1,"result":[{"type":"takePicture","takePictureUrl":"http://camera/image.jpg"}]}""",
            """{"id":1,"result":[{"type":"takePicture","takePictureUrl":[42]}]}""",
            """{"id":1,"result":[{"type":"takePicture","takePictureUrl":["not a URI"]}]}""",
            """{"id":1,"result":[{"type":"takePicture","takePictureUrl":["relative.jpg"]}]}""",
            """{"id":1,"result":[{"type":"contShooting"}]}""",
            """{"id":1,"result":[{"type":"contShooting","contShootingUrl":[42]}]}""",
            """{"id":1,"result":[{"type":"contShooting","contShootingUrl":[{"thumbnailUrl":"http://camera/thumb.jpg"}]}]}""",
        )

        malformedEvents.forEach { response ->
            val client = ScalarWebApiClient(endpoint, QueueTransport(response))

            val error = runCatching { client.getEvent(longPolling = false) }.exceptionOrNull()

            assertTrue(error is CameraApiException)
            assertEquals(6, (error as CameraApiException).code)
            assertEquals("Camera returned an invalid body-capture event", error.message)
        }
    }

    @Test
    fun fallsBackToCurrentPostviewWhenFastPreviewIsUnavailable() = runBlocking {
        val transport = QueueTransport(
            """{"id":1,"result":["Original",["Original"]]}""",
            """{"id":2,"result":[["http://192.168.122.1:60152/original.jpg"]]}""",
        )
        val client = ScalarWebApiClient(endpoint, transport)

        val result = client.takePicture(
            setOf(
                "getAvailablePostviewImageSize",
                "setPostviewImageSize",
                "actTakePicture",
            ),
            PostviewSizePreference.FastPreview,
        )

        assertEquals("Original", result.postviewSize)
        assertEquals(
            listOf("getAvailablePostviewImageSize", "actTakePicture"),
            transport.methods,
        )
    }

    @Test
    fun loadsOnlyAdvertisedDynamicSettings() = runBlocking {
        val transport = QueueTransport(
            """{"id":1,"result":[["getAvailableApiList","getApplicationInfo","getAvailableIsoSpeedRate","setIsoSpeedRate","getAvailableExposureCompensation","setExposureCompensation","actTakePicture"]]}""",
            """{"id":2,"result":["Smart Remote Embedded","1.00"]}""",
            """{"id":3,"result":["AUTO",["AUTO","100","200"]]}""",
            """{"id":4,"result":[0,3,-3,1]}""",
        )
        val client = ScalarWebApiClient(endpoint, transport)

        val capabilities = client.loadCapabilities()

        assertEquals("Smart Remote Embedded", capabilities.applicationName)
        assertTrue(capabilities.canTakePicture)
        assertEquals("AUTO", capabilities.settings.getValue(CameraSettingId.IsoSpeedRate).currentLabel)
        assertEquals(
            listOf("-1.0 EV", "-0.7 EV", "-0.3 EV", "+0.0 EV", "+0.3 EV", "+0.7 EV", "+1.0 EV"),
            capabilities.settings.getValue(CameraSettingId.ExposureCompensation).options.map { it.label },
        )
    }

    @Test
    fun mapsUnsupportedApiError() = runBlocking {
        val transport = QueueTransport("""{"id":1,"error":[15,"Not supported"]}""")
        val client = ScalarWebApiClient(endpoint, transport)

        val error = runCatching { client.startLiveview() }.exceptionOrNull() as CameraApiException

        assertEquals(15, error.code)
        assertTrue(error.isUnsupported)
    }

    @Test
    fun usesSonyMovieStartAndStopCommands() = runBlocking {
        val transport = QueueTransport(
            """{"id":1,"result":[0]}""",
            """{"id":2,"result":[""]}""",
        )
        val client = ScalarWebApiClient(endpoint, transport)

        client.startMovieRecording()
        client.stopMovieRecording()

        assertEquals(listOf("startMovieRec", "stopMovieRec"), transport.methods)
    }

    @Test
    fun rejectsMalformedMovieCommandResults() = runBlocking {
        val startClient = ScalarWebApiClient(
            endpoint,
            QueueTransport("""{"id":1,"result":[1]}"""),
        )
        val stopClient = ScalarWebApiClient(
            endpoint,
            QueueTransport("""{"id":1,"result":[0]}"""),
        )

        val startError = runCatching { startClient.startMovieRecording() }.exceptionOrNull()
        val stopError = runCatching { stopClient.stopMovieRecording() }.exceptionOrNull()

        assertTrue(startError is CameraApiException)
        assertTrue(stopError is CameraApiException)
    }

    @Test
    fun retainsGetterOnlyShootModeAsReadOnly() = runBlocking {
        val transport = QueueTransport(
            """{"id":1,"result":[["getAvailableApiList","getAvailableShootMode"]]}""",
            """{"id":2,"result":["movie",["movie"]]}""",
        )
        val client = ScalarWebApiClient(endpoint, transport)

        val setting = client.loadCapabilities().settings.getValue(CameraSettingId.ShootMode)

        assertEquals("movie", setting.currentWireValue)
        assertTrue(!setting.isWritable)
    }

    @Test
    fun recoversWhenInitialLongExposureRequestTimesOut() = runBlocking {
        val methods = mutableListOf<String>()
        val timeouts = mutableListOf<Int>()
        val transport = JsonRpcTransport { _, body, timeout ->
            val method = Json.parseToJsonElement(body).jsonObject.getValue("method").jsonPrimitive.content
            methods += method
            timeouts += timeout
            if (method == "actTakePicture") {
                throw SocketTimeoutException("Exposure is still running")
            }
            """{"id":2,"result":[["http://192.168.122.1:60152/long.jpg"]]}"""
        }
        val client = ScalarWebApiClient(endpoint, transport)

        val result = client.takePicture(setOf("actTakePicture"))

        assertEquals(URI("http://192.168.122.1:60152/long.jpg"), result.remoteUri)
        assertEquals(listOf("actTakePicture", "awaitTakePicture"), methods)
        assertEquals(listOf(90_000, 90_000), timeouts)
    }

    @Test
    fun rejectsSinglePhotoCaptureWhileDriveModeIsContinuous() = runBlocking {
        val transport = QueueTransport(
            """{"id":1,"result":[{"contShootingMode":"Continuous","candidate":["Single","Continuous"]}]}""",
        )
        val client = ScalarWebApiClient(endpoint, transport)

        val error = runCatching {
            client.takePicture(setOf("actTakePicture", "getAvailableContShootingMode"))
        }.exceptionOrNull()

        assertEquals("Select Single drive mode before taking a photo", error?.message)
        assertEquals(listOf("getAvailableContShootingMode"), transport.methods)
    }

    @Test
    fun loadsAndSetsObjectBasedContinuousDriveMode() = runBlocking {
        val transport = QueueTransport(
            """{"id":1,"result":[["getAvailableApiList","getAvailableContShootingMode","setContShootingMode"]]}""",
            """{"id":2,"result":[{"contShootingMode":"Continuous","candidate":["Single","Continuous"]}]}""",
            """{"id":3,"result":[]}""",
        )
        val client = ScalarWebApiClient(endpoint, transport)

        val setting = client.loadCapabilities().settings.getValue(CameraSettingId.ContShootingMode)
        client.setSetting(CameraSettingId.ContShootingMode, "Single")

        assertEquals("Continuous", setting.currentWireValue)
        assertEquals(listOf("Single", "Continuous"), setting.options.map { it.wireValue })
        assertEquals("Single", transport.paramsAt(2).first())
    }

    @Test
    fun loadsAndSetsContinuousShootingSpeedWhenAdvertised() = runBlocking {
        val transport = QueueTransport(
            """{"id":1,"result":[["getAvailableApiList","getAvailableContShootingSpeed","setContShootingSpeed"]]}""",
            """{"id":2,"result":[{"contShootingSpeed":"Hi","candidate":["Hi","Mid","Lo"]}]}""",
            """{"id":3,"result":[]}""",
        )
        val client = ScalarWebApiClient(endpoint, transport)

        val setting = client.loadCapabilities().settings.getValue(CameraSettingId.ContShootingSpeed)
        client.setSetting(CameraSettingId.ContShootingSpeed, "Lo")

        assertEquals("Hi", setting.currentWireValue)
        assertEquals(listOf("Hi", "Mid", "Lo"), setting.options.map { it.wireValue })
        assertEquals(true, setting.isWritable)
        assertEquals("Lo", transport.paramsAt(2).first())
    }

    @Test
    fun omitsContinuousShootingSpeedWhenCameraDoesNotAdvertiseIt() = runBlocking {
        val transport = QueueTransport(
            """{"id":1,"result":[["getAvailableApiList","startContShooting"]]}""",
        )
        val client = ScalarWebApiClient(endpoint, transport)

        val capabilities = client.loadCapabilities()

        assertEquals(null, capabilities.settings[CameraSettingId.ContShootingSpeed])
        assertEquals(listOf("getAvailableApiList"), transport.methods)
    }

    @Test
    fun loadsReadOnlyExposureModeWhenAvailableValueApiIsAbsent() = runBlocking {
        val transport = QueueTransport(
            """{"id":1,"result":[["getAvailableApiList","getExposureMode"]]}""",
            """{"id":2,"result":["Aperture Priority"]}""",
        )
        val client = ScalarWebApiClient(endpoint, transport)

        val setting = client.loadCapabilities().settings.getValue(CameraSettingId.ExposureMode)

        assertEquals("Aperture Priority", setting.currentLabel)
        assertEquals(false, setting.isWritable)
    }

    private class QueueTransport(vararg responses: String) : JsonRpcTransport {
        private val queue = ArrayDeque(responses.toList())
        val requests = mutableListOf<String>()
        val readTimeouts = mutableListOf<Int>()
        val methods: List<String>
            get() = requests.map { Json.parseToJsonElement(it).jsonObject.getValue("method").jsonPrimitive.content }

        override suspend fun postJson(endpoint: URI, body: String, readTimeoutMillis: Int): String {
            requests += body
            readTimeouts += readTimeoutMillis
            return queue.removeFirst()
        }

        fun versionAt(index: Int): String =
            Json.parseToJsonElement(requests[index]).jsonObject.getValue("version").jsonPrimitive.content

        fun paramsAt(index: Int): List<String> =
            Json.parseToJsonElement(requests[index]).jsonObject
                .getValue("params")
                .let { it as kotlinx.serialization.json.JsonArray }
                .map { element ->
                    if (element is kotlinx.serialization.json.JsonObject) {
                        element.values.first().jsonPrimitive.content
                    } else {
                        element.jsonPrimitive.content
                    }
                }
    }
}
