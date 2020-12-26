package io.github.poeschl.kump

import com.xenomachina.argparser.ArgParser
import com.xenomachina.argparser.default
import com.xenomachina.argparser.mainBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.IntStream
import javax.imageio.ImageIO
import kotlin.streams.toList
import kotlin.system.measureTimeMillis

class Application(host: String, port: Int, connections: Int) {

    companion object {
        private val LOGGER = KotlinLogging.logger { }
        private const val SNAPSHOT_FILENAME = "snapshot.png"
        private const val X_SPLIT = 20
        private const val Y_SPLIT = 20
    }

    private val flutInterfaces = createInterfacePool(host, port, connections)

    init {
        createOutputFolder()
    }

    fun snapshot() {

        val size = flutInterfaces[0].getPlaygroundSize()
        LOGGER.info { "Screenshot size: $size" }

        val screenshotTime = measureTimeMillis {
            val imageMatrix = getPixels(size)
            writeSnapshot(imageMatrix, File("output/$SNAPSHOT_FILENAME"))
        }

        LOGGER.debug { "Took snapshot in $screenshotTime ms" }
        flutInterfaces.forEach { it.close() }
    }

    private fun createOutputFolder() {
        val outputFolder = Path.of("output")
        if (Files.notExists(outputFolder)) {
            Files.createDirectory(outputFolder)
        }
    }

    private fun getPixels(size: Pair<Int, Int>): PixelMatrix {
        val matrix = PixelMatrix(size.first, size.second)

        val xSlices = size.first / X_SPLIT
        val xPartitial = size.first % xSlices
        val ySlices = size.second / X_SPLIT
        val yPartitial = size.second % ySlices

        val areas = mutableListOf<Area>()
        IntStream.rangeClosed(0, X_SPLIT - 1).forEach { xIndex ->
            IntStream.rangeClosed(0, Y_SPLIT - 1).forEach { yIndex ->
                val origin = Point(xSlices * xIndex, ySlices * yIndex)
                val corner = if (xIndex == 0 && yIndex == 0) {
                    Point(origin.x + xSlices + xPartitial - 1, origin.y + ySlices + yPartitial - 1)
                } else {
                    Point(origin.x + xSlices - 1, origin.y + ySlices - 1)
                }
                areas.add(Area(origin, corner))
            }
        }

        runBlocking {
            val deferredData = areas
                .mapIndexed { index, area ->
                    val flutInterface = flutInterfaces[index % flutInterfaces.size]
                    async(Dispatchers.IO) {
                        flutInterface.getPixelArea(area.origin, area.endCorner)
                    }
                }

            deferredData.forEach { deferred -> matrix.insertAll(deferred.await()) }
        }
        return matrix
    }

    private fun writeSnapshot(matrix: PixelMatrix, file: File) {
        val bufferedImage = BufferedImage(matrix.width, matrix.height, BufferedImage.TYPE_INT_RGB)
        val canvas = bufferedImage.graphics

        matrix.processData {
            canvas.color = it.color
            canvas.fillRect(it.point.x, it.point.y, 1, 1)
        }

        canvas.dispose()
        ImageIO.write(bufferedImage, file.extension, file)
    }

    private fun createInterfacePool(host: String, port: Int, size: Int): List<PixelFlutInterface> {
        return IntStream.range(0, size)
            .mapToObj { PixelFlutInterface(host, port) }
            .toList()
    }
}

fun main(args: Array<String>) = mainBody {
    System.setProperty(org.slf4j.impl.SimpleLogger.DEFAULT_LOG_LEVEL_KEY, "DEBUG")

    ArgParser(args).parseInto(::Args).run {
        val logger = KotlinLogging.logger {}

        logger.info { "Connecting to $host:$port with $connections connections" }

        when {
            singleSnapshot -> Application(host, port, connections).snapshot();
            else -> logger.warn { "No action selected, please use the --help to get all possible actions" }
        }
    }
}

class Args(parser: ArgParser) {
    val host by parser.storing("--host", help = "The host of the pixelflut server").default("localhost")
    val port by parser.storing("-p", "--port", help = "The port of the server") { toInt() }.default(1234)
    val connections by parser.storing("-c", "--connections", help = "Number of connections to the server") { toInt() }.default(3)
    val singleSnapshot by parser.flagging("--single","--single-snapshot", help = "Create a snapshot and exit").default(false)
}
