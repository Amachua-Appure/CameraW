package com.cameraw

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class GyroflowLogger(context: Context) : SensorEventListener {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE_UNCALIBRATED) ?: sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER_UNCALIBRATED) ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val isRecording = AtomicBoolean(false)
    private val queue = LinkedBlockingQueue<String>()
    private var writerThread: Thread? = null

    private var lastAx = 0f
    private var lastAy = 0f
    private var lastAz = 0f
    private var firstTimestampNs = -1L

    fun start(outputFile: File) {
        if (gyroSensor == null || accelSensor == null) {
            Log.e("GyroflowLogger", "Required sensors missing!")
            return
        }

        firstTimestampNs = -1L
        queue.clear()
        isRecording.set(true)

        writerThread = thread(isDaemon = true, name = "GyroflowWriter") {
            try {
                BufferedWriter(FileWriter(outputFile)).use { bw ->
                    bw.write("GYROFLOW IMU LOG\n")
                    bw.write("version,1.2\n")
                    bw.write("id,${Build.MODEL}\n")
                    bw.write("orientation,XYZ\n")
                    bw.write("timestamp,${System.currentTimeMillis()}\n")
                    bw.write("vendor,CameraW\n")
                    bw.write("tscale,0.001\n")
                    bw.write("gscale,1.0\n")
                    bw.write("ascale,1.0\n")
                    bw.write("t,gx,gy,gz,ax,ay,az\n")

                    while (isRecording.get() || queue.isNotEmpty()) {
                        val line = queue.poll(100, TimeUnit.MILLISECONDS)
                        if (line != null) {
                            bw.write(line)
                        }
                    }
                    bw.flush()
                }
            } catch (e: Exception) {
                Log.e("GyroflowLogger", "File write error", e)
            }
        }

        sensorManager.registerListener(this, accelSensor, SensorManager.SENSOR_DELAY_FASTEST)
        sensorManager.registerListener(this, gyroSensor, SensorManager.SENSOR_DELAY_FASTEST)
    }

    fun stop() {
        isRecording.set(false)
        sensorManager.unregisterListener(this)
        writerThread?.join(1000)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!isRecording.get()) return

        if (firstTimestampNs == -1L) {
            firstTimestampNs = event.timestamp
        }

        val tMs = (event.timestamp - firstTimestampNs) / 1_000_000.0

        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER || event.sensor.type == Sensor.TYPE_ACCELEROMETER_UNCALIBRATED) {
            lastAx = event.values[0]
            lastAy = event.values[1]
            lastAz = event.values[2]
        } else if (event.sensor.type == Sensor.TYPE_GYROSCOPE || event.sensor.type == Sensor.TYPE_GYROSCOPE_UNCALIBRATED) {
            val gx = event.values[0]
            val gy = event.values[1]
            val gz = event.values[2]

            queue.offer("$tMs,$gx,$gy,$gz,$lastAx,$lastAy,$lastAz\n")
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}