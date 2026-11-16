kotlin
package com.chat.horsligne

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.UUID

class MainActivity : Activity() {

    private val appUuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private var btAdapter: BluetoothAdapter? = null
    private var activeSocket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null

    private lateinit var statusText: TextView
    private lateinit var chatHistory: TextView
    private lateinit var scrollView: ScrollView
    private lateinit var messageInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        btAdapter = btManager.adapter

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            setBackgroundColor(Color.parseColor("#F4F6F8"))
        }

        statusText = TextView(this).apply {
            text = "Statut : Déconnecté"
            textSize = 16f
            setTextColor(Color.parseColor("#1E293B"))
            setPadding(0, 0, 0, 16)
        }
        root.addView(statusText)

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        val hostBtn = Button(this).apply {
            text = "Attendre un ami"
            setOnClickListener { startServer() }
        }
        val joinBtn = Button(this).apply {
            text = "Rejoindre un ami"
            setOnClickListener { chooseDeviceToConnect() }
        }
        btnRow.addView(hostBtn)
        btnRow.addView(joinBtn)
        root.addView(btnRow)

        scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            ).apply { setMargins(0, 24, 0, 24) }
            setBackgroundColor(Color.WHITE)
            setPadding(24, 24, 24, 24)
        }

        chatHistory = TextView(this).apply {
            text = "Bienvenue ! Activez le Bluetooth et associez vos téléphones dans les réglages Android avant de démarrer.\n\n"
            textSize = 15f
            setTextColor(Color.BLACK)
        }
        scrollView.addView(chatHistory)
        root.addView(scrollView)

        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        messageInput = EditText(this).apply {
            hint = "Écrire un message..."
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val sendBtn = Button(this).apply {
            text = "Envoyer"
            setOnClickListener { sendMessage() }
        }

        inputRow.addView(messageInput)
        inputRow.addView(sendBtn)
        root.addView(inputRow)

        setContentView(root)
        checkPermissions()
    }

    private fun checkPermissions() {
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }
        val missing = perms.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), 101)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startServer() {
        val adapter = btAdapter ?: return
        if (!adapter.isEnabled) {
            startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }
        updateStatus("Statut : En attente de connexion d'un ami...")
        Thread {
            try {
                val serverSocket = adapter.listenUsingRfcommWithServiceRecord("ChatHorsLigne", appUuid)
                val socket = serverSocket.accept()
                serverSocket.close()
                manageConnection(socket, "Ami connecté")
            } catch (e: Exception) {
                updateStatus("Erreur serveur : ${e.message}")
            }
        }.start()
    }

    @SuppressLint("MissingPermission")
    private fun chooseDeviceToConnect() {
        val adapter = btAdapter ?: return
        if (!adapter.isEnabled) {
            startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }
        val paired: Set<BluetoothDevice> = adapter.bondedDevices
        if (paired.isEmpty()) {
            appendChat("Système : Aucun téléphone associé. Associez d'abord le téléphone de votre ami dans les paramètres Bluetooth d'Android.")
            return
        }
        val devicesList = paired.toList()
        val names = devicesList.map { it.name ?: it.address }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Choisir votre ami")
            .setItems(names) { _, which ->
                connectToDevice(devicesList[which])
            }
            .show()
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice) {
        updateStatus("Statut : Connexion à ${device.name}...")
        Thread {
            try {
                val socket = device.createRfcommSocketToServiceRecord(appUuid)
                socket.connect()
                manageConnection(socket, "Connecté à ${device.name}")
            } catch (e: Exception) {
                updateStatus("Échec de connexion : ${e.message}")
            }
        }.start()
    }

    private fun manageConnection(socket: BluetoothSocket, label: String) {
        activeSocket = socket
        outputStream = socket.outputStream
        updateStatus("Statut : $label")
        appendChat("--- $label ---")

        val reader = BufferedReader(InputStreamReader(socket.inputStream, Charsets.UTF_8))
        try {
            while (true) {
                val line = reader.readLine() ?: break
                appendChat("Ami : $line")
            }
        } catch (e: Exception) {
            updateStatus("Statut : Connexion fermée")
        }
    }

    private fun sendMessage() {
        val text = messageInput.text.toString().trim()
        if (text.isEmpty() || outputStream == null) return
        Thread {
            try {
                outputStream?.write((text + "\n").toByteArray(Charsets.UTF_8))
                outputStream?.flush()
                runOnUiThread {
                    appendChat("Moi : $text")
                    messageInput.setText("")
                }
            } catch (e: Exception) {
                updateStatus("Erreur d'envoi : ${e.message}")
            }
        }.start()
    }

    private fun updateStatus(msg: String) {
        runOnUiThread { statusText.text = msg }
    }

    private fun appendChat(msg: String) {
        runOnUiThread {
            chatHistory.append("$msg\n")
            scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }
}
